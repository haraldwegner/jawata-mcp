package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28i, Stage 25, step A4 (ARCHITECTURE-28i-s25.md): what the editor's Testing view asks of the engine, answered over the engine's own tools
 * ({@code find_tests}, {@code run_tests}) as a script of canned answers stands in for them.
 */
class LspTestsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    /** A test file as the running system writes an absolute path: `/work/src/FooTest.java` has no drive on Windows and is not absolute there. */
    private static final String FOO_TEST = java.nio.file.Path.of("/work/src/FooTest.java").toAbsolutePath().toString();

    /** Answers find_tests with the test classes it was given and run_tests start/status/cancel from a script, and records every run_tests action. */
    private static final class Fake implements ToolCalls {
        final List<String> actions = new CopyOnWriteArrayList<>();
        final List<ObjectNode> startArguments = new CopyOnWriteArrayList<>();
        final List<ToolResponse> statuses = new ArrayList<>();
        ToolResponse startAnswer = ToolResponse.success(Map.of("sessionId", "s1", "state", "RUNNING"), ResponseMeta.builder().build());
        ToolResponse listAnswer = ToolResponse.success(Map.of("testClasses", List.of()), ResponseMeta.builder().build());
        int looked;

        @Override
        public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
            if ("find_tests".equals(name)) {
                return listAnswer;
            }
            String action = arguments.path("action").asText();
            actions.add(action);
            switch (action) {
                case "start" -> {
                    startArguments.add(arguments.deepCopy());
                    return startAnswer;
                }
                case "status" -> {
                    int at = Math.min(looked++, statuses.size() - 1);
                    return statuses.get(at);
                }
                default -> {
                    return ToolResponse.success(Map.of("state", "CANCELLED"), ResponseMeta.builder().build());
                }
            }
        }

        @Override
        public List<Map<String, Object>> getToolDefinitions() {
            return List.of();
        }
    }

    private static ToolResponse status(String state, int finished, Map<String, Object> extra) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("state", state);
        data.put("progress", Map.of("plannedTests", 3, "testsFinished", finished));
        data.putAll(extra);
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    private final List<JsonRpcMessage> said = new CopyOnWriteArrayList<>();

    private LspTests over(Fake fake) {
        return new LspTests(fake, CallerIdentity.editor("lsp-test"), () -> null, said::add, 5);
    }

    @Test
    @DisplayName("the list names each test class and method with the scope that runs it, and the class's file as a URI")
    void theTestsAreListedWithTheScopeThatRunsEach() throws Exception {
        Fake fake = new Fake();
        fake.listAnswer = ToolResponse.success(Map.of("testClasses", List.of(Map.of("className", "FooTest", "filePath", FOO_TEST, "line", 9,
            "framework", "JUnit5", "testMethods", List.of(Map.of("name", "adds", "line", 12, "displayName", "adding works"), Map.of("name", "subtracts", "line", 20))))),
            ResponseMeta.builder().build());

        JsonNode listed = JSON.valueToTree(over(fake).list(JSON.createObjectNode()));

        JsonNode foo = listed.path(0);
        assertEquals("FooTest", foo.path("name").asText());
        assertEquals(java.nio.file.Path.of(FOO_TEST).toUri().toString(), foo.path("uri").asText());
        assertEquals(9, foo.path("line").asInt());
        assertEquals("class", foo.path("scope").path("kind").asText());
        assertEquals("adding works", foo.path("tests").path(0).path("label").asText(), "a display name is the label");
        assertEquals("subtracts", foo.path("tests").path(1).path("label").asText(), "no display name: the method name");
        assertEquals("method", foo.path("tests").path(1).path("scope").path("kind").asText());
        assertEquals("subtracts", foo.path("tests").path(1).path("scope").path("methodName").asText());
    }

    @Test
    @DisplayName("a run that fails to start is an error carrying the engine's reason, not an empty result")
    void aRunThatCannotStartIsAnError() {
        Fake fake = new Fake();
        fake.startAnswer = ToolResponse.error("PRECONDITION", "the project does not compile", "fix it");

        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> over(fake).run(JSON.createObjectNode().set("scope", JSON.createObjectNode().put("kind", "class"))));

        assertTrue(refused.getMessage().contains("the project does not compile"), refused.getMessage());
    }

    @Test
    @DisplayName("a run is followed to its end: progress is pushed when the counts move, and the result carries each failure's message and zero-based line")
    void aRunIsFollowedAndItsFailuresCarryMessageAndLine() throws Exception {
        Fake fake = new Fake();
        fake.statuses.add(status("RUNNING", 0, Map.of()));
        fake.statuses.add(status("RUNNING", 0, Map.of()));
        fake.statuses.add(status("RUNNING", 2, Map.of()));
        fake.statuses.add(status("FINISHED", 3, Map.of("summary", Map.of("total", 3, "passed", 2, "failed", 1), "failures", List.of(Map.of("testClass", "com.x.FooTest",
            "testMethod", "adds", "status", "FAILED", "message", "expected 4 but was 5", "stackTrace",
            "org.opentest4j.AssertionFailedError: expected 4 but was 5\n\tat org.junit.Assertions.fail(Assertions.java:10)\n\tat com.x.FooTest.adds(FooTest.java:42)\n")))));
        ObjectNode params = JSON.createObjectNode().put("token", "t1");
        params.set("scope", JSON.createObjectNode().put("kind", "method").put("typeName", "com.x.FooTest").put("methodName", "adds"));

        JsonNode result = JSON.valueToTree(over(fake).run(params));

        assertEquals("FINISHED", result.path("state").asText());
        assertEquals(1, result.path("summary").path("failed").asInt());
        JsonNode failure = result.path("failures").path(0);
        assertEquals("expected 4 but was 5", failure.path("message").asText());
        assertEquals(41, failure.path("line").asInt(), "the test's own frame is line 42, which is 41 zero-based");
        assertEquals("method", fake.startArguments.get(0).path("scope").path("kind").asText(), "the scope reaches run_tests as it was given");
        assertEquals(3, said.size(), "one push for the first counts, none for the unchanged look, then one per move");
        assertEquals("jawata/tests/progress", said.get(0).getMethod());
        assertEquals("t1", said.get(0).getParams().path("token").asText());
        assertEquals(2, said.get(1).getParams().path("progress").path("testsFinished").asInt());
        assertFalse(fake.actions.contains("cancel"));
    }

    @Test
    @DisplayName("a run whose request is dropped while it works is cancelled in the engine, so no test JVM is left running")
    void aDroppedRunIsCancelled() throws Exception {
        Fake fake = new Fake();
        fake.statuses.add(status("RUNNING", 0, Map.of()));
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<Throwable> thrown = new AtomicReference<>();
        Thread worker = new Thread(() -> {
            try {
                over(fake).run(JSON.createObjectNode().set("scope", JSON.createObjectNode().put("kind", "package").put("packageName", "com.x")));
            } catch (Throwable e) {
                thrown.set(e);
            } finally {
                ended.countDown();
            }
        });
        worker.start();
        for (int i = 0; i < 200 && said.isEmpty(); i++) {
            Thread.sleep(10);
        }
        worker.interrupt();

        assertTrue(ended.await(10, TimeUnit.SECONDS));
        assertTrue(thrown.get() instanceof InterruptedException, String.valueOf(thrown.get()));
        assertTrue(fake.actions.contains("cancel"), fake.actions.toString());
    }

    @Test
    @DisplayName("a run whose status the engine can no longer answer is an error carrying the reason, and the run is cancelled so no test JVM is left running")
    void aRunWhoseStatusFailsIsCancelled() {
        Fake fake = new Fake();
        fake.statuses.add(ToolResponse.error("NOT_FOUND", "no such run", "start it again"));

        IllegalStateException refused = assertThrows(IllegalStateException.class,
            () -> over(fake).run(JSON.createObjectNode().set("scope", JSON.createObjectNode().put("kind", "class"))));

        assertTrue(refused.getMessage().contains("no such run"), refused.getMessage());
        assertTrue(fake.actions.contains("cancel"), fake.actions.toString());
    }

    @Test
    @DisplayName("the source line of a failure is the test class's own frame; a trace with none, or only another class's frames, gives no line")
    void theLineOfAFailureIsTheTestClassOwnFrame() {
        String trace = "x\n\tat a.Other.run(Other.java:7)\n\tat com.x.FooTest$Inner.t(FooTest.java:15)\n";

        assertEquals(14, LspTests.lineOf("com.x.FooTest$Inner", trace).getAsInt(), "a nested class's frames are in its outer class's file");
        assertTrue(LspTests.lineOf("com.x.BarTest", trace).isEmpty());
        assertTrue(LspTests.lineOf("com.x.FooTest", "no frames here").isEmpty());
    }
}
