package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 7 — the form a JOB takes, and what it refuses.
 *
 * <p>A job says what one member is FOR. The failure it exists to refuse is the one an
 * agent cataloguing code falls into by default: writing the signature back in prose.
 * A summary that restates the identifier costs a row, answers no question, and then
 * ranks against real questions forever.</p>
 *
 * <p><b>THE FIXTURES ARE SHAPED AROUND THE GATES THAT RUN FIRST, and the first version
 * of this class was not.</b> The shape and story filters apply to EVERY type and sit
 * AHEAD of this one, so a short summary is refused before the job branch is reached —
 * measured, not assumed: {@code "Parse compilation unit."} came back
 * <i>"names a topic; it does not make a claim … at least 4 words"</i>, which is the
 * story filter, not this rule. Every restatement fixture below is therefore long enough
 * to clear those gates while still being nothing but the identifier re-spaced, and each
 * refusal is asserted on wording ONLY the job branch emits. A needle shared with an
 * earlier gate proves that something declined, never that the right thing did.</p>
 */
class JobFormTest {

    /**
     * A member whose name is long enough that re-spacing it clears the shape gates —
     * which is what makes the restatement cases reach the rule under test at all.
     */
    private static final String ANCHOR =
        "org.jawata.mcp.knowledge.ExperienceMaintenance#parseMemoryFileFrontmatterBlock";

    /** The identifier, spelled with spaces. A sentence in shape and the name in content. */
    private static final String RESTATEMENT = "Parse memory file frontmatter block.";

    /** What the member is FOR, which is the thing an identifier cannot tell you. */
    private static final String GOOD_JOB =
        "Reads a markdown memory file and answers the entry it describes, so a folder of"
            + " notes becomes rows the store can retrieve.";

    private static Optional<EntryForm.Refusal> job(String summary) {
        return EntryForm.check("job", summary, List.of(), null, null, ANCHOR);
    }

    @Test
    void a_job_that_says_what_the_member_is_for_is_admitted() {
        assertTrue(job(GOOD_JOB).isEmpty(),
            "a job owes no situation and no outcome — it is derived from the code and never"
                + " turned out any way — so a plain statement of what the member is for is"
                + " the whole form. If this is refused, an earlier gate is eating jobs and"
                + " every refusal below proves nothing");
    }

    @Test
    void a_signature_restatement_is_refused_by_name() {
        Optional<EntryForm.Refusal> r = job("Parses the unit and answers (ICompilationUnit)");
        assertTrue(r.isPresent(), "a summary carrying a signature is not a job");
        assertEquals("summary", r.get().field());
        assertTrue(r.get().message().contains("signature"),
            () -> "the refusal must name the signature rule, which only the job branch"
                + " emits: " + r.get().message());
    }

    /**
     * THE CASE THAT ACTUALLY OCCURS, and the one a parenthesis check alone would miss:
     * the identifier re-spaced into prose. It reads like a sentence and carries exactly
     * the information the identifier already carried.
     */
    @Test
    void the_name_re_spaced_into_prose_is_not_a_job() {
        Optional<EntryForm.Refusal> r = job(RESTATEMENT);
        assertTrue(r.isPresent(),
            "the identifier spelled with spaces is the name, not its purpose");
        assertEquals("summary", r.get().field());
        assertTrue(r.get().message().contains("restates"),
            () -> "and it must say WHY, so the author can fix it: " + r.get().message());
    }

    /**
     * A BARE IDENTIFIER IS REFUSED EARLIER, AND THAT IS CORRECT — recorded here rather
     * than claimed for this rule. {@code "parseMemoryFileFrontmatterBlock"} is one token,
     * so the shape filter every type passes through declines it as too short to be a
     * claim. This rule never sees it. Pinning it as the job branch's work would be an
     * assertion that passes for the wrong reason.
     */
    @Test
    void a_bare_identifier_is_refused_before_this_rule_is_reached() {
        Optional<EntryForm.Refusal> r = job("parseMemoryFileFrontmatterBlock");
        assertTrue(r.isPresent(), "a bare identifier is not a summary of anything");
        assertFalse(r.get().message().contains("restates"),
            () -> "the SHAPE gate owns this one, not the job rule — if the job wording"
                + " appears here the two have been conflated: " + r.get().message());
    }

    /**
     * THE RULE IS SCOPED TO THE CODE LANE. Without this, a branch that refused these
     * summaries for every type would pass every assertion above while breaking the store.
     */
    @Test
    void the_same_summary_is_admitted_for_a_type_that_is_not_a_job() {
        assertTrue(
            EntryForm.check("domain_fact", RESTATEMENT, List.of(), null, null, ANCHOR)
                .isEmpty(),
            "a domain fact may legitimately restate a name — it is a fact about an API."
                + " The job rule must bind to the code lane only");
    }

    /**
     * AND THE COMPARISON IS AGAINST THE ANCHOR, not a ban on the words. The same summary
     * with no anchor is admitted, because with nothing to compare against there is
     * nothing to call a restatement OF — which is also why the five-argument form, used
     * at twelve call sites that hold no anchor, needed no change.
     */
    @Test
    void without_an_anchor_there_is_nothing_to_call_a_restatement_of() {
        assertTrue(EntryForm.check("job", RESTATEMENT, List.of(), null, null).isEmpty(),
            "the rule compares a summary to its anchor; with no anchor it must stay silent"
                + " rather than guess which words are 'too close to the name'");
    }
}
