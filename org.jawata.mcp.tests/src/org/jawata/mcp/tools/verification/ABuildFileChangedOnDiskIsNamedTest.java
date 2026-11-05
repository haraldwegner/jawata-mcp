package org.jawata.mcp.tools.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.jawata.mcp.engine.LanguageAwareReconciler;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.java.JavaBuildVerdict;
import org.jawata.mcp.java.JavaProvider;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RefreshWorkspaceTool;
import org.jawata.mcp.tools.SearchSymbolsTool;
import org.jawata.mcp.tools.ToolRegistry;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.resident.workspace.OwnedReconciler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h S14, item d: when a build file ({@code pom.xml}, {@code build.gradle}, {@code .classpath}, {@code MANIFEST.MF}) changed on disk,
 * the answer of the next tool call NAMES that file and says {@code refresh_workspace} is the next step. The engine still never re-imports a
 * project by itself: it tells, and the caller decides, because a re-import discards open buffers and can take minutes.
 */
class ABuildFileChangedOnDiskIsNamedTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();
    private JdtServiceImpl service;
    private ToolRegistry registry;

    private void wire() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        registry = new ToolRegistry();
        registry.register(new SearchSymbolsTool(() -> service));
        OwnedReconciler host = new OwnedReconciler(() -> service);
        registry.register(new RefreshWorkspaceTool(() -> service, () -> new JavaBuildVerdict(service), () -> host));
        registry.setReconciler(new LanguageAwareReconciler(host, () -> List.of(new JavaProvider())));
    }

    private Path root() {
        return service.allProjects().iterator().next().projectRoot();
    }

    private ToolResponse call(String tool) throws Exception {
        ObjectNode args = mapper.createObjectNode();
        if (tool.equals("search_symbols")) {
            args.put("query", "App");
        }
        ToolResponse answer = registry.callTool(tool, args);
        assertTrue(answer.isSuccess(), tool + " must succeed; got: " + answer.getError());
        return answer;
    }

    private static String said(ToolResponse answer) {
        return answer.getMeta() == null || answer.getMeta().getSteering() == null ? "" : answer.getMeta().getSteering();
    }

    private static void edit(Path file) throws Exception {
        Files.writeString(file, Files.readString(file) + "\n<!-- edited on disk -->\n");
    }

    /** Write the file (when the fixture does not hold it), let the first call take its baseline, change it, and return the next answer's words. */
    private String sayingAfterAChangeTo(String relative) throws Exception {
        wire();
        Path file = root().resolve(relative);
        if (!Files.exists(file)) {
            Files.createDirectories(file.getParent());
            Files.writeString(file, "first\n");
        }
        String quiet = said(call("search_symbols"));
        assertFalse(quiet.contains("refresh_workspace"), "CONTROL: nothing has changed since the baseline, so nothing is said: " + quiet);
        edit(file);
        return said(call("search_symbols"));
    }

    private void assertNamedWithTheNextStep(String relative) throws Exception {
        String said = sayingAfterAChangeTo(relative);
        Path file = root().resolve(relative).toAbsolutePath().normalize();
        assertTrue(said.contains(file.toString()), "the answer names the changed file " + file + ": " + said);
        assertTrue(said.contains("refresh_workspace"), "and names refresh_workspace as the next step: " + said);
    }

    @Test
    @DisplayName("a changed pom.xml is named in the next answer, with refresh_workspace as the next step")
    void aChangedPomIsNamed() throws Exception {
        assertNamedWithTheNextStep("pom.xml");
    }

    @Test
    @DisplayName("a changed build.gradle is named in the next answer, with refresh_workspace as the next step")
    void aChangedBuildGradleIsNamed() throws Exception {
        assertNamedWithTheNextStep("build.gradle");
    }

    @Test
    @DisplayName("a changed .classpath is named in the next answer, with refresh_workspace as the next step")
    void aChangedClasspathIsNamed() throws Exception {
        assertNamedWithTheNextStep(".classpath");
    }

    @Test
    @DisplayName("a changed MANIFEST.MF is named in the next answer, with refresh_workspace as the next step")
    void aChangedManifestIsNamed() throws Exception {
        assertNamedWithTheNextStep("META-INF/MANIFEST.MF");
    }

    @Test
    @DisplayName("a build file that appears after the first look is named too")
    void aBuildFileThatAppearsIsNamed() throws Exception {
        wire();
        call("search_symbols");
        Path appeared = root().resolve("build.gradle");
        Files.writeString(appeared, "plugins { id 'java' }\n");

        String said = said(call("search_symbols"));

        assertTrue(said.contains(appeared.toAbsolutePath().normalize().toString()) && said.contains("refresh_workspace"), said);
    }

    @Test
    @DisplayName("a changed source file and a file that is no build file say nothing about a build file, and the call after a named change says nothing either")
    void anUnrelatedChangeIsNotNamed() throws Exception {
        wire();
        Path source = root().resolve("src/main/java/com/example/Unrelated.java");
        Files.createDirectories(source.getParent());
        Files.writeString(source, "package com.example;\npublic class Unrelated {\n}\n");
        Path settings = root().resolve("settings.gradle");
        Files.writeString(settings, "rootProject.name = 'x'\n");
        Path notes = root().resolve("notes.txt");
        Files.writeString(notes, "notes\n");
        call("search_symbols");

        Files.writeString(source, "package com.example;\npublic class Unrelated {\n    int v() { return 1; }\n}\n");
        edit(settings);
        edit(notes);
        String said = said(call("search_symbols"));

        assertFalse(said.contains("refresh_workspace"), "a source edit, an undeclared gradle file and a text file are not a changed build file: " + said);

        edit(root().resolve("pom.xml"));
        assertTrue(said(call("search_symbols")).contains("refresh_workspace"), "CONTROL: the same call names a changed pom.xml");
        assertFalse(said(call("search_symbols")).contains("refresh_workspace"), "and it is said once: the change is not repeated on the call after it");
    }

    @Test
    @DisplayName("the engine does not re-import on its own: the sync after a changed pom.xml builds and refreshes nothing")
    void aChangedBuildFileIsNotReimportedOrBuilt() throws Exception {
        wire();
        OwnedReconciler sync = new OwnedReconciler(() -> service);
        sync.syncBeforeCall(new JavaProvider().sourceExtensions());   // the baseline
        edit(root().resolve("pom.xml"));

        org.jawata.spi.host.SyncReport report = sync.syncBeforeCall(new JavaProvider().sourceExtensions());

        assertEquals(List.of(root().resolve("pom.xml").toAbsolutePath().normalize().toString()), report.changedBuildFiles(),
            "the report names the changed build file");
        assertEquals(0, report.refreshedFiles(), "and refreshes no file of the model");
        assertEquals(0, report.builtProjects(), "and builds no project: a build over the old classpath would only report the stale answer more slowly");
    }

    @Test
    @DisplayName("refresh_workspace's own answer does not tell the caller to run refresh_workspace")
    void theRefreshAnswerDoesNotAskForARefresh() throws Exception {
        wire();
        call("search_symbols");
        edit(root().resolve("pom.xml"));

        String said = said(call("refresh_workspace"));

        assertFalse(said.contains("Build file changed on disk"), "the remedy is not told to run itself: " + said);
    }
}
