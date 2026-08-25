package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Sprint 28c D9 — the shapes that may not enter, measured on what was actually
 * in the store.
 *
 * <p>Every refused string below is REAL: Harald read his own entries back and
 * found log lines, release announcements, sprint-phase notes, compaction
 * artifacts and rows whose whole summary was a chapter heading. None of these is
 * an invented adversarial case, which matters — a gate tuned against imagined
 * noise catches imagined noise.</p>
 */
class StoryTemplateTest {

    private static String kindOf(String summary) {
        StoryTemplate.Refusal r = StoryTemplate.refuse(summary);
        return r == null ? null : r.kind();
    }

    /** The noise, one row per shape, each string as it really appeared. */
    @Test
    void every_observed_noise_shape_is_refused_by_the_shape_it_has() {
        assertEquals("fallback_slip",
            kindOf("jawata-fallback slip: Bash: throwaway clone"));
        assertEquals("log_line",
            kindOf("[main] INFO org.jawata.mcp.knowledge.H2ExperienceStore - store opened"));
        assertEquals("log_line",
            kindOf("2026-08-24 11:20:23 embedding backfill converged"));
        assertEquals("status_note",
            kindOf("SPRINT 25 EXECUTING (2026-07-15, 'God mode' — jawata drives agents)"));
        assertEquals("status_note",
            kindOf("v2.7.1 RELEASED 2026-07-10/11 on both repos, tags pushed, CI green"));
        assertEquals("compaction_artifact",
            kindOf("Context compacted — the conversation summary follows"));
        assertEquals("section_heading", kindOf("4. Testing"));
        assertEquals("section_heading",
            kindOf("5.5 Edge-Case and Integration Tests (from Plan 19.7-19.8)"));
        assertEquals("not_a_claim", kindOf("Overview"));
        assertEquals("not_a_claim", kindOf("Test plan"));
        assertEquals("not_a_claim", kindOf("Project Structure"));
        assertEquals("not_a_claim", kindOf("Durable"));
        assertEquals("empty", kindOf("   "));
    }

    /**
     * THE DISCRIMINATOR, and the reason the status rule keys on CAPITALS.
     *
     * <p>Both strings are about a release. One is an announcement that was true
     * when written and is false now; the other is one of the most useful entries
     * in this store — the record of a feature that shipped inert past 1,591 green
     * tests. A rule that refused "mentions a version and a status word" would
     * take the second with the first, and the store would lose exactly the kind
     * of entry it exists for.</p>
     *
     * <p>A status NOTE announces: the shouted word sits immediately after the
     * identifier. A lesson NARRATES: the same word appears inside a sentence.
     * That is a real difference in the data, not a trick — and if this pair ever
     * stops discriminating, the rule is wrong and this test is where it says
     * so.</p>
     */
    @Test
    void an_announcement_is_refused_and_a_lesson_that_narrates_one_is_not() {
        assertEquals("status_note",
            kindOf("v2.7.1 RELEASED 2026-07-10/11 on both repos"),
            "an announcement: the shouted status sits right after the version");

        assertNull(StoryTemplate.refuse(
            "v3.4.0 shipped semantic recall INERT — all three production sites built"
            + " ExperienceRetrieval via the no-index constructor while every test wired"
            + " one by hand"),
            "and a lesson that NARRATES a release must survive: this is the entry that"
                + " records a feature shipping dead past 1,591 green tests, and losing it"
                + " to a status rule would be the store discarding its own best work");

        assertNull(StoryTemplate.refuse(
            "Sprint 28b's own /report seat filed the first three findings against the"
            + " build that shipped it, which is what closing a loop on yourself looks like"),
            "naming a sprint is not announcing its phase");
    }

    /**
     * THE GIBBERISH SENTENCE IS **NOT** CAUGHT HERE, and that is the design.
     *
     * <p>D10's worked example — <i>"when a number invented to diagnose a problem
     * becomes the figure leadership reviews every week"</i> — is well-formed,
     * long enough, unshouted, unnumbered and unlogged. Every mechanical rule
     * passes it, because the thing wrong with it is that a stranger cannot tell
     * whether they are in it: what number, whose leadership, which problem.</p>
     *
     * <p>Asserting the PASS is what keeps the two halves honest. If someone
     * later "improves" the gate until this string is refused, they will have
     * built a meaning-judge inside the gate — and a meaning-judge that runs
     * without a reader is the thing that turns away real knowledge. The cold
     * reader owns this case; the gate must not pretend to.</p>
     */
    @Test
    void a_well_formed_platitude_passes_the_gate_because_only_a_reader_can_catch_it() {
        assertNull(StoryTemplate.refuse(
            "when a number invented to diagnose a problem becomes the figure leadership"
            + " reviews every week"),
            "the mechanical gate must NOT catch this — it is well-formed, and what is"
                + " wrong with it is only visible to a reader with no context. A gate that"
                + " caught it would be judging meaning, which is how real knowledge gets"
                + " turned away.");
    }

    /** A real story, with everything the template asks for, is not refused. */
    @Test
    void a_written_story_passes() {
        assertNull(StoryTemplate.refuse(
            "re-read the queue head before re-arming, because it moves while the"
            + " consumer is away"));
    }

    /**
     * The word floor is a JUDGEMENT and its cost is asserted, not hidden.
     *
     * <p>Four words is not a measured threshold. Pinning both sides of it means a
     * later reader sees exactly what it buys and exactly what it costs, rather
     * than discovering the cost when a real three-word claim is refused.</p>
     */
    @Test
    void the_word_floor_costs_a_genuine_short_claim_and_says_so() {
        assertEquals(4, StoryTemplate.MIN_CLAIM_WORDS);
        assertEquals("not_a_claim", kindOf("Locks deadlock here"),
            "three words is refused — including when it IS a claim; that is the cost");
        assertTrue(StoryTemplate.refuse("Locks deadlock here").why().contains("REPHRASE"),
            "so the refusal must ask for a rephrase, not declare the knowledge worthless");
        assertNull(StoryTemplate.refuse("Locks deadlock in the writer"),
            "and four words is admitted");
    }

    /**
     * The author is taught the template BEFORE writing, in the tool schema every
     * client loads — derived from the same fields the gate refuses on.
     *
     * <p>The schema teaches before the mistake and the refusal teaches after it.
     * Two hand-written copies of the same rules drift with nothing comparing them,
     * which is why both come from this class.</p>
     */
    @Test
    void the_author_guidance_is_derived_from_the_fields_and_the_refusals() {
        String g = StoryTemplate.authorGuidance();
        for (StoryTemplate.Field f : StoryTemplate.FIELDS) {
            assertTrue(g.contains(f.name()), "the schema omits the field " + f.name() + ": " + g);
        }
        for (String kind : StoryTemplate.refusalKinds()) {
            assertTrue(g.contains(kind.replace('_', ' ')),
                "an author is refused for '" + kind + "' but never told about it: " + g);
        }
    }

    /** The cold reader is asked BOTH questions, and given every field's conditions. */
    @Test
    void the_cold_reader_prompt_is_built_from_the_same_fields_the_gate_uses() {
        String p = StoryTemplate.coldReaderPrompt();
        assertTrue(p.contains("WHEN does this apply"), p);
        assertTrue(p.contains("DO DIFFERENTLY"),
            "question (b) is the half that kills comprehensible platitudes; without it "
                + "the review is a spell-check");
        for (StoryTemplate.Field f : StoryTemplate.FIELDS) {
            assertTrue(p.contains(f.name()), "the prompt omits the field " + f.name());
            for (String c : f.conditions()) {
                assertTrue(p.contains(c),
                    "the prompt omits a condition the gate's own message quotes: " + c);
            }
        }
    }

    /**
     * The documented rule cannot drift from the enforced one.
     *
     * <p>{@code docs/story-template.md} is what a human reads, including Harald;
     * this class is what the gate applies. A refusal added in code and not in the
     * doc means an author is refused for a rule nobody published.</p>
     */
    @Test
    void the_published_template_names_every_field_and_every_refusal() throws Exception {
        String doc = Files.readString(docFile(), StandardCharsets.UTF_8)
            .toLowerCase(Locale.ROOT);
        List<String> missing = new ArrayList<>();
        for (StoryTemplate.Field f : StoryTemplate.FIELDS) {
            if (!doc.contains(f.name().toLowerCase(Locale.ROOT))) {
                missing.add("field: " + f.name());
            }
        }
        // The kinds are spelled with underscores in code and with spaces in prose,
        // so the check is on the WORDS, not on the identifier — otherwise the doc
        // would have to be written in code's voice to satisfy its own test.
        for (String kind : StoryTemplate.refusalKinds()) {
            String words = kind.replace('_', ' ');
            if (!doc.contains(words) && !doc.contains(kind)) {
                missing.add("refusal: " + kind);
            }
        }
        assertTrue(missing.isEmpty(),
            "docs/story-template.md does not publish everything the gate enforces: "
                + missing);
    }

    private static Path docFile() {
        List<String> tried = new ArrayList<>();
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("docs").resolve("story-template.md");
            tried.add(candidate.toString());
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        fail("docs/story-template.md was not found; looked in: " + tried);
        throw new IllegalStateException("unreachable");
    }

    /** Sanity: the template really does carry the four fields plus the anchor. */
    @Test
    void the_template_has_the_fields_the_spec_names() {
        assertEquals(List.of("situation", "summary", "details", "outcome", "anchor"),
            StoryTemplate.FIELDS.stream().map(StoryTemplate.Field::name).toList());
        for (StoryTemplate.Field f : StoryTemplate.FIELDS) {
            assertNotNull(f.question(), f.name() + " has no question");
            assertTrue(!f.conditions().isEmpty(), f.name() + " has no conditions");
        }
    }
    /**
     * THE DRIFT CONTROL, and it exists because the drift happened.
     *
     * <p>The published template told authors, bolded and as a hard REFUSED, that
     * naming a technology in a situation gets the entry turned away. That was true
     * of an older gate. {@link AdmissionPolicy#misplacedInSituation} now admits a
     * bare product name and refuses only a structural ADDRESS — and it was changed
     * precisely because stripping the technology out destroys the field's
     * discriminating power. The document kept the reversed rule for two days,
     * instructing exactly the over-generalisation the rest of it exists to prevent.</p>
     *
     * <p>The parity test above runs code → doc and cannot see this: it checks that
     * everything the code emits is MENTIONED, never that what the doc claims is
     * still true. This asserts the one direction that bit, on the one rule where a
     * silent reversal is most expensive.</p>
     */
    @Test
    void the_published_template_does_not_reinstate_the_technology_ban() throws Exception {
        String doc = Files.readString(docFile(), StandardCharsets.UTF_8);
        // The gate's own behaviour is the fact the doc must not contradict.
        for (String tech : new String[] {"WebKitGTK", "PostgreSQL", "GitHub"}) {
            String situation = "a desktop app built on " + tech + " comes up blank";
            assertNull(EntryForm.check("domain_fact", "Set the compositor variable.",
                    List.of(), situation, null).orElse(null),
                tech + " must be admissible in a situation — it is what makes the entry findable");
        }
        String lower = doc.toLowerCase(Locale.ROOT);
        assertTrue(!lower.contains("naming a technology in the situation gets the entry refused"),
            "docs/story-template.md has reinstated the technology ban the gate no longer applies");
        assertTrue(lower.contains("technology names belong in the situation"),
            "the document must state the rule the gate actually enforces");
    }
}
