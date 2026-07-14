package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
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
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D10, C15) — the profiling floor: threads, deadlock, histogram, GC,
 * native memory, heap dumps. Every answer structured, never a raw dump handed
 * back with "go read it yourself".
 */
class ProfileFloorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool debug;
    private ProfileTool profile;
    private ObjectMapper om;
    private Path targetClasses;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        debug = new DebugTool(() -> service, sessions);
        profile = new ProfileTool(() -> service, sessions);
        om = new ObjectMapper();

        targetClasses = Files.createTempDirectory("jawata-profile-target-");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example/debug");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", targetClasses.toString(),
            pkg.resolve("DebugTarget.java").toString(),
            pkg.resolve("DeadlockTarget.java").toString());
        assertEquals(0, rc, "the profiling fixtures must compile");
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

    private ObjectNode profileAction(String name, String sessionId) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        if (sessionId != null) {
            args.put("sessionId", sessionId);
        }
        return args;
    }

    /** Launch a fixture under the preset and RESUME it — profiling a held JVM proves nothing. */
    private String launchAndResume(String mainClass) {
        ObjectNode launch = debugAction("launch");
        launch.put("mainClass", mainClass);
        launch.put("classpath", targetClasses.toString());
        ToolResponse launched = debug.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        String sessionId = (String) data(launched).get("sessionId");

        ObjectNode resume = debugAction("resume");
        resume.put("sessionId", sessionId);
        assertTrue(debug.execute(resume).isSuccess());
        return sessionId;
    }

    // ========================================================== threads

    @Test
    @DisplayName("threads: every OS thread, structured — name, state, a bounded stack")
    void threadsAreStructured() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget");
        Thread.sleep(300); // let it get past main() into the loop

        ToolResponse r = profile.execute(profileAction("threads", sessionId));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> threads = (List<Map<String, Object>>) d.get("threads");
        assertFalse(threads.isEmpty());
        assertTrue(((Number) d.get("threadCount")).intValue() > 0);

        Map<String, Object> main = threads.stream()
            .filter(t -> "main".equals(t.get("name")))
            .findFirst().orElseThrow(() -> new AssertionError("main thread must be present: " + threads));
        assertNotNull(main.get("state"), "every thread reports its state: " + main);
        assertTrue(main.get("stack") instanceof List, "a bounded stack, structured: " + main);
    }

    @Test
    @DisplayName("threads: maxFrames actually bounds the stack")
    void threadsRespectsMaxFrames() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget");
        Thread.sleep(300);

        ObjectNode args = profileAction("threads", sessionId);
        args.put("maxFrames", 2);
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> threads = (List<Map<String, Object>>) data(r).get("threads");
        for (Map<String, Object> t : threads) {
            @SuppressWarnings("unchecked")
            List<String> stack = (List<String>) t.get("stack");
            assertTrue(stack.size() <= 2, "maxFrames=2 must bound every stack: " + t);
        }
    }

    // ========================================================== deadlock

    @Test
    @DisplayName("deadlock: none on a healthy target — an absence, not a failure")
    void noDeadlockOnHealthyTarget() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget");
        Thread.sleep(300);

        ToolResponse r = profile.execute(profileAction("deadlock", sessionId));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(Boolean.FALSE, data(r).get("deadlocked"));
    }

    @Test
    @DisplayName("deadlock: the seeded fixture is caught, and BOTH threads are NAMED in the summary")
    void seededDeadlockIsNamedInSummary() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DeadlockTarget");
        // The fixture's own 500ms hold guarantees the deadlock has formed by
        // the time we ask — deterministic, not a race with the probe.
        Thread.sleep(1500);

        ToolResponse r = profile.execute(profileAction("deadlock", sessionId));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        assertEquals(Boolean.TRUE, d.get("deadlocked"), "got: " + d);
        @SuppressWarnings("unchecked")
        List<String> blocked = (List<String>) d.get("blockedThreads");
        assertTrue(blocked.contains("lock-a-then-b"), "got: " + blocked);
        assertTrue(blocked.contains("lock-b-then-a"), "got: " + blocked);

        String summary = (String) d.get("summary");
        assertNotNull(summary);
        assertTrue(summary.contains("lock-a-then-b"), "the blocker is NAMED in the summary: " + summary);
        assertTrue(summary.contains("lock-b-then-a"), "the blocker is NAMED in the summary: " + summary);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cycle = (List<Map<String, Object>>) d.get("cycle");
        assertEquals(2, cycle.size(), "a 2-thread cycle has 2 wait-for edges: " + cycle);
    }

    @Test
    @DisplayName("threads dump on the deadlocked fixture ALSO flags it, without contradicting the deadlock action")
    void threadsDumpAgreesWithDeadlockAction() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DeadlockTarget");
        Thread.sleep(1500);

        ToolResponse threadsResponse = profile.execute(profileAction("threads", sessionId));
        assertTrue(threadsResponse.isSuccess());
        assertNotNull(data(threadsResponse).get("deadlockWarning"),
            "threads must flag the deadlock it just saw: " + data(threadsResponse));
    }

    // ========================================================== histogram / gc / nmt

    @Test
    @DisplayName("histogram: ranked rows, capped — but totals cover the WHOLE heap")
    void histogramIsRankedAndCapped() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget");
        Thread.sleep(300);

        ObjectNode args = profileAction("histogram", sessionId);
        args.put("limit", 5);
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");
        assertEquals(5, rows.size(), "limit must actually cap the returned rows: " + rows.size());
        assertTrue(((Number) d.get("totalClasses")).intValue() > 5,
            "a live JVM has more than 5 classes loaded");
        assertTrue(((Number) d.get("totalInstances")).longValue() > 0);
        assertEquals(Boolean.TRUE, d.get("truncated"));

        Map<String, Object> first = rows.get(0);
        assertEquals(1, ((Number) first.get("rank")).intValue());
        assertNotNull(first.get("class"));
        assertTrue(((Number) first.get("instances")).longValue() > 0);
        assertTrue(((Number) first.get("bytes")).longValue() > 0);
    }

    @Test
    @DisplayName("gc: heap regions structured, not a raw dump")
    void gcReportsStructuredRegions() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget");
        Thread.sleep(300);

        ToolResponse r = profile.execute(profileAction("gc", sessionId));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> regions = (List<Map<String, Object>>) data(r).get("regions");
        assertFalse(regions.isEmpty(), "at least one heap region must be reported");
        for (Map<String, Object> region : regions) {
            assertNotNull(region.get("region"));
        }
    }

    @Test
    @DisplayName("nmt: enabled on a preset-launched target, with categories")
    void nmtEnabledOnPresetTarget() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget");
        Thread.sleep(300);

        ToolResponse r = profile.execute(profileAction("nmt", sessionId));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);
        assertEquals(Boolean.TRUE, d.get("enabled"), "got: " + d);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> categories = (List<Map<String, Object>>) d.get("categories");
        assertFalse(categories.isEmpty(), "the dev/sim preset enables NMT by default");
    }

    @Test
    @DisplayName("nmt: honest capability-absent on a target that was NOT launched with it — never a fake zero report")
    void nmtAbsentIsReportedHonestly() throws Exception {
        // A foreign JVM with JUST the debug agent — no NativeMemoryTracking flag.
        // Attaching does not (and must not) enable it retroactively.
        Process foreign = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
            "-cp", targetClasses.toString(), "com.example.debug.DebugTarget")
            .redirectErrorStream(true)
            .start();
        try {
            Thread.sleep(1500);
            assertTrue(foreign.isAlive());

            ObjectNode attach = debugAction("attach");
            attach.put("pid", foreign.pid());
            ToolResponse attached = debug.execute(attach);
            assertTrue(attached.isSuccess(), "got: " + attached.getError());
            String sessionId = (String) data(attached).get("sessionId");

            ToolResponse r = profile.execute(profileAction("nmt", sessionId));
            assertTrue(r.isSuccess(), "got: " + r.getError());
            Map<String, Object> d = data(r);
            assertEquals(Boolean.FALSE, d.get("enabled"), "got: " + d);
            assertNotNull(d.get("why"), "the absence must be EXPLAINED, not just false: " + d);
            assertTrue(String.valueOf(d.get("why")).contains("NativeMemoryTracking"),
                "names the actual cause: " + d.get("why"));
        } finally {
            foreign.descendants().forEach(ProcessHandle::destroyForcibly);
            foreign.destroyForcibly();
            foreign.waitFor(10, TimeUnit.SECONDS);
        }
    }

    // ========================================================== heap_dump + artifacts

    @Test
    @DisplayName("heap_dump: a bounded (live-only) dump, artifact-stored with provenance")
    void heapDumpIsBoundedAndStored() throws Exception {
        String sessionId = launchAndResume("com.example.debug.DebugTarget");
        Thread.sleep(300);

        ObjectNode args = profileAction("heap_dump", sessionId);
        args.put("live", true);
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        String artifactId = (String) d.get("artifactId");
        assertNotNull(artifactId);
        assertEquals(Boolean.TRUE, d.get("live"));
        assertTrue(((Number) d.get("bytes")).longValue() > 0, "a real HPROF file was written");

        ToolResponse listed = profile.execute(profileAction("artifacts", null));
        assertTrue(listed.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> all = (List<Map<String, Object>>) data(listed).get("artifacts");
        assertTrue(all.stream().anyMatch(a -> artifactId.equals(a.get("artifactId"))),
            "the dump must be findable via artifacts: " + all);

        ObjectNode del = profileAction("artifact_delete", null);
        del.put("artifactId", artifactId);
        ToolResponse deleted = profile.execute(del);
        assertTrue(deleted.isSuccess(), "got: " + deleted.getError());
        assertEquals(Boolean.TRUE, data(deleted).get("deleted"));
    }

    // ========================================================== session handling

    @Test
    @DisplayName("an unknown sessionId is an honest miss, not a crash")
    void unknownSessionIsHonestMiss() {
        ToolResponse r = profile.execute(profileAction("threads", "prof-nope"));
        assertFalse(r.isSuccess());
        assertNotNull(r.getError());
    }

    @Test
    @DisplayName("a missing sessionId is refused with what is needed")
    void missingSessionIdIsRefused() {
        ToolResponse r = profile.execute(profileAction("threads", null));
        assertFalse(r.isSuccess());
        assertTrue(r.getError().getMessage().toLowerCase().contains("sessionid"),
            "got: " + r.getError());
    }

    // ========================================================== D16: subagent hand-off (both front doors)

    @Test
    @DisplayName("D16: the profile description documents the SAME subagent hand-off clause as debug")
    void descriptionDocumentsSubagentHandoff() {
        String description = profile.getDescription();
        assertTrue(description.toUpperCase().contains("SUBAGENT"),
            "the profile front door must document the hand-off, same as debug's");
        assertTrue(description.contains("sessionId"),
            "the hand-off must name sessionId as the shared handle");
    }
}
