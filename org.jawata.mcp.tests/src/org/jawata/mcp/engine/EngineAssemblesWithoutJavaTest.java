package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IProjectDescription;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.jawata.mcp.ResidentDegradationTestHooks;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.change.ChangeEngine;
import org.jawata.mcp.change.GatedApply;
import org.jawata.mcp.contract.FakeHost;
import org.jawata.mcp.contract.PlainTextChange;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.standin.FakeBuildVerdict;
import org.jawata.standin.FakeLanguageProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28k, Stage 8 (contract §18 ring 1, the C++ slot): the engine assembles with a host that serves no Java and a language that
 * imports no Java tooling. The tools that name no language answer; a second language brings a tool of its own and takes it away when it
 * leaves; and a plain text change is applied, gated by that language's verdict and undone, with no Java anywhere.
 */
class EngineAssemblesWithoutJavaTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path scratch;

    private FakeHost host;
    private JawataEngine engine;

    @BeforeEach
    void assembleOverAHostThatServesNoJava() {
        host = new FakeHost(scratch.resolve("engine"), Map.of(
            SettingKeys.EXPERIENCE_STORE.name(), "memory",
            SettingKeys.EMBED_DISABLED.name(), "true"));
        engine = JawataEngine.assemble(host);
    }

    @AfterEach
    void stopTheEngine() {
        engine.stop();
        EngineEnvironment.hostedBy(null, null);
        EngineState.current().reset();
        ResidentDegradationTestHooks.clear();
    }

    private List<String> publishedTools() {
        return engine.api().tools().getToolDefinitions().stream().map(tool -> String.valueOf(tool.get("name"))).toList();
    }

    private ToolResponse call(String tool, String argumentsJson) throws Exception {
        JsonNode arguments = JSON.readTree(argumentsJson);
        return engine.api().tools().callTool(tool, arguments);
    }

    @Test
    @DisplayName("with no language bound the engine publishes the tools that name no language, and none of Java's")
    void theNeutralToolsArePublishedAlone() throws Exception {
        List<String> tools = publishedTools();
        Object status = ((Map<?, ?>) call("health_check", "{}").getData()).get("status");

        assertAll(
            () -> assertTrue(tools.containsAll(List.of("health_check", "experience", "hire", "field", "refactoring")), "got: " + tools),
            () -> assertFalse(tools.contains("search_symbols"), "a Java tool without Java: " + tools),
            () -> assertFalse(tools.contains("load_project"), "loading a Java project without Java: " + tools),
            () -> assertFalse(String.valueOf(status).contains("Loading"),
                "a host that serves no Java waits for no language, so the engine is not 'loading': " + status));
    }

    @Test
    @DisplayName("a language that binds brings its tool, the tool answers, and the tool is gone when the language leaves")
    void aSecondLanguageBringsItsToolAndTakesItAway() throws Exception {
        FakeLanguageProvider language = new FakeLanguageProvider();

        Object before = ((Map<?, ?>) call("health_check", "{}").getData()).get("languages");
        engine.whiteboard().bindProvider(language);
        List<String> withIt = publishedTools();
        ToolResponse echoed = call("fake_echo", "{\"text\":\"hello\"}");
        Object during = ((Map<?, ?>) call("health_check", "{}").getData()).get("languages");
        engine.whiteboard().unbindProvider(language);
        Object after = ((Map<?, ?>) call("health_check", "{}").getData()).get("languages");

        assertAll(
            () -> assertEquals(List.of(), before, "no language is bound yet, and the key says so rather than being absent"),
            () -> assertEquals(List.of("fake"), during, "health_check lists the language that bound"),
            () -> assertEquals(List.of(), after, "and stops listing it when it leaves"),
            () -> assertTrue(withIt.contains("fake_echo"), "got: " + withIt),
            () -> assertTrue(echoed.isSuccess(), "got: " + echoed.getError()),
            () -> assertEquals(Map.of("echo", "hello", "language", "fake"), echoed.getData()),
            () -> assertFalse(publishedTools().contains("fake_echo"), "the language left, so its tool did"));
    }

    @Test
    @DisplayName("the neutral tools answer with no Java at all: health, memory, the field recording, the hire path, the refactoring lifecycle")
    void theNeutralToolsAnswer() throws Exception {
        ToolResponse health = call("health_check", "{}");
        ToolResponse memory = call("experience", "{\"kind\":\"stats\"}");
        ToolResponse field = call("field", "{\"action\":\"silence\"}");
        ToolResponse hire = call("hire", "{}");
        ToolResponse refactoring = call("refactoring", "{\"action\":\"inspect\",\"changeId\":\"nothing-staged\"}");

        assertAll(
            () -> assertTrue(health.isSuccess(), "health_check: " + health.getError()),
            () -> assertTrue(memory.isSuccess(), "experience: " + memory.getError()),
            () -> assertTrue(field.isSuccess(), "field: " + field.getError()),
            // The last two are asked for what they cannot give; they must answer with a reason, not with an exception.
            () -> assertNotNull(hire.getError(), "hire with no brief is refused, with a reason"),
            () -> assertNotNull(refactoring.getError(), "a change that was never staged is refused, with a reason"));
    }

    @Test
    @DisplayName("a plain text change is applied under the stand-in language's verdict: one that builds stands, one that does not is undone")
    void aPlainTextChangeIsGatedByTheStandInVerdict() throws Exception {
        FakeLanguageProvider language = new FakeLanguageProvider();
        ProviderContext hostPorts = new ProviderContext() {
            @Override
            public org.jawata.spi.host.Reconciler reconciler() {
                return host.reconciler();
            }

            @Override
            public org.jawata.spi.host.WorkspaceWrites writes() {
                return host.writes();
            }
        };
        ChangeContext context = new ChangeContext(host.writes(), host.reconciler(), language.verdict(hostPorts), language.paths(hostPorts));
        Path folder = Files.createDirectories(scratch.resolve("project"));
        IWorkspace workspace = ResourcesPlugin.getWorkspace();
        IProject project = workspace.getRoot().getProject("stand-in-" + System.nanoTime());
        IProjectDescription description = workspace.newProjectDescription(project.getName());
        description.setLocationURI(folder.toUri());
        project.create(description, null);
        project.open(null);
        try {
            Path good = folder.resolve("good.txt");
            Path bad = folder.resolve("bad.txt");

            GatedApply.Result accepted = GatedApply.perform(new PlainTextChange(good, "nothing wrong here"), context, GatedApply.Mode.UNDO);
            GatedApply.Result refused = GatedApply.perform(new PlainTextChange(bad, "this one is " + FakeBuildVerdict.BROKEN), context,
                GatedApply.Mode.UNDO);

            assertAll(
                () -> assertFalse(accepted.refused(), "got: " + accepted.failure()),
                () -> assertEquals("nothing wrong here", Files.readString(good)),
                () -> assertTrue(refused.refused(), "the verdict said the file does not build; the change named " + refused.outcome().modifiedFilePaths()
                    + ", the platform resolves the path to " + language.paths(hostPorts).fileOf(bad)),
                () -> assertTrue(refused.undone(), "and the change was undone"),
                () -> assertFalse(Files.exists(bad), "so the file the change created is gone"));

            ChangeEngine.perform(accepted.outcome().undoChange(), context);
            assertFalse(Files.exists(good), "undoing the accepted change gives back a folder without the file");
        } finally {
            project.delete(false, true, null);
        }
    }
}
