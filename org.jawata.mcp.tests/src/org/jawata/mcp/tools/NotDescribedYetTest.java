package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 7, E9 — AN UNREAD AREA ANSWERS "not described yet".
 *
 * <p>{@code ARCHITECTURE-28f-database.md} M7 states the gate in those words, and the plan's
 * deliverable 5 puts the rendering in {@code MemoryView}. This is the half that must exist
 * for the view to have anything to render: the ANSWER.</p>
 *
 * <h2>Why an absence has to be spoken rather than left blank</h2>
 *
 * <p>A package with no area row is the ordinary state of a store that has just begun
 * describing. The distinction a reader has to be able to make is between "nobody has
 * described this yet" and "we looked and there is nothing to say" — and a blank renders the
 * first as the second. That is the one confusion this product refuses everywhere else it
 * reports a count: the describing block publishes the numerator ONLY and says so, rather
 * than inventing a ratio at the point of display.</p>
 *
 * <p><b>It is answered by the engine and not by the view, and that is not an accident of
 * where it was easy to write.</b> Which packages a scope holds is JDT's answer and the
 * resident's alone. A studio that inferred "not described" from a key being absent would be
 * deriving the half it cannot see — which is how a view ends up confidently reporting a
 * number nothing measured.</p>
 */
class NotDescribedYetTest {

    private static final String SCOPE = "com.example";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("compile-clean");
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> service, store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private ObjectNode next() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "next");
        a.put("scope", SCOPE);
        return a;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> areasOf(ToolResponse r) {
        assertTrue(r.isSuccess(), "got " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> areas = (List<Map<String, Object>>) data.get("areas");
        assertTrue(areas != null && !areas.isEmpty(),
            "PROOF OF LIFE: the fixture has source in " + SCOPE + ", so the scope holds at"
                + " least one package — without this every assertion below could pass over"
                + " an empty list: " + data);
        return areas;
    }

    private String areaOf(ToolResponse r, String pkg) {
        return areasOf(r).stream()
            .filter(a -> pkg.equals(a.get("package")))
            .map(a -> String.valueOf(a.get("area")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "the scope holds " + pkg + " but the answer names no such package: "
                    + areasOf(r)));
    }

    /** Record the area the way the cataloguer seat does — through the front door. */
    private void describeTheArea() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary",
            "The lane between an agent's questions and what this machine has already"
                + " learned: it holds the rows and answers a cue with what fits or with an"
                + " honest nothing.");
        ArrayNode packages = a.putArray("packages");
        packages.add(SCOPE);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "the area must be recordable; got " + r.getError());
    }

    @Test
    @DisplayName("a package nobody has described answers 'not described yet'")
    void a_package_nobody_has_described_answers_not_described_yet() {
        assertEquals("not described yet", areaOf(tool.execute(next()), SCOPE),
            "the answer is a SENTENCE a reader can act on, not an absent key they have to"
                + " interpret");
    }

    /**
     * THE CONTROL, and without it the case above passes against a verb that answers
     * "not described yet" for everything, forever — which is the same words and no
     * information.
     */
    @Test
    @DisplayName("once the area is described, the same package stops saying it")
    void once_the_area_is_described_the_same_package_stops_saying_it() {
        assertEquals("not described yet", areaOf(tool.execute(next()), SCOPE),
            "proof of life: it says so BEFORE, or the change below proves nothing");

        describeTheArea();

        assertEquals("described", areaOf(tool.execute(next()), SCOPE),
            "the answer follows the store rather than being a constant");
    }

    /**
     * An area row for a DIFFERENT package does not silence this one.
     *
     * <p>The join is on the package name, and a join that matched anything would be
     * indistinguishable from a working one on a fixture with a single package — which is
     * exactly the fixture this class uses.</p>
     */
    @Test
    @DisplayName("an area described elsewhere does not answer for this package")
    void an_area_described_elsewhere_does_not_answer_for_this_package() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary",
            "Somewhere else entirely: the package that holds the refactoring doors and"
                + " nothing this scope can see.");
        a.putArray("packages").add("com.somewhere.else");
        assertTrue(tool.execute(a).isSuccess());

        assertEquals("not described yet", areaOf(tool.execute(next()), SCOPE),
            "an area row exists, but not for THIS package, so the answer is unchanged");
    }

    /** The units queue is untouched by all this — the area answer rides beside it. */
    @Test
    @DisplayName("the answer rides beside the queue rather than replacing it")
    void the_answer_rides_beside_the_queue() {
        ToolResponse r = tool.execute(next());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();

        assertFalse(((List<?>) data.get("units")).isEmpty(),
            "the queue still hands out units: " + data.keySet());
        assertTrue(((Number) data.get("inScope")).intValue() > 0,
            "and still reports how much the scope holds: " + data);
    }
}
