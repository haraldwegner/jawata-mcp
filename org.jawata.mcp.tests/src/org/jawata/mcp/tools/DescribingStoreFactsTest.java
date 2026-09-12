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
 * Sprint 28f Stage 7, deliverable 5 — WHAT {@code MemoryView} RENDERS BESIDE THE COVERAGE
 * COUNTS: which areas are described, and a sample of what the cataloguer actually wrote.
 *
 * <h2>Why this class loads no project, and its sibling does</h2>
 *
 * <p>{@link NotDescribedYetTest} drives {@code describe action=next}, whose areas answer
 * needs JDT to know which packages a scope holds. THIS answer is read from store rows
 * alone, which is the whole reason it can ride on {@code stats} — a response the studio
 * can ask for without a project. Folding the two together would make every store-only case
 * pay a project load, which is the same split {@code JobLocationTest} was made for.</p>
 *
 * <h2>The two populations are different, and neither contains the other</h2>
 *
 * <p>{@code describe action=next} answers over the packages a SCOPE holds; this answers
 * over the packages the STORE has rows for. A package nobody has touched is present there
 * and invisible here — so this block publishes the DESCRIBED set and says nothing about
 * the outstanding one, the same way the coverage block publishes the numerator only. The
 * view names the verb that answers the other half rather than inventing it.</p>
 */
class DescribingStoreFactsTest {

    /** Two jobs here, so the per-type spread has something to hold back. */
    private static final String LEDGER = "com.example.Ledger";
    /** And one here, so the spread has somewhere to spend the room it saved. */
    private static final String GAUGE = "com.example.Gauge";

    private ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        // No project: this answer is store rows, and a supplier that returns one would
        // hide a dependency on JDT if the code ever grew one.
        tool = new ExperienceTool(() -> null, store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> describing() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "stats");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        Object block = data.get("describing");
        assertTrue(block instanceof Map,
            "PROOF OF LIFE: stats must carry the describing block at all — without this"
                + " every assertion below would pass over a missing key: " + data.keySet());
        return (Map<String, Object>) block;
    }

    @SuppressWarnings("unchecked")
    private List<String> describedAreas() {
        return (List<String>) describing().get("describedAreas");
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> sample() {
        return (List<Map<String, Object>>) describing().get("acceptedSample");
    }

    /** Record a job the way the cataloguer does — anchored to the member it explains. */
    private void recordJob(String symbol, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", symbol);
        a.put("summary", summary);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(),
            "the gate must ACCEPT this row, or the sample below samples nothing and the"
                + " test measures the gate instead of the block: " + r.getError());
    }

    /** Record an area the way the cataloguer does — scoped to the package it summarises. */
    private void recordArea(String pkg, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary", summary);
        a.putArray("packages").add(pkg);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "the area must be recordable; got " + r.getError());
    }

    /**
     * THE CONTROL IS THE FIRST HALF, not a separate case: a block that named every package
     * forever would satisfy the second half alone, and an empty one would satisfy nothing
     * at all. Both states are asserted against the same store.
     */
    @Test
    @DisplayName("an area appears in the described list once, and only once, it is recorded")
    void an_area_appears_once_it_is_recorded() {
        assertTrue(describedAreas().isEmpty(),
            "proof of life: NOTHING is described before anything is recorded, or the"
                + " assertion below cannot tell a working read from a constant");

        recordArea("com.example",
            "The lane between an agent's questions and what this machine already learned.");

        assertEquals(List.of("com.example"), describedAreas(),
            "the list follows the store rather than being a constant");
    }

    /**
     * A JOB ROW ADDS NO PACKAGE, and this pins the measurement the block is built on.
     *
     * <p>A package holding job rows and no area row would be the obvious "worked on, not
     * summarised yet" answer, and it cannot be read: {@code symbol} and {@code packages[]}
     * are mutually exclusive at the record verb, a job is anchored by {@code symbol}, and
     * the store persists a package only from {@code scope.packages}. So every job row's
     * package is null. Without this case a later reader could wire the outstanding list to
     * job rows, watch it compile and pass, and ship a list that is empty forever — which
     * reads as "nothing left to do".</p>
     */
    @Test
    @DisplayName("a job row does not make its package count as described")
    void a_job_row_does_not_make_its_package_count_as_described() {
        recordJob(LEDGER + "#recordFailure",
            "Marks the breaker as having just seen an error, so the window can open.");

        assertTrue(describedAreas().isEmpty(),
            "the described list is read from AREA rows alone; a job row carries no package"
                + " at all, so nothing about it can reach this list: " + describedAreas());
    }

    /** The sample is what a reader judges the cataloguer's work BY, so it carries both. */
    @Test
    @DisplayName("a sampled row carries the symbol it explains and the sentence written")
    void a_sampled_row_carries_the_symbol_and_the_summary() {
        recordJob(LEDGER + "#recordFailure",
            "Marks the breaker as having just seen an error, so the window can open.");

        List<Map<String, Object>> rows = sample();
        assertEquals(1, rows.size(), "one accepted row, one sampled row: " + rows);
        assertEquals(LEDGER + "#recordFailure", rows.get(0).get("symbol"),
            "the anchor is what identifies the row — a job carries no bundle and no"
                + " package, which is why the sample is listed by symbol: " + rows);
        assertTrue(String.valueOf(rows.get(0).get("summary")).contains("window can open"),
            "and the SENTENCE, because reading what was written is the whole point of a"
                + " sample beside a count: " + rows);
    }

    /**
     * THE SPREAD, and it is the one rule the sample applies.
     *
     * <p>Three jobs on one type and one on another. Without the per-type cap the first
     * type's three rows would be the sample and the second type would be invisible — a
     * sample that shows one class is not a sample of the work.</p>
     */
    @Test
    @DisplayName("one class cannot fill the sample, and a second class is represented")
    void one_class_cannot_fill_the_sample() {
        recordJob(LEDGER + "#recordFailure",
            "Marks the breaker as having just seen an error, so the window can open.");
        recordJob(LEDGER + "#lastFailureTime",
            "Answers when the most recent error arrived, for the retry window.");
        recordJob(LEDGER + "#reset",
            "Clears the error window so the next caller is let through again.");
        recordJob(GAUGE + "#scale",
            "Answers how many units one screen pixel stands for right now.");

        List<Map<String, Object>> rows = sample();
        long fromLedger = rows.stream()
            .filter(r -> String.valueOf(r.get("symbol")).startsWith(LEDGER + "#")).count();
        long fromGauge = rows.stream()
            .filter(r -> String.valueOf(r.get("symbol")).startsWith(GAUGE + "#")).count();

        assertEquals(2, fromLedger,
            "at most two rows share a declaring type, so the third is held back: " + rows);
        assertEquals(1, fromGauge,
            "and the room that saved is spent on the other type, which is the whole"
                + " point — a sample showing one class is not a sample: " + rows);
    }

    /**
     * The count is the POPULATION, not the sample's own size — which is what makes it worth
     * publishing at all. Four rows accepted, three sampled: a count equal to the sample
     * would be a number restating the list beside it.
     */
    @Test
    @DisplayName("the accepted count is the population the sample was drawn from")
    void the_accepted_count_is_the_population() {
        recordJob(LEDGER + "#recordFailure",
            "Marks the breaker as having just seen an error, so the window can open.");
        recordJob(LEDGER + "#lastFailureTime",
            "Answers when the most recent error arrived, for the retry window.");
        recordJob(LEDGER + "#reset",
            "Clears the error window so the next caller is let through again.");
        recordJob(GAUGE + "#scale",
            "Answers how many units one screen pixel stands for right now.");

        assertEquals(4, ((Number) describing().get("acceptedRows")).intValue(),
            "four rows were accepted: " + describing());
        assertEquals(3, sample().size(),
            "and three were sampled, so the count is not the list's own length: "
                + sample());
        assertFalse(describing().containsKey("partial"),
            "and nothing claims the read was capped at this size: " + describing());
    }
}
