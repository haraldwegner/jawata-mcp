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
 * <p><b>CODEGEN and two of the three composers — NEITHER MARK: a DEFECT FOUND.</b> The
 * plan names this outcome: "a shape that can degrade and does not stamp is a defect found
 * by this list, not a new issue." An earlier version of this class marked codegen
 * CANNOT DEGRADE, on the reasoning that it takes no gate and makes no claim a gate would
 * support. A C2b audit disproved it and the disproof is at the source:
 * {@code GenerateConstructorTool.buildType} DROPS the generic suffix
 * ({@code List&lt;String&gt;} becomes raw {@code List}), which its own javadoc admits — and
 * {@code fieldsInitialized} reports field NAMES, which are unaffected, so the one field
 * that could carry the signal reports success. That is a DEGRADED result delivered with
 * COMPLETE's payload.</p>
 *
 * @see ResponseShapeCensusTest for the population this marks
 */
class DegradationStampMarkingTest {

    /** The two verdicts D2 allows. */
    private enum Mark {
        CARRIES_THE_STAMP,
        CANNOT_DEGRADE,
        /**
         * Not a D2 mark — the plan's own third outcome: "a shape that can degrade and does
         * not stamp is a defect found by this list, not a new issue." Recorded here so the
         * population is fully accounted while the defect stays visible.
         */
        DEFECT_FOUND
    }

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
        "refactor_to_pattern replace_pattern_with_idiom");

    /**
     * COMPOSING — builds its OWN response after calling only {@code prepareChange} on a
     * gated tool, so the caller never sees the gated family's response.
     *
     * <p>An earlier version put these in FORWARDING on the claim that they "hand the work
     * to a tool in a gated family". False, and the audit read it: both call
     * {@code extract.prepareChange(...)} — the preparation step only, never
     * {@code executeWithService} — then emit
     * {@code {operation, applied:true, filesModified, undoChangeId, sectionsExtracted, summary}}.
     * No {@code compileVerified}, no {@code introducedErrors}, <b>not even a diff</b>.</p>
     *
     * <p>And {@code applied: true} IS the composite claim, made while nothing verifies the
     * final state: {@code RecipeEngine} gates each step in REPORT mode, which keeps
     * introduced TYPE errors, and its own comment says the composite "is verified by the
     * whole composite the caller applies" — but no caller applies one, because the engine
     * has already performed every step.</p>
     */
    private static final java.util.Set<String> COMPOSING = java.util.Set.of(
        "refactor_to_pattern compose_method",
        "refactor_to_pattern decompose_conditional");



    /** Family to its verdict — the only hand-made judgement in this class. */
    private static final Map<String, Mark> MARK_BY_FAMILY = new LinkedHashMap<>();
    static {
        MARK_BY_FAMILY.put("applying", Mark.CARRIES_THE_STAMP);
        MARK_BY_FAMILY.put("engine", Mark.CARRIES_THE_STAMP);
        MARK_BY_FAMILY.put("rule", Mark.CARRIES_THE_STAMP);
        MARK_BY_FAMILY.put("forwarding", Mark.CARRIES_THE_STAMP);
        // Filed as jawata-mcp#80.
        MARK_BY_FAMILY.put("composing", Mark.DEFECT_FOUND);
        // Filed as jawata-mcp#79 — the list found it, which is what D2 asks of it.
        MARK_BY_FAMILY.put("codegen", Mark.DEFECT_FOUND);
    }

    private static Map<String, KindDelegate> delegatesOf(AbstractTool door) {
        return door instanceof KindedTool k ? k.delegates() : Map.of();
    }

    /**
     * Family for one shape. The class walk decides FIRST; the address sets are consulted
     * only when it finds nothing, so a delegate re-based onto a new unmarked base is NAMED
     * rather than absorbed by its address — which the audit found the earlier ordering
     * allowed for exactly the ten address-matched shapes.
     */
    private static String familyOf(String shape, Object delegate) {
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
        if (SELF_BUILT.contains(shape)) {
            return "codegen";
        }
        if (FORWARDING.contains(shape)) {
            return "forwarding";
        }
        return COMPOSING.contains(shape) ? "composing" : null;
    }

    /** door kind -> family, derived. */
    private static Map<String, String> familyByShape() {
        Map<String, String> byShape = new TreeMap<>();
        for (AbstractTool door : RefactoringDoors.all(() -> null, new RefactoringChangeCache())) {
            delegatesOf(door).forEach((kind, delegate) -> {
                String shape = door.getName() + " " + kind;
                byShape.put(shape, familyOf(shape, delegate));
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

        TreeSet<String> verdictsInUse = new TreeSet<>();
        byShape.values().forEach(f -> {
            Mark m = MARK_BY_FAMILY.get(f);
            if (m != null) {
                verdictsInUse.add(m.name());
            }
        });
        assertTrue(verdictsInUse.size() >= 2,
            "every SHAPE derived to one verdict, so the marking is one judgement wearing "
                + "several names. In use: " + verdictsInUse);

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
        // The former "n > 0 per family" assertion was a tautology: counts is built by
        // merge(f, 1, sum) over the derived values, so every key present has n >= 1 by
        // construction. What it MEANT to check is a marked family with no members, which
        // needs the other map — and that is what this does.
        TreeSet<String> markedButEmpty = new TreeSet<>(MARK_BY_FAMILY.keySet());
        markedButEmpty.removeAll(counts.keySet());
        assertEquals(new TreeSet<String>(), markedButEmpty,
            "these families carry a verdict and no shape derives to them, so the verdict is "
                + "about nothing: " + markedButEmpty);
    }

    private static Map<String, Integer> countByFamily(Map<String, String> byShape) {
        Map<String, Integer> counts = new TreeMap<>();
        byShape.values().forEach(f -> counts.merge(f == null ? "(none)" : f, 1, Integer::sum));
        return counts;
    }
}
