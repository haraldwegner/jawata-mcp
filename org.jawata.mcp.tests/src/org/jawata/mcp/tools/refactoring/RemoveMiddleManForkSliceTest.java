package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.InlineTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue row 36 — Remove Middle Man ON CODE WE DID NOT AUTHOR.
 *
 * <p>The per-row contract asks for a demonstration on the fork corpus, and says why in
 * terms a hand-written fixture cannot meet: a fixture written to exercise a refactoring is
 * written, consciously or not, in the shape that refactoring handles.</p>
 *
 * <p>{@code find_quality_issue kind=middle_man} reports 28 candidates over the fork.
 * {@code TaskSet} delegates 3 of 3 methods to a {@code BlockingQueue}, and it was chosen
 * over the larger {@code GiantController} (7 of 7) for a measured reason recorded in
 * {@code PROVENANCE.md}: that class's delegate is a Lombok type whose accessors JDT never
 * sees, so the rewrite is correct and the compile gate refuses it. A demonstration whose
 * only outcome is an environmental refusal proves less than one that completes.</p>
 *
 * <h2>What the fork changed about the operation</h2>
 *
 * <p>The rejected module was not wasted. {@code GiantController} writes its getters
 * {@code giant.getHealth()} and its setters {@code this.giant.setHealth(h)} — one receiver,
 * two spellings, in one class — and the first version of the tool recognised only the bare
 * form, so half its forwarders were invisible. That is fixed. {@code TaskSet} uses the bare
 * spelling throughout, which is exactly why one module could not have surfaced it.</p>
 */
class RemoveMiddleManForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private InlineTool tool;
    private ObjectMapper mapper;
    private Path taskSet;
    private Path worker;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("fork-leader-followers");
        tool = new InlineTool(() -> service, new RefactoringChangeCache());
        mapper = new ObjectMapper();
        Path pkg = helper.getTempDirectory()
            .resolve("fork-leader-followers/src/main/java/com/iluwatar/leaderfollowers");
        taskSet = pkg.resolve("TaskSet.java");
        // Worker is the caller: it is where `taskSet.getTask()` lives, which
        // find_references confirms is one of two call sites in the module.
        worker = pkg.resolve("Worker.java");
    }

    private String read(Path p) throws Exception {
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    private static int lineOf(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("the slice no longer contains: " + needle);
    }

    @Test
    @DisplayName("row 36 on a verbatim fork slice: all three forwarders go and every caller repoints")
    void removesTheMiddleManFromForkCode() throws Exception {
        String before = read(taskSet);
        // PROVENANCE, asserted rather than trusted. Edit this slice into a shape that suits
        // the operation and the clause it satisfies is void — and the MIT terms require the
        // attribution retained besides.
        assertTrue(before.contains("The MIT License") && before.contains("Ilkka Seppälä"),
            "the slice must still carry upstream's licence header");
        assertTrue(before.contains("private final BlockingQueue<Task> queue"),
            "PROOF OF LIFE: the delegate field must still be there");
        assertTrue(before.contains("queue.put(task)") && before.contains("return queue.take()"),
            "PROOF OF LIFE: the forwarders must still forward");

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "middle_man");
        args.put("filePath", taskSet.toString());
        args.put("line", lineOf(before, "public class TaskSet"));
        args.put("column", 13);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            () -> "Remove Middle Man refused real upstream code: " + r.getError());

        String after = read(taskSet);
        for (String gone : new String[] {
            "public void addTask(", "public Task getTask()", "public int getSize()"}) {
            assertFalse(after.contains(gone), gone + " still forwards:\n" + after);
        }
        assertTrue(after.contains("public BlockingQueue<Task> queue()"),
            "the accessor exposing the delegate was generated, with the field's own generic"
                + " type — which a same-package fixture would not have exercised:\n" + after);

        // THE CALLER, in another file, and the assertion that made this slice worth having.
        // `TaskSet.getTask()` forwards to `queue.take()` — the forwarder's name and the
        // delegate's are DIFFERENT — so the rewritten call must read `.queue().take()`.
        // The first version emitted the forwarder's own name onto the queue and produced
        // code that does not compile. Both names were identical in the hand-written
        // fixture, so nothing there could have shown it.
        String caller = read(worker);
        assertTrue(caller.contains("queue().take()"),
            "the caller reaches the delegate directly, by the DELEGATE's method name:\n"
                + caller);
        assertFalse(caller.contains("taskSet.getTask()"),
            "and no longer through the middle man:\n" + caller);
    }

    @Test
    @DisplayName("a fork class that forwards nothing is refused, on the same corpus")
    void aNonMiddleManInTheSameSliceIsRefused() throws Exception {
        String before = read(worker);

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "middle_man");
        args.put("filePath", worker.toString());
        args.put("line", lineOf(before, "public class Worker"));
        args.put("column", 13);
        ToolResponse r = tool.execute(args);

        // The refusal on FOREIGN code matters as much as the success: it says the operation
        // recognises a middle man rather than reshaping whatever it is pointed at.
        assertFalse(r.isSuccess(), "Worker does real work; it is not a middle man");
        assertTrue(String.valueOf(r.getError()).contains("no middle man"),
            "and says so rather than reporting a no-op success: " + r.getError());
        assertTrue(before.equals(read(worker)),
            "a refused refactoring leaves the source byte-identical");
    }
}
