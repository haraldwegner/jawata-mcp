package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.util.Map;

import org.jawata.mcp.ResidentDegradationTestHooks;
import org.jawata.mcp.contract.FakeHost;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h S14 (D15): the engine does not hand its host a bare "sync"; it hands it the source suffixes of the languages it serves,
 * asked at the moment of the call. Without that wiring a language other than Java never has a file looked for.
 */
class JawataEngineSourceExtensionsTest {

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

    @Test
    @DisplayName("a tool call through the engine reaches the host's reconciler with the languages' suffixes, not as a bare sync")
    void theEngineHandsTheHostTheLanguagesSuffixesAtEveryCall() throws Exception {
        FakeHost host = new FakeHost(scratch.resolve("engine"), Map.of(
            SettingKeys.EXPERIENCE_STORE.name(), "memory",
            SettingKeys.EMBED_DISABLED.name(), "true"));
        engine = JawataEngine.assemble(host);
        engine.start();

        engine.getToolRegistry().callTool("health_check", new ObjectMapper().createObjectNode());

        assertFalse(host.fakeReconciler().extensionSets().isEmpty(),
            "the host was asked to sync with a set of suffixes: the engine's reconciler is the language-aware one");
    }
}
