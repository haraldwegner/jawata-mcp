package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 audit (2026-07-14, T1.7) — a conditional breakpoint whose condition invokes a
 * method that never returns must NOT wedge the session. The condition used to be evaluated
 * on the JDI event pump — the one thread draining the event queue — via a blocking
 * invokeMethod; a non-returning invoke wedged it for the rest of the session, and every
 * later {@code wait} timed out forever with no hit.
 */
class PumpDeadlockTest {

    private static final String TARGET = "com.example.debug.NestedInvokeTarget";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool tool;
    private ObjectMapper om;
    private String sessionId;
    private int loopLine;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        tool = new DebugTool(() -> service, sessions);
        om = new ObjectMapper();

        Path file = service.getProjectRoot()
            .resolve("src/main/java/com/example/debug/NestedInvokeTarget.java");
        var lines = Files.readAllLines(file);
        loopLine = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains("tick++;")) {
                loopLine = i + 1;
                break;
            }
        }
        assertTrue(loopLine > 0, "the fixture must still have the tick++ loop line");

        Path classes = Files.createTempDirectory("jawata-pump-deadlock-");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classes.toString(), file.toString()),
            "the deadlock fixture must compile");

        ObjectNode launch = action("launch");
        launch.put("mainClass", TARGET);
        launch.put("classpath", classes.toString());
        ToolResponse launched = tool.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        sessionId = (String) data(launched).get("sessionId");
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode action(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private ObjectNode onSession(String name) {
        ObjectNode args = action(name);
        args.put("sessionId", sessionId);
        return args;
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a condition whose invoke never returns STOPS the breakpoint and keeps the session alive")
    void aHangingConditionDoesNotWedgeTheSession() {
        // A conditional breakpoint whose condition invokes needsTheHeldLock() — which blocks
        // forever on a lock the holder thread owns. On the old pump-thread evaluation this
        // wedged the session permanently.
        ObjectNode bp = onSession("breakpoint_set");
        bp.put("kind", "conditional");
        bp.put("className", TARGET);
        bp.put("line", loopLine);
        bp.put("condition", "needsTheHeldLock()");
        assertTrue(tool.execute(bp).isSuccess());

        assertTrue(tool.execute(onSession("resume")).isSuccess());

        // THE DISCRIMINATING PROOF. On the old pump-thread evaluation, the condition's invoke
        // blocked the pump forever, so the hit was NEVER published and this wait would return
        // hit=false only after its full 30s (or hang the pump so hard the JUnit @Timeout above
        // fires). With the fix, the evaluation runs off the pump with a ~5s bound, so the
        // breakpoint STOPS — the hanging condition surfaced, not hidden — well within 30s.
        ObjectNode wait = onSession("wait");
        wait.put("timeoutMillis", 30_000);
        long start = System.currentTimeMillis();
        ToolResponse waited = tool.execute(wait);
        long elapsed = System.currentTimeMillis() - start;
        assertTrue(waited.isSuccess(), "got: " + waited.getError());
        Map<String, Object> hit = data(waited);
        assertEquals(Boolean.TRUE, hit.get("hit"),
            "the breakpoint must STOP (the hanging condition is surfaced, not hidden): " + hit);
        assertTrue(elapsed < 25_000,
            "and it must stop when the eval TIMES OUT (~5s), not sit until the wait's own "
                + "30s window expires — that would mean the pump was blocked: " + elapsed + "ms");
        assertNotNull(hit.get("conditionError"),
            "the hit must say the condition could not be evaluated: " + hit);
        assertTrue(String.valueOf(hit.get("conditionError")).contains("not returning"),
            "naming WHY — a non-returning invoke: " + hit.get("conditionError"));

        // THE SESSION IS ALIVE, and stays alive: every subsequent call RETURNS (the @Timeout
        // guarantees nothing hangs). The target's own main thread is legitimately stuck now —
        // its condition invoked a method that blocked on a held lock — but that is the user's
        // condition's consequence, not our wedged pump. A fresh wait must still come back
        // rather than hang forever; hit=false is fine (the target thread is blocked), a hang
        // is not.
        assertTrue(tool.execute(onSession("status")).isSuccess(), "the session still answers");

        assertTrue(tool.execute(onSession("resume")).isSuccess());
        ObjectNode wait2 = onSession("wait");
        wait2.put("timeoutMillis", 8_000);
        ToolResponse waited2 = tool.execute(wait2);
        assertTrue(waited2.isSuccess(),
            "a second wait must RETURN, not hang — the pump kept draining events even though "
                + "the target thread is stuck on the lock its own condition took: " + waited2.getError());
    }
}
