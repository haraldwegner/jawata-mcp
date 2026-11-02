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

    @Test
    @DisplayName("an engine that stopped leaves no identity check behind: the next identity is not told STILL LOADING by a workspace that is gone")
    void stoppingTheEngineTakesItsIdentityChecksBack() throws Exception {
        FakeHost host = new FakeHost(scratch.resolve("engine"), Map.of(
            SettingKeys.EXPERIENCE_STORE.name(), "memory",
            SettingKeys.EMBED_DISABLED.name(), "true"));
        engine = JawataEngine.assemble(host);
        engine.start();
        engine.stop();
        engine = null;
        EngineState.current().reset();
        try {
            org.jawata.mcp.models.WorkspaceIdentity.install("probe-workspace", java.util.List.of(Path.of("simple-maven")));

            String described = org.jawata.mcp.models.WorkspaceIdentity.describe();

            org.junit.jupiter.api.Assertions.assertFalse(described.contains("STILL LOADING"),
                "the stopped engine's load check still answers for it: " + described);
        } finally {
            org.jawata.mcp.models.WorkspaceIdentity.install(null, java.util.List.of());
        }
    }

    /** The stand-in language of the engine tests, with one answer replaced: the suffix of its source files. */
    private static org.jawata.spi.lang.LanguageProvider declaring(String suffix) {
        org.jawata.standin.FakeLanguageProvider standIn = new org.jawata.standin.FakeLanguageProvider();
        return (org.jawata.spi.lang.LanguageProvider) java.lang.reflect.Proxy.newProxyInstance(
            org.jawata.spi.lang.LanguageProvider.class.getClassLoader(), new Class<?>[] {org.jawata.spi.lang.LanguageProvider.class},
            (proxy, method, args) -> {
                switch (method.getName()) {
                    case "sourceExtensions":
                        return java.util.Set.of(suffix);
                    case "equals":
                        return proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    default:
                        try {
                            return method.invoke(standIn, args);
                        } catch (java.lang.reflect.InvocationTargetException e) {
                            throw e.getCause();
                        }
                }
            });
    }

    @Test
    @DisplayName("the suffix a language bound on the engine's own whiteboard declares is the one the host is told, and it stops being told when the language leaves")
    void theHostIsToldTheSuffixOfTheLanguageBoundOnTheEngine() throws Exception {
        FakeHost host = new FakeHost(scratch.resolve("engine"), Map.of(
            SettingKeys.EXPERIENCE_STORE.name(), "memory",
            SettingKeys.EMBED_DISABLED.name(), "true"));
        engine = JawataEngine.assemble(host);
        engine.start();
        org.jawata.spi.lang.LanguageProvider language = declaring(".zzlang");

        engine.whiteboard().bindProvider(language);
        engine.getToolRegistry().callTool("health_check", new ObjectMapper().createObjectNode());
        java.util.List<java.util.Set<String>> whileBound = host.fakeReconciler().extensionSets();

        engine.whiteboard().unbindProvider(language);
        engine.getToolRegistry().callTool("health_check", new ObjectMapper().createObjectNode());
        java.util.List<java.util.Set<String>> afterwards = host.fakeReconciler().extensionSets();

        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.of(".zzlang"), whileBound.get(whileBound.size() - 1),
            "the engine asked the providers bound on ITS whiteboard, and the host got exactly the suffix this one declared");
        org.junit.jupiter.api.Assertions.assertEquals(java.util.Set.<String>of(), afterwards.get(afterwards.size() - 1),
            "the language left, so the next call names no suffix");
    }
}
