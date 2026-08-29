package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractTool;
import org.jawata.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d S8.10 — Extract Class ACROSS A FILE BOUNDARY.
 *
 * <p><b>The question, and why nothing had asked it.</b> Extract Class shipped in
 * Stage 7 moving a field cluster into a new class and rewriting the accesses. It
 * had only ever been shown doing that inside the DECLARING FILE. The dossier
 * recorded that as a disclosed coverage boundary; Harald's ruling closed it by
 * proof rather than by documentation — <i>"within the same file would make this
 * refactoring only halfway"</i>. A field move that does not follow its readers is
 * not a move.</p>
 *
 * <p><b>The omission was STRUCTURAL, not an oversight.</b> Both existing
 * before-cases declare the moved fields {@code private}. A private field cannot be
 * read from another file at all, so no test built on those fixtures could have
 * covered this — which is why it survived a checkpoint and an audit. The fixture
 * here makes them package-private: the narrowest visibility a second file can
 * reach, therefore the narrowest shape that can ask the question.</p>
 *
 * <p><b>What decides it.</b> The second file is parsed WITH BINDINGS and its error
 * count asserted at zero. That is the assertion that cannot be fooled: if the
 * fields moved and their readers did not follow, every one of them resolves to
 * nothing and the count is non-zero. A textual assertion could not take its place,
 * because the correct rewritten form is the engine's to choose — a holder field, an
 * accessor, a delegate — and asserting one spelling would fail a correct result.</p>
 *
 * <p><b>A red here is the first measurement, not a setback.</b> This is a question
 * the codebase has never answered. Either outcome is reported.</p>
 */
class ExtractClassAcrossFilesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ExtractTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper mapper;
    private Path projectRoot;
    private Path pkgDir;
    private Path measurementFile;
    private Path reportFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("extract-crossfile");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new ExtractTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        projectRoot = helper.getTempDirectory().resolve("extract-crossfile");
        pkgDir = projectRoot.resolve("src/main/java/com/example/crossfile");
        measurementFile = pkgDir.resolve("Measurement.java");
        reportFile = pkgDir.resolve("Report.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private long compileErrors(Path file) throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(file);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return java.util.Arrays.stream(ast.getProblems()).filter(IProblem::isError).count();
    }

    /**
     * The caret, DERIVED rather than hardcoded. A line number frozen into a test
     * silently starts pointing somewhere else the first time the fixture's javadoc
     * gains a sentence, and the test then measures whatever is now on that line.
     */
    private ObjectNode args() throws Exception {
        String source = Files.readString(measurementFile);
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            int col = lines[i].indexOf("class Measurement");
            if (col >= 0) {
                ObjectNode n = mapper.createObjectNode();
                n.put("kind", "class");
                n.put("filePath", measurementFile.toString());
                n.put("line", i);
                n.put("column", col + "class ".length());
                n.put("newTypeName", "MeasurementMeta");
                ArrayNode fields = n.putArray("fields");
                fields.add("label");
                fields.add("unit");
                return n;
            }
        }
        throw new AssertionError("fixture no longer declares Measurement");
    }

    /**
     * THE STAGE'S QUESTION: does the rewrite follow the fields' readers into a file
     * the operation was never pointed at?
     */
    @Test
    @DisplayName("S8.10: the move follows its readers into the second file, and both compile")
    void theRewriteFollowsReadersIntoTheSecondFile() throws Exception {
        String reportBefore = Files.readString(reportFile);
        // PROOF OF LIFE, on the SECOND file specifically. Every assertion below is
        // about that file changing correctly; if it never referenced the moved state,
        // a green would mean the fixture stopped asking the question.
        assertTrue(reportBefore.contains("m.label") && reportBefore.contains("m.unit"),
            "the second file must read both moved fields through a parameter");
        assertTrue(reportBefore.contains("current.unit"),
            "the second file must read a moved field through a LOCAL too — a rewrite"
                + " keyed on parameter names would pass without this");
        assertTrue(reportBefore.contains("m.label = newLabel"),
            "the second file must WRITE a moved field — a read-only rewrite would miss"
                + " it, and the write is the reference most likely to be dropped");

        ToolResponse r = tool.execute(args());
        assertTrue(r.isSuccess(), () -> "extract(kind=class) failed: " + r.getError());

        Path created = pkgDir.resolve("MeasurementMeta.java");
        assertTrue(Files.exists(created),
            "the extracted class must exist as its own file");
        String extracted = Files.readString(created);
        assertTrue(extracted.contains("label") && extracted.contains("unit"),
            () -> "the moved fields must live in the extracted class now:\n" + extracted);

        // THE ANSWER, in two halves. First: the second file was touched at all. If the
        // operation rewrites only the declaring file, this is where that shows, and it
        // shows as a plain fact rather than as a confusing compile error.
        String reportAfter = Files.readString(reportFile);
        assertFalse(reportBefore.equals(reportAfter),
            () -> "THE SECOND FILE WAS NOT TOUCHED. The fields moved out of Measurement"
                + " and their readers here were left pointing at state that no longer"
                + " exists — the operation's guarantee stops at the file boundary:\n"
                + reportAfter);

        // Second: it was touched CORRECTLY. Parsed with bindings, so a reference that
        // resolves to nothing is an error rather than a plausible-looking string.
        assertEquals(0, compileErrors(reportFile),
            () -> "the SECOND file must still compile. A non-zero count here means the"
                + " references were rewritten into something that does not resolve:\n"
                + reportAfter);
        assertEquals(0, compileErrors(measurementFile),
            () -> "the DECLARING file must still compile — its own in-file reader has to"
                + " follow the move as well:\n" + readQuietly(measurementFile));
        assertEquals(0, compileErrors(created),
            () -> "the EXTRACTED class must compile:\n" + extracted);
    }

    /** Undo, across BOTH files: a two-file change owes a two-file way back. */
    @Test
    @DisplayName("S8.10: undo restores both files byte-identically and removes the new one")
    void undoRestoresBothFiles() throws Exception {
        String measurementBefore = Files.readString(measurementFile);
        String reportBefore = Files.readString(reportFile);
        Path created = pkgDir.resolve("MeasurementMeta.java");

        ToolResponse r = tool.execute(args());
        assertTrue(r.isSuccess(), () -> "extract(kind=class) failed: " + r.getError());
        String undoId = (String) getData(r).get("undoChangeId");
        assertNotNull(undoId, "an applied refactoring owes an undo handle");
        assertFalse(reportBefore.equals(Files.readString(reportFile)),
            "precondition: the second file must have changed, or restoring it passes"
                + " for free and this test proves nothing");

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", undoId));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));

        assertEquals(measurementBefore, Files.readString(measurementFile),
            "undo must restore the DECLARING file byte-identically");
        assertEquals(reportBefore, Files.readString(reportFile),
            "undo must restore the SECOND file byte-identically. This is the half a"
                + " single-file undo would silently skip: the operation reached a file"
                + " the caller never named, so the way back has to reach it too");
        assertFalse(Files.exists(created),
            "undo must delete the created class — leaving it is a state that is neither"
                + " before nor after");
    }

    /**
     * PURITY, and the boundary it draws is different here than in the single-file
     * case: the operation is EXPECTED to modify a file the caller did not name, so
     * "it touched only what was named" is the wrong claim. The right one is that it
     * touched only what it had a reason to — the declaring file, the readers, and
     * the class it created.
     */
    @Test
    @DisplayName("S8.10: the operation touches no Java source beyond the two files and the new one")
    void theOperationTouchesNothingElse() throws Exception {
        Path created = pkgDir.resolve("MeasurementMeta.java");
        Map<Path, String> before = javaSourceSnapshot(projectRoot);
        assertTrue(before.size() > 1,
            "PROOF OF LIFE: the snapshot must see more than the file being changed, or"
                + " 'nothing else was touched' is a claim about an empty set");

        ToolResponse r = tool.execute(args());
        assertTrue(r.isSuccess(), () -> "extract(kind=class) failed: " + r.getError());

        Map<Path, String> after = javaSourceSnapshot(projectRoot);

        Set<Path> added = new TreeSet<>(after.keySet());
        added.removeAll(before.keySet());
        assertEquals(Set.of(created), added,
            "exactly one Java source may appear, and it is the extracted class");

        Set<Path> removed = new TreeSet<>(before.keySet());
        removed.removeAll(after.keySet());
        assertEquals(Set.of(), removed, "the operation must delete no Java source");

        Set<Path> changed = new TreeSet<>();
        for (Map.Entry<Path, String> e : before.entrySet()) {
            String now = after.get(e.getKey());
            if (now != null && !now.equals(e.getValue())) {
                changed.add(e.getKey());
            }
        }
        assertEquals(Set.of(measurementFile, reportFile), changed,
            "exactly two pre-existing sources may differ: the file the fields were"
                + " extracted FROM, and the file that reads them. Anything more is the"
                + " operation reaching outside the scope the move gives it");
    }

    /** Every .java under the project, mapped to its exact bytes. */
    private static Map<Path, String> javaSourceSnapshot(Path root) throws Exception {
        Map<Path, String> snapshot = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path p : walk.filter(Files::isRegularFile)
                              .filter(p -> p.toString().endsWith(".java"))
                              .toList()) {
                // ISO-8859-1 is a lossless byte-to-char mapping, so string equality
                // here IS byte equality — a reformat cannot slip through as "equal".
                snapshot.put(p, new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));
            }
        }
        return snapshot;
    }

    private static String readQuietly(Path p) {
        try {
            return Files.readString(p);
        } catch (Exception e) {
            return "<unreadable: " + e + ">";
        }
    }
}
