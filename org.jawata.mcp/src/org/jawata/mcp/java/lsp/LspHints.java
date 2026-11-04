package org.jawata.mcp.java.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Pattern;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TextBlock;
import org.eclipse.jdt.core.dom.TypeLiteral;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The language server's annotations that sit in the text without being in it (Sprint 28h, S16d): inlay hints, which name the parameter
 * an argument fills and the type a {@code var} stands for, and code lenses, which say how many places refer to each type and method.
 * The hints are read from the compiler's bindings of the text the editor holds, saved or not. A lens first names where it sits and is
 * resolved when the editor shows it, by the engine's own reference search through the gate, so the count is the count an agent gets.
 */
final class LspHints {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int HINT_TYPE = 1;
    private static final int HINT_PARAMETER = 2;
    /** The name JDT gives a parameter of a class it has no source for: it says nothing, so it is not shown. */
    private static final Pattern SYNTHETIC_NAME = Pattern.compile("arg\\d+");

    /** The command a resolved lens carries; the editor's extension turns it into the references view. */
    static final String SHOW_REFERENCES = "jawata.showReferences";
    /** The command an implementations lens carries; the extension turns it into the implementations view. */
    static final String SHOW_IMPLEMENTATIONS = "jawata.showImplementations";
    /** What a lens counts, as it is written into the lens and read back when the editor resolves it; these are also the {@code find_references} kinds. */
    private static final String KIND_REFERENCES = "references";
    private static final String KIND_IMPLEMENTATIONS = "implementations";

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final Function<String, ICompilationUnit> unitOf;

    LspHints(ToolCalls tools, CallerIdentity caller, Function<String, ICompilationUnit> unitOf) {
        this.tools = tools;
        this.caller = caller;
        this.unitOf = unitOf;
    }

    /**
     * The hints inside the range asked for. An argument is named after its parameter unless it already says so (a plain name equal to
     * the parameter's) or the call has one parameter and the argument is not a literal; a {@code var} gets the type it stands for.
     */
    Object inlayHint(JsonNode params) throws JavaModelException {
        ICompilationUnit unit = unitOf.apply(params.path("textDocument").path("uri").asText());
        List<Map<String, Object>> hints = new ArrayList<>();
        if (unit == null) {
            return hints;
        }
        String text = unit.getSource();
        JavaRanges.Lines lines = JavaRanges.linesOf(text);
        JsonNode range = params.path("range");
        int from = lines.offsetOf(range.path("start").path("line").asInt(), range.path("start").path("character").asInt());
        int to = lines.offsetOf(range.path("end").path("line").asInt(), range.path("end").path("character").asInt());
        CompilationUnit root = LspStructure.syntaxOf(unit, true);
        root.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                argumentNames(hints, lines, from, to, node.resolveMethodBinding(), node.arguments());
                chainedResult(hints, lines, text, from, to, node);
                return true;
            }

            /** A lambda parameter written without its type gets the type the compiler infers for it. */
            @Override
            public boolean visit(LambdaExpression node) {
                for (Object parameter : node.parameters()) {
                    if (parameter instanceof VariableDeclarationFragment each) {
                        typeAfter(hints, lines, from, to, each.getName(), each.resolveBinding());
                    }
                }
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                argumentNames(hints, lines, from, to, node.resolveMethodBinding(), node.arguments());
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                argumentNames(hints, lines, from, to, node.resolveConstructorBinding(), node.arguments());
                return true;
            }

            @Override
            public boolean visit(VariableDeclarationStatement node) {
                if (node.getType().isVar()) {
                    for (Object fragment : node.fragments()) {
                        VariableDeclarationFragment each = (VariableDeclarationFragment) fragment;
                        typeAfter(hints, lines, from, to, each.getName(), each.resolveBinding());
                    }
                }
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                if (node.getType().isVar()) {
                    typeAfter(hints, lines, from, to, node.getName(), node.resolveBinding());
                }
                return true;
            }
        });
        return hints;
    }

    private static void argumentNames(List<Map<String, Object>> hints, JavaRanges.Lines lines, int from, int to, IMethodBinding method,
            List<?> arguments) {
        List<String> names = method == null ? List.of() : parameterNames(method);
        if (names.isEmpty() || (names.size() == 1 && !method.isVarargs() && arguments.size() == 1 && !isLiteral((Expression) arguments.get(0)))) {
            return;
        }
        for (int i = 0; i < arguments.size(); i++) {
            if (i >= names.size() && !method.isVarargs()) {
                break;
            }
            Expression argument = (Expression) arguments.get(i);
            String name = names.get(Math.min(i, names.size() - 1));
            boolean outside = argument.getStartPosition() < from || argument.getStartPosition() > to;
            boolean saysSo = argument instanceof SimpleName same && same.getIdentifier().equals(name);
            if (!outside && !saysSo) {
                JavaRanges.Range place = lines.span(argument.getStartPosition(), 0);
                Map<String, Object> hint = new LinkedHashMap<>();
                hint.put("position", Map.of("line", place.line(), "character", place.column()));
                hint.put("label", name + ":");
                hint.put("kind", HINT_PARAMETER);
                hint.put("paddingRight", true);
                hints.add(hint);
            }
        }
    }

    /** The names of the method's parameters as its source declares them; empty where the source is not known. */
    private static List<String> parameterNames(IMethodBinding method) {
        try {
            IJavaElement element = method.getMethodDeclaration().getJavaElement();
            String[] names = element instanceof IMethod declared ? declared.getParameterNames() : new String[0];
            for (String name : names) {
                if (SYNTHETIC_NAME.matcher(name).matches()) {
                    return List.of();
                }
            }
            return List.of(names);
        } catch (JavaModelException e) {
            throw new IllegalStateException("the parameter names of " + method.getName() + " could not be read: " + e.getMessage(), e);
        }
    }

    private static boolean isLiteral(Expression expression) {
        return expression instanceof NumberLiteral || expression instanceof StringLiteral || expression instanceof TextBlock
            || expression instanceof BooleanLiteral || expression instanceof CharacterLiteral || expression instanceof NullLiteral
            || expression instanceof TypeLiteral
            || expression instanceof PrefixExpression prefix && isLiteral(prefix.getOperand());
    }

    private static void typeAfter(List<Map<String, Object>> hints, JavaRanges.Lines lines, int from, int to, SimpleName name, IVariableBinding variable) {
        typeHint(hints, lines, from, to, name.getStartPosition() + name.getLength(), variable == null ? null : variable.getType());
    }

    /**
     * A call whose receiver is a call that ends its line: the receiver's result type is shown after it, which is what a chain written one
     * call per line hides. A chain on one line is not hinted, because each call is visible beside the next.
     */
    private static void chainedResult(List<Map<String, Object>> hints, JavaRanges.Lines lines, String text, int from, int to, MethodInvocation call) {
        if (call.getExpression() instanceof MethodInvocation receiver) {
            int end = receiver.getStartPosition() + receiver.getLength();
            if (text.substring(end, call.getName().getStartPosition()).indexOf('\n') >= 0) {
                typeHint(hints, lines, from, to, end, receiver.resolveTypeBinding());
            }
        }
    }

    private static void typeHint(List<Map<String, Object>> hints, JavaRanges.Lines lines, int from, int to, int end, ITypeBinding type) {
        if (type == null || end < from || end > to) {
            return;
        }
        JavaRanges.Range place = lines.span(end, 0);
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("position", Map.of("line", place.line(), "character", place.column()));
        hint.put("label", ": " + type.getName());
        hint.put("kind", HINT_TYPE);
        hints.add(hint);
    }

    /**
     * The lenses of a file, not yet counted: a references lens above each type, method, constructor and field, and an implementations
     * lens above each interface, abstract class and method with no body (an abstract or interface method).
     */
    Object codeLens(JsonNode params) throws JavaModelException {
        String uri = params.path("textDocument").path("uri").asText();
        ICompilationUnit unit = unitOf.apply(uri);
        List<Map<String, Object>> lenses = new ArrayList<>();
        if (unit == null) {
            return lenses;
        }
        JavaRanges.Lines lines = JavaRanges.linesOf(unit.getSource());
        LspStructure.syntaxOf(unit, false).accept(new ASTVisitor() {
            @Override
            public boolean visit(org.eclipse.jdt.core.dom.TypeDeclaration node) {
                lens(lenses, lines, uri, node.getName(), KIND_REFERENCES);
                if (node.isInterface() || Modifier.isAbstract(node.getModifiers())) {
                    lens(lenses, lines, uri, node.getName(), KIND_IMPLEMENTATIONS);
                }
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.EnumDeclaration node) {
                lens(lenses, lines, uri, node.getName(), KIND_REFERENCES);
                return true;
            }

            @Override
            public boolean visit(org.eclipse.jdt.core.dom.RecordDeclaration node) {
                lens(lenses, lines, uri, node.getName(), KIND_REFERENCES);
                return true;
            }

            @Override
            public boolean visit(MethodDeclaration node) {
                lens(lenses, lines, uri, node.getName(), KIND_REFERENCES);
                if (node.getBody() == null && !Modifier.isNative(node.getModifiers())) {
                    lens(lenses, lines, uri, node.getName(), KIND_IMPLEMENTATIONS);
                }
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                for (Object fragment : node.fragments()) {
                    lens(lenses, lines, uri, ((VariableDeclarationFragment) fragment).getName(), KIND_REFERENCES);
                }
                return true;
            }
        });
        return lenses;
    }

    private static void lens(List<Map<String, Object>> lenses, JavaRanges.Lines lines, String uri, ASTNode name, String kind) {
        JavaRanges.Range span = lines.span(name.getStartPosition(), name.getLength());
        Map<String, Object> lens = new LinkedHashMap<>();
        lens.put("range", span.toProtocol());
        lens.put("data", Map.of("uri", uri, "line", span.line(), "character", span.column(), "kind", kind));
        lenses.add(lens);
    }

    /** The lens with its count: the places the engine's reference search finds for the name the lens sits on. */
    Object resolveLens(JsonNode lens) throws ToolNotFoundException {
        JsonNode where = lens.path("data");
        boolean implementations = KIND_IMPLEMENTATIONS.equals(where.path("kind").asText(KIND_REFERENCES));
        int count = lensCount(where, implementations ? KIND_IMPLEMENTATIONS : KIND_REFERENCES,
            implementations ? "totalImplementations" : "totalReferences");
        String noun = implementations ? "implementation" : "reference";
        Map<String, Object> resolved = new LinkedHashMap<>();
        resolved.put("range", JSON.convertValue(lens.path("range"), Map.class));
        resolved.put("data", JSON.convertValue(where, Map.class));
        resolved.put("command", Map.of("title", count == 1 ? "1 " + noun : count + " " + noun + "s",
            "command", implementations ? SHOW_IMPLEMENTATIONS : SHOW_REFERENCES,
            "arguments", List.of(where.path("uri").asText(), where.path("line").asInt(), where.path("character").asInt())));
        return resolved;
    }

    private int lensCount(JsonNode where, String kind, String countField) throws ToolNotFoundException {
        ToolResponse response = tools.callTool("find_references", JSON.createObjectNode().put("kind", kind)
            .put("filePath", Path.of(URI.create(where.path("uri").asText())).toString())
            .put("line", where.path("line").asInt()).put("column", where.path("character").asInt()), caller);
        if (!response.isSuccess()) {
            throw new IllegalStateException("the " + kind + " for a lens could not be counted: " + response);
        }
        int count = JSON.valueToTree(response.getData()).path(countField).asInt(-1);
        if (count < 0) {
            throw new IllegalStateException("the " + kind + " search answered no count: " + response);
        }
        return count;
    }
}
