package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.DataTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 5, row 9 — Encapsulate Collection, reached as {@code data
 * kind=encapsulate_collection}.
 *
 * <p>A class that hands its collection back by name has published its own insides. The cure
 * is a read-only view out and the adds and removes moved onto the class. The fixture is
 * {@code EncapsulateCollectionTargets.java}, which carries three shapes that work beside five
 * that are refused.</p>
 *
 * <p><b>What is asserted is BOTH halves.</b> A run that wrapped the return and generated no
 * mutators would leave callers with no way to add at all — the collection would be
 * encapsulated by being made useless. A run that generated mutators and left the accessor
 * leaking would have changed nothing about the defect. Each case names both.</p>
 */
class EncapsulateCollectionToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        targets = service.getProjectRoot()
            .resolve("src/main/java/com/example/EncapsulateCollectionTargets.java");
    }

    /** The caret on the accessor's NAME — the position a mutable_data finding reports. */
    private ToolResponse at(String accessor, String itemName) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(accessor + "(")) {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "encapsulate_collection");
                args.put("filePath", targets.toString());
                args.put("line", i);
                args.put("column", lines[i].indexOf(accessor));
                if (itemName != null) {
                    args.put("itemName", itemName);
                }
                return tool.execute(args);
            }
        }
        throw new AssertionError("the fixture no longer declares: " + accessor);
    }

    @Test
    @DisplayName("the canonical case: a view out, and the mutators moved onto the class")
    void encapsulatesAList() throws Exception {
        ToolResponse r = at("getStudents", null);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("return Collections.unmodifiableList(students);"),
            "the accessor must hand out a READ-ONLY VIEW rather than the field:\n" + after);
        // THE OTHER HALF. Wrapping alone encapsulates the collection by making it useless —
        // a caller who could add before can no longer add at all.
        assertTrue(after.contains("public void addStudent(String element) {"),
            "and the class must gain the add it took away, typed from the element type:\n"
                + after);
        assertTrue(after.contains("students.add(element);"),
            "which mutates the field the class still owns:\n" + after);
        assertTrue(after.contains("public void removeStudent(String element) {"),
            "and the matching remove:\n" + after);
    }

    @Test
    @DisplayName("a Map takes the Map wrapper and a key/value mutator pair")
    void encapsulatesAMap() throws Exception {
        ToolResponse r = at("getScores", null);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("return Collections.unmodifiableMap(scores);"),
            "a Map's view is unmodifiableMap, not unmodifiableCollection:\n" + after);
        // put, not add — and TWO parameters, read off the map's own type arguments.
        assertTrue(after.contains("public void putScore(String key, Integer value) {"),
            "a map is written with a key AND a value, and the noun is the map's:\n" + after);
        assertTrue(after.contains("public void removeScore(String key) {"),
            "and removed by key alone:\n" + after);
    }

    @Test
    @DisplayName("a Set takes the Set wrapper")
    void encapsulatesASet() throws Exception {
        ToolResponse r = at("getMembers", null);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(Files.readString(targets, StandardCharsets.UTF_8)
                .contains("return Collections.unmodifiableSet(members);"),
            "each declared return type maps to the wrapper whose own return type is exactly"
                + " it — that is why the mapping is a table rather than a default");
    }

    @Test
    @DisplayName("REFUSES an accessor that is already safe — the row's own control")
    void refusesAnAlreadySafeAccessor() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = at("getTopics", null);
        assertFalse(r.isSuccess(), "List.copyOf IS the state this operation produces");
        assertTrue(String.valueOf(r.getError()).contains("does not hand a field back bare"),
            "the refusal must name that reason: " + r.getError());
        assertTrue(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "and a refusal must leave the file byte-for-byte untouched");
    }

    @Test
    @DisplayName("REFUSES a concrete return type, naming the operation that widens it")
    void refusesAConcreteReturnType() throws Exception {
        ToolResponse r = at("getEntries", null);
        assertFalse(r.isSuccess(), "unmodifiableList returns a List, not an ArrayList");
        assertTrue(String.valueOf(r.getError()).contains("no read-only VIEW"),
            "the refusal must name that reason: " + r.getError());
        assertTrue(String.valueOf(r.getError()).contains("change_method_signature"),
            "and point at the way out rather than leaving the caller stuck: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES an array — clone() is a copy, and a copy is not a view")
    void refusesAnArray() throws Exception {
        ToolResponse r = at("getCells", null);
        assertFalse(r.isSuccess(), "an array has no read-only view");
        assertTrue(String.valueOf(r.getError()).contains("COPY"),
            "the refusal must name the difference that makes it the caller's decision: "
                + r.getError());
    }

    @Test
    @DisplayName("REFUSES a static field — that is the global_data kind")
    void refusesAStaticField() throws Exception {
        ToolResponse r = at("getShared", null);
        assertFalse(r.isSuccess(), "static mutable state is reported under another kind");
        assertTrue(String.valueOf(r.getError()).contains("global_data"),
            "the refusal must name the kind that owns it, or a reader fixes it here and"
                + " sees it again there: " + r.getError());
    }

    @Test
    @DisplayName("a mutator the class already has is SKIPPED, and the leak is still closed")
    void skipsAMutatorTheClassAlreadyHas() throws Exception {
        // THE SHAPE THE FORK CORPUS FOUND, and the reason this is not a refusal. Upstream's
        // WorkCenter declares both mutators and still hands its list back: somebody took
        // Fowler's second step and never closed the accessor, which is exactly what
        // mutable_data reports. Refusing it declined the commonest real case.
        ToolResponse r = at("getTracks", null);
        assertTrue(r.isSuccess(), "a class that already has addTrack still has a leak to"
            + " close, which is the defect: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("return Collections.unmodifiableList(tracks);"),
            "the accessor is closed whatever else the class has:\n" + after);
        assertTrue(after.contains("public void addTrack(String name) {"),
            "the class's OWN addTrack survives untouched — it is not regenerated:\n" + after);
        assertFalse(after.contains("public void addTrack(String element) {"),
            "and no second addTrack is emitted, which would not compile:\n" + after);
        assertTrue(after.contains("public void removeTrack(String element) {"),
            "while the one it was MISSING is generated:\n" + after);
    }

    @Test
    @DisplayName("a caller-supplied itemName is honoured")
    void acceptsARequestedItemName() throws Exception {
        // The control for the default: without it, the noun could be hard-coded from the
        // field name and the parameter would be decoration.
        ToolResponse r = at("getTracks", "Song");
        assertTrue(r.isSuccess(), "got: " + r.getError());
        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("public void addSong(String element) {"),
            "the caller's noun is what gets generated:\n" + after);
        assertTrue(after.contains("public void addTrack(String name) {"),
            "and the class's own existing method is left alone:\n" + after);
    }

    @Test
    @DisplayName("the kind is routed and published by the door")
    void theDoorRoutesIt() {
        assertTrue(tool.delegates().containsKey("encapsulate_collection"),
            "row 9 must be reachable as data kind=encapsulate_collection");
        assertEquals("encapsulate_collection",
            tool.delegates().get("encapsulate_collection").kindName(),
            "the routing key and the delegate's own name are two spellings of one fact");
        assertTrue(tool.publishedKinds().contains("encapsulate_collection"),
            "and a client reading tools/list must see it — the enum is the routing table");
    }
}
