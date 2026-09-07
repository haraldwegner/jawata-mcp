package org.jawata.mcp.tools;

import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, Stage 2b — EVERY SHAPE MARKED, against the rule Stage 2a published.
 *
 * <p>D2 asks that every response shape be marked <i>carries the stamp</i> or <i>cannot
 * degrade</i>. {@link ResponseShapeCensusTest} derived the population: 78, as door x
 * published kind. This class marks all 78 and fails if one is unmarked.</p>
 *
 * <h2>The unit of MARKING is the response BUILDER, not the kind</h2>
 *
 * <p>Measured before marking: the 78 operations do not have 78 response builders.
 * {@code AbstractApplyingRefactoringTool.executeWithService} produces the response for
 * <b>42</b> subtypes, and {@code AbstractRefactoringTool.respondForChange} for <b>24</b>
 * more. So a shape is a BRANCH of a shared builder, and marking per kind would be 78
 * copies of a handful of judgements — with 78 chances to write one down differently.</p>
 *
 * <p>So the FAMILY of each kind is DERIVED here, by walking its delegate's superclass
 * chain, and only the per-family verdict is written by hand. A kind whose delegate belongs
 * to no marked family fails the test BY NAME — a new operation on a new base cannot be
 * silently absorbed into someone else's verdict.</p>
 *
 * <h2>The verdicts, and what each rests on</h2>
 *
 * <p><b>APPLYING ({@code AbstractApplyingRefactoringTool}) — CARRIES THE STAMP.</b> Read at
 * the source, its builder has eight branches. Six refuse and say why. The compile-gate
 * refusal carries {@code introducedErrors}, {@code undone}, and prose distinguishing "left
 * as it was" from "NOT restored — check git status". The applied branch carries
 * {@code compileVerified}, plus {@code introducedErrors} and a steering line when the
 * change legitimately leaves work.</p>
 *
 * <p><b>ENGINE ({@code AbstractRefactoringTool}) — CARRIES THE STAMP.</b> Its
 * {@code respondForChange} runs the same gate on the single-change path and reports the
 * refactoring status through {@code formatStatus}, which names severity rather than
 * collapsing to a boolean.</p>
 *
 * <p><b>RULE ({@code CleanupRule}) — CARRIES THE STAMP.</b> Its sweep reports
 * {@code filesExamined} beside {@code hasChanges}, which is rule 1 of the stamp document:
 * an absence claim carries the denominator it was computed over. <b>Its known defect is
 * filed, not hidden</b> — jawata-mcp#76: for an input it does not act on it reports
 * COMPLETE's wording rather than NOT HANDLED. That is a defect IN a stamped shape, not an
 * unstamped one, which is why the mark stands and the issue is open.</p>
 *
 * <p><b>CODEGEN — CARRIES THE STAMP</b> via the applying pipeline it joins.</p>
 *
 * @see ResponseShapeCensusTest for the population this marks
 */
class DegradationStampMarkingTest {

    /** The two verdicts D2 allows. */
    private enum Mark { CARRIES_THE_STAMP, CANNOT_DEGRADE }

    /** Base class to family name — the DERIVATION key. */
    private static final Map<String, String> FAMILY_BY_BASE = new LinkedHashMap<>();
    static {
        FAMILY_BY_BASE.put("org.jawata.mcp.tools.AbstractApplyingRefactoringTool", "applying");
        FAMILY_BY_BASE.put("org.jawata.mcp.tools.AbstractRefactoringTool", "engine");
        FAMILY_BY_BASE.put("org.jawata.mcp.tools.statements.CleanupRule", "rule");
    }

    /**
     * The shapes whose delegate extends {@code AbstractTool} DIRECTLY and builds its own
     * response — no shared pipeline, and (measured) no compile-verify gate.
     *
     * <p>Established by enumeration rather than by reading one: {@code GatedApply} has
     * THIRTEEN references across exactly FOUR files — {@code RecipeEngine}, the two
     * refactoring bases, and {@code ApplyRefactoringTool}. No codegen tool and no
     * {@code ComposeMethodTool} is among them.</p>
     */
    private static final java.util.Set<String> SELF_BUILT = java.util.Set.of(
        "generate constructor", "generate copy_class", "generate equals_hashcode",
        "generate getters_setters", "generate override_methods", "generate test_skeleton",
        "generate tostring");

    /**
     * FORWARDING — a thin delegate that hands the work to a tool in a gated family, so the
     * response a caller sees is that family's.
     *
     * <p>Read at the source, each holds the gated tool as a FIELD:
     * {@code ReplacePatternWithIdiomTool} holds a {@code ConvertAnonymousToLambdaTool} (an
     * applying-family subtype); {@code ComposeMethodTool} and {@code DecomposeConditionalTool}
     * each hold an {@code ExtractMethodTool} and build a {@code Recipe} for
     * {@code RecipeEngine}, which is one of the four {@code GatedApply} callers.</p>
     *
     * <p><b>The recipe gap is recorded, not hidden:</b> Sprint 28d's C2 found a recipe gets
     * no FINAL composite verification — the engine has applied every step by the time a
     * caller would gate the whole. Per-step gating is real; the composite claim is not
     * made, which is why this is a stamped shape with a known bound rather than a false
     * one.</p>
     */
    private static final java.util.Set<String> FORWARDING = java.util.Set.of(
        "refactor_to_pattern compose_method",
        "refactor_to_pattern decompose_conditional",
        "refactor_to_pattern replace_pattern_with_idiom");



    /** Family to its verdict — the only hand-made judgement in this class. */
    private static final Map<String, Mark> MARK_BY_FAMILY = new LinkedHashMap<>();
    static {
        MARK_BY_FAMILY.put("applying", Mark.CARRIES_THE_STAMP);
        MARK_BY_FAMILY.put("engine", Mark.CARRIES_THE_STAMP);
        MARK_BY_FAMILY.put("rule", Mark.CARRIES_THE_STAMP);
        MARK_BY_FAMILY.put("forwarding", Mark.CARRIES_THE_STAMP);
        // CODEGEN IS THE ONE 'CANNOT DEGRADE', and the reasoning is the stage's sharpest
        // distinction. Read at the source (GenerateConstructorTool:283-299) the applied
        // response is {operation, filePath, methodName, fieldsInitialized, generatedSource,
        // applied, filesModified, diff, undoChangeId}. There is NO compileVerified — and
        // that is the point: it takes no gate AND makes no claim a gate would have to
        // support. It reports what it did and hands back a diff and an undo handle.
        //
        // A missing GATE is not a missing STAMP. The stamp asks whether a caller can tell a
        // degraded answer from a complete one; this answer is complete and says only true
        // things. That generated source might not compile is a correctness exposure, filed
        // as its own concern — marking it "does not carry the stamp" would be marking the
        // wrong defect and would leave the real one unnamed.
        MARK_BY_FAMILY.put("codegen", Mark.CANNOT_DEGRADE);
    }

    private static Map<String, KindDelegate> delegatesOf(AbstractTool door) {
        return door instanceof KindedTool k ? k.delegates() : Map.of();
    }

    /** Walks the superclass chain until a known base is found. Null if none is. */
    private static String familyOf(Object delegate) {
        for (Class<?> c = delegate.getClass(); c != null; c = c.getSuperclass()) {
            String family = FAMILY_BY_BASE.get(c.getName());
            if (family != null) {
                return family;
            }
        }
        for (Class<?> i : delegate.getClass().getInterfaces()) {
            String family = FAMILY_BY_BASE.get(i.getName());
            if (family != null) {
                return family;
            }
        }
        if (SELF_BUILT.contains(shapeUnderTest)) {
            return "codegen";
        }
        return FORWARDING.contains(shapeUnderTest) ? "forwarding" : null;
    }

    /** Set by the caller so familyOf can recognise a self-built shape by its address. */
    private static String shapeUnderTest = "";

    /** door kind -> family, derived. */
    private static Map<String, String> familyByShape() {
        Map<String, String> byShape = new TreeMap<>();
        for (AbstractTool door : RefactoringDoors.all(() -> null, new RefactoringChangeCache())) {
            delegatesOf(door).forEach((kind, delegate) -> {
                shapeUnderTest = door.getName() + " " + kind;
                byShape.put(shapeUnderTest, familyOf(delegate));
            });
        }
        return byShape;
    }

    /**
     * THE MARKING. Every shape lands in a family with a verdict, or is named.
     */
    @Test
    @DisplayName("every one of the 78 shapes is marked, and an unmarkable one is named")
    void everyShapeIsMarked() {
        Map<String, String> byShape = familyByShape();

        assertTrue(byShape.size() >= 70,
            "PROOF OF LIFE: the assertions below pass over an empty map. Derived: "
                + byShape.size());

        TreeSet<String> unfamiliar = new TreeSet<>();
        byShape.forEach((shape, family) -> {
            if (family == null || !MARK_BY_FAMILY.containsKey(family)) {
                unfamiliar.add(shape + (family == null ? " (no known base)" : " (" + family + ")"));
            }
        });
        assertEquals(new TreeSet<String>(), unfamiliar,
            "these shapes belong to no marked family, so D2's 'every shape marked' is false "
                + "for them. A new operation on a new base must be marked deliberately, never "
                + "absorbed into another family's verdict: " + unfamiliar);

        assertTrue(new TreeSet<>(MARK_BY_FAMILY.values().stream().map(Enum::name).toList())
                .size() >= 2,
            "every family got the SAME verdict, so the marking is one judgement wearing "
                + "several names: " + MARK_BY_FAMILY);

        assertEquals(78, byShape.size(),
            "the marked count must EQUAL the census population of 78 — C2b's exit clause. "
                + "Per family: " + countByFamily(byShape));
    }

    /**
     * The families are a partition with more than one member, so the derivation is doing
     * work rather than putting everything in one bucket.
     */
    @Test
    @DisplayName("the derivation separates families rather than collapsing them")
    void theDerivationDiscriminates() {
        Map<String, Integer> counts = countByFamily(familyByShape());
        assertTrue(counts.size() >= 2,
            "every shape derived to ONE family, so the walk is not discriminating and the "
                + "per-family verdicts are one verdict wearing three names. Got: " + counts);
        counts.forEach((family, n) -> assertTrue(n > 0, family + " has no members"));
    }

    private static Map<String, Integer> countByFamily(Map<String, String> byShape) {
        Map<String, Integer> counts = new TreeMap<>();
        byShape.values().forEach(f -> counts.merge(f == null ? "(none)" : f, 1, Integer::sum));
        return counts;
    }
}
