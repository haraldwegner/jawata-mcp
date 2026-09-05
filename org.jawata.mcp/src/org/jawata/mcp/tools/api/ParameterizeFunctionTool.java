package org.jawata.mcp.tools.api;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code change_method_signature kind=parameterize_function} — Fowler row 27, Parameterize
 * Function.
 *
 * <p>Two methods with the same body and different constants are one method that has not been
 * written yet: {@code tenPercentRaise} and {@code fivePercentRaise} differ by a number, and the
 * number is what the caller actually wants to say. Making it a parameter collapses the pair
 * into one function and lets a caller ask for a rate nobody thought to write a method for.</p>
 *
 * <h2>The half this row performs, and the half it hands to a sibling kind</h2>
 *
 * <p>This kind performs the FIRST half: the constant becomes a parameter and every existing
 * call site passes it, so behaviour is unchanged and the function is now general. Folding the
 * redundant sibling into it afterwards is a redirect, and this same door already publishes one
 * — {@code change_method_signature kind=change_signature} with {@code retargetCallsTo}. That is
 * a composition rather than a gap, and it is named here so a reader does not go looking for a
 * missing feature.</p>
 *
 * <h2>The target is NAMED, and the name is the constant itself</h2>
 *
 * <p>Like {@link ReplaceQueryWithParameterTool}, this row does not publish a character range: a
 * finding names symbols, not offsets. It names the method and the LITERAL's own source text —
 * {@code literal=1.1} — which is what a reader sees in the code, and the range is derived. The
 * two rows share {@link IntroducedParameter} for everything after that, because they differ
 * only in which node they point at.</p>
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li><b>A literal that does not occur in the method.</b> Named rather than silently doing
 *       nothing.</li>
 *   <li><b>A literal that occurs more than once.</b> Two occurrences of {@code 1.1} may be two
 *       unrelated constants that happen to be equal, and parameterizing the wrong one changes
 *       a line the caller did not name. The refusal reports how many were found.</li>
 *   <li><b>Anything JDT's own preconditions reject.</b> Its message is returned as it stands.</li>
 * </ul>
 */
public class ParameterizeFunctionTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a method. */
        public static final String NOT_A_METHOD = "NOT_A_METHOD";
        /** {@code literal} was absent, and the target cannot be derived without it. */
        public static final String LITERAL_REQUIRED = "LITERAL_REQUIRED";
        /** No literal with that text occurs in the method's body. */
        public static final String LITERAL_NOT_FOUND = "LITERAL_NOT_FOUND";
        /** Several literals with that text occur; which one is the caller's choice. */
        public static final String AMBIGUOUS_LITERAL = "AMBIGUOUS_LITERAL";

        private Refusal() {
        }
    }

    public ParameterizeFunctionTool(Supplier<IJdtService> serviceSupplier,
                                    RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "parameterize_function";
    }

    @Override
    public String getName() {
        return "parameterize_function";
    }

    @Override
    public String getDescription() {
        return """
            Parameterize Function — a constant buried in a method becomes a parameter, so two
            methods differing only by that constant can become one. Name the METHOD
            (symbol=pkg.Type#m, or a position) and the LITERAL's own source text (literal=1.1);
            the range is derived, because a finding names symbols and never a character offset.
            Every existing call site is rewritten to pass the constant, so behaviour is
            unchanged. Folding the now-redundant sibling method in afterwards is
            kind=change_signature with retargetCallsTo, on this same door. Optional:
            parameterName. Refuses a literal that occurs zero times, or more than once.""";
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
        properties.put("symbol", FqnTarget.symbolSchemaProperty("method holding the constant"));
        properties.put("literal", Map.of("type", "string",
            "description", "REQUIRED. The constant's own source text, exactly as written — "
                + "1.1, 42, \\\"paid\\\", true. Refused if it occurs zero times or more than once."));
        properties.put("parameterName", Map.of("type", "string",
            "description", "Name for the new parameter (optional; JDT derives one)."));
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

        String literal = getStringParam(arguments, "literal");
        if (literal == null || literal.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("literal",
                "literal is required: it names the constant that becomes a parameter, written"
                    + " exactly as it appears in the source. This row addresses its target by"
                    + " NAME rather than by a character range, so that a finding can drive it.",
                Refusal.LITERAL_REQUIRED));
        }

        String filePath = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath",
                "filePath is required (or name the method with symbol=pkg.Type#method)"));
        }

        IJavaElement element = service.getElementAtPosition(Path.of(filePath), line, column);
        if (!(element instanceof IMethod method)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a method.", Refusal.NOT_A_METHOD));
        }

        ICompilationUnit unit = method.getCompilationUnit();
        String source = unit.getSource();
        List<Expression> found = IntroducedParameter.within(IntroducedParameter.parse(unit),
            method, Expression.class,
            node -> isLiteral(node) && literal.equals(textOf(source, node)));
        if (found.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("literal",
                "'" + method.getElementName() + "' contains no literal '" + literal + "'."
                    + " The text must match what is WRITTEN — 1.1 is not 1.10, and a string"
                    + " literal carries its quotes.",
                Refusal.LITERAL_NOT_FOUND));
        }
        if (found.size() > 1) {
            return Preparation.fail(ToolResponse.invalidParameter("literal",
                "'" + method.getElementName() + "' writes '" + literal + "' " + found.size()
                    + " times, and WHICH of them becomes the parameter is a choice only you can"
                    + " make — two equal constants are not always the same constant.",
                Refusal.AMBIGUOUS_LITERAL));
        }

        HeadlessJdtConfig.ensureInitialized();

        IntroducedParameter.Configured configured = IntroducedParameter.configure(
            unit, found.get(0), getStringParam(arguments, "parameterName"));
        if (configured.isRefused()) {
            return Preparation.fail(ToolResponse.error("REFACTORING_FAILED",
                "parameterize_function refused: " + configured.refusal(),
                "JDT's own preconditions declined. Nothing was modified."));
        }
        CheckedChange checked = engine.propose(
            new PreparedRefactoring(configured.change(), "parameterize " + literal),
            "parameterize " + literal + " in " + method.getElementName());
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error("REFACTORING_FAILED",
                "parameterize_function refused: " + checked.messages(),
                "JDT's own preconditions declined. Nothing was modified."));
        }

        Change change = checked.change();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("method", method.getElementName());
        extras.put("literal", literal);
        extras.put("parameterName", configured.name());
        extras.put("filesAffected", ChangeEngine.affectedFilePaths(change, service).size());
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }

        return Preparation.of(change,
            "parameterize " + literal + " in " + method.getElementName(), extras);
    }

    /**
     * The literal kinds a constant can be written as.
     *
     * <p>Deliberately NOT including {@code NullLiteral}: parameterizing a null produces a
     * parameter whose only sensible argument is null, which generalises nothing. And not
     * {@code TypeLiteral} either — {@code Foo.class} is a type, and a caller wanting to vary it
     * is asking for a different refactoring.</p>
     */
    private static boolean isLiteral(Expression node) {
        return node instanceof NumberLiteral || node instanceof StringLiteral
            || node instanceof BooleanLiteral || node instanceof CharacterLiteral;
    }

    /** The literal exactly as WRITTEN, which is what the caller can see and therefore name. */
    private static String textOf(String source, Expression node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength());
    }
}
