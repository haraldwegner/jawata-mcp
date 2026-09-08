package org.jawata.mcp.tools.smell;

import org.jawata.mcp.models.CodeAddress;
import org.jawata.mcp.models.NextStep;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#71 — <b>a cure whose door needs a decision names it.</b>
 *
 * <p>Some doors require an input no finding can carry, because it is a DECISION rather than a
 * fact. {@code extract kind=class} is the clearest: its schema says so deliberately — <i>"fields[]
 * has no default on purpose — WHICH state travels together is the design decision this carries
 * out"</i> — and {@code newTypeName} is the same shape of answer. The door is right to require
 * them.</p>
 *
 * <p><b>The defect was upstream.</b> A {@code god_class} finding rendered <i>"TIER: RUN — run
 * extract kind=class"</i> and handed over {@code {symbol, filePath}}, so copying the instruction
 * verbatim earned {@code INVALID_PARAMETER 'newTypeName'}. Measured against the released engine
 * before the change.</p>
 *
 * <p><b>ONE layer was broken, and my first account of this said three.</b> {@code Cure} has
 * carried a {@code needs} component since 28d's v4.1 clause, and <b>fifteen cures already
 * declared one</b> — {@code sections}, {@code cloneGroupId}, {@code literal}, {@code parameters},
 * {@code functions}, {@code delegateField} and more. The break was entirely at the wire:
 * {@code NextStep} — the shape both the findings channel and the refusal channel render through —
 * had no such component, so {@code Cures.stepsFor} passed the discriminator and dropped the
 * needs. Every one of those fifteen declarations reached nobody.</p>
 *
 * <p><b>The correction matters because I acted on the wrong count.</b> A first pass reported ZERO
 * declarations, from a single-line search that cannot see a multi-line {@code new Cure(...)}, and
 * that number went into a commit message and a plan record before the multi-line rows were read.
 * What this fix is worth is therefore larger than first stated: it did not add a feature for two
 * cures, it connected fifteen that were already written and silent.</p>
 *
 * <p>Both directions here. A step that renders {@code needs} unconditionally would satisfy the
 * first case and be exactly as wrong as the silence it replaces, so a door that asks for nothing
 * but an address must publish no {@code needs} key at all.</p>
 */
class CureNamesWhatTheAgentMustSupplyTest {

    private static CodeAddress anAddress() {
        return new CodeAddress("src/main/java/com/example/Fat.java", 10, 4, "com.example.Fat");
    }

    @Test
    @DisplayName("mcp#71: a step whose door needs a decision input names it on the wire")
    void aStepNamesItsDecisionInputs() {
        NextStep step = new NextStep("extract kind=class", anAddress(), null,
            List.of("newTypeName", "fields"));
        Map<String, Object> wire = step.rendered();

        assertAll(
            () -> assertTrue(wire.containsKey("needs"),
                "the step says what the agent must supply: " + wire),
            () -> assertEquals(List.of("newTypeName", "fields"), wire.get("needs"),
                "and names them, in the door's own parameter spelling: " + wire),
            // The address still travels. Without this, "names its needs" would also be
            // satisfied by a step that had stopped carrying the part it CAN supply.
            () -> assertFalse(((Map<?, ?>) wire.get("arguments")).isEmpty(),
                "while still carrying the arguments it can fill itself: " + wire));
    }

    /**
     * The control. An empty list and "declares nothing" are the same fact, and publishing an
     * empty array would make every step look like it wanted something.
     */
    @Test
    @DisplayName("mcp#71 control: a step that needs nothing publishes no needs key")
    void aStepThatNeedsNothingSaysNothing() {
        Map<String, Object> wire =
            new NextStep("apply_cleanup kind=remove_dead_code", anAddress(), null).rendered();

        assertAll(
            () -> assertFalse(wire.containsKey("needs"),
                "a door that asks only for an address must not appear to want more: " + wire),
            () -> assertTrue(wire.containsKey("operation") && wire.containsKey("arguments"),
                "while still rendering the shape every consumer reads: " + wire));
    }

    /**
     * The catalogue half, asserted separately from the wire half — the two failed independently
     * and a test that only drove the record would have passed over a catalogue declaring nothing.
     */
    @Test
    @DisplayName("mcp#71: the cure table declares the decision inputs extract kind=class needs")
    void theCatalogueDeclaresThem() {
        for (String smell : List.of("god_class", "temporary_field")) {
            List<CureCatalog.Cure> cures = CureCatalog.curesFor(smell);
            assertTrue(cures != null && !cures.isEmpty(), smell + " declares a cure");
            CureCatalog.Cure extractClass = cures.stream()
                .filter(c -> "extract kind=class".equals(c.recipe()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(smell + " routes to extract kind=class"));
            assertEquals(List.of("newTypeName", "fields"), extractClass.needs(),
                smell + "'s extract kind=class cure names what the door still wants");
        }
    }
}
