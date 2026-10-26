package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.Path;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.CompileWorkspaceTool;
import org.jawata.mcp.tools.RefreshWorkspaceTool;
import org.jawata.resident.workspace.OwnedReconciler;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28k E10b: the compile check exists once. {@code compile_workspace}, {@code refresh_workspace} and {@code get_diagnostics} read
 * the rows of the language's build verdict instead of sweeping the host's markers themselves, so each row has to carry what those
 * tools told their callers before: how severe, which project, main or test code, where in the file, and the compiler's own ids.
 */
class JavaBuildVerdictRowsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private String projectName;
    private BuildScope scope;
    private BuildVerdict verdict;
    private IFile brokenMain;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void aBuiltProjectWithAMainErrorATestErrorAWarningAndANote() throws Exception {
        service = helper.loadProjectCopy("compile-clean");
        projectName = service.getJavaProject().getProject().getName();
        scope = new BuildScope(List.of(projectName));
        brokenMain = writeBeside("Clean.java", "BrokenMain",
            "    void broken() {\n        int x = ;\n    }\n", "");
        writeBeside("CleanSupport.java", "BrokenInTest", "    void broken() {\n        int x = ;\n    }\n", "");
        writeBeside("Clean.java", "NotedAndUnused", "    // TODO tidy later\n", "import java.util.List;\n");
        new OwnedReconciler(() -> service).rebuild(scope, BuildKind.FULL, new NullProgressMonitor());
        verdict = new JavaBuildVerdict(service);
    }

    @Test
    @DisplayName("every row carries its severity, its project, whether it is test code, and the compiler's position and ids")
    void everyRowCarriesWhatTheToolsTellTheirCallers() {
        List<Diagnostic> rows = verdict.diagnostics(scope, new NullProgressMonitor());

        Diagnostic main = only(rows, "BrokenMain.java", Diagnostic.Severity.ERROR);
        assertEquals(projectName, main.project());
        assertEquals(Diagnostic.TestScope.MAIN, main.scope());
        assertTrue(main.line() > 0, "a marker's line is 1-based and present: " + main);
        assertTrue(main.detail().charStart() >= 0 && main.detail().charEnd() >= main.detail().charStart(),
            "the character range is the compiler's own: " + main);
        assertTrue(main.detail().problemId() >= 0, "the compiler's problem id travels with the row: " + main);

        assertEquals(Diagnostic.TestScope.TEST, only(rows, "BrokenInTest.java", Diagnostic.Severity.ERROR).scope(),
            "an error under the test source root is test code");

        Diagnostic unused = only(rows, "NotedAndUnused.java", Diagnostic.Severity.WARNING);
        assertTrue(unused.message().contains("never used"), "the warning is the compiler's own: " + unused);
        assertEquals(Diagnostic.TestScope.MAIN, unused.scope());
    }

    @Test
    @DisplayName("errors are exactly the error-severity rows of diagnostics, in the same order")
    void errorsAreTheErrorRowsOfDiagnostics() {
        List<Diagnostic> all = verdict.diagnostics(scope, new NullProgressMonitor());
        List<Diagnostic> errors = verdict.errors(scope, new NullProgressMonitor());

        assertEquals(all.stream().filter(d -> d.severity() == Diagnostic.Severity.ERROR).toList(), errors);
        assertTrue(errors.size() >= 2, "both broken files are errors: " + errors);
        assertTrue(all.size() > errors.size(), "the warning is in diagnostics and not in errors: " + all);
    }

    @Test
    @DisplayName("a note in the code is a task row, and is not a problem row")
    void aNoteIsATaskAndNotAProblem() {
        List<Diagnostic> tasks = verdict.tasks(scope, new NullProgressMonitor());

        assertTrue(tasks.stream().anyMatch(t -> t.message().contains("tidy later")
                && t.filePath().endsWith("NotedAndUnused.java")),
            "the to-do comment is a task row: " + tasks);
        assertFalse(verdict.diagnostics(scope, new NullProgressMonitor()).stream()
                .anyMatch(d -> d.message().contains("tidy later")),
            "and the problem rows do not carry it");
    }

    @Test
    @DisplayName("a file asked about alone answers its own rows; a file with none, a non-Java path and no path answer none")
    void aFileAnswersItsOwnRows() {
        String broken = brokenMain.getLocation().toOSString();
        List<Diagnostic> own = verdict.diagnosticsIn(broken);

        assertFalse(own.isEmpty(), "the broken file has rows");
        assertTrue(own.stream().allMatch(d -> d.filePath().endsWith("BrokenMain.java")), "and only its own: " + own);
        assertEquals(verdict.diagnostics(scope, new NullProgressMonitor()).stream()
                .filter(d -> d.filePath() != null && d.filePath().endsWith("BrokenMain.java")).toList(),
            own, "the same rows the project sweep holds for that file");
        assertEquals(List.of(), verdict.diagnosticsIn(brokenMain.getParent().getFile(new Path("Clean.java"))
            .getLocation().toOSString()), "a clean file has none");
        assertEquals(List.of(), verdict.diagnosticsIn("notes.txt"));
        assertEquals(List.of(), verdict.diagnosticsIn(null));
    }

    @Test
    @DisplayName("a project nobody loaded is an error row naming it, never an empty answer that reads as clean")
    void aProjectNobodyLoadedIsAnErrorRowNamingIt() {
        BuildScope missing = new BuildScope(List.of("no-such-project"));

        List<Diagnostic> rows = verdict.diagnostics(missing, new NullProgressMonitor());

        assertEquals(1, rows.size(), rows.toString());
        Diagnostic row = rows.get(0);
        assertEquals(Diagnostic.Severity.ERROR, row.severity());
        assertEquals("no-such-project", row.project());
        assertNull(row.filePath(), "the row is about a project, not a file");
        assertTrue(row.message().contains("no such project"), row.message());
        assertEquals(rows, verdict.errors(missing, new NullProgressMonitor()));
    }

    @Test
    @DisplayName("compile_workspace answers the verdict's rows: errors by default, warnings and notes only when asked, test code only for scope=test")
    void compileWorkspaceAnswersTheRowsThatMeetItsFilters() {
        CompileWorkspaceTool tool = new CompileWorkspaceTool(() -> service, () -> new JavaBuildVerdict(service), () -> new OwnedReconciler(() -> service));

        JsonNode byDefault = answered(tool.execute(mapper.createObjectNode()));
        assertTrue(rowsOf(byDefault).stream().allMatch(d -> "ERROR".equals(d.get("severity").asText())),
            "the default is errors only: " + byDefault);
        assertTrue(ending(rowsOf(byDefault), "BrokenMain.java") && ending(rowsOf(byDefault), "BrokenInTest.java"),
            "both broken files are in it: " + byDefault);
        assertEquals(0, byDefault.get("warningCount").asInt(), "a warning under the threshold is not counted either");
        assertTrue(rowsOf(byDefault).stream().noneMatch(d -> d.get("message").asText().contains("tidy later")),
            "and a note is only there when asked for");

        ObjectNode warnings = mapper.createObjectNode();
        warnings.put("minSeverity", "WARNING");
        JsonNode withWarnings = answered(tool.execute(warnings));
        JsonNode unused = rowsOf(withWarnings).stream()
            .filter(d -> d.get("filePath").asText().endsWith("NotedAndUnused.java")).findFirst().orElse(null);
        assertNotNull(unused, "the warning is there when asked for: " + withWarnings);
        assertEquals("WARNING", unused.get("severity").asText());
        assertTrue(unused.has("charStart") && unused.has("charEnd") && unused.has("line"),
            "with the position the compiler gave it: " + unused);
        assertTrue(withWarnings.get("warningCount").asInt() >= 1);

        ObjectNode notes = mapper.createObjectNode();
        notes.put("includeTaskMarkers", true);
        assertTrue(rowsOf(answered(tool.execute(notes))).stream()
                .anyMatch(d -> d.get("message").asText().contains("tidy later")),
            "the to-do comment is a row when asked for");

        ObjectNode testOnly = mapper.createObjectNode();
        testOnly.put("scope", "test");
        List<JsonNode> inTest = rowsOf(answered(tool.execute(testOnly)));
        assertTrue(ending(inTest, "BrokenInTest.java") && !ending(inTest, "BrokenMain.java"),
            "scope=test keeps the row the verdict marked as test code and drops the main one: " + inTest);
    }

    @Test
    @DisplayName("refresh_workspace answers the verdict's errors and warnings, and no notes")
    void refreshWorkspaceAnswersErrorsAndWarnings() {
        RefreshWorkspaceTool tool = new RefreshWorkspaceTool(() -> service, () -> new JavaBuildVerdict(service), () -> new OwnedReconciler(() -> service));

        JsonNode data = answered(tool.execute(mapper.createObjectNode()));

        assertTrue(data.get("summary").get("errorCount").asInt() >= 2, "both broken files: " + data);
        assertTrue(data.get("summary").get("warningCount").asInt() >= 1, "and the unused import: " + data);
        List<JsonNode> rows = rowsOf(data);
        assertTrue(rows.stream().noneMatch(d -> "INFO".equals(d.get("severity").asText())), "nothing below a warning: " + rows);
        assertTrue(rows.stream().noneMatch(d -> d.get("message").asText().contains("tidy later")), "and no note: " + rows);
        assertTrue(ending(rows, "NotedAndUnused.java"), "the warning is in it: " + rows);
    }

    @Test
    @DisplayName("the runner's pre-flight refuses a broken project with the verdict's error count and the file and line of its first error")
    void theRunnersPreflightReadsTheVerdictsRows() {
        String refusal = RunnerClasspath.buildAndCheck(service.getJavaProject(), new NullProgressMonitor());

        List<Diagnostic> errors = verdict.errors(scope, new NullProgressMonitor());
        Diagnostic first = errors.get(0);
        assertNotNull(refusal, "a project with errors is refused");
        assertTrue(refusal.contains("has " + errors.size() + " compile error(s)"), refusal);
        assertTrue(refusal.contains("First: " + new Path(first.filePath()).lastSegment() + ":" + first.line() + " " + first.message()),
            refusal);
    }

    private JsonNode answered(ToolResponse response) {
        assertTrue(response.isSuccess(), "the tool answers: " + response.getError());
        return mapper.valueToTree(response.getData());
    }

    private static List<JsonNode> rowsOf(JsonNode data) {
        List<JsonNode> rows = new ArrayList<>();
        data.get("diagnostics").forEach(rows::add);
        return rows;
    }

    private static boolean ending(List<JsonNode> rows, String file) {
        return rows.stream().anyMatch(d -> d.has("filePath") && d.get("filePath").asText().endsWith(file));
    }

    private static Diagnostic only(List<Diagnostic> rows, String file, Diagnostic.Severity severity) {
        List<Diagnostic> found = rows.stream()
            .filter(d -> d.filePath() != null && d.filePath().endsWith(file) && d.severity() == severity).toList();
        assertFalse(found.isEmpty(), "a " + severity + " row for " + file + " in " + rows);
        return found.get(0);
    }

    /** A class named {@code typeName} written next to {@code anchorFileName}, with the given body and extra imports. */
    private IFile writeBeside(String anchorFileName, String typeName, String body, String imports) throws Exception {
        IProject project = service.getJavaProject().getProject();
        AtomicReference<IFile> anchor = new AtomicReference<>();
        project.accept(resource -> {
            if (resource instanceof IFile f && anchorFileName.equals(f.getName())) {
                anchor.compareAndSet(null, f);
            }
            return true;
        });
        assertNotNull(anchor.get(), anchorFileName + " must be present in the loaded project");
        IContainer folder = anchor.get().getParent();
        IFile file = folder.getFile(new Path(typeName + ".java"));
        String source = "package com.example;\n" + imports + "public class " + typeName + " {\n" + body + "}\n";
        file.create(new ByteArrayInputStream(source.getBytes(StandardCharsets.UTF_8)), true, new NullProgressMonitor());
        return file;
    }
}
