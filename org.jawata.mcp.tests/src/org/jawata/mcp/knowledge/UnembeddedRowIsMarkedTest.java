package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28f D4 — a row that cannot answer by meaning SAYS SO, instead of scoring zero.
 *
 * <p><b>The line this is about.</b> {@code ExperienceAnalogies.rank} scores each candidate
 * as {@code semantic.getOrDefault(e.id(), 0.0)}, and TWO different things are absent from
 * that map: a row the meaning path weighed and placed below the floor, and a row that has no
 * vector at all. Both land on 0.0 and both render as the same weak answer, so a reader cannot
 * tell "this was considered and is a poor fit" from "this was never in the running". D4 names
 * that in its own words — <i>never scored zero silently</i>.</p>
 *
 * <p><b>THE CONTROL IS THE HALF THAT MAKES IT A TEST.</b> Asserting only that the mark
 * appears would pass against a renderer that marks every row, which is a worse defect than
 * the one being fixed: a false warning printed beside a healthy answer. So both rows are put
 * in ONE result — one with a vector, one without — and the assertions are scoped to each
 * row's own rendered line. A whole-response {@code contains} cannot say WHICH row was marked,
 * which is this sprint's own recorded lesson about counts that cannot tell two things
 * apart.</p>
 *
 * <p><b>The unembedded row reaches the answer by WORDS,</b> because by construction it cannot
 * reach it by meaning — which is also why the mark is worth printing: the row is in front of
 * the reader either way.</p>
 */
class UnembeddedRowIsMarkedTest {

    /** The word both summaries and the cue share, so the keyword path nominates both. */
    private static final String SHARED = "quokka";

    private static final String WITH_VECTOR =
        "the " + SHARED + " ledger reconciles itself after every equinox audit";
    private static final String WITHOUT_VECTOR =
        "the " + SHARED + " warehouse restocks lanterns before the solstice inspection";

    /**
     * The cue, and WHICH SECTION it lands the row in is not something this test pins.
     *
     * <p>Mutation G measured why it must not. The recall answer lists direct hits and then
     * ranks what is left, and the retrieval path drops an already-listed row from the ranked
     * section — so a cue that echoes the row's words puts it in the listing and the ranked
     * branch never runs, while a cue sharing one word fails to surface it at all. Both were
     * tried; the window between them is a property of the nominator, not of the claim, and a
     * test balanced on it would break whenever that nominator is tuned.</p>
     *
     * <p>So the end-to-end case below asserts the mark WHEREVER the answer put the row, and
     * the ranked branch gets a deterministic case of its own that supplies the set directly.
     * Together they cover both renderers without either depending on the nominator's mood.</p>
     */
    private static final String CUE = SHARED + " inspection before the solstice";

    private ObjectMapper mapper;
    private H2ExperienceStore store;
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(String kind, String... kv) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        for (int i = 0; i < kv.length; i += 2) {
            a.put(kv[i], kv[i + 1]);
        }
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> kind + " failed: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    /**
     * The rendered line for THIS row, wherever the answer put it.
     *
     * <p><b>Scoped to the row and deliberately not to a section</b>, and the second half
     * is a correction. An earlier version demanded the ranked line, reasoning that the
     * silent zero lives in the ranking; the run disproved it. A recall answer lists direct
     * hits and then ranks what is left, and the retrieval path EXCLUDES an already-listed
     * row from the ranked section — so an unembedded row commonly appears only in the
     * listing, and a test insisting on a ranked line asserts nothing about it.</p>
     *
     * <p>Which section a row lands in is a property of the cue, not of the claim, so the
     * test does not pin it. What it does pin is the ROW: a whole-response
     * {@code contains} could not say which of the two was marked, and that is the failure
     * this sprint has recorded against counts that cannot tell two things apart.</p>
     */
    private static String lineFor(String answer, String needle) {
        for (String line : answer.split("\\R")) {
            if (line.contains(needle)) {
                return line;
            }
        }
        throw new AssertionError(
            "the answer never mentions '" + needle + "', so nothing below is measuring a"
                + " rendered row. Answer was:\n" + answer);
    }

    @Test
    void a_row_with_no_vector_is_marked_and_a_row_with_one_is_not() {
        Assumptions.assumeTrue(EmbeddingService.shared().available(),
            "no embedder available — every row would be unembedded, so the CONTROL half"
                + " (a row that must NOT be marked) has nothing to stand on");

        // The row that CAN answer by meaning: recorded through the verb, which embeds on
        // write. This is the control, and it is what a marks-everything renderer fails.
        call("record",
            "type", "lesson",
            "summary", WITH_VECTOR,
            "situation", "when the ledger is reconciled at an equinox",
            "verdict", "worked");

        // The row that CANNOT — and it is built by RESTORING a real one rather than by
        // hand. A hand-written row map is a row whose author decided which fields matter,
        // and the first version of this test proved that guess wrong: it never reached the
        // answer at all, so the assertions below measured nothing. Recording it, exporting
        // it, deleting it and importing it back gives a row the product itself produced,
        // in the one state the deliverable is about — stored, and with no vector, exactly
        // as a restored backup leaves it.
        String doomedId = String.valueOf(call("record",
            "type", "lesson",
            "summary", WITHOUT_VECTOR,
            "situation", "when the warehouse is inspected before a solstice",
            "verdict", "worked").get("id"));

        Map<String, Object> exported = store.exportEntries(null, null).stream()
            .filter(row -> doomedId.equals(String.valueOf(row.get("id"))))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the row must be exportable before it can be restored — without it this"
                    + " test has no unembedded row and asserts nothing"));

        store.deleteByIds(List.of(doomedId));
        // Straight through the STORE, never the import VERB: E5 taught the verb to index
        // before it answers, so the verb cannot leave the state this test needs.
        store.importEntries(List.of(exported));

        // format=text answers a STRING, not a map — the rendered surface a reader
        // actually sees, which is where D4 asks for the mark.
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "recall");
        a.put("symptom", CUE);
        a.put("format", "text");
        ToolResponse recalled = tool.execute(a);
        assertTrue(recalled.isSuccess(), () -> "recall failed: " + recalled.getError());
        String answer = String.valueOf(recalled.getData());

        String marked = lineFor(answer, "warehouse restocks lanterns");
        String healthy = lineFor(answer, "ledger reconciles itself");

        assertTrue(marked.contains("no meaning vector yet"),
            () -> "A ROW THAT CANNOT ANSWER BY MEANING MUST SAY SO. Unmarked it sits at a"
                + " score of zero, indistinguishable from a row the meaning path weighed"
                + " and rejected — which is the silent zero D4 exists to end. Its line"
                + " was:\n  " + marked);

        assertFalse(healthy.contains("no meaning vector yet"),
            () -> "THE CONTROL: a row that HAS a vector must not be marked. A renderer that"
                + " marks everything prints a false warning beside a healthy answer, which"
                + " is worse than the defect being fixed. Its line was:\n  " + healthy);
    }

    /** The basis this row was given, by id — so a mark cannot be read off the other row. */
    private static List<String> basisOf(
            List<ExperienceAnalogies.Analogy> ranked, String id) {
        return ranked.stream()
            .filter(a -> id.equals(a.entry().id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the ranking dropped " + id + ", so nothing about it was rendered"))
            .basis();
    }

    /**
     * The RANKED renderer carries the mark too — proved without the nominator in the way.
     *
     * <p><b>This case exists because a mutation stayed green.</b> Deleting the ranked mark
     * left the end-to-end case above passing, because its row reaches the reader through the
     * direct listing and the retrieval path then excludes it from the ranking. The branch was
     * written, shipped, and exercised by nothing — the "published surface with no test" shape
     * this sprint has refused twice at earlier checkpoints.</p>
     *
     * <p>The ranker is driven directly with the set supplied, so which section a cue happens
     * to choose cannot decide whether the branch runs. It needs no embedder either, so unlike
     * the case above it is covered on every machine.</p>
     */
    @Test
    void the_ranked_renderer_marks_the_row_with_no_vector_and_only_that_row() {
        call("record", "type", "lesson", "summary", WITH_VECTOR,
             "situation", "when the ledger is reconciled at an equinox", "verdict", "worked");
        call("record", "type", "lesson", "summary", WITHOUT_VECTOR,
             "situation", "when the warehouse is inspected before a solstice",
             "verdict", "worked");

        List<StoredEntry> pool = store.all();
        assertTrue(pool.size() >= 2, () -> "both rows must be stored: " + pool.size());
        StoredEntry doomed = pool.stream()
            .filter(e -> WITHOUT_VECTOR.equals(e.summary())).findFirst().orElseThrow();
        StoredEntry healthy = pool.stream()
            .filter(e -> WITH_VECTOR.equals(e.summary())).findFirst().orElseThrow();

        List<ExperienceAnalogies.Analogy> ranked = ExperienceAnalogies.rank(
            pool, null, Map.of(), Map.of(), List.of(), 10, () -> null,
            java.util.Set.of(doomed.id()));

        assertTrue(basisOf(ranked, doomed.id()).contains("no meaning vector yet"),
            () -> "the ranked line must say the row could not answer by meaning, instead of"
                + " leaving it at a score of zero that reads as a considered near-miss."
                + " Basis was: " + basisOf(ranked, doomed.id()));
        assertFalse(basisOf(ranked, healthy.id()).contains("no meaning vector yet"),
            () -> "THE CONTROL: a row NOT in the set must not be marked, or the renderer is"
                + " marking everything. Basis was: " + basisOf(ranked, healthy.id()));
    }
}
