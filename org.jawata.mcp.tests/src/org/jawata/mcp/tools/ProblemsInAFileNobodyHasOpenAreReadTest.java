package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * REL44-26 — a file's compile problems are read even though no editor has it open.
 *
 * <p>{@code ICompilationUnit.reconcile} answers {@code null} for a unit that is not a working
 * copy, and in this headless server no file ever is one. GetDiagnosticsTool learned that in
 * Sprint 21e and reads build markers instead; three other readers never did, and turned the
 * {@code null} into "no problems": {@code quick_fix list} answered {@code problemCount=0,
 * fixes=[]} at the line where the compiler said "Set cannot be resolved to a type", and the
 * two analyze kinds reported a file with errors as {@code errorCount 0}. This is the CLASS:
 * every tool that reads problems through {@code reconcile}'s AST, driven on one broken file.</p>
 *
 * <p>The clean twin is the control. A reader that reported an error on every file would pass
 * the broken half; it cannot pass both.</p>
 */
class ProblemsInAFileNobodyHasOpenAreReadTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;
    private Path broken;
    private Path clean;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        mapper = new ObjectMapper();
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        broken = pkg.resolve("NeedsAFix.java");
        Files.writeString(broken, """
            package com.example;

            public class NeedsAFix {
                public Object make() {
                    return new ArrayList<String>();
                }
            }
            """);
        clean = pkg.resolve("NeedsNothing.java");
        Files.writeString(clean, """
            package com.example;

            import java.util.ArrayList;

            public class NeedsNothing {
                public Object make() {
                    return new ArrayList<String>();
                }
            }
            """);
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "the tool answered with an error: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("REL44-26: quick_fix list sees the problem at its line and offers the import")
    @SuppressWarnings("unchecked")
    void quickFixListSeesTheProblem() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", broken.toString());
        args.put("line", 4);   // zero-based: the `return new ArrayList` line
        Map<String, Object> d = data(new GetQuickFixesTool(() -> service).execute(args));

        List<Map<String, Object>> fixes = (List<Map<String, Object>>) d.get("fixes");
        assertAll(
            () -> assertTrue(((Number) d.get("problemCount")).intValue() >= 1,
                "the compiler's error on this line must be seen: " + d),
            () -> assertTrue(fixes.stream().anyMatch(f -> "add_import:java.util.ArrayList"
                    .equals(f.get("fixId"))),
                "and the import that fixes it offered: " + d));
    }

    @Test
    @DisplayName("REL44-26: analyze kind=file counts the error, and none on the clean twin")
    @SuppressWarnings("unchecked")
    void analyzeFileCountsTheError() {
        Map<String, Object> brokenDiag = (Map<String, Object>) data(new AnalyzeFileTool(
            () -> service).execute(fileArgs(broken))).get("diagnostics");
        Map<String, Object> cleanDiag = (Map<String, Object>) data(new AnalyzeFileTool(
            () -> service).execute(fileArgs(clean))).get("diagnostics");
        assertAll(
            () -> assertTrue(((Number) brokenDiag.get("errorCount")).intValue() >= 1,
                "a file the compiler rejects must not be reported as clean: " + brokenDiag),
            () -> assertEquals(0, ((Number) cleanDiag.get("errorCount")).intValue(),
                "the control: a file that compiles reports no errors: " + cleanDiag));
    }

    @Test
    @DisplayName("REL44-26: analyze kind=type says its file has problems")
    @SuppressWarnings("unchecked")
    void analyzeTypeSaysItsFileHasProblems() {
        ObjectNode args = mapper.createObjectNode();
        args.put("typeName", "com.example.NeedsAFix");
        args.put("includeUsages", false);
        Map<String, Object> diag = (Map<String, Object>) data(new AnalyzeTypeTool(
            () -> service).execute(args)).get("diagnostics");
        assertAll(
            () -> assertTrue(((Number) diag.get("errorCount")).intValue() >= 1,
                "the type's file has an error: " + diag),
            () -> assertEquals(true, diag.get("hasProblems"), "and says so: " + diag));
    }

    /**
     * REL44-30 — the one case the shared parse FORCES problem detection for: a working copy.
     *
     * <p>On a unit nobody has open the problems arrive without forcing: with it off, every test
     * above stayed green. The comments said forcing was needed because a resolved AST can be
     * served with no problems attached, and the case that can happen in is a WORKING COPY — the
     * shape a refactoring holds while it edits. This opens the broken file as one and reads its
     * problems through the same parse.</p>
     */
    @Test
    @DisplayName("REL44-30: a broken unit held as a working copy still has its problems read")
    void aWorkingCopysProblemsAreRead() throws Exception {
        org.eclipse.jdt.core.ICompilationUnit copy =
            service.getCompilationUnit(broken).getWorkingCopy(null);
        try {
            org.eclipse.jdt.core.dom.CompilationUnit ast =
                org.jawata.mcp.tools.shared.CompilationProblems.parse(copy);
            assertTrue(ast != null && ast.getProblems().length >= 1,
                () -> "a working copy of a file the compiler rejects must not read as clean: "
                    + (ast == null ? "no tree" : List.of(ast.getProblems())));
        } finally {
            copy.discardWorkingCopy();
        }
    }

    private ObjectNode fileArgs(Path file) {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        return args;
    }
}
