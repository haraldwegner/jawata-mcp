package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.jawata.mcp.refactoring.CreateCompilationUnitChange;
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
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
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
 * Fowler — <b>Combine Functions into Class</b> (row 5). A group of functions that all work
 * on the same piece of data becomes a class holding that data, with the functions as its
 * methods.
 *
 * <p>A delegate of {@link ExtractTool} (kind {@code combine_functions}); not registered
 * standalone. Where {@code extract kind=class} pulls FIELDS out of a class that has too
 * much state, this pulls FUNCTIONS together around state they were all passing around.</p>
 *
 * <h2>What "the same data" means here, and why it is a first parameter</h2>
 *
 * <p>The functions are named by the caller — which ones belong together is the design
 * decision this carries out, and no detector can make it. What the tool then checks is
 * that the grouping is real: every named function must be {@code static} and must take the
 * same type as its FIRST parameter. That parameter is the data, it becomes the new class's
 * one field, and each function becomes an instance method taking whatever came after it.</p>
 *
 * <p>Static, because an instance method already has a receiver and combining those is
 * {@code move kind=method} one at a time. First parameter, because the position has to be
 * decidable: with the shared type appearing second in one function and third in another,
 * "the data" is a guess, and a wrong guess here produces code that compiles.</p>
 *
 * <p>Every call site is rewritten from {@code Owner.fn(data, rest)} to
 * {@code new Type(data).fn(rest)}. A call this tool cannot see is a call left broken, so
 * it refuses when a named function has references it cannot resolve to a plain invocation.</p>
 */
public class CombineFunctionsIntoClassTool extends AbstractApplyingRefactoringTool {

    public CombineFunctionsIntoClassTool(Supplier<IJdtService> serviceSupplier,
                                         RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "combine_functions_into_class";
    }

    @Override
    public String getDescription() {
        return "Combine Functions into Class — gather static functions that share their "
            + "first parameter into a new class holding it. Delegate of extract.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the functions."));
        properties.put("functions", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "Names of the static functions to combine. WHICH functions belong "
                + "together is the design decision this carries out, so there is no default: "
                + "at least two, all sharing the type of their first parameter."));
        properties.put("newTypeName", Map.of("type", "string",
            "description", "Name for the new class."));
        properties.put("fieldName", Map.of("type", "string",
            "description", "Name for the field holding the shared data (default: the first "
                + "function's own name for that parameter)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "functions", "newTypeName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments)
            throws Exception {
        String filePathStr = getStringParam(arguments, "filePath");
        String newTypeName = getStringParam(arguments, "newTypeName");
        List<String> functions = stringArray(arguments, "functions");

        if (filePathStr == null || filePathStr.isBlank()) {
            return Preparation.fail(
                ToolResponse.invalidParameter("filePath", "filePath is required"));
        }
        if (newTypeName == null || newTypeName.isBlank()) {
            return Preparation.fail(
                ToolResponse.invalidParameter("newTypeName", "newTypeName is required"));
        }
        if (functions == null || functions.size() < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("functions",
                "name at least TWO functions. Combining one function into a class is not this"
                    + " refactoring — it is extract kind=class, or nothing at all."));
        }
        Path filePath = service.getPathUtils().resolve(filePathStr);
        ICompilationUnit unit = service.getCompilationUnit(filePath);
        if (unit == null) {
            return Preparation.fail(
                ToolResponse.symbolNotFound("No compilation unit at " + filePathStr));
        }
        return combine(service, unit, functions, newTypeName,
            getStringParam(arguments, "fieldName"));
    }

    private Preparation combine(IJdtService service, ICompilationUnit unit,
                                List<String> functions, String newTypeName, String fieldName)
            throws Exception {
        CompilationUnit ast = parse(unit);
        List<MethodDeclaration> chosen = new ArrayList<>();
        for (String name : functions) {
            MethodDeclaration declaration = methodNamed(ast, name);
            if (declaration == null) {
                return Preparation.fail(ToolResponse.symbolNotFound(
                    "no method named '" + name + "' in " + unit.getElementName()));
            }
            if (!org.eclipse.jdt.core.dom.Modifier.isStatic(declaration.getModifiers())) {
                return Preparation.fail(ToolResponse.invalidParameter("functions",
                    "'" + name + "' is not static, so it already has a receiver. Combining"
                        + " instance methods onto another type is move kind=method, one at a"
                        + " time, and it is a different decision per method."));
            }
            if (declaration.parameters().isEmpty()) {
                return Preparation.fail(ToolResponse.invalidParameter("functions",
                    "'" + name + "' takes no parameters, so it shares no data with the"
                        + " others and there is nothing for the new class to hold."));
            }
            chosen.add(declaration);
        }

        SingleVariableDeclaration firstParameter =
            (SingleVariableDeclaration) chosen.get(0).parameters().get(0);
        String sharedType = firstParameter.getType().toString();
        for (MethodDeclaration declaration : chosen) {
            String type = ((SingleVariableDeclaration) declaration.parameters().get(0))
                .getType().toString();
            if (!sharedType.equals(type)) {
                return Preparation.fail(ToolResponse.invalidParameter("functions",
                    "'" + declaration.getName() + "' takes " + type + " first while '"
                        + chosen.get(0).getName() + "' takes " + sharedType + ". These"
                        + " functions do not share one piece of data, and which parameter"
                        + " would be the field is then a guess — one that produces code that"
                        + " compiles."));
            }
        }
        String field = fieldName != null && !fieldName.isBlank()
            ? fieldName : firstParameter.getName().getIdentifier();

        // Every call must be a plain invocation this rewrite can reach. One it cannot see is
        // one left calling a method that has moved.
        List<Call> calls = new ArrayList<>();
        for (MethodDeclaration declaration : chosen) {
            IMethodBinding binding = declaration.resolveBinding();
            if (binding == null || !(binding.getJavaElement() instanceof IMethod method)) {
                return Preparation.fail(ToolResponse.symbolNotFound(
                    "could not resolve " + declaration.getName() + " to a method element."));
            }
            calls.addAll(callsTo(service, method, declaration.getName().getIdentifier()));
        }

        IType declaring = firstTypeOf(unit);
        if (declaring == null) {
            return Preparation.fail(ToolResponse.symbolNotFound(
                "could not find the type declaring these functions."));
        }
        String packageName = declaring.getPackageFragment().getElementName();
        IContainer parent = (IContainer) unit.getResource().getParent();
        IFile newFile = parent.getFile(new org.eclipse.core.runtime.Path(newTypeName + ".java"));
        if (newFile.exists()) {
            return Preparation.fail(ToolResponse.invalidParameter("newTypeName",
                "A file named " + newTypeName + ".java already exists in this package."));
        }
        String source = buildNewType(packageName, newTypeName, sharedType, field, chosen);

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        ASTRewrite ownerRewrite = ASTRewrite.create(ast.getAST());
        for (MethodDeclaration declaration : chosen) {
            ownerRewrite.remove(declaration, null);
        }
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
                rewrite.replace(node, rewrite.createStringPlaceholder(
                    rewritten(callerSource, node, newTypeName), ASTNode.METHOD_INVOCATION), null);
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

        String label = "combine " + chosen.size() + " functions into " + newTypeName + " ("
            + calls.size() + " call site(s) rewritten)";
        org.eclipse.ltk.core.refactoring.CompositeChange composite =
            new org.eclipse.ltk.core.refactoring.CompositeChange(label);
        composite.add(ChangeEngine.fromFileEdits(label, edits));
        composite.add(new CreateCompilationUnitChange(newFile, source));
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("newType", newTypeName);
        extras.put("field", field);
        extras.put("sharedType", sharedType);
        extras.put("combined", functions);
        extras.put("callSitesRewritten", calls.size());
        return Preparation.of(composite, label, extras);
    }

    private record Call(ICompilationUnit unit, MethodInvocation node) {}

    /** `Owner.fn(data, a, b)` becomes `new Type(data).fn(a, b)`, built from the source text. */
    private static String rewritten(String source, MethodInvocation call, String newTypeName) {
        List<?> arguments = call.arguments();
        ASTNode data = (ASTNode) arguments.get(0);
        StringBuilder rest = new StringBuilder();
        for (int i = 1; i < arguments.size(); i++) {
            if (i > 1) {
                rest.append(", ");
            }
            rest.append(textOf(source, (ASTNode) arguments.get(i)));
        }
        return "new " + newTypeName + "(" + textOf(source, data) + ")."
            + call.getName().getIdentifier() + "(" + rest + ")";
    }

    /** A node's own source text — see the note in MoveStatementsIntoFunctionTool. */
    private static String textOf(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength()).trim();
    }

    private String buildNewType(String packageName, String newTypeName, String sharedType,
                                String field, List<MethodDeclaration> chosen) {
        StringBuilder out = new StringBuilder();
        if (!packageName.isEmpty()) {
            out.append("package ").append(packageName).append(";\n\n");
        }
        out.append("/**\n * The data these functions were all passing to each other, with the")
            .append(" functions on it.\n *\n * <p>Generated by Combine Functions into Class")
            .append(" (Fowler); the grouping was the caller's\n * decision.</p>\n */\n");
        out.append("public class ").append(newTypeName).append(" {\n\n");
        out.append("    private final ").append(sharedType).append(' ').append(field)
            .append(";\n\n");
        out.append("    public ").append(newTypeName).append('(').append(sharedType)
            .append(' ').append(field).append(") {\n        this.").append(field)
            .append(" = ").append(field).append(";\n    }\n");
        for (MethodDeclaration declaration : chosen) {
            out.append('\n').append(asInstanceMethod(declaration, field));
        }
        out.append("}\n");
        return out.toString();
    }

    /**
     * The static function, minus {@code static} and minus its first parameter, whose name
     * now resolves to the field. The body is carried across unchanged: every name in it
     * still resolves, because the parameter it referred to has become a field of the same
     * name.
     */
    private static String asInstanceMethod(MethodDeclaration declaration, String field) {
        SingleVariableDeclaration first =
            (SingleVariableDeclaration) declaration.parameters().get(0);
        StringBuilder parameters = new StringBuilder();
        for (int i = 1; i < declaration.parameters().size(); i++) {
            if (i > 1) {
                parameters.append(", ");
            }
            parameters.append(declaration.parameters().get(i));
        }
        String body = declaration.getBody() == null ? "{\n    }" : declaration.getBody().toString();
        if (!first.getName().getIdentifier().equals(field)) {
            body = body.replaceAll("\\b" + java.util.regex.Pattern.quote(
                first.getName().getIdentifier()) + "\\b", field);
        }
        StringBuilder out = new StringBuilder();
        out.append("    public ").append(declaration.getReturnType2()).append(' ')
            .append(declaration.getName().getIdentifier()).append('(').append(parameters)
            .append(") ");
        for (String line : body.split("\n")) {
            out.append(line.isBlank() ? "" : "    ").append(line.strip().isEmpty() ? "" : line)
                .append('\n');
        }
        return out.toString();
    }

    private static List<Call> callsTo(IJdtService service, IMethod target, String name)
            throws Exception {
        Set<ICompilationUnit> units = new LinkedHashSet<>();
        for (org.eclipse.jdt.core.search.SearchMatch match
                : service.getSearchService().findAllReferences(target, 500)) {
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
                    if (binding != null && target.equals(binding.getJavaElement())
                            && name.equals(node.getName().getIdentifier())) {
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

    private static MethodDeclaration methodNamed(CompilationUnit ast, String name) {
        MethodDeclaration[] found = { null };
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (found[0] == null && name.equals(node.getName().getIdentifier())) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    private static IType firstTypeOf(ICompilationUnit unit) throws Exception {
        IType[] types = unit.getTypes();
        return types.length > 0 ? types[0] : null;
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }

    /** The array parameter, as a list; absent or non-array reads as null. */
    private static List<String> stringArray(JsonNode arguments, String name) {
        JsonNode node = arguments.get(name);
        if (node == null || !node.isArray()) {
            return null;
        }
        List<String> values = new ArrayList<>();
        node.forEach(element -> values.add(element.asText()));
        return values;
    }

}
