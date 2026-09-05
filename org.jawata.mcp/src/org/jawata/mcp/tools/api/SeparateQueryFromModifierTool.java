package org.jawata.mcp.tools.api;

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
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.SearchMatch;
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

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code change_method_signature kind=separate_query_from_modifier} — Fowler row 61, Separate
 * Query from Modifier.
 *
 * <p>A method that changes state AND answers a question cannot be asked without also causing.
 * You cannot call it to find out, cannot call it twice, cannot move it, cannot memoise it. The
 * cure is two methods: a command that changes and answers nothing, and a query that answers and
 * changes nothing.</p>
 *
 * <h2>The subset this row performs, and why it is a subset</h2>
 *
 * <p>Fowler's mechanics copy the method, strip the side effects from the copy, and strip the
 * return from the original. <b>That is not available to a machine here</b>, because the copy
 * then RUNS THE BODY A SECOND TIME: every call, every log line, every I/O in it happens twice,
 * and deciding which of those are safe to repeat is the purity question row 45 already
 * established cannot be decided. A tool that duplicated a body and hoped would be the worst
 * kind of refactoring — one that compiles.</p>
 *
 * <p>So this row performs the case where NO copy is needed: the method's return hands back a
 * FIELD IT JUST WROTE. Then the query is a read of that field, the command is the method with
 * its return removed, and nothing runs twice. The transformation is exact rather than hopeful,
 * and it is the commonest real shape — a method that updates state and returns the state it
 * updated.</p>
 *
 * <p>Anything else is REFUSED with the reason, rather than performed approximately.</p>
 *
 * <h2>What happens at the call sites</h2>
 *
 * <p>{@code int n = breaker.recordFailure();} becomes {@code breaker.recordFailure();} followed
 * by {@code int n = breaker.failureCount();} — two adjacent statements, so no other code can
 * change the field between them. A call whose value is IGNORED already is left exactly as it
 * is: it was only ever a command. A call whose value is used somewhere a statement cannot be
 * split out of — nested in a larger expression, in a return, in an argument — is REFUSED,
 * because splitting it would need a temporary this row has no name for.</p>
 *
 * <h2>An existing query is REUSED, not duplicated</h2>
 *
 * <p>If the class already declares a no-argument method that returns exactly that field, that
 * IS the query and the row uses it. Real code is often found half-way through this refactoring
 * — somebody added the getter and never removed the return — which is the shape Stage 5's row 9
 * met on upstream's {@code WorkCenter} and which refusing here would decline the very case the
 * detector is most likely to report.</p>
 */
public class SeparateQueryFromModifierTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Callers are enumerated, never sampled; reaching this cap REFUSES rather than truncates. */
    private static final int MAX_REFERENCES = 1000;

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a method. */
        public static final String NOT_A_METHOD = "NOT_A_METHOD";
        /** The method answers nothing, so there is no query to separate. */
        public static final String RETURNS_VOID = "RETURNS_VOID";
        /** The method's answer is not a plain read of one of its own fields. */
        public static final String RETURN_NOT_A_WRITTEN_FIELD = "RETURN_NOT_A_WRITTEN_FIELD";
        /** The class already has a method of the query's name that answers something else. */
        public static final String QUERY_NAME_TAKEN = "QUERY_NAME_TAKEN";
        /** A call uses the value somewhere a statement cannot be split out of. */
        public static final String CALL_SITE_NOT_SPLITTABLE = "CALL_SITE_NOT_SPLITTABLE";
        /** The reference search hit its cap, so the caller list is a sample. */
        public static final String REFERENCE_CAP_REACHED = "REFERENCE_CAP_REACHED";

        private Refusal() {
        }
    }

    public SeparateQueryFromModifierTool(Supplier<IJdtService> serviceSupplier,
                                         RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "separate_query_from_modifier";
    }

    @Override
    public String getName() {
        return "separate_query_from_modifier";
    }

    @Override
    public String getDescription() {
        return """
            Separate Query from Modifier — a method that both changes state and answers a
            question becomes a void command plus a query, so it can be asked without causing.
            Name the METHOD (symbol=pkg.Type#m, or a position). Performs the case where the
            method RETURNS A FIELD IT WROTE: the return is removed, a query reading that field
            is added (or an existing one reused), and every call site that used the value is
            split into two adjacent statements. Optional: queryName. Refuses anything else —
            notably a return computed from locals, because separating THAT needs the body run
            twice and no tool can decide whether that is safe.""";
    }

    /** Structural: the signature changes and every call site with it. */
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
            "description", "Source file declaring the method."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the method's declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("symbol", FqnTarget.symbolSchemaProperty("method that commands AND asks"));
        properties.put("queryName", Map.of("type", "string",
            "description", "Name for the query (optional; defaults to the field's own name)."));
        schema.put("properties", properties);
        schema.put("required", List.of());
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
                "filePath is required (or name the method with symbol=pkg.Type#method)"));
        }
        IJavaElement element = service.getElementAtPosition(Path.of(filePath),
            getIntParam(arguments, "line", -1), getIntParam(arguments, "column", -1));
        if (!(element instanceof IMethod method)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a method.", Refusal.NOT_A_METHOD));
        }
        if ("V".equals(method.getReturnType())) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' answers nothing, so it is already a command"
                    + " and there is no query in it to separate.", Refusal.RETURNS_VOID));
        }

        ICompilationUnit unit = method.getCompilationUnit();
        CompilationUnit ast = IntroducedParameter.parse(unit);
        MethodDeclaration decl = declarationOf(ast, method);
        if (decl == null || decl.getBody() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "could not locate the method's body.", Refusal.NOT_A_METHOD));
        }

        List<ReturnStatement> returns = returnsOf(decl);
        @SuppressWarnings("unchecked")
        List<Statement> statements = decl.getBody().statements();
        if (returns.size() != 1 || statements.isEmpty()
            || statements.get(statements.size() - 1) != returns.get(0)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' must end with exactly ONE return, and this"
                    + " one has " + returns.size() + ". Separating a method with several exits"
                    + " means deciding what the query answers on each path, which is yours to"
                    + " decide and not a tool's.",
                Refusal.RETURN_NOT_A_WRITTEN_FIELD));
        }

        ReturnStatement returned = returns.get(0);
        IVariableBinding field = fieldReadBy(returned.getExpression());
        if (field == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' returns an expression rather than a plain"
                    + " read of one of its own fields. Separating THAT would mean running the"
                    + " body a second time to compute the answer — every call and every write"
                    + " in it happening twice — and no tool can decide whether that is safe.",
                Refusal.RETURN_NOT_A_WRITTEN_FIELD));
        }
        if (!writesField(decl, field)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' returns '" + field.getName() + "' but never"
                    + " writes it, so it is already a QUERY and there is no command to"
                    + " separate.", Refusal.RETURN_NOT_A_WRITTEN_FIELD));
        }

        String queryName = getStringParam(arguments, "queryName");
        if (queryName == null || queryName.isBlank()) {
            queryName = field.getName();
        }
        IType declaring = method.getDeclaringType();
        MethodDeclaration existing = noArgMethodNamed(ast, decl, queryName);
        boolean reuseExisting = existing != null;
        if (reuseExisting && !returnsExactly(existing, field)) {
            return Preparation.fail(ToolResponse.invalidParameter("queryName",
                "'" + declaring.getElementName() + "' already declares " + queryName + "() and"
                    + " it answers something other than '" + field.getName() + "'. Give the"
                    + " query another name.", Refusal.QUERY_NAME_TAKEN));
        }

        List<SearchMatch> references =
            service.getSearchService().findAllReferences(method, MAX_REFERENCES);
        if (references.size() >= MAX_REFERENCES) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' has at least " + MAX_REFERENCES
                    + " references, which is the search cap — the caller list would be a SAMPLE"
                    + " and a split that missed a caller would not compile.",
                Refusal.REFERENCE_CAP_REACHED));
        }

        Map<ICompilationUnit, CompilationUnit> asts = new LinkedHashMap<>();
        Map<ICompilationUnit, ASTRewrite> rewrites = new LinkedHashMap<>();
        asts.put(unit, ast);
        rewrites.put(unit, ASTRewrite.create(ast.getAST()));

        int split = 0;
        int alreadyCommands = 0;
        for (SearchMatch match : references) {
            ICompilationUnit callerUnit = unitOf(service, match);
            if (callerUnit == null) {
                continue;
            }
            CompilationUnit callerAst = asts.computeIfAbsent(callerUnit,
                IntroducedParameter::parse);
            rewrites.computeIfAbsent(callerUnit, u -> ASTRewrite.create(callerAst.getAST()));
            MethodInvocation call = invocationAt(callerAst, match.getOffset());
            if (call == null) {
                continue;                       // a javadoc reference or an import, not a call
            }
            Statement statement = splittableStatement(call);
            if (statement == null) {
                if (call.getParent() instanceof ExpressionStatement) {
                    alreadyCommands++;
                    continue;                   // the value was never used: already a command
                }
                return Preparation.fail(ToolResponse.invalidParameter("position",
                    "a call in " + callerUnit.getElementName() + " uses the answer inside a"
                        + " larger expression, so the command cannot be lifted out into a"
                        + " statement of its own without inventing a temporary this row has no"
                        + " name for.", Refusal.CALL_SITE_NOT_SPLITTABLE));
            }
            splitCall(rewrites.get(callerUnit), callerUnit, call, statement, queryName);
            split++;
        }

        ASTRewrite own = rewrites.get(unit);
        own.replace(decl.getReturnType2(), ast.getAST().newPrimitiveType(PrimitiveType.VOID),
            null);
        own.remove(returned, null);
        if (!reuseExisting) {
            insertQuery(own, ast, decl, queryName, field, unit.getSource());
        }

        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();
        for (Map.Entry<ICompilationUnit, ASTRewrite> entry : rewrites.entrySet()) {
            ICompilationUnit each = entry.getKey();
            TextEdit edit = entry.getValue().rewriteAST(new Document(each.getSource()),
                FormatterOptions.forGeneratedCode(asts.get(each)));
            if (edit.hasChildren()) {
                byFile.put((IFile) each.getResource(), List.of(edit));
            }
        }

        String label = "separate query " + queryName + "() from modifier "
            + method.getElementName() + "()";
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("method", method.getElementName());
        extras.put("queryName", queryName);
        extras.put("field", field.getName());
        extras.put("queryReused", reuseExisting);
        extras.put("callSitesSplit", split);
        extras.put("callSitesAlreadyCommands", alreadyCommands);
        return Preparation.of(ChangeEngine.fromFileEdits(label, byFile), label + " ("
            + split + " call site(s) split, " + alreadyCommands + " already command-only"
            + (reuseExisting ? ", existing query reused" : "") + ")", extras);
    }

    /** The declaration, joined on the ELEMENT's own range rather than searched for by name. */
    private static MethodDeclaration declarationOf(CompilationUnit ast, IMethod method)
            throws Exception {
        ISourceRange range = method.getNameRange();
        if (range == null || range.getOffset() < 0) {
            return null;
        }
        ASTNode node = NodeFinder.perform(ast, range.getOffset(), range.getLength());
        while (node != null && !(node instanceof MethodDeclaration)) {
            node = node.getParent();
        }
        return (MethodDeclaration) node;
    }

    /** This method's OWN returns — a lambda's belong to the lambda. */
    private static List<ReturnStatement> returnsOf(MethodDeclaration decl) {
        List<ReturnStatement> found = new ArrayList<>();
        decl.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                found.add(node);
                return true;
            }

            @Override
            public boolean visit(LambdaExpression node) {
                return false;
            }
        });
        return found;
    }

    /** The field this expression is a PLAIN read of — {@code f} or {@code this.f} — else null. */
    private static IVariableBinding fieldReadBy(Expression expression) {
        return switch (expression) {
            case SimpleName name when name.resolveBinding() instanceof IVariableBinding v
                && v.isField() -> v;
            case FieldAccess access when access.getExpression() instanceof ThisExpression -> {
                IVariableBinding v = access.resolveFieldBinding();
                yield v != null && v.isField() ? v : null;
            }
            case null, default -> null;
        };
    }

    /** Whether this method assigns to, or increments, that same field on its own receiver. */
    private static boolean writesField(MethodDeclaration decl, IVariableBinding field) {
        boolean[] writes = {false};
        decl.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                check(node.getLeftHandSide());
                return true;
            }

            @Override
            public boolean visit(PrefixExpression node) {
                check(node.getOperand());
                return true;
            }

            @Override
            public boolean visit(PostfixExpression node) {
                check(node.getOperand());
                return true;
            }

            private void check(Expression target) {
                IVariableBinding hit = fieldReadBy(target);
                if (hit != null && hit.isEqualTo(field)) {
                    writes[0] = true;
                }
            }
        });
        return writes[0];
    }

    /** A no-argument method of that name declared beside {@code decl}, or null. */
    private static MethodDeclaration noArgMethodNamed(CompilationUnit ast, MethodDeclaration decl,
                                                      String name) {
        ASTNode owner = decl.getParent();
        if (!(owner instanceof AbstractTypeDeclaration type)) {
            return null;
        }
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof MethodDeclaration each && each != decl
                && name.equals(each.getName().getIdentifier())
                && each.parameters().isEmpty()) {
                return each;
            }
        }
        return null;
    }

    /** Whether that method's whole body is {@code return <field>;}. */
    private static boolean returnsExactly(MethodDeclaration method, IVariableBinding field) {
        if (method.getBody() == null || method.getBody().statements().size() != 1) {
            return false;
        }
        Object only = method.getBody().statements().get(0);
        if (!(only instanceof ReturnStatement ret)) {
            return false;
        }
        IVariableBinding read = fieldReadBy(ret.getExpression());
        return read != null && read.isEqualTo(field);
    }

    private static ICompilationUnit unitOf(IJdtService service, SearchMatch match) {
        return match.getElement() instanceof IJavaElement element
            ? (ICompilationUnit) element.getAncestor(IJavaElement.COMPILATION_UNIT) : null;
    }

    private static MethodInvocation invocationAt(CompilationUnit ast, int offset) {
        ASTNode node = NodeFinder.perform(ast, offset, 0);
        while (node != null && !(node instanceof MethodInvocation)) {
            node = node.getParent();
        }
        return (MethodInvocation) node;
    }

    /**
     * The STATEMENT this call's value lands in, when it can be split; else null.
     *
     * <p>Two shapes qualify — {@code T v = o.m();} and {@code v = o.m();} — because in both the
     * command can be lifted to the line above and the value read back on the original line. Any
     * other position (an argument, a condition, a return) would need a temporary.</p>
     */
    private static Statement splittableStatement(MethodInvocation call) {
        ASTNode parent = call.getParent();
        if (parent instanceof VariableDeclarationFragment fragment
            && fragment.getInitializer() == call
            && fragment.getParent() instanceof VariableDeclarationStatement statement
            && statement.getParent() instanceof Block) {
            return statement;
        }
        if (parent instanceof Assignment assignment && assignment.getRightHandSide() == call
            && assignment.getParent() instanceof ExpressionStatement statement
            && statement.getParent() instanceof Block) {
            return statement;
        }
        return null;
    }

    /** Lift the command above the statement, and read the answer back through the query. */
    private static void splitCall(ASTRewrite rewrite, ICompilationUnit unit, MethodInvocation call,
                                  Statement statement, String queryName) throws Exception {
        String source = unit.getSource();
        String commandText = source.substring(call.getStartPosition(),
            call.getStartPosition() + call.getLength());
        String receiver = call.getExpression() == null ? ""
            : source.substring(call.getExpression().getStartPosition(),
                call.getExpression().getStartPosition() + call.getExpression().getLength()) + ".";

        ListRewrite block = rewrite.getListRewrite(statement.getParent(),
            Block.STATEMENTS_PROPERTY);
        block.insertBefore(rewrite.createStringPlaceholder(commandText + ";",
            ASTNode.EXPRESSION_STATEMENT), statement, null);
        rewrite.replace(call, rewrite.createStringPlaceholder(receiver + queryName + "()",
            ASTNode.METHOD_INVOCATION), null);
    }

    /** Add the query beside the command it was separated from. */
    private static void insertQuery(ASTRewrite rewrite, CompilationUnit ast,
                                    MethodDeclaration decl, String queryName,
                                    IVariableBinding field, String source) {
        AbstractTypeDeclaration type = (AbstractTypeDeclaration) decl.getParent();
        String returnType = source.substring(decl.getReturnType2().getStartPosition(),
            decl.getReturnType2().getStartPosition() + decl.getReturnType2().getLength());
        String query = "\n"
            + "/** The answer " + decl.getName().getIdentifier() + "() used to return, now"
            + " askable without causing. */\n"
            + "public " + (java.lang.reflect.Modifier.isStatic(field.getModifiers())
                ? "static " : "")
            + returnType + " " + queryName + "() {\n"
            + "    return " + field.getName() + ";\n"
            + "}\n";
        ListRewrite members = rewrite.getListRewrite(type,
            type.getBodyDeclarationsProperty());
        members.insertAfter(rewrite.createStringPlaceholder(query, ASTNode.METHOD_DECLARATION),
            decl, null);
    }
}
