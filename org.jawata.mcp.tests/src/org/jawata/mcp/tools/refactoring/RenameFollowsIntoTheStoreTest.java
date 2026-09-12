package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.Confidence;
import org.jawata.mcp.knowledge.ExperienceEntry;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.SymbolFact;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractTool;
import org.jawata.mcp.tools.RefactoringDoors;
import org.jawata.mcp.tools.RenameSymbolTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 7, deliverable 2 — a rename through jawata takes the knowledge with it.
 *
 * <p>A job is the one kind of row whose subject can be renamed out from under it. An
 * experience survives a rename — it is about something that happened — but a job says what
 * {@code Foo#bar} is FOR, and once {@code bar} becomes {@code baz} the row points at a name
 * the workspace no longer has: it resolves to nothing, drops out of every symbol recall, and
 * reads exactly like a job nobody wrote.</p>
 *
 * <h2>The claim that is not "it moved" — WHEN it moved</h2>
 *
 * <p>The anchor must follow the CHANGE and not the INTENTION. The pipeline that runs a
 * refactoring can still refuse one after building it — the compile gate undoes a change that
 * broke the code — and {@code auto_apply: false} builds the whole change and applies none of
 * it. In both cases the code is untouched, so an anchor that moved anyway would be pointing
 * at a name that exists nowhere. {@link #a_staged_rename_moves_no_anchor} is the case that
 * proves the ordering rather than the plumbing: the tool SUCCEEDS, the change is built and
 * cached, and the anchor must still be where it was.</p>
 *
 * <h2>What this class does NOT reach, said rather than left to be assumed</h2>
 *
 * <p>{@link #the_standalone_tools_carry_the_store_through} asserts the pass-through from
 * {@code RefactoringDoors.standalone} — the one source the application and every test build
 * these tools from — into the tool itself. The remaining link is the application's own
 * argument at that call, and {@code JawataApplication.registerTools} is private with one
 * caller, so no test here can drive it. That line is therefore covered by reading and not by
 * a gate, which is exactly the shape this sprint keeps finding; it is named here so the next
 * reader knows it is a gap rather than a guarantee.</p>
 */
class RenameFollowsIntoTheStoreTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String GREET = "com.example.Clean#greet";
    private static final String MOVED = "com.example.Clean#salute";
    private static final String SIBLING = "com.example.Clean#add";

    private JdtServiceImpl service;
    private H2ExperienceStore store;
    private RenameSymbolTool tool;
    private ObjectMapper json;
    private Path source;

    @BeforeEach
    void setUp() throws Exception {
        // compile-clean rather than simple-maven: this test renames a member on disk, and
        // simple-maven is the shared fixture whose population several other tests count.
        service = helper.loadProjectCopy("compile-clean");
        store = H2ExperienceStore.open(null);
        tool = new RenameSymbolTool(() -> service, new RefactoringChangeCache(), () -> store);
        json = new ObjectMapper();
        source = service.getProjectRoot().resolve("src/main/java/com/example/Clean.java");
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private static ExperienceEntry job(String summary, String symbol) {
        return ExperienceEntry.of(
            SymbolFact.of("job", summary, Confidence.MEDIUM).symbol(symbol).build()).build();
    }

    private String anchorOf(String id) {
        return store.all().stream()
            .filter(e -> id.equals(e.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no row " + id))
            .symbolFqn();
    }

    /** The caret on {@code greet}'s declaration, read off the fixture rather than pinned. */
    private ObjectNode renameGreetTo(String newName) throws Exception {
        String[] lines = Files.readString(source).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].indexOf("greet()");
            if (at >= 0) {
                ObjectNode args = json.createObjectNode();
                args.put("filePath", source.toString());
                args.put("line", i);        // the tool's coordinates are ZERO-based
                args.put("column", at);
                args.put("newName", newName);
                return args;
            }
        }
        throw new AssertionError("the fixture no longer declares greet()");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("a renamed member takes its job with it, and the response says how many")
    void a_renamed_member_takes_its_job_with_it() throws Exception {
        String moved = store.put(job("Greets by name for the welcome banner.", GREET));
        String untouched = store.put(job("Adds two figures for the totals row.", SIBLING));

        ToolResponse response = tool.execute(renameGreetTo("salute"));

        assertTrue(response.isSuccess(), "the rename must land; got " + response.getError());
        Map<String, Object> data = dataOf(response);
        assertEquals(1, data.get("anchorsMoved"),
            "the COUNT is reported, because a mover that answers only 'done' cannot be told"
                + " from one that moved nothing — and moving nothing is the normal case: "
                + data);
        assertEquals(MOVED, anchorOf(moved), "the job now points at the member's new name");
        assertEquals(SIBLING, anchorOf(untouched),
            "THE CONTROL: a sibling job on the same type is untouched. Without it an"
                + " implementation that rewrote every anchor in the store would satisfy"
                + " every assertion above");
    }

    /**
     * THE ORDERING CASE, and the one that could not be proved any other way.
     *
     * <p>The tool SUCCEEDS here: the change is built, validated and cached under a
     * {@code changeId}, and the caller is told so. Nothing has been written to disk. An
     * anchor that moved on the strength of that would point at a name that exists nowhere,
     * and would go on doing so for as long as the caller never applied the change.</p>
     */
    @Test
    @DisplayName("a STAGED rename moves no anchor — the change has not landed")
    void a_staged_rename_moves_no_anchor() throws Exception {
        String job = store.put(job("Greets by name for the welcome banner.", GREET));

        ObjectNode args = renameGreetTo("salute");
        args.put("auto_apply", false);
        ToolResponse response = tool.execute(args);

        Map<String, Object> data = dataOf(response);
        assertTrue(response.isSuccess(), "staging must succeed; got " + response.getError());
        assertEquals(Boolean.FALSE, data.get("applied"),
            "PROOF OF LIFE: this is the staged path, not a failure that happens to move"
                + " nothing — " + data);
        assertNull(data.get("anchorsMoved"), "nothing followed, and nothing claims to: " + data);
        assertEquals(GREET, anchorOf(job),
            "the anchor is where it was, because the code is where it was");
    }

    @Test
    @DisplayName("a REFUSED rename moves no anchor")
    void a_refused_rename_moves_no_anchor() throws Exception {
        String job = store.put(job("Greets by name for the welcome banner.", GREET));

        ToolResponse response = tool.execute(renameGreetTo("class"));

        assertFalse(response.isSuccess(), "'class' is not a Java identifier");
        assertEquals(GREET, anchorOf(job), "and the job still points where it did");
    }

    /**
     * The wiring, as far as a test can follow it — see the class javadoc for where it stops.
     */
    @Test
    @DisplayName("the standalone tools carry the store through to rename_symbol")
    void the_standalone_tools_carry_the_store_through() {
        ExperienceStore wired = store;
        RenameSymbolTool withStore = null;
        for (AbstractTool t : RefactoringDoors.standalone(
                () -> service, new RefactoringChangeCache(), () -> wired)) {
            if (t instanceof RenameSymbolTool r) {
                withStore = r;
            }
        }
        assertTrue(withStore != null, "standalone must still build a rename_symbol");
        assertTrue(withStore.followsAnchors(),
            "the store reaches the tool through the one source the application builds from");

        RenameSymbolTool without = null;
        for (AbstractTool t : RefactoringDoors.standalone(
                () -> service, new RefactoringChangeCache(), () -> null)) {
            if (t instanceof RenameSymbolTool r) {
                without = r;
            }
        }
        assertFalse(without.followsAnchors(),
            "THE CONTROL: the accessor reports the store it was GIVEN rather than answering"
                + " true for any instance — without it the assertion above would pass"
                + " against a method that returns a constant");
    }
}
