package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.WorkedPackages;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g D31 — COVERAGE FOLLOWS USE.
 *
 * <p>The describing queue is the workspace in file order, which is a route nobody's attention
 * takes: the package somebody opened this morning and the package nobody has touched in a year
 * are the same row to it. These cases pin the other input — where the work has been — and the
 * two things the deliverable owes beyond recording it: that the record outlives the session, and
 * that a batch which followed the work SAYS so, since a reader cannot otherwise tell it from a
 * sweep.</p>
 *
 * <h2>Where the recording happens, and why that is not an accident</h2>
 *
 * <p>It rides on {@code describe action=area}, which the hook already fires on a tool call
 * naming a {@code .java} file. That is the event — somebody opened something in this package —
 * and it is ALREADY ARRIVING, which is what lets the measure's third clause (<i>recording does
 * not make the file-open path slower or noisier</i>) hold by construction: no second round trip,
 * nothing added to the response. {@link #the_file_open_answer_is_unchanged_by_the_recording} is
 * the half of that a test can actually hold.</p>
 */
class CoverageFollowsUseTest {

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

    /** A real source file of the loaded fixture — taken from the service, not composed. */
    private String aJavaFile() {
        List<Path> files = service.getAllJavaFiles();
        assertFalse(files.isEmpty(),
            "PROOF OF LIFE: the fixture must hold source, or every assertion below runs over a"
                + " project with nothing in it");
        return files.get(0).toString();
    }

    private ToolResponse area(String filePath) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "area");
        a.put("filePath", filePath);
        return tool.execute(a);
    }

    private ToolResponse next() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "next");
        a.put("limit", 5);
        return tool.execute(a);
    }

    private void recordArea(String pkg, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary", summary);
        a.putArray("packages").add(pkg);
        assertTrue(tool.execute(a).isSuccess(), "the area must be recordable");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(ToolResponse r) {
        assertTrue(r.isSuccess(), "got " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("opening a file records the package somebody is working in")
    void opening_a_file_records_the_package() {
        String file = aJavaFile();
        String pkg = String.valueOf(dataOf(area(file)).get("package"));

        List<String> worked = new WorkedPackages(() -> store).stillUndescribed(10);
        assertEquals(List.of(pkg), worked,
            () -> "opening a file in " + pkg + " must record it as worked in; got " + worked);
    }

    /**
     * The clause says the record <i>survives the session</i>, and the thing that makes it true
     * is that it is in the store rather than in a session's own scratch state.
     *
     * <p>Asked through a SECOND {@link WorkedPackages} built after the fact, which is what a
     * later session is: the first object is gone and the row is still there. Asking the same
     * instance again would pass over an in-memory field and prove nothing about durability.</p>
     */
    @Test
    @DisplayName("the record is in the store, so a later session still sees it")
    void the_record_outlives_the_object_that_wrote_it() {
        String pkg = String.valueOf(dataOf(area(aJavaFile())).get("package"));

        WorkedPackages aLaterSession = new WorkedPackages(() -> store);
        assertTrue(aLaterSession.stillUndescribed(10).contains(pkg),
            "a package recorded through one object must be readable through another");
    }

    /**
     * The verdict is DERIVED, not stored — which is the design decision this case exists for.
     *
     * <p>A stored "worked in and still undescribed" flag would be stale the moment somebody
     * described that package, and nothing in the describing path clears it. Here the row stays
     * exactly as written and the ANSWER changes, because the question is asked against the
     * described rows every time.</p>
     */
    @Test
    @DisplayName("describing the package drops it from the queue without touching its row")
    void a_described_package_stops_being_outstanding() {
        String pkg = String.valueOf(dataOf(area(aJavaFile())).get("package"));
        WorkedPackages worked = new WorkedPackages(() -> store);
        assertTrue(worked.stillUndescribed(10).contains(pkg), "PROOF OF LIFE: it starts outstanding");

        recordArea(pkg, "This package holds the fixture's own sample types, and nothing else.");

        assertFalse(worked.stillUndescribed(10).contains(pkg),
            "once the package HAS an area, it is no longer a package nobody has described —"
                + " and nothing re-wrote its row to say so");
    }

    /**
     * The measure's second clause: the batch takes it first AND says so.
     *
     * <p>Both halves are asserted, because either alone is satisfiable without the deliverable:
     * an order with nothing saying why is indistinguishable from the file order it replaced, and
     * a sentence with no reordering behind it is a claim.</p>
     */
    @Test
    @DisplayName("a use-driven batch leads with the worked package and names it")
    void the_next_batch_prefers_the_worked_package_and_says_so() {
        String pkg = String.valueOf(dataOf(area(aJavaFile())).get("package"));

        Map<String, Object> out = dataOf(next());
        assertEquals("use-driven — units from packages somebody has been working in come first;"
                + " the rest follow in file order",
            out.get("order"),
            () -> "the batch must declare which kind it is: " + out);
        assertEquals(List.of(pkg), out.get("preferredPackages"),
            () -> "and name the work it followed: " + out);
    }

    /**
     * THE ORDER ITSELF, on a fixture that can actually tell one order from another.
     *
     * <p>The case above asserts the batch SAYS it followed the work. It cannot assert that it
     * DID: {@code compile-clean} holds one package, so "the worked package comes first" is true
     * there whatever the sort does, and an assertion that cannot fail is the shape this sprint
     * has shipped five times. {@code simple-maven} has two packages, so it can.</p>
     *
     * <p>It calibrates itself against the sweep rather than hard-coding a package name: it reads
     * the order the queue gives UNPROMPTED, picks a package that is NOT at the front of it, and
     * only then opens a file there. A fixed name would pass for free on the day file order
     * already put that package first, and silently go on passing after it stopped.</p>
     */
    @Test
    @DisplayName("the worked package actually comes first, not merely announced")
    void the_batch_leads_with_the_worked_package() throws Exception {
        JdtServiceImpl bigger = helper.loadProjectCopy("simple-maven");
        ExperienceTool wide = new ExperienceTool(() -> bigger, store);

        // The WHOLE fixture, not a page of it. At 50 the calibration never reached a second
        // package — simple-maven's other one sorts far down file order — and the proof of life
        // below fired rather than the test passing over a sweep it had only seen the front of.
        List<Map<String, Object>> sweep = unitsOf(wide, 1000);
        String front = pkgOf(sweep.get(0));
        Map<String, Object> elsewhere = sweep.stream()
            .filter(u -> !front.equals(pkgOf(u)))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "PROOF OF LIFE: this fixture must hold more than one package, or the order"
                    + " assertion below cannot fail and proves nothing"));

        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "area");
        a.put("filePath", String.valueOf(elsewhere.get("unit")));
        assertTrue(wide.execute(a).isSuccess(), "the file must be openable");

        String worked = pkgOf(elsewhere);
        List<Map<String, Object>> afterwards = unitsOf(wide, 5);
        assertEquals(worked, pkgOf(afterwards.get(0)),
            () -> "the batch must now LEAD with " + worked + ", which file order had behind "
                + front + "; got " + pkgOf(afterwards.get(0)));
    }

    private static String pkgOf(Map<String, Object> unit) {
        return String.valueOf(unit.get("package"));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> unitsOf(ExperienceTool t, int limit) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "next");
        a.put("limit", limit);
        ToolResponse r = t.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        List<Map<String, Object>> units =
            (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("units");
        assertFalse(units.isEmpty(), "PROOF OF LIFE: the queue must hand out something");
        return units;
    }

    /**
     * THE CONTROL, and without it the case above proves nothing.
     *
     * <p>If every batch said "use-driven" the sentence would carry no information, and a reader
     * could not tell the two apart — which is the whole thing the clause asks for. With nothing
     * worked in, the batch says it is a sweep and names no packages at all.</p>
     */
    @Test
    @DisplayName("with nothing worked in, the batch says it is a sweep and names nobody")
    void a_sweep_says_it_is_a_sweep() {
        Map<String, Object> out = dataOf(next());
        assertEquals("file order — no worked-in package is still undescribed, so this batch is"
                + " a sweep rather than a use-driven one",
            out.get("order"),
            () -> "an unprompted batch must not claim to have followed anyone: " + out);
        assertNull(out.get("preferredPackages"),
            () -> "and must name no packages, rather than an empty list a reader has to"
                + " interpret: " + out);
    }

    /**
     * The measure's third clause, as far as a test can carry it: the recording is not NOISIER.
     *
     * <p>Nothing was added to the answer — a caller sees exactly the keys it saw before, so the
     * hook that reads this on every file open cannot tell the recording happened. The SLOWER
     * half is not asserted here and is not claimed: it holds by construction rather than by
     * measurement, because the recording adds no round trip to a call that was already being
     * made, and a timing assertion on a shared machine measures the machine.</p>
     */
    @Test
    @DisplayName("the file-open answer is unchanged by the recording")
    void the_file_open_answer_is_unchanged_by_the_recording() {
        Map<String, Object> first = dataOf(area(aJavaFile()));
        Map<String, Object> second = dataOf(area(aJavaFile()));

        assertEquals(first.keySet(), second.keySet(),
            "the second open must answer in the same shape as the first — the recording is"
                + " invisible to the caller");
        assertTrue(first.containsKey("package"),
            "PROOF OF LIFE: the answer carries the package, so the comparison above is over a"
                + " real response rather than two empty maps");
    }
}
