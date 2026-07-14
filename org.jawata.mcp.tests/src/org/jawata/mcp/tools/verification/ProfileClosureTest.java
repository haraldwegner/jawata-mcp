package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.jawata.mcp.tools.GetCallHierarchyIncomingTool;
import org.jawata.mcp.tools.ProfileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D15, C20) — the loop closes on the PROFILE side too.
 *
 * <p>{@link DebugClosureTest} already proved the claim for a fact the
 * DEBUGGER produces (a breakpoint hit's symbol goes straight into {@code
 * get_call_hierarchy}). This is the same claim for a fact the PROFILER
 * produces: a JFR hotspot ranking already carries a compiler-accurate
 * symbol ({@code ClassName#methodName}, Stage 16's own convention) — that
 * symbol goes STRAIGHT into {@code get_call_hierarchy} too. No search in
 * between; a search would only re-derive what the profiler just measured,
 * and could rank something else entirely.</p>
 */
class ProfileClosureTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool debug;
    private ProfileTool profile;
    private JdtServiceImpl service;
    private ObjectMapper om;
    private Path targetClasses;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        debug = new DebugTool(() -> service, sessions);
        profile = new ProfileTool(() -> service, sessions);
        om = new ObjectMapper();

        targetClasses = Files.createTempDirectory("jawata-profile-closure-");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example/debug");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", targetClasses.toString(),
            pkg.resolve("DebugTarget.java").toString(),
            pkg.resolve("HotLoopTarget.java").toString());
        assertEquals(0, rc, "the hotspot fixture must compile");
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode debugAction(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private ObjectNode profileAction(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    @Test
    @DisplayName("THE CLOSURE (profile side): a hotspot's symbol hands STRAIGHT to get_call_hierarchy — no search")
    void aHotspotSymbolGoesDirectlyIntoTheStaticTools() throws Exception {
        ObjectNode launch = debugAction("launch");
        launch.put("mainClass", "com.example.debug.HotLoopTarget");
        launch.put("classpath", targetClasses.toString());
        ToolResponse launched = debug.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        String sessionId = (String) data(launched).get("sessionId");
        assertTrue(debug.execute(debugAction("resume").put("sessionId", sessionId)).isSuccess());

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 3);
        ToolResponse sampled = profile.execute(sample);
        assertTrue(sampled.isSuccess(), "got: " + sampled.getError());
        String artifactId = (String) data(sampled).get("artifactId");

        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", artifactId);
        hotspots.put("dimension", "cpu");
        hotspots.put("limit", 1);
        ToolResponse ranked = profile.execute(hotspots);
        assertTrue(ranked.isSuccess(), "got: " + ranked.getError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data(ranked).get("rows");
        Map<String, Object> top = rows.get(0);
        String symbol = String.valueOf(top.get("symbol"));
        assertEquals("com.example.debug.HotLoopTarget#burnCpu", symbol,
            "the profiler already hands over a compiler-accurate symbol: " + top);

        // HAND IT STRAIGHT OVER — no search, same principle as the debug-side closure.
        GetCallHierarchyIncomingTool callers = new GetCallHierarchyIncomingTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("symbol", symbol);

        ToolResponse r = callers.execute(args);
        assertTrue(r.isSuccess(),
            "the symbol the PROFILER produced must resolve in the STATIC tools with no "
                + "intermediate search: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> hierarchy = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> incoming = (List<Map<String, Object>>) hierarchy.get("callers");
        assertNotNull(incoming, "got: " + hierarchy);
        assertTrue(incoming.stream().anyMatch(c -> "main".equals(c.get("callerMethod"))),
            "burnCpu is called from main — a fact the COMPILER knows, reached from a fact "
                + "the PROFILER measured: " + incoming);
    }
}
