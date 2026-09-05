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
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CatchClause;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
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
 * {@code change_method_signature kind=replace_exception_with_precheck} — Fowler row 47, Replace
 * Exception with Precheck.
 *
 * <p>An exception is for the unexpected. Catching one for a condition the code could simply have
 * ASKED about uses the machinery as control flow: the reader cannot tell which failures are
 * anticipated, and a catch broad enough to be convenient is broad enough to swallow a real
 * fault.</p>
 *
 * <p>Fowler's own example is the whole idea in three lines: a method that returns
 * {@code values[periodNumber]} inside a {@code try} and returns {@code 0} from an
 * {@code ArrayIndexOutOfBoundsException} handler becomes a method that checks the index first.
 * Nothing about the answer changes; what changes is that the condition is now written down.</p>
 *
 * <h2>The precheck is DERIVED, and that is the whole safety argument</h2>
 *
 * <p>The caller does not supply a condition. The row reads the guarded expression, reads the
 * exception the catch names, and derives the check from the pair — so the check cannot say
 * something other than what the handler was catching. Three pairs are understood, and each is the
 * language raising the failure rather than a library choosing to:</p>
 *
 * <table><caption>the derivations</caption>
 * <tr><th>expression</th><th>caught</th><th>precheck</th></tr>
 * <tr><td>{@code a[i]}</td><td>{@code ArrayIndexOutOfBoundsException} or its supertype
 *     {@code IndexOutOfBoundsException}</td><td>{@code i < 0 || i >= a.length}</td></tr>
 * <tr><td>{@code x.f}, {@code x.length}</td><td>{@code NullPointerException}</td>
 *     <td>{@code x == null}</td></tr>
 * <tr><td>{@code a / b}, {@code a % b}, both integral</td><td>{@code ArithmeticException}</td>
 *     <td>{@code b == 0}</td></tr>
 * </table>
 *
 * <h2>What it REFUSES, and why each refusal is a correctness bound rather than an omission</h2>
 *
 * <p><b>A method invocation anywhere in the guarded statement.</b> This is the bound that does the
 * work. If the statement calls anything, that call may raise the very exception being caught from
 * somewhere inside itself — and a precheck on the visible expression would not prevent it, so the
 * exception would escape a method that used to handle it. That is a behaviour change no compile
 * gate can see, and it is the same shape row 45 refuses for its own derived expressions: purity is
 * not decidable here, so the row does not guess.</p>
 *
 * <p><b>More than one guardable expression, or none matching the catch.</b> Two would need two
 * prechecks and a decision about their order; none means the handler was catching something this
 * row cannot account for.</p>
 *
 * <p><b>A catch that names its exception and uses it.</b> After the change there is no exception,
 * so a handler that reads one cannot be moved. A parameter that is merely declared and never read
 * is fine.</p>
 *
 * <p><b>A handler that falls through.</b> The precheck runs BEFORE the guarded statement, so the
 * handler's body has to end the method — a {@code return} or a {@code throw}. One that falls
 * through would run and then continue into the very statement it was handling.</p>
 *
 * <p><b>Several catches, a {@code finally}, or a try-with-resources.</b> Each is a different
 * refactoring wearing this one's shape.</p>
 *
 * <h2>Unrouted, and the reason is measured</h2>
 *
 * <p>No detector reports this. {@code find_quality_issue(kind=catches)} is a SEARCH — it takes an
 * exception name and returns the sites that catch it — so it emits no finding and names no cure,
 * and {@code find_string_literals} over 1003 files returns nothing for "Precheck" on a scan the
 * tool reports COMPLETE. The row is therefore addressed by the method, which is what a reader
 * looking at a catch already has.</p>
 */
public class ReplaceExceptionWithPrecheckTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a method. */
        public static final String NOT_A_METHOD = "NOT_A_METHOD";
        /** The method contains no {@code try} this row could act on. */
        public static final String NO_TRY_TO_REPLACE = "NO_TRY_TO_REPLACE";
        /** Several candidate try statements and nothing said which. */
        public static final String AMBIGUOUS_TRY = "AMBIGUOUS_TRY";
        /** Several catches, a finally, or resources — a different refactoring. */
        public static final String NOT_A_SIMPLE_CATCH = "NOT_A_SIMPLE_CATCH";
        /** The handler reads the exception, or continues into the code it was handling. */
        public static final String HANDLER_CANNOT_MOVE = "HANDLER_CANNOT_MOVE";
        /** No check can be derived from the guarded statement and the exception together. */
        public static final String NO_PRECHECK_DERIVABLE = "NO_PRECHECK_DERIVABLE";

        private Refusal() {
        }
    }

    /** A guarded expression, the exception it raises, and the condition that avoids it. */
    private record Precheck(String condition, String description) {
    }

    public ReplaceExceptionWithPrecheckTool(Supplier<IJdtService> serviceSupplier,
                                            RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "replace_exception_with_precheck";
    }

    @Override
    public String getName() {
        return "replace_exception_with_precheck";
    }

    @Override
    public String getDescription() {
        return """
            Replace Exception with Precheck — a try/catch used as control flow becomes an
            explicit check. Name the METHOD (symbol=pkg.Type#m, or a position); optional
            exceptionType picks one try when the method has several. The check is DERIVED from
            the guarded expression and the caught exception together, so it cannot mean
            something the handler did not: an array index against its length, a receiver
            against null, a divisor against zero. Refuses anything else — notably a guarded
            statement containing a METHOD CALL, because the call could raise that same
            exception from inside itself and a precheck would not stop it escaping.""";
    }

    /** Behaviour-preserving within one method body; no signature and no call site moves. */
    @Override
    public boolean isStructural() {
        return false;
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
        properties.put("symbol", FqnTarget.symbolSchemaProperty("method holding the try/catch"));
        properties.put("exceptionType", Map.of("type", "string",
            "description", "Simple name of the caught exception, to pick one try when the"
                + " method has several. Optional when there is only one."));
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
        String wanted = getStringParam(arguments, "exceptionType");
        IJavaElement element = service.getElementAtPosition(Path.of(filePath),
            getIntParam(arguments, "line", -1), getIntParam(arguments, "column", -1));
        if (!(element instanceof IMethod method)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a method.", Refusal.NOT_A_METHOD));
        }

        ICompilationUnit unit = method.getCompilationUnit();
        CompilationUnit ast = IntroducedParameter.parse(unit);
        MethodDeclaration decl = MethodLookup.declaration(ast, method);
        if (decl == null || decl.getBody() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "could not locate the method's body.", Refusal.NOT_A_METHOD));
        }

        List<TryStatement> candidates = triesIn(decl, wanted);
        if (candidates.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' contains no try"
                    + (wanted == null ? "" : " catching '" + wanted + "'")
                    + " for this row to replace.", Refusal.NO_TRY_TO_REPLACE));
        }
        if (candidates.size() > 1) {
            return Preparation.fail(ToolResponse.invalidParameter("exceptionType",
                "'" + method.getElementName() + "' contains " + candidates.size() + " try"
                    + " statements; name one with exceptionType so the row acts on the catch"
                    + " you meant rather than the first one it read.", Refusal.AMBIGUOUS_TRY));
        }

        TryStatement tried = candidates.get(0);
        String source = unit.getSource();
        if (tried.catchClauses().size() != 1 || tried.getFinally() != null
            || !tried.resources().isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the try has " + tried.catchClauses().size() + " catch clause(s)"
                    + (tried.getFinally() != null ? ", a finally" : "")
                    + (tried.resources().isEmpty() ? "" : ", and resources")
                    + " — a precheck replaces exactly one handler, and several handlers are"
                    + " several conditions in an order this row cannot choose.",
                Refusal.NOT_A_SIMPLE_CATCH));
        }

        // The change inserts a SECOND statement where the try stood, so there has to be a
        // statement list to insert it into. `if (x) try {…} catch {…}` without braces is legal
        // Java and has none — one statement is all that position can hold.
        if (!(tried.getParent() instanceof Block)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the try is not inside a block — it is the single statement of an unbraced"
                    + " enclosing statement, which has nowhere to put the guard beside it.",
                Refusal.NOT_A_SIMPLE_CATCH));
        }

        CatchClause handler = (CatchClause) tried.catchClauses().get(0);
        IVariableBinding caught = handler.getException().resolveBinding();
        if (caught != null && readsVariable(handler.getBody(), caught)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the handler READS the exception it caught, and after this change there is no"
                    + " exception for it to read.", Refusal.HANDLER_CANNOT_MOVE));
        }
        List<?> handlerStatements = handler.getBody().statements();
        if (handlerStatements.isEmpty() || !endsTheMethod(
                (Statement) handlerStatements.get(handlerStatements.size() - 1))) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the handler falls through, and a precheck runs BEFORE the code it guards — so"
                    + " a handler that does not end the method would run and then continue into"
                    + " the very statement it was handling.", Refusal.HANDLER_CANNOT_MOVE));
        }

        List<?> guarded = tried.getBody().statements();
        if (guarded.size() != 1) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the try guards " + guarded.size() + " statements; with more than one, the check"
                    + " would have to say which of them it is about.",
                Refusal.NO_PRECHECK_DERIVABLE));
        }
        Statement body = (Statement) guarded.get(0);
        if (containsInvocation(body)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the guarded statement CALLS something, and that call may raise the same"
                    + " exception from inside itself — a check on what is visible here would"
                    + " not stop it, so the exception would escape a method that used to"
                    + " handle it.", Refusal.NO_PRECHECK_DERIVABLE));
        }

        String exception = handler.getException().getType().toString();
        List<Precheck> derived = derive(body, exception, source);
        if (derived.size() != 1) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                derived.isEmpty()
                    ? "nothing in the guarded statement raises " + exception + " in a way this"
                        + " row can check for. It understands an array index, a null receiver"
                        + " and a zero divisor, and refuses rather than guessing at the rest."
                    : "the guarded statement has " + derived.size() + " expressions that could"
                        + " raise " + exception + ", so a single check cannot say which it is"
                        + " about.",
                Refusal.NO_PRECHECK_DERIVABLE));
        }
        Precheck precheck = derived.get(0);

        // The rewrite: the guard first, then the statement it guards, both where the try was.
        //
        // The two bodies are MOVED as nodes rather than spliced as text, and the difference is
        // visible in the output rather than theoretical. Splicing the handler's source put its
        // original indentation — which was one level deeper, inside a catch — into a guard at the
        // method's own level. Every assertion still passed, because a `contains` on the condition
        // cannot see whitespace, and the result compiled. Moving the node hands the position to
        // the rewriter, which indents it where it now sits.
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        ListRewrite around = rewrite.getListRewrite(tried.getParent(),
            Block.STATEMENTS_PROPERTY);
        org.eclipse.jdt.core.dom.IfStatement guard = ast.getAST().newIfStatement();
        guard.setExpression((Expression) rewrite.createStringPlaceholder(
            precheck.condition(), ASTNode.INFIX_EXPRESSION));
        guard.setThenStatement((Statement) rewrite.createMoveTarget(handler.getBody()));
        around.insertBefore(guard, tried, null);
        around.replace(tried, rewrite.createMoveTarget(body), null);

        TextEdit edit = rewrite.rewriteAST(new Document(source),
            FormatterOptions.forGeneratedCode(ast));
        Map<IFile, List<TextEdit>> byFile =
            Map.of((IFile) unit.getResource(), List.of(edit));

        String label = "replace catch (" + exception + ") with a precheck on "
            + precheck.description();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("method", method.getElementName());
        extras.put("exceptionType", exception);
        extras.put("precheck", precheck.condition());
        return Preparation.of(ChangeEngine.fromFileEdits(label, byFile),
            label + " — the handler now runs when " + precheck.condition(), extras);
    }

    /** The try statements of THIS method, optionally narrowed to the exception a caller named. */
    private static List<TryStatement> triesIn(MethodDeclaration decl, String wanted) {
        List<TryStatement> found = new ArrayList<>();
        decl.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(TryStatement node) {
                if (wanted == null || catchesByName(node, wanted)) {
                    found.add(node);
                }
                return true;
            }
        });
        return found;
    }

    /** Does this try declare a handler for that exception, named as a caller would spell it? */
    private static boolean catchesByName(TryStatement node, String wanted) {
        for (Object clause : node.catchClauses()) {
            if (((CatchClause) clause).getException().getType().toString().endsWith(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Every check this row can derive from the guarded statement, given what was caught.
     *
     * <p>Each entry pairs an expression the LANGUAGE raises that exception for with the condition
     * under which it would. A library method that documents the same exception is deliberately not
     * here: whether it throws is that method's business, and reading its contract is not something
     * this row can do.</p>
     */
    private static List<Precheck> derive(Statement body, String exception, String source) {
        List<Precheck> found = new ArrayList<>();
        boolean index = exception.endsWith("ArrayIndexOutOfBoundsException")
            || exception.endsWith("IndexOutOfBoundsException");
        boolean nulls = exception.endsWith("NullPointerException");
        boolean arithmetic = exception.endsWith("ArithmeticException");

        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(ArrayAccess node) {
                if (index) {
                    String array = text(source, node.getArray());
                    String at = text(source, node.getIndex());
                    found.add(new Precheck(at + " < 0 || " + at + " >= " + array + ".length",
                        array + "[" + at + "]"));
                }
                return true;
            }

            @Override
            public boolean visit(FieldAccess node) {
                if (nulls) {
                    receiver(node.getExpression());
                }
                return true;
            }

            @Override
            public boolean visit(QualifiedName node) {
                // x.length on an array, and x.f where x is a variable — both dereference x.
                if (nulls && node.getQualifier() instanceof SimpleName name
                    && name.resolveBinding() instanceof IVariableBinding) {
                    receiver(name);
                }
                return true;
            }

            @Override
            public boolean visit(InfixExpression node) {
                if (arithmetic
                    && (node.getOperator() == InfixExpression.Operator.DIVIDE
                        || node.getOperator() == InfixExpression.Operator.REMAINDER)
                    && isIntegral(node.getRightOperand())) {
                    String divisor = text(source, node.getRightOperand());
                    found.add(new Precheck(divisor + " == 0", divisor));
                }
                return true;
            }

            private void receiver(Expression expression) {
                if (expression instanceof SimpleName name
                    && name.resolveBinding() instanceof IVariableBinding) {
                    String who = text(source, name);
                    found.add(new Precheck(who + " == null", who));
                }
            }
        });
        return found;
    }

    /** Integer division throws; floating-point division answers infinity, so it must not. */
    private static boolean isIntegral(Expression expression) {
        ITypeBinding type = expression.resolveTypeBinding();
        if (type == null) {
            return false;
        }
        String name = type.isPrimitive() ? type.getName() : type.getQualifiedName();
        return switch (name) {
            case "int", "long", "short", "byte", "char",
                 "java.lang.Integer", "java.lang.Long", "java.lang.Short",
                 "java.lang.Byte", "java.lang.Character" -> true;
            default -> false;
        };
    }

    private static boolean containsInvocation(Statement body) {
        boolean[] any = { false };
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                any[0] = true;
                return true;
            }
        });
        return any[0];
    }

    private static boolean endsTheMethod(Statement last) {
        return last instanceof ReturnStatement || last instanceof ThrowStatement;
    }

    private static boolean readsVariable(Block block, IVariableBinding variable) {
        boolean[] read = { false };
        block.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.resolveBinding() instanceof IVariableBinding v && v.isEqualTo(variable)) {
                    read[0] = true;
                }
                return true;
            }
        });
        return read[0];
    }

    private static String text(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength());
    }
}
