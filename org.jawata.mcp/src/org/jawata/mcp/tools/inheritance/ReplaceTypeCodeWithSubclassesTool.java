package org.jawata.mcp.tools.inheritance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.CreateCompilationUnitChange;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.jawata.mcp.tools.shared.TypeLookup;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code hierarchy direction=replace_type_code_with_subclasses} — Fowler row 59.
 *
 * <p>A class that carries a type code and branches on it is several classes wearing one name.
 * This gives each code value a SUBCLASS, so the branching becomes dispatch.</p>
 *
 * <h2>Conservative, and deliberately the same scope as its neighbour</h2>
 *
 * <p>{@code refactor_to_pattern kind=replace_type_code_with_class} — the OTHER cure for the same
 * smell, shipped in Sprint 19 — introduces its abstraction and reports the mapping without
 * retyping fields or rewriting usages, because that "cascades through arithmetic/switch/
 * serialization and is the agent's call". <b>The same sentence is true here and this row makes
 * the same choice.</b> It generates the subclasses and reports the mapping; it does NOT delete
 * the field, rewrite constructor calls, or introduce a factory. Two cures for one smell that
 * scope themselves differently would be a worse answer than either.</p>
 *
 * <h2>The accessor is the precondition, and it is Fowler's own first step</h2>
 *
 * <p>Fowler's mechanic opens by self-encapsulating the type code, because a subclass has nothing
 * to override until the code is READ through a method. This row REQUIRES that step to have
 * happened, refuses by name when it has not, and points at the operation that performs it —
 * rather than doing it silently as a hidden first half.</p>
 */
public class ReplaceTypeCodeWithSubclassesTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a class. */
        public static final String NOT_A_TYPE = "NOT_A_TYPE";
        /** The class is final, so it cannot be subclassed at all. */
        public static final String CLASS_IS_FINAL = "CLASS_IS_FINAL";
        /** No group of same-typed static final constants sharing a prefix. */
        public static final String NO_TYPE_CODE_CONSTANTS = "NO_TYPE_CODE_CONSTANTS";
        /** The code is not read through a method, so a subclass has nothing to override. */
        public static final String CODE_NOT_ENCAPSULATED = "CODE_NOT_ENCAPSULATED";
        /** A file one of the generated subclasses would occupy already exists. */
        public static final String SUBCLASS_FILE_EXISTS = "SUBCLASS_FILE_EXISTS";
        /** No single base constructor, or none taking the code — nothing for a subclass to call. */
        public static final String BASE_CONSTRUCTOR_AMBIGUOUS = "BASE_CONSTRUCTOR_AMBIGUOUS";

        private Refusal() {
        }
    }

    public ReplaceTypeCodeWithSubclassesTool(Supplier<IJdtService> serviceSupplier,
                                             RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "replace_type_code_with_subclasses";
    }

    @Override
    public String getName() {
        return "replace_type_code_with_subclasses";
    }

    @Override
    public String getDescription() {
        return """
            Replace Type Code with Subclasses — each value of a type code becomes a SUBCLASS, so
            branching on the code becomes dispatch. Point at the class (typeName=pkg.Type, or a
            position); an optional prefix picks the constant group when there is more than one.
            REQUIRES the code to be read through a no-argument accessor, which is Fowler's own
            first step (self-encapsulate) — without one a subclass has nothing to override, and
            this row refuses and names the operation that performs it rather than doing it
            silently. CONSERVATIVE, on the same terms as its sibling replace_type_code_with_class:
            it generates the subclasses and reports the mapping, and does NOT delete the field,
            rewrite constructor calls, or introduce a factory.""";
    }

    /** Structural: it adds types to the hierarchy. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the class that carries the type code."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the class declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("typeName", Map.of("type", "string",
            "description", "Fully-qualified class name, as an alternative to a position."));
        properties.put("prefix", Map.of("type", "string",
            "description", "Optional constant prefix (e.g. GRADE) when the class carries more"
                + " than one group of type codes."));
        schema.put("properties", properties);
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        java.util.Optional<ToolResponse> nameForm =
            FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return Preparation.fail(nameForm.get());
        }
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath",
                "filePath is required (or name the class with typeName=pkg.Type)"));
        }
        org.eclipse.jdt.core.IJavaElement element = service.getElementAtPosition(
            java.nio.file.Path.of(filePath),
            getIntParam(arguments, "line", -1), getIntParam(arguments, "column", -1));
        IType type = element instanceof IType found ? found
            : element == null ? null : (IType) element.getAncestor(org.eclipse.jdt.core.IJavaElement.TYPE);
        if (type == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a class.", Refusal.NOT_A_TYPE));
        }

        ICompilationUnit unit = type.getCompilationUnit();
        CompilationUnit ast = parse(unit);
        AbstractTypeDeclaration declared = TypeLookup.declaration(ast, type);
        if (!(declared instanceof TypeDeclaration target) || target.isInterface()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the target is not a class.", Refusal.NOT_A_TYPE));
        }
        if (Modifier.isFinal(target.getModifiers())) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + type.getElementName() + "' is final, so it cannot be subclassed. Its"
                    + " sibling cure — refactor_to_pattern kind=replace_type_code_with_class —"
                    + " needs no subclassing and is the answer for a final class.",
                Refusal.CLASS_IS_FINAL));
        }

        // THE CONSTANT GROUP, chosen the way the sibling row chooses it: same declared type,
        // shared prefix before the first underscore, largest group wins unless a prefix is named.
        String wanted = getStringParam(arguments, "prefix");
        Map<String, List<String>> byPrefix = new LinkedHashMap<>();
        Map<String, String> typeOfPrefix = new LinkedHashMap<>();
        for (Object each : target.bodyDeclarations()) {
            if (!(each instanceof FieldDeclaration field)
                    || !Modifier.isStatic(field.getModifiers())
                    || !Modifier.isFinal(field.getModifiers())) {
                continue;
            }
            for (Object frag : field.fragments()) {
                String name = ((VariableDeclarationFragment) frag).getName().getIdentifier();
                int underscore = name.indexOf('_');
                if (underscore <= 0) {
                    continue;
                }
                String prefix = name.substring(0, underscore);
                byPrefix.computeIfAbsent(prefix, k -> new ArrayList<>()).add(name);
                typeOfPrefix.putIfAbsent(prefix, field.getType().toString());
            }
        }
        String prefix = null;
        for (Map.Entry<String, List<String>> group : byPrefix.entrySet()) {
            if (wanted != null && !wanted.isBlank()) {
                if (group.getKey().equals(wanted)) {
                    prefix = group.getKey();
                }
            } else if (group.getValue().size() >= 2
                    && (prefix == null || group.getValue().size() > byPrefix.get(prefix).size())) {
                prefix = group.getKey();
            }
        }
        if (prefix == null) {
            return Preparation.fail(ToolResponse.invalidParameter("prefix",
                "'" + type.getElementName() + "' declares no group of two or more static final"
                    + " constants sharing a PREFIX_ — which is what a type code looks like."
                    + (wanted == null ? "" : " Asked for '" + wanted + "'; found "
                        + byPrefix.keySet() + "."),
                Refusal.NO_TYPE_CODE_CONSTANTS));
        }
        List<String> constants = byPrefix.get(prefix);

        // THE ACCESSOR — Fowler's first step, required rather than performed. A no-argument
        // method whose body is a bare `return <field>;`, where the field's type matches the
        // constants'. Without it the generated subclasses would have nothing to override and
        // the whole change would be inert.
        String accessor = accessorReturningTheCode(target, typeOfPrefix.get(prefix));
        if (accessor == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the type code is not read through a no-argument accessor returning "
                    + typeOfPrefix.get(prefix) + ", so a subclass would have nothing to"
                    + " override. That is Fowler's own first step and this row does not do it"
                    + " silently: run data kind=encapsulate_field on the code field first.",
                Refusal.CODE_NOT_ENCAPSULATED));
        }

        // THE BASE'S CONSTRUCTOR, because the subclass must have one. A subclass declaring none
        // gets an implicit default calling super(), which does not exist once the base declares
        // a constructor of its own — so the generated file would not compile.
        //
        // AND THE COMPILE GATE WOULD NOT SAY SO. Probed: a generated file containing
        // `return NO_SUCH_CONSTANT_PROBE;` — an unresolved symbol — still reported SUCCESS.
        //
        // THE PRECISE CLAIM, because the first version of this note overstated it and a
        // MUTATION corrected it minutes later. The gate is NOT blind to created files: a later
        // mutation put `public int null()` into one and the gate refused it, naming
        // "SYNTAX: Syntax error on token null". Created files ARE parsed. What is not checked
        // is RESOLUTION — an unresolved symbol, a missing constructor, a bad override. That is
        // precisely the half generated code gets wrong, because generated code is
        // syntactically correct by construction and wrong about what it REFERS to.
        //
        // MEASURED, and it is why this row's parity golden earns its keep: with the
        // constructor generation disabled, NINE of ten tests stayed green — every `contains`
        // assertion and the compile gate alike — and only the GOLDEN went red. For a row that
        // generates source, the golden is the one instrument that sees an unresolvable
        // reference. Recorded for the checkpoint.
        MethodDeclaration baseConstructor = soleConstructor(target);
        if (baseConstructor == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + type.getElementName() + "' declares no constructor, or more than one, so"
                    + " which one a subclass should adopt is a design decision this row would be"
                    + " inventing. Give it exactly one and run this again.",
                Refusal.BASE_CONSTRUCTOR_AMBIGUOUS));
        }
        // WHICH parameter carries the code — the first of the constants' own type. The
        // generated subclass takes every OTHER parameter and supplies the constant at this
        // position, which is what makes the subclass worth having: `new EnrolmentProvisional()`
        // rather than `new Enrolment(GRADE_PROVISIONAL)`.
        List<String> carried = new ArrayList<>();
        List<String> parameterNames = new ArrayList<>();
        int codeAt = -1;
        for (Object each : baseConstructor.parameters()) {
            org.eclipse.jdt.core.dom.SingleVariableDeclaration parameter =
                (org.eclipse.jdt.core.dom.SingleVariableDeclaration) each;
            parameterNames.add(parameter.getName().getIdentifier());
            if (codeAt < 0 && parameter.getType().toString().equals(typeOfPrefix.get(prefix))) {
                codeAt = parameterNames.size() - 1;
            } else {
                carried.add(parameter.getType() + " " + parameter.getName().getIdentifier());
            }
        }
        if (codeAt < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + type.getElementName() + "'s constructor takes no "
                    + typeOfPrefix.get(prefix) + " parameter, so a subclass has no way to supply"
                    + " its own code and would only repeat the base.",
                Refusal.BASE_CONSTRUCTOR_AMBIGUOUS));
        }

        String pkg = ast.getPackage() == null ? null
            : ast.getPackage().getName().getFullyQualifiedName();
        IContainer parent = (IContainer) unit.getResource().getParent();
        List<Change> creations = new ArrayList<>();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (String constant : constants) {
            String subclass = type.getElementName() + camel(strip(constant, prefix));
            IFile file = parent.getFile(new org.eclipse.core.runtime.Path(subclass + ".java"));
            if (file.exists()) {
                return Preparation.fail(ToolResponse.invalidParameter("position",
                    "a file named " + subclass + ".java already exists in this package, so the"
                        + " subclass for " + constant + " cannot be created.",
                    Refusal.SUBCLASS_FILE_EXISTS));
            }
            List<String> superArguments = new ArrayList<>(parameterNames);
            superArguments.set(codeAt, constant);
            creations.add(new CreateCompilationUnitChange(file, subclassSource(pkg, subclass,
                type.getElementName(), accessor, typeOfPrefix.get(prefix), constant,
                carried, superArguments)));
            mapping.put(constant, subclass);
        }

        String label = "replace type code: generated " + creations.size() + " subclass(es) of "
            + type.getElementName() + " from its '" + prefix + "_*' constants";
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("sourceClass", type.getElementName());
        extras.put("typeCodePrefix", prefix);
        extras.put("accessor", accessor);
        extras.put("constantMapping", mapping);
        extras.put("note", "The field, its constructor and every `new " + type.getElementName()
            + "` remain: retyping them cascades through construction and serialization and is"
            + " your call. Each subclass overrides " + accessor + "().");
        return Preparation.of(new CompositeChange(label, creations.toArray(new Change[0])),
            label, extras);
    }

    /** A no-argument method whose body is a bare {@code return <field>;} of the code's type. */
    private String accessorReturningTheCode(TypeDeclaration target, String codeType) {
        for (MethodDeclaration method : target.getMethods()) {
            if (method.isConstructor() || !method.parameters().isEmpty()
                    || method.getReturnType2() == null
                    || !method.getReturnType2().toString().equals(codeType)
                    || method.getBody() == null
                    || method.getBody().statements().size() != 1) {
                continue;
            }
            Statement only = (Statement) method.getBody().statements().get(0);
            if (only instanceof ReturnStatement returned
                    && returned.getExpression() instanceof SimpleName) {
                return method.getName().getIdentifier();
            }
        }
        return null;
    }

    private String strip(String constant, String prefix) {
        return constant.startsWith(prefix + "_") ? constant.substring(prefix.length() + 1)
            : constant;
    }

    /** {@code SENIOR_MANAGER} becomes {@code SeniorManager}. */
    private String camel(String screamingSnake) {
        StringBuilder out = new StringBuilder();
        for (String word : screamingSnake.split("_")) {
            if (word.isEmpty()) {
                continue;
            }
            out.append(Character.toUpperCase(word.charAt(0)))
                .append(word.substring(1).toLowerCase());
        }
        return out.toString();
    }

    /**
     * The base's ONE constructor, or null when it has none or several.
     *
     * <p>Several is refused rather than guessed: which one a type-code subclass should adopt is
     * a design decision, and picking the first would be inventing an answer.</p>
     */
    private MethodDeclaration soleConstructor(TypeDeclaration target) {
        MethodDeclaration only = null;
        for (MethodDeclaration method : target.getMethods()) {
            if (!method.isConstructor()) {
                continue;
            }
            if (only != null) {
                return null;
            }
            only = method;
        }
        return only;
    }

    private String subclassSource(String pkg, String name, String base, String accessor,
                                  String codeType, String constant, List<String> carried,
                                  List<String> superArguments) {
        StringBuilder out = new StringBuilder();
        if (pkg != null) {
            out.append("package ").append(pkg).append(";\n\n");
        }
        out.append("/**\n")
            .append(" * The ").append(constant).append(" case of {@link ").append(base)
            .append("}, as its own type.\n *\n")
            .append(" * <p>Generated by Replace Type Code with Subclasses. ").append(base)
            .append("'s code field and\n")
            .append(" * constructor are UNCHANGED: retyping them cascades through construction")
            .append(" and\n * serialization, so it is left to you.</p>\n */\n");
        out.append("public class ").append(name).append(" extends ").append(base).append(" {\n\n");
        // THE CONSTRUCTOR, and it is not optional. A subclass with none gets an implicit
        // default that calls super() — which does not exist when the base declares a
        // constructor of its own, so the generated file would not compile. It supplies the
        // CONSTANT at the code's position, which is the point of the subclass: `new
        // EnrolmentProvisional()` instead of `new Enrolment(GRADE_PROVISIONAL)`.
        out.append("    public ").append(name).append('(');
        for (int i = 0; i < carried.size(); i++) {
            out.append(i == 0 ? "" : ", ").append(carried.get(i));
        }
        out.append(") {\n        super(").append(String.join(", ", superArguments))
            .append(");\n    }\n\n");
        out.append("    @Override\n")
            .append("    public ").append(codeType).append(' ').append(accessor).append("() {\n")
            .append("        return ").append(constant).append(";\n")
            .append("    }\n}\n");
        return out.toString();
    }

    /** Parse with bindings. See {@link PullUpConstructorBodyTool}'s note on the sixteen copies. */
    private CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
