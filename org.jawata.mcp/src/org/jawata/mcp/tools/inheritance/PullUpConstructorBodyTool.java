package org.jawata.mcp.tools.inheritance;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SuperConstructorInvocation;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FormatterOptions;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.jawata.mcp.tools.shared.MethodLookup;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code hierarchy direction=pull_up_constructor_body} — Fowler row 29, Pull Up Constructor Body.
 *
 * <p>Subclass constructors that begin by assigning the SUPERCLASS's own fields are doing the
 * superclass's job. The assignments move into a superclass constructor and the subclass calls
 * {@code super(...)} instead — so the superclass's state is established in one place, by the
 * class that owns it.</p>
 *
 * <h2>What it acts on, and why the subset is the design</h2>
 *
 * <p>Fowler's mechanic starts with "constructors on subclasses with mostly identical bodies" and
 * ends with a judgement about which statements are common. Performing THAT in general means
 * deciding which of several near-identical bodies is the canonical one, which is a design
 * decision this row would be inventing. So it performs the case where the answer is not a
 * judgement at all: <b>a LEADING run of statements, each assigning a field the SUPERCLASS
 * declares, from a parameter of this constructor.</b> Those statements belong to the superclass
 * by ownership rather than by resemblance, and nothing has to be chosen.</p>
 *
 * <p>Every other shape is refused BY NAME, so a caller is told which case they have rather than
 * handed a half-done pull-up.</p>
 *
 * <h2>It GENERATES the superclass constructor only if one is missing</h2>
 *
 * <p>If the superclass already declares a constructor taking exactly those parameter types, the
 * row calls it and generates nothing — the class had already done half of this refactoring, and
 * a second constructor of the same signature would not compile. That is row 9's finding applied
 * before foreign code could force it: <i>real code is found part-way through somebody else's
 * edit</i>, and a fixture is written in the state the operation expects.</p>
 */
public class PullUpConstructorBodyTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a constructor. */
        public static final String NOT_A_CONSTRUCTOR = "NOT_A_CONSTRUCTOR";
        /** The declaring class extends nothing but {@code Object}. */
        public static final String NO_SUPERCLASS = "NO_SUPERCLASS";
        /** The superclass is not source we can add a constructor to. */
        public static final String SUPERCLASS_NOT_IN_SOURCE = "SUPERCLASS_NOT_IN_SOURCE";
        /** The constructor already delegates upward with arguments. */
        public static final String ALREADY_CALLS_SUPER = "ALREADY_CALLS_SUPER";
        /** No LEADING statement assigns a superclass field from a parameter. */
        public static final String NOTHING_TO_PULL_UP = "NOTHING_TO_PULL_UP";

        private Refusal() {
        }
    }

    public PullUpConstructorBodyTool(Supplier<IJdtService> serviceSupplier,
                                     RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "pull_up_constructor_body";
    }

    @Override
    public String getName() {
        return "pull_up_constructor_body";
    }

    @Override
    public String getDescription() {
        return """
            Pull Up Constructor Body — a subclass constructor that starts by assigning the
            SUPERCLASS's own fields hands those assignments to the superclass and calls
            super(...) instead. Point at the constructor (symbol=pkg.Type#Type, or a position).
            Acts on the LEADING run of statements that assign a superclass-declared field from
            one of this constructor's parameters; the superclass constructor is GENERATED only
            if one with those parameter types is missing, because a class part-way through this
            refactoring already has it. REFUSES a constructor that already calls super with
            arguments, a superclass outside source, and a body whose leading statements are
            anything else — each by name, so you are told which case you have.""";
    }

    /** Structural: it moves a member through the hierarchy and changes a constructor's contract. */
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
            "description", "Source file declaring the subclass constructor."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the constructor's declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("symbol", FqnTarget.symbolSchemaProperty(
            "subclass constructor whose leading assignments belong to the superclass"));
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
                "filePath is required (or name the constructor with symbol=pkg.Type#Type)"));
        }
        IJavaElement element = service.getElementAtPosition(Path.of(filePath),
            getIntParam(arguments, "line", -1), getIntParam(arguments, "column", -1));
        if (!(element instanceof IMethod method) || !method.isConstructor()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a constructor.", Refusal.NOT_A_CONSTRUCTOR));
        }

        ICompilationUnit unit = method.getCompilationUnit();
        CompilationUnit ast = parse(unit);
        MethodDeclaration decl = MethodLookup.declaration(ast, method);
        if (decl == null || decl.getBody() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "could not locate the constructor's body.", Refusal.NOT_A_CONSTRUCTOR));
        }

        IMethodBinding bound = decl.resolveBinding();
        ITypeBinding subclass = bound == null ? null : bound.getDeclaringClass();
        ITypeBinding superclass = subclass == null ? null : subclass.getSuperclass();
        if (superclass == null || "java.lang.Object".equals(superclass.getQualifiedName())) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + (subclass == null ? "?" : subclass.getName()) + "' extends nothing but"
                    + " Object, so there is no superclass to pull the body up INTO.",
                Refusal.NO_SUPERCLASS));
        }
        if (!(superclass.getJavaElement() instanceof IType superType)
                || superType.getCompilationUnit() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the superclass '" + superclass.getName() + "' is not source this workspace can"
                    + " edit, so no constructor can be added to it.",
                Refusal.SUPERCLASS_NOT_IN_SOURCE));
        }

        @SuppressWarnings("unchecked")
        List<Statement> body = decl.getBody().statements();
        if (!body.isEmpty() && body.get(0) instanceof SuperConstructorInvocation existing
                && !existing.arguments().isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "this constructor already delegates upward with arguments, so the superclass is"
                    + " already establishing its own state and what remains here is the"
                    + " subclass's own work.", Refusal.ALREADY_CALLS_SUPER));
        }

        // THE LEADING RUN, and leading is the whole safety argument: a statement further down
        // may depend on one above it, so moving it changes the order things happen in. The run
        // stops at the first statement that is not a superclass-field assignment from a
        // parameter, and everything after it stays exactly where it is.
        List<Statement> pulled = new ArrayList<>();
        List<String> parameters = new ArrayList<>();
        for (Statement statement : body) {
            // A BARE super() carries no information and is not a reason to stop: it says only
            // "the superclass sets itself up with nothing", which is what this row is about to
            // change. It is skipped here and REPLACED below, rather than left beside the new
            // call, which would delegate upward twice.
            if (statement instanceof SuperConstructorInvocation bare && bare.arguments().isEmpty()) {
                continue;
            }
            String parameter = superFieldAssignedFromParameter(statement, decl, superclass);
            if (parameter == null) {
                break;
            }
            pulled.add(statement);
            parameters.add(parameter);
        }
        if (pulled.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "no LEADING statement of this constructor assigns a field declared by '"
                    + superclass.getName() + "' from one of its own parameters. That is what"
                    + " this row moves — statements that belong to the superclass by OWNERSHIP"
                    + " rather than by resembling another subclass's.",
                Refusal.NOTHING_TO_PULL_UP));
        }

        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();

        // THE SUBCLASS: the pulled statements go, and super(...) takes their place.
        ASTRewrite subRewrite = ASTRewrite.create(ast.getAST());
        ListRewrite statements = subRewrite.getListRewrite(decl.getBody(),
            org.eclipse.jdt.core.dom.Block.STATEMENTS_PROPERTY);
        for (Statement gone : pulled) {
            statements.remove(gone, null);
        }
        String superCall = "super(" + String.join(", ", parameters) + ");";
        if (!body.isEmpty() && body.get(0) instanceof SuperConstructorInvocation bare) {
            subRewrite.replace(bare, subRewrite.createStringPlaceholder(superCall,
                ASTNode.SUPER_CONSTRUCTOR_INVOCATION), null);
        } else {
            statements.insertFirst(subRewrite.createStringPlaceholder(superCall,
                ASTNode.SUPER_CONSTRUCTOR_INVOCATION), null);
        }
        // THE SUPERCLASS: a constructor taking those parameters, generated ONLY if missing.
        //
        // A NESTED superclass lives in the SAME compilation unit as its subclass, and then both
        // edits belong to ONE rewrite. The first version built a second ASTRewrite regardless
        // and put both results into a map keyed by file — so the second put REPLACED the first
        // and the subclass was silently left untouched while the superclass gained its
        // constructor. Nothing below the row could see that: the result compiled, because a
        // subclass that still assigns the fields itself is valid Java.
        List<String> types = parameterTypes(decl, parameters);
        boolean generated = false;
        ICompilationUnit superUnit = superType.getCompilationUnit();
        boolean sameFile = superUnit.equals(unit);
        if (!declaresConstructor(superclass, types)) {
            CompilationUnit superAst = sameFile ? ast : parse(superUnit);
            // THROUGH THE SHARED LOOKUP, joined on the element's own source range. The first
            // version of this walked ast.types() — the file's TOP-LEVEL types — and could not
            // see a nested superclass, which is the defect Stage 5 found in FIVE code
            // generators at once and closed by building this class. Written again here, by
            // hand, one stage later; caught by this row's own test rather than by review.
            AbstractTypeDeclaration target =
                org.jawata.mcp.tools.shared.TypeLookup.declaration(superAst, superType);
            if (target == null) {
                return Preparation.fail(ToolResponse.invalidParameter("position",
                    "could not locate '" + superclass.getName() + "' in "
                        + superUnit.getElementName() + ".", Refusal.SUPERCLASS_NOT_IN_SOURCE));
            }
            ASTRewrite superRewrite = sameFile ? subRewrite : ASTRewrite.create(superAst.getAST());
            // The type asks ITSELF which property holds its members, rather than a cast
            // deciding — a record, an enum and a class each answer differently.
            ListRewrite members = superRewrite.getListRewrite(target,
                target.getBodyDeclarationsProperty());
            // THE IMPLICIT DEFAULT IS DESTROYED BY ADDING ANY CONSTRUCTOR, so if the class had
            // none it gets an explicit one back. Every OTHER subclass reaches its superclass
            // through that default — none of them names it, which is exactly why it is easy to
            // take away without noticing.
            //
            // FOUND BY THE COMPILE GATE, not by reasoning: the first version emitted only the
            // new constructor and the gate answered "Implicit super constructor Employment() is
            // undefined" for two sibling subclasses. No fixture of mine would have caught it
            // either, because a fixture written for this row has the subclass the row acts on
            // and no reason to have the ones that merely inherit.
            boolean hadNone = declaredConstructorCount(superclass) == 0;
            if (hadNone) {
                members.insertLast(superRewrite.createStringPlaceholder(
                    "protected " + superclass.getName() + "() {\n}",
                    ASTNode.METHOD_DECLARATION), null);
            }
            members.insertLast(superRewrite.createStringPlaceholder(
                generatedConstructor(superclass.getName(), types, parameters, pulled,
                    unit.getSource()),
                ASTNode.METHOD_DECLARATION), null);
            if (!sameFile) {
                byFile.put((IFile) superUnit.getResource(), List.of(superRewrite.rewriteAST(
                    new Document(superUnit.getSource()),
                    FormatterOptions.forGeneratedCode(superAst))));
            }
            generated = true;
        }

        // THE SUBCLASS LAST, so that when the superclass shares this file its insertion is
        // already recorded on the same rewrite and both reach the document together.
        byFile.put((IFile) unit.getResource(), List.of(subRewrite.rewriteAST(
            new Document(unit.getSource()), FormatterOptions.forGeneratedCode(ast))));

        String label = "pull " + pulled.size() + " leading assignment(s) out of "
            + subclass.getName() + "'s constructor into " + superclass.getName();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("subclass", subclass.getName());
        extras.put("superclass", superclass.getName());
        extras.put("statementsPulled", pulled.size());
        extras.put("constructorGenerated", generated);
        return Preparation.of(ChangeEngine.fromFileEdits(label, byFile),
            label + (generated
                ? " (a constructor was generated there; it had none taking " + types + ")"
                : " (its constructor taking " + types + " already existed and is now called)"),
            extras);
    }

    /**
     * Parse with bindings — this row needs them, because "a field the SUPERCLASS declares" is a
     * question only a resolved binding answers.
     *
     * <p><b>This is the sixteenth private copy of these four lines in this bundle</b> — measured,
     * not estimated: {@code search_symbols(query="parse", kind=Method)} returns fifteen others,
     * one per tool that needs an AST. Following the convention rather than reaching for
     * {@code SourceScan}, whose {@code parse} belongs to a scan that tracks unparseable files and
     * is the wrong shape for one unit. The duplication is real and is recorded for the
     * checkpoint rather than fixed inside a row.</p>
     */
    private CompilationUnit parse(ICompilationUnit unit) {
        org.eclipse.jdt.core.dom.ASTParser parser = org.eclipse.jdt.core.dom.ASTParser
            .newParser(org.eclipse.jdt.core.dom.AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }

    /**
     * The parameter name this statement assigns a superclass field from, or null.
     *
     * <p>Accepts the three spellings that denote the receiver's own state — {@code f = p},
     * {@code this.f = p} — and nothing else. A qualified write through another object is not
     * this constructor establishing its own state, which is the same boundary
     * {@code CqsDetector} had to learn.</p>
     */
    private String superFieldAssignedFromParameter(Statement statement, MethodDeclaration decl,
                                                   ITypeBinding superclass) {
        if (!(statement instanceof org.eclipse.jdt.core.dom.ExpressionStatement expr)
                || !(expr.getExpression() instanceof Assignment assignment)
                || assignment.getOperator() != Assignment.Operator.ASSIGN) {
            return null;
        }
        IVariableBinding field = assignedField(assignment.getLeftHandSide());
        if (field == null || !field.isField() || field.getDeclaringClass() == null
                || !superclass.getKey().equals(field.getDeclaringClass().getKey())) {
            return null;
        }
        if (!(assignment.getRightHandSide() instanceof SimpleName value)) {
            return null;
        }
        IVariableBinding read = value.resolveBinding() instanceof IVariableBinding v ? v : null;
        if (read == null || read.isField()) {
            return null;
        }
        for (Object each : decl.parameters()) {
            if (each instanceof SingleVariableDeclaration parameter
                    && parameter.getName().getIdentifier().equals(value.getIdentifier())) {
                return value.getIdentifier();
            }
        }
        return null;
    }

    private IVariableBinding assignedField(Expression target) {
        if (target instanceof SimpleName name
                && name.resolveBinding() instanceof IVariableBinding variable) {
            return variable;
        }
        if (target instanceof FieldAccess access && access.getExpression() instanceof ThisExpression) {
            return access.resolveFieldBinding();
        }
        if (target instanceof org.eclipse.jdt.core.dom.QualifiedName qualified
                && qualified.getQualifier() instanceof Name
                && qualified.resolveBinding() instanceof IVariableBinding variable) {
            return variable;
        }
        return null;
    }

    private List<String> parameterTypes(MethodDeclaration decl, List<String> names) {
        List<String> types = new ArrayList<>();
        for (String name : names) {
            for (Object each : decl.parameters()) {
                if (each instanceof SingleVariableDeclaration parameter
                        && parameter.getName().getIdentifier().equals(name)) {
                    types.add(parameter.getType().toString());
                }
            }
        }
        return types;
    }

    /**
     * How many constructors the class actually WROTE — zero means it relies on the implicit one.
     *
     * <p>{@code isDefaultConstructor()} is the whole point of this method. JDT reports the
     * IMPLICIT constructor in {@code getDeclaredMethods()} alongside real ones, so counting
     * constructors answers "at least one" for a class that declares none — which is exactly
     * backwards for the question being asked, and made the first version of this check silently
     * never fire.</p>
     */
    private int declaredConstructorCount(ITypeBinding type) {
        int written = 0;
        for (IMethodBinding candidate : type.getDeclaredMethods()) {
            written += candidate.isConstructor() && !candidate.isDefaultConstructor() ? 1 : 0;
        }
        return written;
    }

    private boolean declaresConstructor(ITypeBinding type, List<String> types) {
        for (IMethodBinding candidate : type.getDeclaredMethods()) {
            if (!candidate.isConstructor()
                    || candidate.getParameterTypes().length != types.size()) {
                continue;
            }
            boolean same = true;
            for (int i = 0; i < types.size(); i++) {
                same &= candidate.getParameterTypes()[i].getName().equals(types.get(i));
            }
            if (same) {
                return true;
            }
        }
        return false;
    }

    /** The generated constructor, carrying the subclass's own assignment text verbatim. */
    private String generatedConstructor(String name, List<String> types, List<String> parameters,
                                        List<Statement> pulled, String subclassSource) {
        StringBuilder text = new StringBuilder();
        text.append("protected ").append(name).append('(');
        for (int i = 0; i < types.size(); i++) {
            text.append(i == 0 ? "" : ", ").append(types.get(i)).append(' ')
                .append(parameters.get(i));
        }
        text.append(") {\n");
        for (Statement statement : pulled) {
            // The subclass's OWN text, not a regenerated equivalent: it is already the
            // assignment the author wrote, and re-spelling it is a chance to spell it wrong.
            text.append("    ").append(subclassSource.substring(statement.getStartPosition(),
                statement.getStartPosition() + statement.getLength()).trim()).append('\n');
        }
        text.append("}");
        return text.toString();
    }
}
