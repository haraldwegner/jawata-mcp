package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.ResidentDegradationTestHooks;
import org.jawata.mcp.contract.FakeHost;
import org.jawata.spi.host.Notice;
import org.jawata.standin.FakeLanguageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28k, Stage 9 (contract §4.2, §8): what the engine does with the host ports that only a person's host can answer. It tells the
 * person what they would have to act on, shows them its long work, asks to be told the moment they start debugging, and waits for the
 * languages the host says it serves and no others.
 */
class EngineSpeaksToThePersonTest {

    private static final String START_UP_WORK = "Preparing the knowledge store for recall by meaning";

    @TempDir
    Path scratch;

    private FakeHost host;
    private JawataEngine engine;

    private void assemble(Map<String, String> settings, String... servedLanguages) {
        host = new FakeHost(scratch.resolve("engine"), settings);
        host.serves(servedLanguages);
        engine = JawataEngine.assemble(host);
    }

    @AfterEach
    void stopTheEngine() {
        if (engine != null) {
            engine.stop();
        }
        EngineEnvironment.hostedBy(null, null);
        EngineState.current().reset();
        ResidentDegradationTestHooks.clear();
    }

    @Test
    @DisplayName("the work the engine does in the background at start-up is shown to the person, from its start to its end")
    void startUpWorkIsShownToThePerson() throws Exception {
        assemble(Map.of(SettingKeys.EXPERIENCE_STORE.name(), "memory", SettingKeys.EMBED_DISABLED.name(), "true"));

        long deadline = System.currentTimeMillis() + 15_000;
        while (!host.fakePerson().activities().contains("end:" + START_UP_WORK) && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }

        List<String> shown = host.fakePerson().activities();
        assertEquals("begin:" + START_UP_WORK, shown.get(0), "the work is shown before it is done: " + shown);
        assertTrue(shown.contains("end:" + START_UP_WORK), "and it ends in the person's view too: " + shown);
    }

    @Test
    @DisplayName("the engine asks the host to tell it the moment the person starts debugging, once")
    void theEngineAsksToBeToldWhenThePersonDebugs() {
        assemble(Map.of(SettingKeys.EXPERIENCE_STORE.name(), "memory", SettingKeys.EMBED_DISABLED.name(), "true"));

        assertEquals(1, host.fakeDebugTurn().registrations());
    }

    @Test
    @DisplayName("a knowledge store that cannot be opened is a warning to the person, and the engine carries on")
    void anUnopenableStoreIsToldToThePerson() throws Exception {
        Path notAFolder = Files.writeString(scratch.resolve("not-a-folder"), "a file where the store's folder should be");

        assemble(Map.of(SettingKeys.EXPERIENCE_STORE.name(), notAFolder.toString(), SettingKeys.EMBED_DISABLED.name(), "true"));

        List<Notice> told = host.fakePerson().notices();
        assertTrue(told.stream().anyMatch(n -> n.level() == Notice.Level.WARNING && n.text().contains("knowledge store")),
            "the person is told the store did not open: " + told);
    }

    @Test
    @DisplayName("the engine waits for the languages the host says it serves, and is ready when they have all bound")
    void theEngineAwaitsWhatTheHostServes() {
        assemble(Map.of(SettingKeys.EXPERIENCE_STORE.name(), "memory", SettingKeys.EMBED_DISABLED.name(), "true"), "fake");

        EngineState.current().booted();
        ProjectLoadingState before = EngineState.current().loading();
        engine.whiteboard().bindProvider(new FakeLanguageProvider());
        ProjectLoadingState after = EngineState.current().loading();

        assertEquals(ProjectLoadingState.LOADING, before, "a language the host serves has not bound yet");
        assertNotEquals(ProjectLoadingState.LOADING, after, "and the engine is ready once it has");
    }
}
