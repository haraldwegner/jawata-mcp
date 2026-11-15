package org.jawata.mcp.tools.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.Status;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.JvmTargets;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.spi.host.DebugTurn;
import org.jawata.spi.host.HostRuns;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h S18 (28k's list 9a B, A19-2): the engine hands a run to the host's {@link HostRuns} port. The agent asks for a program by the
 * name of the person's run configuration, the host starts it with the debug agent and answers the process id, and the debugger attaches;
 * {@code discover} says which programs the host started for jawata. The host here is a fake that starts a real debuggable JVM, so the
 * attach is the engine's own.
 */
class DebugHostRunsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper om = new ObjectMapper();
    private RuntimeSessionRegistry sessions;
    private JdtServiceImpl service;
    private Path targetClasses;
    private final List<Process> started = new ArrayList<>();
    private FakeHostRuns host;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        host = new FakeHostRuns();
        targetClasses = Files.createTempDirectory("jawata-hostruns-target-");
        Path source = service.getProjectRoot().resolve("src/main/java/com/example/debug/DebugTarget.java");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, null, "-g", "-d", targetClasses.toString(),
            source.toString());
        assertEquals(0, rc, "the debug fixture must compile");
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
        started.forEach(Process::destroyForcibly);
    }

    private DebugTool tool(Optional<HostRuns> runs, DebugTurn turn) throws Exception {
        return new DebugTool(() -> service, sessions, new RuntimeArtifactStore(Files.createTempDirectory("jawata-hostruns-art-")), null, turn,
            runs);
    }

    private ObjectNode launch(String configuration) {
        ObjectNode args = om.createObjectNode();
        args.put("action", "launch");
        args.put("configuration", configuration);
        return args;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse response) {
        return (Map<String, Object>) response.getData();
    }

    /** A host that starts the fixture as a real JVM with the debug agent and never holds it, as "Run for jawata" does. */
    private final class FakeHostRuns implements HostRuns {
        final List<String> asked = new CopyOnWriteArrayList<>();
        final List<RunForJawata> running = new CopyOnWriteArrayList<>();

        @Override
        public RunForJawata runForJawata(String configuration, IProgressMonitor monitor) throws CoreException {
            asked.add(configuration);
            if (!"App".equals(configuration)) {
                throw new CoreException(new Status(Status.ERROR, "test", "There is no run configuration named " + configuration + "."));
            }
            try {
                Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                    "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0", "-cp", targetClasses.toString(),
                    "com.example.debug.DebugTarget").redirectErrorStream(true).start();
                started.add(process);
                long deadline = System.currentTimeMillis() + 20_000;
                while (process.isAlive() && JvmTargets.debugAddress(process.pid()) == null && System.currentTimeMillis() < deadline) {
                    Thread.sleep(100);
                }
                if (!process.isAlive()) {
                    // A host never answers with the pid of a program that has already ended; say how it ended instead.
                    throw new CoreException(new Status(Status.ERROR, "test", "the started program ended with exit code " + process.exitValue()
                        + ": " + new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)));
                }
                RunForJawata run = new RunForJawata(configuration, process.pid(), "", AGENT);
                running.add(run);
                return run;
            } catch (java.io.IOException | InterruptedException failed) {
                throw new CoreException(new Status(Status.ERROR, "test", failed.toString()));
            }
        }

        @Override
        public List<RunForJawata> runningForJawata() {
            return List.copyOf(running);
        }
    }

    @Test
    @DisplayName("launch with a configuration asks the host for the run, then attaches to the pid it answers")
    void launchWithAConfigurationStartsThroughTheHostAndAttaches() throws Exception {
        ToolResponse response = tool(Optional.of(host), DebugTurn.FREE).execute(launch("App"));

        assertTrue(response.isSuccess(), "got: " + response.getError());
        assertEquals(List.of("App"), host.asked, "the host was asked once, by the configuration's name");
        assertEquals("attached", data(response).get("origin"), "the program is the host's, so the debugger attached; it did not launch");
        assertEquals(host.running.get(0).pid(), ((Number) data(response).get("pid")).longValue(), "attached to the pid the host answered");
        assertEquals(1, sessions.size());
    }

    @Test
    @DisplayName("a host that runs no programs is refused by name, with the way that still works")
    void aHostWithoutRunsRefusesAConfigurationLaunch() throws Exception {
        ToolResponse response = tool(Optional.empty(), DebugTurn.FREE).execute(launch("App"));

        assertFalse(response.isSuccess());
        assertEquals("HOST_RUNS_NO_PROGRAMS", response.getError().getCode());
        assertTrue(String.valueOf(response.getError().getHint()).contains("mainClass"), "names the launch that does work");
        assertEquals(0, sessions.size());
    }

    @Test
    @DisplayName("a configuration the host cannot find is the host's own refusal, not an internal error")
    void aConfigurationTheHostRefusesIsReportedWithItsReason() throws Exception {
        ToolResponse response = tool(Optional.of(host), DebugTurn.FREE).execute(launch("Nope"));

        assertFalse(response.isSuccess());
        assertEquals("HOST_RUN_FAILED", response.getError().getCode());
        assertTrue(response.getError().getMessage().contains("no run configuration named Nope"), response.getError().getMessage());
        assertEquals(0, sessions.size());
    }

    @Test
    @DisplayName("a configuration together with a main class is refused: the host builds the command from the person's configuration")
    void aConfigurationExcludesAMainClass() throws Exception {
        ObjectNode args = launch("App");
        args.put("mainClass", "com.example.debug.DebugTarget");

        ToolResponse response = tool(Optional.of(host), DebugTurn.FREE).execute(args);

        assertFalse(response.isSuccess());
        assertEquals("INVALID_PARAMETER", response.getError().getCode());
        assertTrue(host.asked.isEmpty(), "nothing was started");
    }

    @Test
    @DisplayName("while the person debugs, the host is not asked to run anything")
    void thePersonsTurnIsRespectedBeforeTheHostIsAsked() throws Exception {
        DebugTurn personDebugs = new DebugTurn() {
            @Override
            public boolean personIsDebugging() {
                return true;
            }

            @Override
            public void onPersonStartsDebugging(Runnable endAgentSessions) {
            }
        };

        ToolResponse response = tool(Optional.of(host), personDebugs).execute(launch("App"));

        assertEquals("PERSON_IS_DEBUGGING", response.getError().getCode());
        assertTrue(host.asked.isEmpty(), "the person wins; the program was never started");
    }

    @Test
    @DisplayName("discover says which program the host started for jawata, with its configuration and who started it")
    void discoverLabelsAProgramStartedForJawata() throws Exception {
        DebugTool tool = tool(Optional.of(host), DebugTurn.FREE);
        long pid = host.runForJawata("App", null).pid();
        host.running.set(0, new HostRuns.RunForJawata("App", pid, "", HostRuns.PERSON));

        ObjectNode args = om.createObjectNode();
        args.put("action", "discover");
        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), "got: " + response.getError());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> jvms = (List<Map<String, Object>>) data(response).get("jvms");
        Map<String, Object> labelled = jvms.stream().filter(j -> ((Number) j.get("pid")).longValue() == pid).findFirst().orElseThrow();
        assertEquals(Boolean.TRUE, labelled.get("startedForJawata"));
        assertEquals("App", labelled.get("configuration"));
        assertEquals("person", labelled.get("startedBy"));
        assertEquals("started by the person for jawata, configuration App", labelled.get("label"));
        assertTrue(jvms.stream().filter(j -> ((Number) j.get("pid")).longValue() != pid)
            .allMatch(j -> j.get("startedForJawata") == null), "no other program is labelled");
    }
}
