package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.core.runtime.IProgressMonitor;
import org.jawata.mcp.ResidentDegradationTestHooks;
import org.jawata.mcp.contract.FakeHost;
import org.jawata.mcp.tools.Tool;
import org.jawata.spi.host.HostRuns;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;
import org.jawata.spi.lang.LanguageProvider;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;
import org.jawata.standin.FakeLanguageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28h S18 (28k list 9a B, A19-2): what a host says about running programs reaches the tools of a language. The engine reads the
 * host's {@code instruments().runs()} once, when it wires the tools, and hands it to every language's tools in what they need; the debug
 * tool of the Java provider takes it from there. A language here records what it was handed.
 */
class HostRunsReachLanguageToolsTest {

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

    /** A language that is the stand-in with one difference: it keeps what its tools were handed. */
    private static final class Recording implements LanguageProvider, ToolSource {
        private final FakeLanguageProvider inner = new FakeLanguageProvider();
        volatile ToolNeeds handed;

        @Override
        public String language() {
            return inner.language();
        }

        @Override
        public BuildVerdict verdict(ProviderContext context) {
            return inner.verdict(context);
        }

        @Override
        public ChangePaths paths(ProviderContext context) {
            return inner.paths(context);
        }

        @Override
        public SymbolAnchors anchors(ProviderContext context) {
            return inner.anchors(context);
        }

        @Override
        public Optional<TestRuntime> tests(ProviderContext context) {
            return inner.tests(context);
        }

        @Override
        public List<Tool> tools(ToolNeeds needs) {
            handed = needs;
            return inner.tools(needs);
        }
    }

    private Recording bindOver(FakeHost host) {
        engine = JawataEngine.assemble(host);
        Recording language = new Recording();
        engine.whiteboard().bindProvider(language);
        return language;
    }

    private FakeHost host() {
        return new FakeHost(scratch.resolve("engine"), Map.of(
            SettingKeys.EXPERIENCE_STORE.name(), "memory",
            SettingKeys.EMBED_DISABLED.name(), "true"));
    }

    @Test
    @DisplayName("a host that runs programs hands them to a language's tools")
    void theHostsRunsReachALanguage() {
        FakeHost host = host();
        HostRuns runs = new HostRuns() {
            @Override
            public RunForJawata runForJawata(String configuration, IProgressMonitor monitor) {
                return new RunForJawata(configuration, 1L, "", AGENT);
            }

            @Override
            public List<RunForJawata> runningForJawata() {
                return List.of();
            }
        };
        host.runs(runs);

        Recording language = bindOver(host);

        assertNotNull(language.handed, "the engine asked the language for its tools");
        assertTrue(language.handed.hostRuns().isPresent(), "the host's runs were handed on");
        assertSame(runs, language.handed.hostRuns().get());
    }

    @Test
    @DisplayName("what the Java provider's debug tool is handed is what it asks: a host that refuses the run is heard, not a host that runs nothing")
    void theJavaDebugToolAsksTheHostItWasHanded() throws Exception {
        HostRuns refusing = new HostRuns() {
            @Override
            public RunForJawata runForJawata(String configuration, IProgressMonitor monitor) throws org.eclipse.core.runtime.CoreException {
                throw new org.eclipse.core.runtime.CoreException(new org.eclipse.core.runtime.Status(org.eclipse.core.runtime.IStatus.ERROR,
                    "test", "the host has no configuration " + configuration));
            }

            @Override
            public List<RunForJawata> runningForJawata() {
                return List.of();
            }
        };
        ToolNeeds needs = new ToolNeeds(new org.jawata.mcp.change.RefactoringChangeCache(), () -> null, null,
            new org.jawata.mcp.runtime.RuntimeSessionRegistry(), arguments -> null,
            new org.jawata.mcp.runtime.RuntimeArtifactStore(scratch.resolve("art")), org.jawata.spi.host.DebugTurn.FREE,
            new org.jawata.mcp.change.PlanSlot(), null, Optional.of(refusing));

        Tool debug = org.jawata.mcp.fixtures.InertShippedTools.javaProvider().tools(needs).stream()
            .filter(tool -> "debug".equals(tool.getName())).findFirst().orElseThrow();
        var arguments = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        arguments.put("action", "launch");
        arguments.put("configuration", "Ghost");
        var answer = debug.execute(arguments);

        assertEquals("HOST_RUN_FAILED", answer.getError().getCode(), "the host was asked, and its refusal is the answer: " + answer.getError());
        engine = JawataEngine.assemble(host());
    }

    @Test
    @DisplayName("a host that runs nothing hands a language nothing, so the debug tool says so instead of guessing")
    void aHostWithoutRunsHandsNothing() {
        Recording language = bindOver(host());

        assertEquals(Optional.empty(), language.handed.hostRuns());
    }
}
