package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 8 D2 — WHAT THE PACKAGE THIS FILE LIVES IN IS FOR.
 *
 * <p>The orientation an agent wants the first time it opens a file in a part of the
 * codebase it has not been in: the area describing that package, and the jobs inside it.</p>
 *
 * <h2>The two halves have different owners, and that is the whole design</h2>
 *
 * <p>PATH → PACKAGE is JDT's: which package a file declares is a fact about source roots,
 * and a caller reading it off the directory names would be guessing at a layout it cannot
 * see. That half is why this is an engine action at all.</p>
 *
 * <p>PACKAGE → ROWS is the ordinary recall, unchanged. Its package cue already fits BOTH an
 * entry that governs the package (an area, which carries {@code packageName}) and one
 * holding a symbol inside it (a job, which carries only {@code symbolFqn}) — and the second
 * half is the one a reader would doubt, so it has its own case below.</p>
 */
class DescribeAreaTest {

    private static final String PKG = "com.example";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("compile-clean");
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> service, store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    /** A real source file of the loaded fixture — taken from the service, not composed. */
    private String aJavaFile() {
        java.util.List<Path> files = service.getAllJavaFiles();
        assertFalse(files.isEmpty(),
            "PROOF OF LIFE: the fixture must hold source, or every assertion below runs"
                + " over a project with nothing in it");
        return files.get(0).toString();
    }

    private ToolResponse area(String filePath) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "area");
        a.put("filePath", filePath);
        return tool.execute(a);
    }

    private String areaText(String filePath) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "area");
        a.put("filePath", filePath);
        a.put("format", "text");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        return String.valueOf(r.getData());
    }

    private void recordArea(String pkg, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "area");
        a.put("summary", summary);
        a.putArray("packages").add(pkg);
        assertTrue(tool.execute(a).isSuccess(), "the area must be recordable");
    }

    private void recordJob(String symbol, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", symbol);
        a.put("summary", summary);
        assertTrue(tool.execute(a).isSuccess(), "the job must be recordable");
    }

    @Test
    @DisplayName("a file's own package is derived and named back")
    @SuppressWarnings("unchecked")
    void a_files_package_is_derived_and_named_back() {
        ToolResponse r = area(aJavaFile());

        assertTrue(r.isSuccess(), "got " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(PKG, data.get("package"),
            "the package is JDT's answer about this file, and the caller needs it back to"
                + " memo on: " + data.keySet());
    }

    @Test
    @DisplayName("the area written for that package is what comes back")
    void the_area_written_for_that_package_comes_back() {
        recordArea(PKG,
            "The lane between an agent's questions and what this machine already learned.");

        assertTrue(areaText(aJavaFile()).contains("what this machine already learned"),
            "opening a file in a described package answers with that description");
    }

    /**
     * THE HALF A READER WOULD DOUBT. A job row carries no package at all — only a symbol —
     * so "the package's jobs" works solely because the recall's package cue also fits an
     * entry whose SYMBOL lies inside it. Without this case, the action could ship answering
     * with areas alone and look correct.
     */
    @Test
    @DisplayName("and so are the jobs inside it, which carry no package of their own")
    void and_so_are_the_jobs_inside_it() {
        recordJob(PKG + ".Clean#greet",
            "Answers the caller with a greeting built from the name it was handed.");

        assertTrue(areaText(aJavaFile()).contains("built from the name it was handed"),
            "a job anchored in the package is part of what that package IS");
    }

    /**
     * A FILE IN NO LOADED PROJECT IS NAMED, not answered with nothing.
     *
     * <p>"This file is outside every loaded project" and "this package has nothing written
     * about it" are different facts, and a caller handed an empty answer for the first will
     * read it as the second — which is the confusion this whole retrieval path exists to
     * prevent.
     */
    @Test
    @DisplayName("a path outside every loaded project is refused by name")
    void a_path_outside_every_loaded_project_is_refused() {
        ToolResponse r = area("/nowhere/at/all/Absent.java");

        assertFalse(r.isSuccess(), "an unknown file is not an empty area: " + r.getData());
        String message = String.valueOf(r.getError());
        assertTrue(message.contains("/nowhere/at/all/Absent.java"),
            "and the refusal names the path, so the caller can see what was asked: "
                + message);
    }

    /**
     * THE CONTROL for the case above: an undescribed package is an ordinary ABSENCE and
     * succeeds. Without it, a refusal for every input would satisfy that test.
     */
    @Test
    @DisplayName("a package nobody has described is an absence, not a failure")
    void a_package_nobody_has_described_is_an_absence() {
        ToolResponse r = area(aJavaFile());

        assertTrue(r.isSuccess(),
            "nothing is recorded, and that is a normal answer rather than an error: "
                + r.getError());
    }
}
