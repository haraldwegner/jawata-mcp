package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CreateCompilationUnitChange;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.shared.FormatterOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fowler — <b>Replace Function with Command</b> (row 48). A function becomes an object: its
 * parameters become fields set at construction, and its body becomes {@code execute()}.
 *
 * <p>A delegate of {@link ExtractTool} (kind {@code function_to_command}); not registered
 * standalone.</p>
 *
 * <h2>Why this is worth doing, and why it is worth refusing</h2>
 *
 * <p>The point of a command object is that the call gets taken apart: the arguments can be
 * gathered separately from the moment of running, the run can be undone or repeated, and the
 * body — now a method on a class with fields instead of a method with parameters — can be
 * broken into named steps that share those fields. That last one is the usual reason: a long
 * function whose locals all thread through every extraction becomes decomposable the moment
 * they are fields.</p>
 *
 * <p>It is also a real cost. One function becomes a class and every call site becomes two
 * operations, so this refuses anything that would make the trade worse than it looks:</p>
 *
 * <ul>
 *   <li><b>Static functions only.</b> An instance method also needs its receiver carried
 *       into the command, which is a second field the caller never named and a decision
 *       about whether the command holds the object or a copy of what it read.</li>
 *   <li><b>Not overridden, and overriding nothing.</b> A command has no dispatch, so
 *       replacing a polymorphic function with one collapses behaviour silently.</li>
 *   <li><b>Every call must be a plain invocation</b> this rewrite can reach; one it cannot
 *       see is one left calling a function that no longer exists.</li>
 * </ul>
 */
public class ReplaceFunctionWithCommandTool extends AbstractApplyingRefactoringTool {

    public ReplaceFunctionWithCommandTool(Supplier<IJdtService> serviceSupplier,
                                          RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "replace_function_with_command";
    }

    @Override
    public String getDescription() {
        return "Replace Function with Command — turn a static function into an object whose "
            + "fields are its parameters and whose execute() is its body. Delegate of extract.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the function."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of a caret in the function."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of that caret."));
        properties.put("newTypeName", Map.of("type", "string",
            "description", "Name for the command class."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "newTypeName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments)
            throws Exception {
        String filePathStr = getStringParam(arguments, "filePath");
        String newTypeName = getStringParam(arguments, "newTypeName");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (filePathStr == null || filePathStr.isBlank()) {
            return Preparation.fail(
                ToolResponse.invalidParameter("filePath", "filePath is required"));
        }
        if (newTypeName == null || newTypeName.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("newTypeName",
                "newTypeName is required — the command's name is what every call site will"
                    + " read, so it is the caller's to choose."));
        }
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers"));
        }

        Path filePath = service.getPathUtils().resolve(filePathStr);
        IJavaElement element = service.getElementAtPosition(filePath, line, column);
        if (!(element instanceof IMethod method)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "Position does not resolve to a method; got "
                    + (element == null ? "null" : element.getClass().getSimpleName())));
        }
        ICompilationUnit unit = method.getCompilationUnit();
        if (unit == null) {
            return Preparation.fail(ToolResponse.symbolNotFound(
                method.getElementName() + " has no source in this workspace."));
        }
        return replace(service, unit, method, newTypeName);
    }

    private Preparation replace(IJdtService service, ICompilationUnit unit, IMethod method,
                                String newTypeName) throws Exception {
        CompilationUnit ast = parse(unit);
        MethodDeclaration declaration = declarationOf(ast, method);
        if (declaration == null || declaration.getBody() == null) {
            return Preparation.fail(ToolResponse.symbolNotFound(
                "could not locate a body for " + method.getElementName()));
        }
        if (!Modifier.isStatic(declaration.getModifiers())) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                method.getElementName() + " is an instance method, so a command for it would"
                    + " also have to carry its receiver — a field nobody named, and a"
                    + " decision about whether the command holds the object or what it read"
                    + " from it. Make it static first, or use extract kind=class."));
        }
        IType declaring = method.getDeclaringType();
        if (declaring != null) {
            IType[] subtypes = service.getSearchService().getAllSubtypes(declaring);
            if (subtypes != null && subtypes.length > 0) {
                for (IType subtype : subtypes) {
                    for (IMethod candidate : subtype.getMethods()) {
                        if (candidate.getElementName().equals(method.getElementName())) {
                            return Preparation.fail(ToolResponse.invalidParameter("position",
                                subtype.getElementName() + " declares "
                                    + method.getElementName() + " too. A command has no"
                                    + " dispatch, so replacing a function that something"
                                    + " else redeclares collapses the choice silently."));
                        }
                    }
                }
            }
        }

        List<Call> calls = callsTo(service, method);
        for (Call call : calls) {
            if (call.node == null) {
                return Preparation.fail(ToolResponse.invalidParameter("position",
                    "a reference to " + method.getElementName() + " in "
                        + call.unit.getElementName() + " is not a plain invocation, so this"
                        + " rewrite cannot reach it and it would be left calling a function"
                        + " that no longer exists."));
            }
        }

        IContainer parent = (IContainer) unit.getResource().getParent();
        IFile newFile = parent.getFile(
            new org.eclipse.core.runtime.Path(newTypeName + ".java"));
        if (newFile.exists()) {
            return Preparation.fail(ToolResponse.invalidParameter("newTypeName",
                "A file named " + newTypeName + ".java already exists in this package."));
        }

        String packageName = ast.getPackage() == null ? ""
            : ast.getPackage().getName().getFullyQualifiedName();
        String source = buildCommand(packageName, newTypeName, declaration, unit.getSource());

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        ASTRewrite ownerRewrite = ASTRewrite.create(ast.getAST());
        ownerRewrite.remove(declaration, null);

        Map<ICompilationUnit, List<Call>> byUnit = new LinkedHashMap<>();
        for (Call call : calls) {
            byUnit.computeIfAbsent(call.unit, u -> new ArrayList<>()).add(call);
        }
        for (Map.Entry<ICompilationUnit, List<Call>> entry : byUnit.entrySet()) {
            ICompilationUnit callerCu = entry.getKey();
            boolean sameFile = callerCu.equals(unit);
            CompilationUnit callerAst = sameFile ? ast : parse(callerCu);
            ASTRewrite rewrite = sameFile ? ownerRewrite : ASTRewrite.create(callerAst.getAST());
            String callerSource = callerCu.getSource();
            for (Call call : entry.getValue()) {
                MethodInvocation node = sameFile ? call.node : reboundIn(callerAst, call.node);
                StringBuilder replacement = new StringBuilder("new ").append(newTypeName)
                    .append('(');
                List<?> arguments = node.arguments();
                for (int i = 0; i < arguments.size(); i++) {
                    if (i > 0) {
                        replacement.append(", ");
                    }
                    replacement.append(textOf(callerSource, (ASTNode) arguments.get(i)));
                }
                replacement.append(").execute()");
                rewrite.replace(node, rewrite.createStringPlaceholder(
                    replacement.toString(), ASTNode.METHOD_INVOCATION), null);
            }
            if (!sameFile) {
                edits.put((IFile) callerCu.getResource(),
                    List.of(rewrite.rewriteAST(new Document(callerCu.getSource()),
                        FormatterOptions.forGeneratedCode(callerAst))));
            }
        }
        edits.put((IFile) unit.getResource(),
            List.of(ownerRewrite.rewriteAST(new Document(unit.getSource()),
                FormatterOptions.forGeneratedCode(ast))));

        String label = "replace function " + method.getElementName() + " with command "
            + newTypeName + " (" + calls.size() + " call site(s) rewritten)";
        CompositeChange composite = new CompositeChange(label);
        composite.add(ChangeEngine.fromFileEdits(label, edits));
        composite.add(new CreateCompilationUnitChange(newFile, source));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("newType", newTypeName);
        extras.put("function", method.getElementName());
        extras.put("fields", declaration.parameters().size());
        extras.put("callSitesRewritten", calls.size());
        // The reason to do this at all, said out loud rather than left to be discovered.
        extras.put("note", "The parameters are fields now, so the body can be broken into"
            + " named steps that share them — refactor_to_pattern kind=compose_method is the"
            + " next step, and it was not possible while they were parameters.");
        return Preparation.of(composite, label, extras);
    }

    private record Call(ICompilationUnit unit, MethodInvocation node) {}

    /** The command class: a field per parameter, a constructor, and the body as execute(). */
    private static String buildCommand(String packageName, String newTypeName,
                                       MethodDeclaration declaration, String source) {
        StringBuilder out = new StringBuilder();
        if (!packageName.isEmpty()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("/**\n * ").append(declaration.getName().getIdentifier())
            .append(", as a command: its parameters are fields, its body is execute().\n *\n")
            .append(" * <p>Generated by Replace Function with Command (Fowler). The point of")
            .append(" the shape is that\n * the body can now be broken into named steps that")
            .append(" share the fields, which was not\n * possible while they were")
            .append(" parameters.</p>\n */\n");
        out.append("public class ").append(newTypeName).append(" {\n\n");

        List<?> parameters = declaration.parameters();
        for (Object parameter : parameters) {
            SingleVariableDeclaration p = (SingleVariableDeclaration) parameter;
            out.append("    private final ").append(p.getType()).append(' ')
                .append(p.getName().getIdentifier()).append(";\n");
        }
        out.append('\n').append("    public ").append(newTypeName).append('(');
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(parameters.get(i));
        }
        out.append(") {\n");
        for (Object parameter : parameters) {
            String name = ((SingleVariableDeclaration) parameter).getName().getIdentifier();
            out.append("        this.").append(name).append(" = ").append(name).append(";\n");
        }
        out.append("    }\n\n");

        out.append("    public ").append(declaration.getReturnType2()).append(" execute() ");
        out.append(textOf(source, declaration.getBody())).append('\n');
        out.append("}\n");
        return out.toString();
    }

    /** A node's own source text — see the note in MoveStatementsIntoFunctionTool. */
    private static String textOf(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength()).trim();
    }

    private static List<Call> callsTo(IJdtService service, IMethod target) throws Exception {
        Set<ICompilationUnit> units = new LinkedHashSet<>();
        for (org.eclipse.jdt.core.search.SearchMatch match
                : org.jawata.mcp.refactoring.CompleteReferences.of(service, target)) {
            if (match.getElement() instanceof IJavaElement element) {
                ICompilationUnit unit = (ICompilationUnit) element
                    .getAncestor(IJavaElement.COMPILATION_UNIT);
                if (unit != null) {
                    units.add(unit);
                }
            }
        }
        List<Call> calls = new ArrayList<>();
        for (ICompilationUnit unit : units) {
            CompilationUnit ast = parse(unit);
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodInvocation node) {
                    IMethodBinding binding = node.resolveMethodBinding();
                    if (binding != null && target.equals(binding.getJavaElement())) {
                        calls.add(new Call(unit, node));
                    }
                    return true;
                }
            });
        }
        return calls;
    }

    private static MethodInvocation reboundIn(CompilationUnit ast, MethodInvocation original) {
        MethodInvocation[] found = { null };
        int start = original.getStartPosition();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (found[0] == null && node.getStartPosition() == start) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0] != null ? found[0] : original;
    }

    private static MethodDeclaration declarationOf(CompilationUnit ast, IMethod method) {
        MethodDeclaration[] found = { null };
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding binding = node.resolveBinding();
                if (found[0] == null && binding != null
                        && method.equals(binding.getJavaElement())) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
