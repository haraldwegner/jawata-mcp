package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 8 D4 — WHICH OF THESE MEMBERS HAVE A JOB WRITTEN.
 *
 * <p>The stop rule's question, asked of the store. A turn that added members and described
 * none of them leaves the next reader to work out what they are for by reading them, which
 * is the cost this sprint exists to remove.</p>
 *
 * <p>It loads NO project, and that is a property worth pinning: whether a job row exists is
 * a question about the store, so this answers at the end of a turn even where the resident
 * has nothing loaded — which is the state a stop gate most often finds.</p>
 */
class JobsForTest {

    private static final String DESCRIBED = "com.example.Ledger#recordFailure";
    private static final String UNDESCRIBED = "com.example.Ledger#reset";

    private ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private ToolResponse jobsFor(String... symbols) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "jobs_for");
        var arr = a.putArray("symbols");
        for (String s : symbols) {
            arr.add(s);
        }
        return tool.execute(a);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ToolResponse r) {
        assertTrue(r.isSuccess(), "got " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    private void recordJob(String symbol, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", symbol);
        a.put("summary", summary);
        assertTrue(tool.execute(a).isSuccess(), "the job must be recordable");
    }

    /**
     * BOTH LISTS FROM ONE CALL, which is the whole reason this is a batch: a turn adds
     * several members at once, and asking per symbol would put N round trips on a gate that
     * runs at the end of every turn.
     */
    @Test
    @DisplayName("a described member and an undescribed one are separated in one answer")
    void a_described_and_an_undescribed_member_are_separated() {
        recordJob(DESCRIBED,
            "Marks the breaker as having just seen an error, so the window can open.");

        Map<String, Object> data = dataOf(jobsFor(DESCRIBED, UNDESCRIBED));

        assertEquals(List.of(DESCRIBED), data.get("described"),
            "the one with a job written: " + data);
        assertEquals(List.of(UNDESCRIBED), data.get("missing"),
            "and the one without, which is what the gate acts on: " + data);
    }

    /**
     * THE INSTRUCTION RIDES WITH THE GAP AND NOT OTHERWISE.
     *
     * <p>A gate that blocks without saying exactly what to run is a gate that gets worked
     * around; an instruction attached to a clean answer is noise in every turn that had
     * nothing to fix. Both halves are asserted, because either alone is satisfied by a
     * field that is always present or always absent.</p>
     */
    @Test
    @DisplayName("the exact call to make is present with a gap and absent without one")
    void the_exact_call_rides_with_the_gap() {
        assertTrue(String.valueOf(dataOf(jobsFor(UNDESCRIBED)).get("next"))
                .contains("kind=record, type=job"),
            "with something missing, the answer says exactly what to run");

        recordJob(UNDESCRIBED,
            "Clears the error window so the next caller is let through again.");

        assertFalse(dataOf(jobsFor(UNDESCRIBED)).containsKey("next"),
            "and with nothing missing it says nothing — an instruction on a clean answer"
                + " is noise in every turn that had nothing to fix");
    }

    /**
     * NO SYMBOLS IS A REFUSAL, not an empty success.
     *
     * <p>An empty answer would read as "nothing is missing", which is the one thing a caller
     * must not conclude from a question it never actually asked.</p>
     */
    @Test
    @DisplayName("asking about nothing is refused rather than answered with nothing missing")
    void asking_about_nothing_is_refused() {
        ToolResponse r = jobsFor();

        assertFalse(r.isSuccess(),
            "an empty answer here would read as 'nothing is missing': " + r.getData());
        assertTrue(String.valueOf(r.getError()).contains("symbols"),
            "and the refusal names the argument: " + r.getError());
    }

    /**
     * FILES WITHOUT A PROJECT REFUSE, and this is the assertion the whole gate rests on.
     *
     * <p>The stop rule knows which FILES a turn changed; which MEMBERS those files declare
     * is JDT's answer, and a caller parsing the file itself would be guessing. So asking by
     * file needs a project — and a resident with none must say so rather than answer with
     * an empty {@code missing}, which a gate would read as "nothing to describe" and let
     * every turn through while looking present.</p>
     */
    @Test
    @DisplayName("asking by file without a project refuses instead of finding nothing")
    void asking_by_file_without_a_project_refuses() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "jobs_for");
        a.putArray("filePaths").add("/src/main/java/com/example/Ledger.java");

        ToolResponse r = tool.execute(a);

        assertFalse(r.isSuccess(),
            "no project means the question could not be answered, NOT that nothing is"
                + " missing — the second would silently disarm the gate: " + r.getData());
        assertTrue(String.valueOf(r.getError()).contains("NOT an answer that nothing is"),
            "and it says which of the two it is, in the words a gate author needs: "
                + r.getError());
    }

    /**
     * THE CONTROL for the refusal above: the store-only form still answers with no project.
     *
     * <p>That is the property that lets this run at the END of a turn, which is where a
     * stop gate lives and where a resident most often has nothing loaded. Without this
     * case, a version that refused every call would satisfy the test above.</p>
     */
    @Test
    @DisplayName("and the symbol form still answers with no project loaded")
    void the_symbol_form_still_answers_with_no_project() {
        Map<String, Object> data = dataOf(jobsFor(UNDESCRIBED));

        assertEquals(List.of(UNDESCRIBED), data.get("missing"),
            "this whole class runs with a null service, and this is the assertion that"
                + " says so on purpose rather than by accident: " + data);
    }
}
