package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D9, C11) — replay with a declared invariant: stop at the FIRST moment the
 * program is wrong.
 *
 * <p>The fixture violates {@code balance >= 0} at event 7 — and again at 8, and again at
 * 9. That is deliberate: it is what makes "the FIRST violation" a real assertion rather
 * than a tautology. A capture at event 8 would be a capture of a program already broken,
 * which tells you almost nothing about why.</p>
 */
class ReplayInvariantTest {

    private static final String REPLAY_APP = "com.example.replay.ReplayApp";
    /** The fixture's own declaration of where it first goes wrong. */
    private static final int VIOLATING_EVENT = 7;

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @TempDir
    Path artifactRoot;

    private RuntimeSessionRegistry sessions;
    private RuntimeArtifactStore artifacts;
    private DebugTool tool;
    private ObjectMapper om;
    private Path classes;
    private List<String> source;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("replay-app");
        sessions = new RuntimeSessionRegistry();
        artifacts = new RuntimeArtifactStore(artifactRoot);
        tool = new DebugTool(() -> service, sessions, artifacts);
        om = new ObjectMapper();

        Path file = service.getProjectRoot()
            .resolve("src/main/java/com/example/replay/ReplayApp.java");
        source = Files.readAllLines(file);

        classes = Files.createTempDirectory("jawata-replay-");
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", classes.toString(), file.toString()),
            "the replay fixture must compile");
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private int lineOf(String snippet) {
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i).contains(snippet)) {
                return i + 1;
            }
        }
        throw new AssertionError("the fixture no longer contains: " + snippet);
    }

    private ObjectNode replayArgs(String invariant) {
        ObjectNode args = om.createObjectNode();
        args.put("action", "replay");
        args.put("mainClass", REPLAY_APP);
        args.put("classpath", classes.toString());
        args.put("invariant", invariant);
        args.put("className", REPLAY_APP);
        // Check AFTER the balance has been updated — the line that prints it.
        args.put("line", lineOf("System.out.println(\"event \""));
        args.put("timeoutMillis", 60_000);
        return args;
    }

    @Test
    @DisplayName("EXACTLY ONE capture, at the FIRST violation — not the second, not the tenth")
    void capturesTheFirstViolationAndOnlyThat() {
        ObjectNode args = replayArgs("balance >= 0");
        args.putArray("capture").add("event").add("amount").add("balance");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);

        assertEquals(Boolean.TRUE, result.get("violated"), "got: " + result);

        @SuppressWarnings("unchecked")
        Map<String, Object> violation = (Map<String, Object>) result.get("firstViolation");
        @SuppressWarnings("unchecked")
        Map<String, Object> captured = (Map<String, Object>) violation.get("captured");

        // THE ASSERTION THAT MATTERS. The invariant is broken at 7, 8 and 9 — we must have
        // stopped at 7. Stopping at 8 would be stopping in a program that was ALREADY wrong.
        assertEquals(VIOLATING_EVENT, ((Number) captured.get("event")).intValue(),
            "the FIRST violation is event " + VIOLATING_EVENT + ": " + captured);
        assertEquals(40, ((Number) captured.get("amount")).intValue(),
            "the withdrawal that broke it: " + captured);
        assertEquals(-40, ((Number) captured.get("balance")).intValue(),
            "and the state it broke into: " + captured);

        // Only ONE hit was recorded — the breakpoint is disarmed the moment it fires, so no
        // later violation can be mistaken for the first.
        assertEquals(1, ((Number) violation.get("hitNumber")).intValue(),
            "exactly one capture: " + violation);

        // The program is still STANDING IN the violation — not a post-mortem.
        assertEquals("apply", violation.get("method"), "got: " + violation);
        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) violation.get("state");
        assertEquals(Boolean.TRUE, state.get("suspended"),
            "the thread is suspended AT the first violation, so you can look around from "
                + "there rather than reason backwards from the wreckage: " + state);
        assertNotNull(state.get("stack"));
    }

    @Test
    @DisplayName("the capture is an ARTIFACT, with the provenance that makes it evidence")
    void theCaptureIsStoredWithItsProvenance() throws Exception {
        ObjectNode args = replayArgs("balance >= 0");
        args.putArray("capture").add("balance");

        Map<String, Object> result = data(tool.execute(args));
        String artifactId = (String) result.get("artifactId");
        assertNotNull(artifactId, "the capture is stored: " + result);

        Map<String, Object> manifest = artifacts.readManifest(artifactId).orElseThrow();
        assertEquals("replay", manifest.get("kind"));
        assertEquals(REPLAY_APP, manifest.get("target"));
        assertEquals("balance >= 0", manifest.get("invariant"),
            "the manifest says WHAT was being checked — a capture whose origin is unknown is "
                + "evidence of nothing: " + manifest);
        assertEquals(Boolean.TRUE, manifest.get("violated"));
        assertNotNull(manifest.get("createdMillis"));
        assertEquals(result.get("sessionId"), manifest.get("sessionId"));

        Path capture = artifacts.root().resolve(artifactId).resolve("capture.json");
        assertTrue(Files.isRegularFile(capture), "the capture itself is on disk");
        Map<?, ?> stored = new ObjectMapper().readValue(capture.toFile(), Map.class);
        assertEquals("apply", stored.get("method"), "got: " + stored);
    }

    @Test
    @DisplayName("an invariant that HOLDS is an answer — and is not confused with 'inconclusive'")
    void anInvariantThatHoldsIsReportedAsSuch() {
        // This one is true at every event: the fixture never withdraws more than 40.
        ToolResponse r = tool.execute(replayArgs("amount <= 40"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);

        assertEquals(Boolean.FALSE, result.get("violated"), "got: " + result);
        assertEquals(Boolean.TRUE, result.get("programEnded"),
            "the replay RAN OUT — which is what makes 'it held' sayable at all");
        assertTrue(String.valueOf(result.get("conclusion")).contains("held at every check"),
            "got: " + result.get("conclusion"));
    }

    @Test
    @DisplayName("'no violation yet, still running' is NOT reported as 'the invariant held'")
    void anInconclusiveRunIsNotDressedUpAsAPass() throws Exception {
        // A program that never ends, and an invariant that never breaks. When we give up, the
        // program is STILL RUNNING — so "the invariant held" is a claim we have not earned.
        // These two outcomes must never be merged: one is a result, the other is a timeout.
        Path forever = Files.createTempDirectory("jawata-replay-forever-");
        Path src = forever.resolve("Forever.java");
        Files.writeString(src, """
            public final class Forever {
                static int tick;
                public static void main(String[] args) throws Exception {
                    while (true) {
                        tick++;
                        Thread.sleep(20);
                    }
                }
            }
            """);
        assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", forever.toString(), src.toString()));

        ObjectNode args = om.createObjectNode();
        args.put("action", "replay");
        args.put("mainClass", "Forever");
        args.put("classpath", forever.toString());
        args.put("invariant", "tick >= 0");     // true forever
        args.put("className", "Forever");
        args.put("line", 5);                    // tick++
        args.put("timeoutMillis", 2_000);

        Map<String, Object> result = data(tool.execute(args));
        assertEquals(Boolean.FALSE, result.get("violated"));
        assertEquals(Boolean.FALSE, result.get("programEnded"),
            "the program is still going: " + result);

        String conclusion = String.valueOf(result.get("conclusion"));
        assertTrue(conclusion.contains("STILL RUNNING"),
            "an unfinished run must not read as a clean one: " + conclusion);
        assertTrue(conclusion.contains("not yet"),
            "and must not claim the invariant held: " + conclusion);
    }

    @Test
    @DisplayName("an invariant that cannot be EVALUATED stops and says so — it never silently passes")
    void anUnevaluatableInvariantStopsRatherThanSilentlyNeverMatching() {
        // `amount` is a local of apply(), and does not exist at the line after the loop. An
        // invariant that cannot be evaluated must NOT be treated as "not violated" — a
        // condition that silently never matches looks exactly like a bug that never happens,
        // which is the most expensive kind of wrong answer a debugger can give.
        ObjectNode args = replayArgs("amount <= 40");
        args.put("line", lineOf("System.out.println(\"replay complete"));
        args.put("timeoutMillis", 30_000);

        Map<String, Object> result = data(tool.execute(args));
        assertEquals(Boolean.TRUE, result.get("violated"),
            "it STOPPED rather than sailing past: " + result);

        @SuppressWarnings("unchecked")
        Map<String, Object> violation = (Map<String, Object>) result.get("firstViolation");
        assertNotNull(violation.get("conditionError"),
            "and it says the condition was the problem, not the program: " + violation);
        assertTrue(String.valueOf(violation.get("note")).contains("not because it was true"),
            "the stop must not be read as a match: " + violation.get("note"));
    }
}
