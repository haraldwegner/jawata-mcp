package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;

import org.jawata.mcp.ShippedTools;
import org.jawata.mcp.ShippedTools.Built;
import org.jawata.mcp.fixtures.InertShippedTools;
import org.jawata.mcp.java.JavaProvider;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ToolRegistry;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.WorkspaceWrites;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;
import org.jawata.spi.lang.LanguageProvider;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 28k E19: the engine is "loading" until the language its host serves has bound, then publishes everything. Written against
 * the real producer and the real Java provider over an inert model, and against a plain fake language that imports no Java tooling.
 * Nothing is registered anywhere: the publisher hands the published set to a recorder, because registering publishes into a
 * process-wide operation registry that no test may leave behind.
 */
class LanguagePublisherTest {

    private final EngineState state = EngineState.current();
    private final List<Built> published = new ArrayList<>();
    private final EngineWhiteboard board = new EngineWhiteboard();
    private final AtomicInteger surfaceChecks = new AtomicInteger();

    private static final ProviderContext NO_HOST = new ProviderContext() {
        @Override
        public Reconciler reconciler() {
            return null;
        }

        @Override
        public WorkspaceWrites writes() {
            return WorkspaceWrites.DIRECT;
        }
    };

    @BeforeEach
    void bootTheStateOnly() {
        state.reset();
        state.booted();
    }

    @AfterEach
    void leaveNoStateBehind() {
        state.reset();
    }

    private Built neutral() {
        return ShippedTools.neutral(InertShippedTools.wiring(new ToolRegistry()), ProviderPorts.over(board, NO_HOST));
    }

    private Built latest() {
        return published.get(published.size() - 1);
    }

    private static Set<String> names(Built built) {
        Set<String> names = new TreeSet<>();
        built.tools().forEach(tool -> names.add(tool.getName()));
        return names;
    }

    /** A tool that does nothing, so a fake language has something to publish. */
    private static Tool tool(String name) {
        return new Tool() {
            @Override
            public String getName() {
                return name;
            }

            @Override
            public String getDescription() {
                return name;
            }

            @Override
            public Map<String, Object> getInputSchema() {
                return Map.of("type", "object");
            }

            @Override
            public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }
        };
    }

    /** A language that names nothing of Java: it has tools of its own and answers no port. */
    private static final class FakeLanguage implements LanguageProvider, ToolSource {
        final String language;
        final List<String> toolNames;
        final AtomicInteger built = new AtomicInteger();

        FakeLanguage(String language, String... toolNames) {
            this.language = language;
            this.toolNames = List.of(toolNames);
        }

        @Override
        public String language() {
            return language;
        }

        @Override
        public BuildVerdict verdict(ProviderContext context) {
            return null;
        }

        @Override
        public ChangePaths paths(ProviderContext context) {
            return null;
        }

        @Override
        public SymbolAnchors anchors(ProviderContext context) {
            return null;
        }

        @Override
        public Optional<TestRuntime> tests(ProviderContext context) {
            return Optional.empty();
        }

        @Override
        public List<Tool> tools(ToolNeeds needs) {
            built.incrementAndGet();
            return toolNames.stream().map(LanguagePublisherTest::tool).toList();
        }
    }

    @Test
    @DisplayName("the engine is loading with the neutral tools only, until the Java provider binds, and then publishes all 44")
    void loadingUntilTheJavaProviderBindsThenFortyFour() {
        Built neutral = neutral();
        new LanguagePublisher(board, neutral, published::add, java.util.Set.of("java"), state, surfaceChecks::incrementAndGet);

        assertEquals(ProjectLoadingState.LOADING, state.loading(), "no Java provider yet: the engine says it is loading");
        assertEquals(names(neutral), names(latest()), "only the tools that name no language are published");
        assertTrue(names(latest()).size() < 44, "the Java tools are not there yet: " + names(latest()).size());
        assertEquals(0, surfaceChecks.get(), "the published surface is not checked before the language it needs is there");

        JavaProvider java = new JavaProvider(InertShippedTools.wiring(new ToolRegistry()).jdt());
        board.bindProvider(java);

        assertEquals(InertShippedTools.names(), names(latest()), "the same 44 tools the product ships");
        assertEquals(44, latest().tools().size());
        assertEquals(1, surfaceChecks.get(), "checked once, after the Java tools were published");
        assertNotEquals(ProjectLoadingState.LOADING, state.loading(), "bound: no longer loading");
        assertNotNull(latest().quality(), "the quality tool the watch engine binds to arrives with the Java tools");
    }

    @Test
    @DisplayName("before any language the quality tool the watch engine binds to is absent, not a stub")
    void noQualityToolBeforeALanguage() {
        new LanguagePublisher(board, neutral(), published::add, java.util.Set.of("java"), state, surfaceChecks::incrementAndGet);

        assertNull(latest().quality());
    }

    @Test
    @DisplayName("a host that serves no language is never loading, and nothing is checked")
    void aHostWithoutALanguageIsNotLoading() {
        new LanguagePublisher(board, neutral(), published::add, java.util.Set.of(), state, surfaceChecks::incrementAndGet);

        assertNotEquals(ProjectLoadingState.LOADING, state.loading());
        board.bindProvider(new FakeLanguage("fake", "fake_tool"));
        assertEquals(0, surfaceChecks.get());
    }

    @Test
    @DisplayName("a second language adds its tools without rebuilding the first one's")
    void aSecondLanguageDoesNotRebuildTheFirst() {
        FakeLanguage one = new FakeLanguage("one", "one_tool");
        FakeLanguage two = new FakeLanguage("two", "two_tool");
        new LanguagePublisher(board, neutral(), published::add, java.util.Set.of(), state, surfaceChecks::incrementAndGet);

        board.bindProvider(one);
        board.bindProvider(two);

        assertTrue(names(latest()).containsAll(Set.of("one_tool", "two_tool")));
        assertEquals(1, one.built.get(), "the first language's tools were built when it arrived and not again");
        assertEquals(1, two.built.get());
    }

    @Test
    @DisplayName("a language that leaves takes its tools with it")
    void aLanguageThatLeavesTakesItsToolsWithIt() {
        FakeLanguage fake = new FakeLanguage("fake", "fake_tool");
        Built neutral = neutral();
        new LanguagePublisher(board, neutral, published::add, java.util.Set.of(), state, surfaceChecks::incrementAndGet);

        board.bindProvider(fake);
        assertTrue(names(latest()).contains("fake_tool"));

        board.unbindProvider(fake);
        assertFalse(names(latest()).contains("fake_tool"));
        assertEquals(names(neutral), names(latest()));
    }

    @Test
    @DisplayName("when the awaited language leaves the engine is loading again, and it is not loading once the language is back")
    void theAwaitedLanguageLeavingPutsTheEngineBackToLoading() {
        new LanguagePublisher(board, neutral(), published::add, java.util.Set.of("fake"), state, surfaceChecks::incrementAndGet);
        FakeLanguage fake = new FakeLanguage("fake", "fake_tool");

        board.bindProvider(fake);
        assertNotEquals(ProjectLoadingState.LOADING, state.loading());

        board.unbindProvider(fake);
        assertEquals(ProjectLoadingState.LOADING, state.loading(), "no language: the engine is waiting for it again");

        board.bindProvider(fake);
        assertNotEquals(ProjectLoadingState.LOADING, state.loading());
        assertEquals(2, surfaceChecks.get(), "the surface is checked each time the language arrives");
    }

    @Test
    @DisplayName("a language that is not the awaited one does not end the loading state")
    void anotherLanguageDoesNotEndLoading() {
        new LanguagePublisher(board, neutral(), published::add, java.util.Set.of("java"), state, surfaceChecks::incrementAndGet);

        board.bindProvider(new FakeLanguage("cpp", "cpp_tool"));

        assertEquals(ProjectLoadingState.LOADING, state.loading());
        assertEquals(0, surfaceChecks.get());
    }
}
