package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#73 — <b>an absence is an answer, and it has to be said where the reader
 * can see it.</b>
 *
 * <p>{@code experience(kind=recall)} documents itself as returning <i>"exactly the fitting
 * node(s) … OR an authoritative absence — never a similarity pile."</i> For a symbol the
 * store has no entry for it returned eight resemblance rows and no absence statement at all,
 * in the same shape a real match uses.</p>
 *
 * <p><b>The server already distinguished the two cases</b> — a match gets the closed-set
 * steering, an absence keeps the generic line — <b>and could not say so where it mattered.</b>
 * That steering is carried in {@code meta.steering}, and {@code renderText} never renders
 * meta. {@code format=text} is the form the hooks inject. So the distinction existed for a
 * JSON reader and was invisible to the reader it was for.</p>
 *
 * <p>Why that is worse than unhelpful: the closed-set steering instructs an agent to match its
 * observation to ONE of the rows and not to generate a novel cause. Applied to rows that are
 * only NEAR, it aims the agent at whichever unrelated entry ranked first. The sibling case
 * already honours the rule — an unavailable store renders <i>"this is NOT an absence"</i> —
 * so this is that discipline applied to its other half rather than a new idea.</p>
 */
class RecallSaysTheAbsenceTest {

    private static Map<String, Object> analogy(String summary) {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("summary", summary);
        a.put("basis", "meaning-near");
        return a;
    }

    private static Map<String, Object> entry(String summary) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("type", "lesson");
        e.put("summary", summary);
        e.put("status", "accepted");
        return e;
    }

    /** The issue's case A: a symbol that cannot exist. Nothing anchored, resemblance only. */
    private static Map<String, Object> unknownSymbolResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("result", ExperienceRetrieval.RESULT_ANALOGY);
        r.put("entries", List.of());
        r.put("analogies", List.of(
            analogy("Sharding boosts database scalability through horizontal partitioning."),
            analogy("The Observer pattern notifies subscribers when a publisher changes.")));
        return r;
    }

    /** The issue's case B, and the CONTROL: a cue the store really does have an entry for. */
    private static Map<String, Object> anchoredResult() {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("result", ExperienceRetrieval.RESULT_MATCH);
        r.put("entries", List.of(entry("The cure table refuses an ambiguous step at boot.")));
        r.put("analogies", List.of(analogy("A neighbouring lesson that merely resembles it.")));
        return r;
    }

    @Test
    @DisplayName("mcp#73: a cue with nothing anchored SAYS so, in the text the hooks inject")
    void anUnanchoredCueStatesTheAbsence() {
        String text = ExperienceRetrieval.renderText(unknownSymbolResult());

        assertAll(
            () -> assertTrue(text.contains("NOTHING IS ANCHORED TO THIS CUE"),
                "the response must state the absence in its BODY — the steering that "
                    + "distinguished these cases rides in meta, which format=text drops, "
                    + "and format=text is what the hooks inject. got: " + text),
            () -> assertTrue(text.contains("NOMINEES"),
                "and it must name what the rows ARE, so they are plainly not answers"),
            // The rows still render: this states the absence, it does not suppress the
            // nominees. Without this a fix that simply stopped returning them would pass.
            () -> assertTrue(text.contains("Sharding") && text.contains("Observer"),
                "the nominees are still offered — an absence statement is not a refusal to "
                    + "nominate; got: " + text),
            () -> assertTrue(text.indexOf("NOTHING IS ANCHORED") < text.indexOf("Sharding"),
                "and it must come FIRST, before the rows it is about"));
    }

    @Test
    @DisplayName("mcp#73's control: a cue that IS anchored must not carry the absence line")
    void anAnchoredCueDoesNotClaimAnAbsence() {
        String text = ExperienceRetrieval.renderText(anchoredResult());

        assertAll(
            () -> assertFalse(text.contains("NOTHING IS ANCHORED"),
                "a real match must NOT be told nothing is anchored — without this the "
                    + "statement is unconditional noise rather than a discriminator, and it "
                    + "would then be as wrong as the silence it replaced. got: " + text),
            () -> assertTrue(text.contains("The cure table refuses an ambiguous step"),
                "proof of life: the anchored entry really does render, so the assertion "
                    + "above is measuring a rendered response and not an empty string"),
            // The shape the issue actually complained about: an anchored result carries BOTH
            // a real hit and resemblance rows, and only the unanchored case is the defect.
            () -> assertTrue(text.contains("merely resembles it"),
                "analogies still render beside a match, which is the pre-existing behaviour "
                    + "this must not change"));
    }
}
