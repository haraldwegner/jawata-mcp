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
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.InfixExpression;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.ReturnStatement;
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
import org.jawata.mcp.tools.shared.MethodLookup;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code change_method_signature kind=replace_error_code_with_exception} — Fowler row 46, Replace
 * Error Code with Exception.
 *
 * <p>A method that answers {@code -1} for failure is asking every caller to remember. Most do not:
 * the value flows on as if it were an answer, and the failure surfaces somewhere else as nonsense.
 * An exception cannot be ignored by forgetting.</p>
 *
 * <h2>This row DELIBERATELY CHANGES BEHAVIOUR, and that is the refactoring rather than a defect</h2>
 *
 * <p>Every other row in this stage is behaviour-preserving. This one is not, and cannot be: a
 * caller that used to carry on with {@code -1} now propagates. <b>That is Fowler's whole point</b>
 * — the silent carrying-on was the bug — but it means the change must not be made quietly, so:</p>
 *
 * <ul>
 *   <li><b>A CHECKED exception is what makes it safe.</b> Then the compiler demands every caller
 *       say what it does about the failure, and nothing changes silently. The response says so
 *       when an unchecked one is used instead, because the caller may have a reason and this row
 *       should not overrule it.</li>
 *   <li><b>A caller that TESTS the code is REFUSED.</b> Such a caller is handling the failure
 *       today, and turning its {@code if (code == -1)} into a {@code try}/{@code catch} is a
 *       restructure of that caller's control flow, shaped by code this row did not write. It
 *       refuses and names the file rather than leaving a test that can no longer be true.</li>
 * </ul>
 *
 * <h2>The caller NAMES the error value, because nothing can infer it</h2>
 *
 * <p>Over the fork's 1354 main sources there are 43 {@code return null}, 38 {@code return false}
 * and 3 {@code return -1}. Almost all of them are ordinary control flow. No analysis separates an
 * error code from a legitimate answer — only the person reading the method knows — so
 * {@code errorValue} is required and matched AS WRITTEN, which is the same rule row 27 applies to
 * its literal.</p>
 */
public class ReplaceErrorCodeWithExceptionTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Callers are enumerated, never sampled; reaching this cap REFUSES rather than truncates. */
    private static final int MAX_REFERENCES = 1000;

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a method. */
        public static final String NOT_A_METHOD = "NOT_A_METHOD";
        /** {@code errorValue} was absent, and nothing can infer which value means failure. */
        public static final String ERROR_VALUE_REQUIRED = "ERROR_VALUE_REQUIRED";
        /** {@code exceptionType} was absent, and naming the failure IS the change. */
        public static final String EXCEPTION_TYPE_REQUIRED = "EXCEPTION_TYPE_REQUIRED";
        /** The method returns that value nowhere. */
        public static final String NO_SUCH_RETURN = "NO_SUCH_RETURN";
        /** EVERY return is the error value, so the method only fails and this is a different job. */
        public static final String ALL_RETURNS_ARE_THE_ERROR = "ALL_RETURNS_ARE_THE_ERROR";
        /** A caller TESTS the code, so it is handling the failure and must be restructured. */
        public static final String CALLER_TESTS_THE_CODE = "CALLER_TESTS_THE_CODE";
        /** The reference search hit its cap, so the caller list is a sample. */
        public static final String REFERENCE_CAP_REACHED = "REFERENCE_CAP_REACHED";

        private Refusal() {
        }
    }

    public ReplaceErrorCodeWithExceptionTool(Supplier<IJdtService> serviceSupplier,
                                             RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "replace_error_code_with_exception";
    }

    @Override
    public String getName() {
        return "replace_error_code_with_exception";
    }

    @Override
    public String getDescription() {
        return """
            Replace Error Code with Exception — a value returned to mean failure becomes a throw,
            so it cannot be ignored by forgetting. Name the METHOD (symbol=pkg.Type#m, or a
            position), the errorValue AS WRITTEN (-1, null, false), and the exceptionType —
            nothing can infer which value means failure, and naming the failure is the change.
            THIS ROW DELIBERATELY CHANGES BEHAVIOUR in the failure case, which is its point; a
            CHECKED exception makes the compiler demand every caller say what it does about it.
            REFUSES a caller that already TESTS the code — that caller is handling the failure,
            and restructuring its control flow is not something this row will guess at.""";
    }

    /** Structural: the method's contract changes, and callers must answer for the failure. */
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
        properties.put("symbol", FqnTarget.symbolSchemaProperty("method returning an error code"));
        properties.put("errorValue", Map.of("type", "string",
            "description", "REQUIRED. The returned value that means failure, spelled as the"
                + " source spells it: -1, null, false, \"MISSING\"."));
        properties.put("exceptionType", Map.of("type", "string",
            "description", "REQUIRED. The exception to throw. A CHECKED one makes the compiler"
                + " demand every caller answer for the failure."));
        properties.put("message", Map.of("type", "string",
            "description", "Optional message for the thrown exception."));
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
        String errorValue = getStringParam(arguments, "errorValue");
        if (errorValue == null || errorValue.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("errorValue",
                "errorValue is required. Over the fork's 1354 sources there are 43 `return null`,"
                    + " 38 `return false` and 3 `return -1`, and almost all of them are ordinary"
                    + " control flow — no analysis separates an error code from an answer, only"
                    + " the person reading the method.", Refusal.ERROR_VALUE_REQUIRED));
        }
        String exceptionType = getStringParam(arguments, "exceptionType");
        if (exceptionType == null || exceptionType.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("exceptionType",
                "exceptionType is required: naming what went wrong is the entire content of this"
                    + " refactoring, and a generated name would deliver none of it.",
                Refusal.EXCEPTION_TYPE_REQUIRED));
        }
        String message = getStringParam(arguments, "message");
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

        ICompilationUnit unit = method.getCompilationUnit();
        CompilationUnit ast = IntroducedParameter.parse(unit);
        MethodDeclaration decl = MethodLookup.declaration(ast, method);
        if (decl == null || decl.getBody() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "could not locate the method's body.", Refusal.NOT_A_METHOD));
        }

        String source = unit.getSource();
        List<ReturnStatement> failures = new ArrayList<>();
        List<ReturnStatement> answers = new ArrayList<>();
        for (ReturnStatement each : returnsOf(decl)) {
            Expression value = each.getExpression();
            if (value != null && errorValue.equals(text(source, value))) {
                failures.add(each);
            } else {
                answers.add(each);
            }
        }
        if (failures.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("errorValue",
                "'" + method.getElementName() + "' never returns " + errorValue + ". The value is"
                    + " matched AS WRITTEN, so `-1` is not `- 1` and a string carries its quotes"
                    + " — the caller names what they can see.", Refusal.NO_SUCH_RETURN));
        }
        if (answers.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("errorValue",
                "EVERY return in '" + method.getElementName() + "' is " + errorValue + ", so the"
                    + " method only fails. Replacing its one outcome with a throw is not this"
                    + " refactoring — there is no error CODE, because there is no code path that"
                    + " succeeds to distinguish it from.", Refusal.ALL_RETURNS_ARE_THE_ERROR));
        }

        List<SearchMatch> references =
            service.getSearchService().findAllReferences(method, MAX_REFERENCES);
        if (references.size() >= MAX_REFERENCES) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' has at least " + MAX_REFERENCES
                    + " references, which is the search cap — a caller still testing the code"
                    + " would go unseen, and this row's whole safety argument is that there is"
                    + " none.", Refusal.REFERENCE_CAP_REACHED));
        }

        // THE REFUSAL THAT MATTERS. A caller comparing the result to the error value is HANDLING
        // the failure today. Turning that into a try/catch restructures its control flow, shaped
        // by code this row did not write — and leaving the comparison behind would leave a test
        // that can no longer be true, on a caller that believes it is still handling the case.
        int callers = 0;
        for (SearchMatch match : references) {
            ICompilationUnit callerUnit = match.getElement() instanceof IJavaElement e
                ? (ICompilationUnit) e.getAncestor(IJavaElement.COMPILATION_UNIT) : null;
            if (callerUnit == null || callerUnit.equals(unit)) {
                continue;
            }
            CompilationUnit callerAst = IntroducedParameter.parse(callerUnit);
            MethodInvocation call = invocationAt(callerAst, match.getOffset());
            if (call == null) {
                continue;
            }
            callers++;
            String comparison = comparedToTheCode(call, errorValue, callerUnit.getSource());
            if (comparison != null) {
                return Preparation.fail(ToolResponse.invalidParameter("errorValue",
                    "a caller in " + callerUnit.getElementName() + " TESTS the code — "
                        + comparison + " — so it is handling the failure today. Turning that into"
                        + " a try/catch is a restructure of that caller's control flow, shaped by"
                        + " code this row did not write.", Refusal.CALLER_TESTS_THE_CODE));
            }
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        String thrown = "throw new " + exceptionType + "("
            + (message == null || message.isBlank() ? "" : "\"" + message + "\"") + ");";
        for (ReturnStatement failure : failures) {
            rewrite.replace(failure, rewrite.createStringPlaceholder(thrown,
                ASTNode.THROW_STATEMENT), null);
        }
        // The throws clause. An UNCHECKED exception needs none and gets none; a checked one must
        // be declared, and that declaration is what makes every caller answer for the failure.
        boolean declared = false;
        for (Object t : decl.thrownExceptionTypes()) {
            declared |= exceptionType.equals(t.toString());
        }
        if (!declared) {
            ListRewrite thrownTypes = rewrite.getListRewrite(decl,
                MethodDeclaration.THROWN_EXCEPTION_TYPES_PROPERTY);
            thrownTypes.insertLast(rewrite.createStringPlaceholder(exceptionType,
                ASTNode.SIMPLE_TYPE), null);
        }

        TextEdit edit = rewrite.rewriteAST(new Document(source),
            FormatterOptions.forGeneratedCode(ast));
        Map<IFile, List<TextEdit>> byFile = Map.of((IFile) unit.getResource(), List.of(edit));

        String label = "replace the error code " + errorValue + " in "
            + method.getElementName() + " with " + exceptionType;
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("method", method.getElementName());
        extras.put("errorValue", errorValue);
        extras.put("exceptionType", exceptionType);
        extras.put("returnsReplaced", failures.size());
        extras.put("callersChecked", callers);
        return Preparation.of(ChangeEngine.fromFileEdits(label, byFile),
            label + " (" + failures.size() + " return(s) became a throw; " + callers
                + " caller(s) read and none tested the code). THIS CHANGES BEHAVIOUR in the"
                + " failure case, which is the refactoring: a caller that carried on with "
                + errorValue + " now propagates. A CHECKED exception makes the compiler demand"
                + " each one say what it does about that; an unchecked one does not.", extras);
    }

    /** Every return of THIS method — a lambda's belong to the lambda. */
    private static List<ReturnStatement> returnsOf(MethodDeclaration decl) {
        List<ReturnStatement> found = new ArrayList<>();
        decl.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(LambdaExpression node) {
                return false;
            }

            @Override
            public boolean visit(ReturnStatement node) {
                found.add(node);
                return true;
            }
        });
        return found;
    }

    /**
     * The comparison a caller makes against the error value, or {@code null} if it makes none.
     *
     * <p>Two shapes: the call compared directly ({@code m(x) == -1}), and the call's value stored
     * in a local that is then compared. The second is the commoner one and the reason this cannot
     * be a check on the invocation's parent alone.</p>
     */
    private static String comparedToTheCode(MethodInvocation call, String errorValue,
                                            String source) {
        if (call.getParent() instanceof InfixExpression direct
            && isComparison(direct.getOperator())
            && (errorValue.equals(text(source, direct.getLeftOperand()))
                || errorValue.equals(text(source, direct.getRightOperand())))) {
            return text(source, direct);
        }
        if (!(call.getParent()
                instanceof org.eclipse.jdt.core.dom.VariableDeclarationFragment held)) {
            return null;
        }
        String name = held.getName().getIdentifier();
        MethodDeclaration owner = enclosingMethod(call);
        if (owner == null || owner.getBody() == null) {
            return null;
        }
        String[] found = { null };
        owner.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(InfixExpression node) {
                if (found[0] != null || !isComparison(node.getOperator())) {
                    return true;
                }
                String left = text(source, node.getLeftOperand());
                String right = text(source, node.getRightOperand());
                if ((name.equals(left) && errorValue.equals(right))
                    || (name.equals(right) && errorValue.equals(left))) {
                    found[0] = text(source, node);
                }
                return true;
            }
        });
        return found[0];
    }

    private static boolean isComparison(InfixExpression.Operator operator) {
        return operator == InfixExpression.Operator.EQUALS
            || operator == InfixExpression.Operator.NOT_EQUALS;
    }

    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration m) {
                return m;
            }
        }
        return null;
    }

    private static MethodInvocation invocationAt(CompilationUnit ast, int offset) {
        ASTNode node = NodeFinder.perform(ast, offset, 0);
        while (node != null && !(node instanceof MethodInvocation)) {
            node = node.getParent();
        }
        return (MethodInvocation) node;
    }

    private static String text(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength());
    }
}
