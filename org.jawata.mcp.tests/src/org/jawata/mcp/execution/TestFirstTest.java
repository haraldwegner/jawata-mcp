package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.mcp.execution.TestFirst.Run;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage 27 (z5): a developer that builds new behaviour writes the test, sees it fail, writes the production code and sees the tests pass. The
 * rule is read from the worker's own calls (its editor writes, in order) and from what its {@code run_tests} calls showed.
 */
class TestFirstTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String PRODUCTION = "/w/org.jawata.mcp/src/org/jawata/mcp/execution/Foo.java";
    private static final String TEST = "/w/org.jawata.mcp.tests/src/org/jawata/mcp/execution/FooTest.java";

    private static ToolCall edit(String path) {
        return new ToolCall("Edit", MAPPER.createObjectNode().put("file_path", path).put("old_string", "a").put("new_string", "b"));
    }

    private static ToolCall write(String path) {
        return new ToolCall("Write", MAPPER.createObjectNode().put("file_path", path).put("content", "x"));
    }

    private static ToolCall runTests() {
        return new ToolCall("mcp__jawata-javata-dev__run_tests", MAPPER.createObjectNode().put("action", "run"));
    }

    private static ToolCall read() {
        return new ToolCall("Read", MAPPER.createObjectNode().put("file_path", PRODUCTION));
    }

    @Test
    @DisplayName("a test written, seen failing, then the production code, then a passing run, has nothing to say")
    void redThenGreenIsRight() {
        List<String> violations = TestFirst.violations(List.of(write(TEST), runTests(), edit(PRODUCTION), runTests()), List.of(Run.RED, Run.GREEN));

        assertTrue(violations.isEmpty(), "the right order was refused: " + violations);
    }

    @Test
    @DisplayName("production code written before any test was seen failing is named, with the file and the call")
    void productionBeforeRedIsNamed() {
        List<String> violations = TestFirst.violations(List.of(edit(PRODUCTION), write(TEST), runTests(), runTests()), List.of(Run.RED, Run.GREEN));

        assertAll(
            () -> assertEquals(1, violations.size(), violations.toString()),
            () -> assertTrue(violations.get(0).contains("before a test was seen failing"), violations.get(0)),
            () -> assertTrue(violations.get(0).contains("Foo.java"), violations.get(0)),
            () -> assertTrue(violations.get(0).contains("call 1"), violations.get(0)));
    }

    @Test
    @DisplayName("a run that ran no test (a compile error) is not a red, so the production code after it is named")
    void aCompileErrorIsNotARed() {
        List<String> violations = TestFirst.violations(List.of(write(TEST), runTests(), edit(PRODUCTION), runTests()), List.of(Run.NOT_RUN, Run.GREEN));

        assertTrue(violations.stream().anyMatch(v -> v.contains("before a test was seen failing")), violations.toString());
    }

    @Test
    @DisplayName("a test that passed before the production code was written was never seen failing, so the production code is named")
    void aTestThatPassedFirstIsNotARed() {
        List<String> violations = TestFirst.violations(List.of(write(TEST), runTests(), edit(PRODUCTION), runTests()), List.of(Run.GREEN, Run.GREEN));

        assertTrue(violations.stream().anyMatch(v -> v.contains("before a test was seen failing")), violations.toString());
    }

    @Test
    @DisplayName("live (z5): a stub written after the test that makes the next run fail for the behaviour is the way to a red where no tool adds the empty method, and is not named")
    void aStubAfterTheTestThatTheNextRunFailsOnIsNotNamed() {
        List<String> violations = TestFirst.violations(
            List.of(write(TEST), edit(PRODUCTION), runTests(), edit(PRODUCTION), runTests()), List.of(Run.RED, Run.GREEN));

        assertTrue(violations.isEmpty(), "the stub the live developer needed was refused: " + violations);
    }

    @Test
    @DisplayName("production code written after the test but followed by a passing run, or by no run, was never seen failing and is named")
    void productionAfterTheTestIsNamedWhenNoRedFollowsIt() {
        List<String> passing = TestFirst.violations(List.of(write(TEST), edit(PRODUCTION), runTests()), List.of(Run.GREEN));
        List<String> unrun = TestFirst.violations(List.of(write(TEST), edit(PRODUCTION)), List.of());

        assertAll(
            () -> assertTrue(passing.stream().anyMatch(v -> v.contains("before a test was seen failing") && v.contains("call 2")), passing.toString()),
            () -> assertTrue(unrun.stream().anyMatch(v -> v.contains("before a test was seen failing") && v.contains("call 2")), unrun.toString()));
    }

    @Test
    @DisplayName("production code that no passing run follows is named")
    void noGreenAfterTheLastProductionChangeIsNamed() {
        List<String> violations = TestFirst.violations(List.of(write(TEST), runTests(), edit(PRODUCTION)), List.of(Run.RED));

        assertAll(
            () -> assertEquals(1, violations.size(), violations.toString()),
            () -> assertTrue(violations.get(0).contains("no passing run"), violations.get(0)));
    }

    @Test
    @DisplayName("a passing run that came before the last production change does not count for it")
    void aGreenBeforeTheLastChangeDoesNotCount() {
        List<String> violations = TestFirst.violations(
            List.of(write(TEST), runTests(), edit(PRODUCTION), runTests(), edit(PRODUCTION)), List.of(Run.RED, Run.GREEN));

        assertTrue(violations.stream().anyMatch(v -> v.contains("no passing run")), violations.toString());
    }

    @Test
    @DisplayName("a document and a test file are never production code, and a run with no production change has nothing to order")
    void documentsAndTestsAreNotProduction() {
        List<String> notes = TestFirst.violations(List.of(write("/w/docs/plan.md"), write(TEST), runTests()), List.of(Run.RED));
        List<String> reading = TestFirst.violations(List.of(read(), runTests()), List.of(Run.GREEN));

        assertAll(
            () -> assertTrue(notes.isEmpty(), notes.toString()),
            () -> assertTrue(reading.isEmpty(), reading.toString()));
    }

    @Test
    @DisplayName("a run_tests call whose result was not recorded counts as no run, never as a red")
    void anUnrecordedRunIsNotARed() {
        List<String> violations = TestFirst.violations(List.of(write(TEST), runTests(), edit(PRODUCTION), runTests()), List.of());

        assertTrue(violations.stream().anyMatch(v -> v.contains("before a test was seen failing")), violations.toString());
    }

    @Test
    @DisplayName("the files the languages of this project give to tests are recognised, and a name that only contains test is not")
    void testPathsAreRecognised() {
        assertAll(
            () -> assertTrue(TestFirst.isTestPath("/w/a/FooTest.java")),
            () -> assertTrue(TestFirst.isTestPath("/w/a/FooTests.java")),
            () -> assertTrue(TestFirst.isTestPath("/w/a/FooIT.java")),
            () -> assertTrue(TestFirst.isTestPath("/w/proj/src/test/java/a/Foo.java")),
            () -> assertTrue(TestFirst.isTestPath("/w/org.jawata.mcp.tests/src/a/Foo.java")),
            () -> assertTrue(TestFirst.isTestPath("/w/web/src/foo.test.ts")),
            () -> assertTrue(TestFirst.isTestPath("/w/web/src/foo.spec.ts")),
            () -> assertTrue(TestFirst.isTestPath("/w/crate/tests/foo.rs")),
            () -> assertTrue(TestFirst.isTestPath("/w/crate/src/foo_test.rs")),
            () -> assertTrue(TestFirst.isTestPath("/w/py/test_foo.py")),
            () -> assertFalse(TestFirst.isTestPath("/w/a/Foo.java")),
            () -> assertFalse(TestFirst.isTestPath("/w/a/Latest.java")),
            () -> assertFalse(TestFirst.isTestPath("/w/a/Contest.java")),
            () -> assertFalse(TestFirst.isTestPath("/w/protests/Foo.java")));
    }

    @Test
    @DisplayName("an editor call is read for the file it writes, whatever the editor names the argument, and a read writes none")
    void theWrittenFileIsRead() {
        ToolCall byPath = new ToolCall("edit", MAPPER.createObjectNode().put("path", PRODUCTION));
        ToolCall byFilePath = new ToolCall("Edit", MAPPER.createObjectNode().put("filePath", PRODUCTION));

        assertAll(
            () -> assertEquals(Optional.of(PRODUCTION), TestFirst.writtenFile(edit(PRODUCTION))),
            () -> assertEquals(Optional.of(PRODUCTION), TestFirst.writtenFile(byPath)),
            () -> assertEquals(Optional.of(PRODUCTION), TestFirst.writtenFile(byFilePath)),
            () -> assertEquals(Optional.empty(), TestFirst.writtenFile(read())),
            () -> assertEquals(Optional.empty(), TestFirst.writtenFile(runTests())));
    }

    // ---- what a run_tests answer showed ----

    private static ToolResponse ran(int total, int failed) {
        return ToolResponse.success(Map.of("summary", Map.of("total", total, "passed", total - failed, "failed", failed)));
    }

    @Test
    @DisplayName("a run_tests answer with a failing test is a red, with tests all passing a green, and anything else ran no test")
    void aRunIsClassifiedByWhatItShowed() {
        assertAll(
            () -> assertEquals(Run.RED, TestRuns.classify(ran(3, 1))),
            () -> assertEquals(Run.GREEN, TestRuns.classify(ran(3, 0))),
            () -> assertEquals(Run.NOT_RUN, TestRuns.classify(ran(0, 0)), "a run that found no test is not a passing run"),
            () -> assertEquals(Run.NOT_RUN, TestRuns.classify(ToolResponse.error("COMPILE_ERROR", "does not compile", "fix it"))),
            () -> assertEquals(Run.NOT_RUN, TestRuns.classify(ToolResponse.success(Map.of("state", "RUNNING"))), "a status with no totals is no run"));
    }

    @Test
    @DisplayName("only run_tests answers are kept, per caller, in order, and handed over once")
    void runsAreKeptPerCallerInOrderAndTakenOnce() {
        TestRuns runs = new TestRuns();
        runs.record("plan-1", "run_tests", ran(3, 1));
        runs.record("plan-1", "search_symbols", ToolResponse.success("ok"));
        runs.record("plan-2", "run_tests", ran(1, 0));
        runs.record("plan-1", "run_tests", ran(3, 0));

        List<Run> first = runs.take("plan-1");

        assertAll(
            () -> assertEquals(List.of(Run.RED, Run.GREEN), first),
            () -> assertTrue(runs.take("plan-1").isEmpty(), "CONTROL: what was handed over was handed over again"),
            () -> assertEquals(List.of(Run.GREEN), runs.take("plan-2"), "CONTROL: the other caller's run was lost"));
    }
}
