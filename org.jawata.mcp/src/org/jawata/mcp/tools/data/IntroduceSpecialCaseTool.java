package org.jawata.mcp.tools.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FormatterOptions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code data kind=special_case} — Fowler row 22, Introduce Special Case (the Null Object).
 *
 * <p>When many clients check a value for absence and all do the same thing on finding it, the
 * check is duplicated logic and one missed copy is a crash. The cure is an object that IS the
 * absent case and answers neutrally, so clients stop asking.</p>
 *
 * <h2>What it generates, and where</h2>
 *
 * <p>A {@code static} nested subclass — {@code Customer.NullCustomer} — with a neutral
 * override for every method the parent leaves overridable, and one shared {@code INSTANCE}.
 * Neutral means: {@code 0} for a number, {@code false} for a boolean, {@code ""} for a
 * String, an empty collection for a collection, {@code null} otherwise.</p>
 *
 * <p><b>Nested rather than a new top-level file, and that is a scoping decision rather than a
 * preference.</b> The change machinery this row runs on edits EXISTING files: a top-level
 * class needs a create-compilation-unit change, which is a different mechanism and a
 * different set of failure modes. Fowler is agnostic about placement, and a nested special
 * case reads at the call site as {@code Customer.NullCustomer.INSTANCE}, which names its own
 * parent. Moving it out later is {@code move kind=class}, an operation that already ships.</p>
 *
 * <h2>What it deliberately does NOT do</h2>
 *
 * <p>It does not rewrite the null checks. Fowler's later steps replace {@code c == null ?
 * "occupant" : c.getName()} with {@code c.getName()}, and that needs to know WHICH expression
 * yields the absent value — a fact this operation is not given and cannot infer from a type
 * alone. Generating the special case is the step that has a well-defined input; the client
 * migration is a separate decision per call site.</p>
 *
 * <p><b>This is stated because it bounds what the row claims, not to excuse it.</b> A caller
 * who runs this and stops has a class nobody uses. What they have gained is the object to
 * migrate TOWARD, which is the part that cannot be done by hand without getting the
 * overrides wrong.</p>
 *
 * <h2>Its route, or rather its absence — recorded per the per-row contract</h2>
 *
 * <p>The contract asks that a row be "routed, or listed as unrouted with the reason". This one
 * is UNROUTED. Measured before building it: {@code find_string_literals} over 948 files finds
 * neither {@code design:null-object} nor {@code special_case} in the cure catalogue, so the
 * entry the plan expected does not exist; and {@code temporary_field}, the detector the plan
 * credits with this row's demand, names a different cure in its own message — "Field 'mapper'
 * is referenced by only one method. Consider Extract Class." Fowler's Temporary Field admits
 * both cures, but no shipped detector names this one, so no finding can offer it yet.</p>
 */
public class IntroduceSpecialCaseTool extends AbstractRefactoringTool implements ToolKindDelegate {

    public IntroduceSpecialCaseTool(Supplier<IJdtService> serviceSupplier,
                                    RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String kindName() {
        return "special_case";
    }

    @Override
    public String kindSummary() {
        return """
            introduce a special case for an absent value — Fowler's Null Object. Generates
            a static nested subclass (Customer.NullCustomer) overriding every overridable
            method with a neutral answer, plus one shared INSTANCE, so clients can stop
            checking for absence. It does NOT rewrite the checks: which expression yields
            the absent value cannot be inferred from a type, and that migration is a
            decision per call site. Refuses a final class, an interface, a class with no
            reachable constructor, and one that already declares the name. Each refusal
            names which.""";
    }

    /** Structural: it adds a type to the parent's published surface. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public String getName() {
        return "special_case";
    }

    @Override
    public String getDescription() {
        return "Introduce Special Case — generate a null-object subclass so clients stop "
            + "checking for absence. Delegate of data.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the type the special case is for."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of a caret in that type."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of that caret."));
        properties.put("specialCaseName", Map.of("type", "string",
            "description", "Name for the generated subclass (default: Null + the type's own "
                + "name). Every call site reads it, so it is worth choosing."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    public ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IType target = service.getTypeAtPosition(filePath, line, column);
            if (target == null || target.getCompilationUnit() == null) {
                return ToolResponse.symbolNotFound(
                    "No source type at " + filePathStr + ":" + line + ":" + column);
            }
            return introduce(service, target,
                getStringParam(arguments, "specialCaseName"), arguments);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(IntroduceSpecialCaseTool.class)
                .warn("special_case failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse introduce(IJdtService service, IType target, String requestedName,
                                   JsonNode arguments) throws Exception {
        ICompilationUnit unit = target.getCompilationUnit();
        CompilationUnit ast = parse(unit);
        TypeDeclaration declaration = typeNamed(ast, target.getElementName());
        if (declaration == null) {
            return ToolResponse.symbolNotFound(
                "could not locate " + target.getElementName() + " in its own source.");
        }
        ITypeBinding binding = declaration.resolveBinding();
        if (binding == null) {
            return ToolResponse.symbolNotFound("could not resolve "
                + target.getElementName() + "; the file may not compile against this"
                + " workspace's classpath.");
        }

        if (binding.isInterface()) {
            return ToolResponse.invalidParameter("position",
                target.getElementName() + " is an INTERFACE. A special case for one is an"
                    + " implementation rather than a subclass, and which of its methods are"
                    + " neutral is a design decision no default can make.");
        }
        if (Modifier.isFinal(binding.getModifiers())) {
            return ToolResponse.invalidParameter("position",
                target.getElementName() + " is FINAL, so it cannot be subclassed. A special"
                    + " case for a final type has to be a sibling behind a shared interface,"
                    + " which is a larger design change than this operation performs.");
        }

        String name = requestedName != null && !requestedName.isBlank()
            ? requestedName
            : "Null" + target.getElementName();
        for (IType nested : target.getTypes()) {
            if (nested.getElementName().equals(name)) {
                return ToolResponse.invalidParameter("specialCaseName",
                    target.getElementName() + " already declares a nested " + name
                        + ". Pass specialCaseName to choose another.");
            }
        }

        String constructor = reachableConstructor(binding);
        if (constructor == null) {
            return ToolResponse.invalidParameter("position",
                target.getElementName() + " has no constructor a subclass can reach — every"
                    + " one is private. A special case cannot extend a type it cannot"
                    + " construct.");
        }

        List<String> overrides = new ArrayList<>();
        for (IMethodBinding method : binding.getDeclaredMethods()) {
            if (method.isConstructor()
                    || Modifier.isFinal(method.getModifiers())
                    || Modifier.isStatic(method.getModifiers())
                    || Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            overrides.add(neutralOverride(method));
        }
        if (overrides.isEmpty()) {
            return ToolResponse.invalidParameter("position",
                target.getElementName() + " leaves no method overridable, so a special case"
                    + " for it could answer nothing differently and would be an empty"
                    + " subclass.");
        }

        StringBuilder body = new StringBuilder();
        body.append("/**\n * The absent ").append(target.getElementName())
            .append(", answering neutrally so clients need not check.\n")
            .append(" * Generated by Introduce Special Case (Fowler row 22).\n */\n")
            .append("public static class ").append(name)
            .append(" extends ").append(target.getElementName()).append(" {\n\n")
            .append("    /** The one instance; it holds no state, so it needs no other. */\n")
            .append("    public static final ").append(name).append(" INSTANCE = new ")
            .append(name).append("();\n\n")
            .append("    private ").append(name).append("() {\n        ")
            .append(constructor).append("\n    }\n");
        for (String override : overrides) {
            body.append('\n').append(override);
        }
        body.append("}");

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        ListRewrite members = rewrite.getListRewrite(declaration,
            declaration.getBodyDeclarationsProperty());
        members.insertLast(rewrite.createStringPlaceholder(body.toString(),
            ASTNode.TYPE_DECLARATION), null);

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        edits.put((IFile) unit.getResource(),
            List.of(rewrite.rewriteAST(new Document(unit.getSource()),
                FormatterOptions.forGeneratedCode(ast))));

        String label = "introduce special case " + target.getElementName() + "." + name
            + " (" + overrides.size() + " method(s) answered neutrally)";
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(ChangeEngine.fromFileEdits(label, edits), label),
            "special_case", arguments);
    }

    /**
     * The {@code super(...)} call a private subclass constructor can make, or null.
     *
     * <p>A no-argument constructor needs none at all. Otherwise the shortest reachable one is
     * called with a neutral argument per parameter — the same neutrality the overrides use,
     * because a special case that had to be handed real values would not be constructible
     * from a static initializer.</p>
     */
    private static String reachableConstructor(ITypeBinding binding) {
        IMethodBinding shortest = null;
        for (IMethodBinding method : binding.getDeclaredMethods()) {
            if (!method.isConstructor() || Modifier.isPrivate(method.getModifiers())) {
                continue;
            }
            if (shortest == null
                    || method.getParameterTypes().length < shortest.getParameterTypes().length) {
                shortest = method;
            }
        }
        // A class that declares NO constructor has an implicit accessible no-arg one.
        boolean declaresAny = false;
        for (IMethodBinding method : binding.getDeclaredMethods()) {
            declaresAny |= method.isConstructor();
        }
        if (!declaresAny) {
            return "// the parent's implicit no-argument constructor";
        }
        if (shortest == null) {
            return null;
        }
        StringBuilder call = new StringBuilder("super(");
        ITypeBinding[] params = shortest.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            call.append(i == 0 ? "" : ", ").append(neutralValue(params[i]));
        }
        return call.append(");").toString();
    }

    /** One overriding method whose body returns the neutral value for its return type. */
    private static String neutralOverride(IMethodBinding method) {
        StringBuilder out = new StringBuilder("    @Override\n    public ");
        out.append(method.getReturnType().getName()).append(' ')
            .append(method.getName()).append('(');
        ITypeBinding[] params = method.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            out.append(i == 0 ? "" : ", ").append(params[i].getName())
                .append(" a").append(i);
        }
        out.append(") {\n");
        if ("void".equals(method.getReturnType().getName())) {
            out.append("        // the absent case does nothing\n");
        } else {
            out.append("        return ").append(neutralValue(method.getReturnType()))
                .append(";\n");
        }
        return out.append("    }\n").toString();
    }

    /** What "nothing" looks like for a type — the whole neutrality policy, in one place. */
    private static String neutralValue(ITypeBinding type) {
        String name = type.getName();
        return switch (name) {
            case "boolean", "Boolean" -> "false";
            case "char", "Character" -> "'\\0'";
            case "byte", "short", "int", "long", "Integer", "Long", "Short", "Byte" -> "0";
            case "float", "double", "Float", "Double" -> "0";
            case "String" -> "\"\"";
            case "List" -> "java.util.List.of()";
            case "Set" -> "java.util.Set.of()";
            case "Map" -> "java.util.Map.of()";
            case "Optional" -> "java.util.Optional.empty()";
            default -> "null";
        };
    }

    private static TypeDeclaration typeNamed(CompilationUnit ast, String name) {
        TypeDeclaration[] found = new TypeDeclaration[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (found[0] == null && node.getName().getIdentifier().equals(name)) {
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
