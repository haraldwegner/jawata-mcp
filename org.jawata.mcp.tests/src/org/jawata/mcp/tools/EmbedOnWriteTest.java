package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jawata.mcp.knowledge.EmbeddingService;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28f E5 — a story is findable by MEANING the moment it is written.
 *
 * <p><b>The half {@code DrainBeforeReturnTest} deliberately does not cover.</b> That one
 * asserts the load's REPORT: nothing it wrote is still waiting for a vector. This one
 * asserts the consequence a reader actually cares about — ask in different words, get the
 * story back — because a count reaching zero and a question being answerable are two
 * claims, and a store can satisfy the first while failing the second.</p>
 *
 * <p><b>The cue shares no content word with the entry, on purpose.</b> A cue echoing the
 * summary is answerable by keyword matching, so it would pass on a store with no vectors
 * at all and prove nothing about embedding. "Sourdough / proofing / poke test" against
 * "bread collapsed / over-risen" is the same idea in different vocabulary, which only the
 * meaning path can bridge.</p>
 *
 * <p><b>And the keyword control is what makes that argument checkable rather than
 * asserted.</b> If the cue turned out to share a word after all, the test would pass for
 * the wrong reason and nobody would know; so the words are compared explicitly and the
 * test fails if they overlap.</p>
 *
 * <p><b>STATED PLAINLY BECAUSE ITS GREEN IS EASY TO MISREAD: this is a REGRESSION LOCK,
 * not evidence for anything Stage 3 built.</b> {@code record} already embedded on write
 * before this sprint — the coverage test this stage rewrote said so in its own comment,
 * <i>"a live record embeds on write, but a restored backup does not"</i>. So this case
 * passes on the code as it stood before E5, and its green says nothing about the work.
 * What E5 actually changed is the OTHER writes — {@code load}, {@code import},
 * {@code wipe_and_import} — which used to hand back rows that were stored and
 * unsearchable; {@code DrainBeforeReturnTest} is the case that goes red when that work is
 * removed.</p>
 *
 * <p>It is kept anyway, and the reason is the defect that opened this stage: the meaning
 * index held only the catalogue while imported rows sat outside it, so every recall for
 * them answered with design patterns. This locks the property from the READER's side for
 * the one write that always had it, so a later change cannot quietly take that away too.</p>
 */
class EmbedOnWriteTest {

    private ObjectMapper mapper;
    private ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ToolResponse call(String kind, String... pairs) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        for (int i = 0; i < pairs.length; i += 2) {
            args.put(pairs[i], pairs[i + 1]);
        }
        return tool.execute(args);
    }

    /** Content words of a phrase, lowercased — the keyword-overlap control's instrument. */
    private static java.util.Set<String> words(String phrase) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (String w : phrase.toLowerCase(java.util.Locale.ROOT).split("[^a-z]+")) {
            if (w.length() > 3) {
                out.add(w);
            }
        }
        return out;
    }

    @Test
    void a_recorded_story_is_found_by_meaning_without_waiting() {
        Assumptions.assumeTrue(EmbeddingService.shared().available(),
            "no embedder available — meaning recall is not running, so this asserts nothing");

        String summary = "the sourdough collapsed after proofing past the poke-test window";
        String cue = "my loaf sank in the middle once the dough had risen far too long";

        // THE CONTROL FIRST. If these share a content word the recall could be answered by
        // the keyword path, and this test would pass over a store holding no vectors at
        // all — green for a reason that has nothing to do with what it claims.
        java.util.Set<String> shared = new java.util.HashSet<>(words(summary));
        shared.retainAll(words(cue));
        assertTrue(shared.isEmpty(),
            () -> "the cue must share NO content word with the entry, or the keyword path"
                + " can answer it and this test says nothing about embedding. Shared: "
                + shared);

        ToolResponse written = call("record",
            "type", "lesson",
            "summary", summary,
            "situation", "when a dough has been left to rise well past its poke test",
            "verdict", "failed_avoid");
        assertTrue(written.isSuccess(),
            () -> "the control: the story has to be stored before it can be found — "
                + written.getData());

        // NO WAIT, NO BACKFILL, NO SECOND CALL. That absence IS the assertion: E5 says the
        // row is searchable the moment it is written, so a reader who asks immediately
        // gets it. A sleep here would turn this into a test of eventual consistency,
        // which is the weaker property the store already had.
        ToolResponse found = call("recall", "symptom", cue, "format", "text");
        assertTrue(found.isSuccess(), () -> "recall failed outright: " + found.getData());

        String answer = String.valueOf(found.getData());
        assertTrue(answer.contains("sourdough") || answer.contains("poke"),
            () -> "A STORY MUST BE FINDABLE BY MEANING THE MOMENT IT IS WRITTEN. The cue"
                + " shares no word with the entry, so only the meaning path can bridge"
                + " them — and if the row has no vector yet, the store answers with"
                + " nominees or an absence instead of the story that was just stored."
                + " Answer was: " + answer);
        assertNotEquals("", answer.trim(),
            "an empty answer would satisfy nothing above it by accident");
    }
}
