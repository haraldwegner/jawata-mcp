package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.EngineSettings;
import org.jawata.spi.host.McpEndpoint;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;
import org.jawata.spi.lang.LanguageProvider;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28k E19: the one place the engine learns of the faces and the language providers that plug into it. A face attaches after
 * the engine starts and detaches before it stops, holds only what {@link EngineApi} hands it, and calls tools through the one entry;
 * a provider that arrives or leaves is heard by whoever asked to be told. These are written against plain fakes that import no
 * Java tooling, which is the point of the slot: a face or a language that needs no host binding runs the same everywhere.
 */
class EngineWhiteboardTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The entry a face is given: answers every tool with its own name, and remembers who asked. */
    private static final class RecordingCalls implements ToolCalls {
        final List<String> asked = new ArrayList<>();

        @Override
        public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
            asked.add(name);
            return ToolResponse.success(Map.of("tool", name));
        }

        @Override
        public List<Map<String, Object>> getToolDefinitions() {
            return List.of();
        }
    }

    private static EngineApi api(ToolCalls calls) {
        return new EngineApi() {
            @Override
            public ToolCalls tools() {
                return calls;
            }

            @Override
            public EngineState state() {
                return EngineState.current();
            }

            @Override
            public EngineSettings settings() {
                return key -> Optional.empty();
            }

            @Override
            public Optional<McpEndpoint> mcp() {
                return Optional.empty();
            }
        };
    }

    /** A face that writes down what it was told and, when attached, makes one call through the entry it was given. */
    private static final class Face implements EngineFace {
        final String name;
        final List<String> events = new ArrayList<>();
        ToolResponse answer;
        boolean failOnAttach;

        Face(String name) {
            this.name = name;
        }

        @Override
        public String name() {
            return name;
        }

        @Override
        public void attach(EngineApi api) {
            if (failOnAttach) {
                throw new IllegalStateException(name + " cannot attach");
            }
            events.add("attach");
            try {
                answer = api.tools().callTool("health_check", JSON.createObjectNode(), CallerIdentity.IN_PROCESS);
            } catch (ToolNotFoundException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void detach() {
            events.add("detach");
        }
    }

    /** A provider that names no language tooling and publishes no tools. */
    private static final class FakeProvider implements LanguageProvider {
        final String language;

        FakeProvider(String language) {
            this.language = language;
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
    }

    @Test
    @DisplayName("a fake face attaches when the engine starts, calls one tool through the one entry, and detaches when it stops")
    void aFaceAttachesCallsOneToolAndDetaches() {
        EngineWhiteboard board = new EngineWhiteboard();
        RecordingCalls calls = new RecordingCalls();
        Face face = new Face("fake");
        board.bindFace(face);
        assertEquals(List.of(), face.events, "a face bound before the engine starts is not attached yet");

        board.started(api(calls));
        assertEquals(List.of("attach"), face.events);
        assertEquals(List.of("health_check"), calls.asked, "the face's one call went through the entry it was handed");
        assertTrue(face.answer.isSuccess());

        board.stopping();
        assertEquals(List.of("attach", "detach"), face.events);
    }

    @Test
    @DisplayName("a face bound after the engine has started is attached at once, and one unbound while it runs is detached")
    void aFaceBoundWhileRunningIsAttachedAtOnce() {
        EngineWhiteboard board = new EngineWhiteboard();
        board.started(api(new RecordingCalls()));
        Face late = new Face("late");

        board.bindFace(late);
        assertEquals(List.of("attach"), late.events);

        board.unbindFace(late);
        assertEquals(List.of("attach", "detach"), late.events);

        board.stopping();
        assertEquals(List.of("attach", "detach"), late.events, "a face already detached is not detached twice");
    }

    @Test
    @DisplayName("stopping detaches every attached face once, and a second stop does nothing")
    void stoppingDetachesEveryFaceOnce() {
        EngineWhiteboard board = new EngineWhiteboard();
        Face a = new Face("a");
        Face b = new Face("b");
        board.bindFace(a);
        board.bindFace(b);
        board.started(api(new RecordingCalls()));

        board.stopping();
        board.stopping();

        assertEquals(List.of("attach", "detach"), a.events);
        assertEquals(List.of("attach", "detach"), b.events);
    }

    @Test
    @DisplayName("a face that fails to attach does not keep the others from attaching")
    void aFailingFaceDoesNotStopTheOthers() {
        EngineWhiteboard board = new EngineWhiteboard();
        Face broken = new Face("broken");
        broken.failOnAttach = true;
        Face fine = new Face("fine");
        board.bindFace(broken);
        board.bindFace(fine);

        board.started(api(new RecordingCalls()));

        assertEquals(List.of("attach"), fine.events);
        board.stopping();
        assertEquals(List.of(), broken.events, "a face that never attached is not detached");
    }

    @Test
    @DisplayName("providers are listed in the order they arrived, and a listener hears each arrival and each departure")
    void providersAreTrackedAndHeard() {
        EngineWhiteboard board = new EngineWhiteboard();
        List<Integer> heard = new ArrayList<>();
        board.whenProvidersChange(() -> heard.add(board.providers().size()));
        FakeProvider first = new FakeProvider("fake-one");
        FakeProvider second = new FakeProvider("fake-two");

        board.bindProvider(first);
        board.bindProvider(second);
        assertEquals(List.of(first, second), board.providers());

        board.unbindProvider(first);
        assertEquals(List.of(second), board.providers());
        assertEquals(List.of(1, 2, 1), heard);
    }

    @Test
    @DisplayName("binding the same provider twice lists it once")
    void aProviderBoundTwiceIsListedOnce() {
        EngineWhiteboard board = new EngineWhiteboard();
        FakeProvider provider = new FakeProvider("fake");

        board.bindProvider(provider);
        board.bindProvider(provider);

        assertEquals(List.of(provider), board.providers());
    }
}
