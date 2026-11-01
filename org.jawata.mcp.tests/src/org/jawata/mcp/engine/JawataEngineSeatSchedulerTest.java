package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.mcp.ResidentDegradationTestHooks;
import org.jawata.mcp.contract.FakeHost;
import org.jawata.mcp.execution.ScheduledHires;
import org.jawata.spi.host.EndpointPolicy;
import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineHost;
import org.jawata.spi.host.EngineLifecycle;
import org.jawata.spi.host.EngineSettings;
import org.jawata.spi.host.HostCapabilities;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.host.Instruments;
import org.jawata.spi.host.LanguageBinding;
import org.jawata.spi.host.PersonChannel;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28h S12 (D29 category C): the engine starts the seat scheduler when its host gives it a seats folder and a workspace folder, ends
 * it when it stops, and carries on without one when either is missing, saying so rather than failing the start of the engine.
 */
class JawataEngineSeatSchedulerTest {

    private static final String THREAD = "jawata-seat-scheduler";

    @TempDir
    Path scratch;

    private JawataEngine engine;

    @AfterEach
    void stopTheEngine() {
        if (engine != null) {
            engine.stop();
        }
        EngineEnvironment.hostedBy(null, null);
        EngineState.current().reset();
        ResidentDegradationTestHooks.clear();
        org.jawata.mcp.knowledge.EmbeddingServiceTestHooks.forgetTheSharedService();
    }

    /** How many scheduler threads are alive in this JVM, so a thread another test left behind is not mistaken for this engine's. */
    private static long schedulerThreads() {
        return Thread.getAllStackTraces().keySet().stream().filter(t -> THREAD.equals(t.getName()) && t.isAlive()).count();
    }

    /**
     * The scheduler threads alive once the count has stopped changing: the previous test's engine was stopped in its {@code @AfterEach} and its
     * thread ends a moment later, and a count taken in that moment would be one too high.
     */
    private static long settledSchedulerThreads() throws InterruptedException {
        long last = schedulerThreads();
        long deadline = System.nanoTime() + 5_000_000_000L;
        int stable = 0;
        while (stable < 3 && System.nanoTime() < deadline) {
            Thread.sleep(100);
            long now = schedulerThreads();
            stable = now == last ? stable + 1 : 0;
            last = now;
        }
        return last;
    }

    /** A seats folder whose one seat declares a schedule that is never due (a 31st of February), so it is read and scheduled and fires nothing. */
    private Path seatsFolder() throws Exception {
        Path seats = Files.createDirectories(scratch.resolve("seats"));
        Files.writeString(seats.resolve("architect.md"), "---\nname: architect\nschedule: \"0 0 31 2 *\"\n---\n\nYou are a seat.\n");
        return seats;
    }

    private Map<String, String> settings(String seatsFolder) {
        Map<String, String> settings = new HashMap<>(Map.of(
            SettingKeys.EXPERIENCE_STORE.name(), "memory",
            SettingKeys.EMBED_DISABLED.name(), "true"));
        if (seatsFolder != null) {
            settings.put(ScheduledHires.SEATS_SETTING, seatsFolder);
        }
        return settings;
    }

    /** The fake host with its workspace folder taken away, which is what a host that keeps no workspace file answers. */
    private static final class WithoutAWorkspaceFolder implements EngineHost {
        private final FakeHost real;

        WithoutAWorkspaceFolder(FakeHost real) {
            this.real = real;
        }

        @Override
        public EngineDirectories directories() {
            EngineDirectories folders = real.directories();
            return new EngineDirectories() {
                @Override
                public Path runtimeDir() {
                    return folders.runtimeDir();
                }

                @Override
                public Path coverageDir() {
                    return folders.coverageDir();
                }

                @Override
                public Path toolsDir() {
                    return folders.toolsDir();
                }

                @Override
                public Path sharedStoreDir() {
                    return folders.sharedStoreDir();
                }

                @Override
                public Optional<Path> dataDir() {
                    return folders.dataDir();
                }

                @Override
                public Optional<Path> workspaceRoot() {
                    return Optional.empty();
                }

                @Override
                public Path fieldDir() {
                    return folders.fieldDir();
                }

                @Override
                public List<Path> memoryRoots() {
                    return folders.memoryRoots();
                }
            };
        }

        @Override
        public HostWorkspace workspace() {
            return real.workspace();
        }

        @Override
        public EngineLifecycle lifecycle() {
            return real.lifecycle();
        }

        @Override
        public EndpointPolicy endpoint() {
            return real.endpoint();
        }

        @Override
        public EngineSettings settings() {
            return real.settings();
        }

        @Override
        public Reconciler reconciler() {
            return real.reconciler();
        }

        @Override
        public WorkspaceWrites writes() {
            return real.writes();
        }

        @Override
        public <B extends LanguageBinding> Optional<B> binding(Class<B> kind) {
            return real.binding(kind);
        }

        @Override
        public PersonChannel person() {
            return real.person();
        }

        @Override
        public Instruments instruments() {
            return real.instruments();
        }

        @Override
        public HostCapabilities capabilities() {
            return real.capabilities();
        }
    }

    @Test
    @DisplayName("the engine starts the seat scheduler when it starts and ends it when it stops")
    void theEngineStartsAndEndsTheSeatScheduler() throws Exception {
        long before = settledSchedulerThreads();
        engine = JawataEngine.assemble(new FakeHost(scratch.resolve("engine"), settings(seatsFolder().toString())));

        engine.start();
        long running = schedulerThreads();
        engine.stop();
        long deadline = System.nanoTime() + 5_000_000_000L;
        while (schedulerThreads() > before && System.nanoTime() < deadline) {
            Thread.sleep(25);
        }

        assertEquals(before + 1, running, "start() started the scheduler thread");
        assertEquals(before, schedulerThreads(), "stop() ended it: the thread is gone, so a stopped engine leaves no schedule firing hires");
    }

    @Test
    @DisplayName("with a seats folder set and no workspace folder the engine starts without a scheduler and does not throw")
    void noWorkspaceFolderMeansNoSchedulerAndNoFailure() throws Exception {
        long before = settledSchedulerThreads();
        engine = JawataEngine.assemble(new WithoutAWorkspaceFolder(
            new FakeHost(scratch.resolve("engine"), settings(seatsFolder().toString()))));

        assertDoesNotThrow(engine::start, "a host with no workspace folder must not fail the start of the engine");

        assertEquals(before, schedulerThreads(), "and no scheduler was started, since a hire has nowhere to run");
    }

    @Test
    @DisplayName("with a seats folder that does not exist the engine starts without a scheduler and does not throw")
    void aMissingSeatsFolderMeansNoSchedulerAndNoFailure() throws Exception {
        long before = settledSchedulerThreads();
        engine = JawataEngine.assemble(new FakeHost(scratch.resolve("engine"), settings(scratch.resolve("no-such-seats").toString())));

        assertDoesNotThrow(engine::start, "a folder that cannot be listed must not fail the start of the engine");

        assertEquals(before, schedulerThreads(), "and no scheduler was started");
    }

    @Test
    @DisplayName("with no seats folder set nothing is scheduled, which is the control for the first case")
    void noSeatsFolderMeansNothingScheduled() throws Exception {
        long before = settledSchedulerThreads();
        engine = JawataEngine.assemble(new FakeHost(scratch.resolve("engine"), settings(null)));

        engine.start();

        assertEquals(before, schedulerThreads(), "the same engine with the setting absent starts no scheduler, so the first case's thread is the setting's doing");
    }
}
