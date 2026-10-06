package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.DiffRenderer;
import org.jawata.mcp.refactoring.DiffView;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.jawata.mcp.tools.MoveClassTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S3 / D7f — a response the client cannot display is a response that did not
 * arrive.
 *
 * <p>Measured in the field: {@code rename_symbol} answered <strong>167,785 characters</strong>
 * and the client spilled it to a file, losing the success it was reporting. So every
 * apply-shaped response now carries a {@code diffSummary}, and the diff text is capped with
 * {@code truncated:true} when it is cut.</p>
 *
 * <p><strong>BOTH refactoring bases are driven here, deliberately.</strong> jawata has two —
 * {@code AbstractRefactoringTool} and {@code AbstractApplyingRefactoringTool} — and this
 * sprint has already recorded a fix that landed in one of them and looked complete. A test
 * that exercised only one would read exactly the same.</p>
 */
class ADiffIsSummarisedAndCappedTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper objectMapper;
    private Path projectPath;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        objectMapper = new ObjectMapper();
        projectPath = service.getProjectRoot();
    }

    // ================= the rule, as pure logic =================

    @Test
    @DisplayName("the summary counts hunks and lines per file, from the renderer's own format")
    void theSummaryAccountsForEveryFile() {
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("A.java", "one\ntwo\nthree\n", "one\nTWO\nthree\n"),
            new DiffRenderer.FileDiff("B.java", "keep\n", "keep\nadded\n")));

        List<DiffRenderer.FileSummary> files = DiffRenderer.summarize(diff);
        assertEquals(2, files.size(), () -> "both files must be accounted for: " + diff);
        assertEquals("A.java", files.get(0).filePath());
        assertEquals(1, files.get(0).hunks());
        assertEquals(1, files.get(0).added(), () -> "one line replaced: " + diff);
        assertEquals(1, files.get(0).removed(), () -> "one line replaced: " + diff);
        assertEquals("B.java", files.get(1).filePath());
        assertEquals(1, files.get(1).added());
        assertEquals(0, files.get(1).removed(), () -> "nothing was removed from B: " + diff);
    }

    @Test
    @DisplayName("a file whose content did not change contributes no row")
    void anUnchangedFileIsNotAFile() {
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("Same.java", "x\n", "x\n")));
        assertEquals("", diff, "the renderer's own documented behaviour");
        assertEquals(List.of(), DiffRenderer.summarize(diff),
            "a summary of nothing is no rows, not a row of zeroes");
    }

    @Test
    @DisplayName("an ADDED line that looks like the '+++' half of a header is not read as one")
    void anAddedLineThatLooksLikeAHeaderIsNotOne() {
        // A Java line `++ b/scale;` renders as `+++ b/scale;`, which looks like half a
        // header. WHAT PROTECTS THIS CASE IS THE `--- a/` REQUIREMENT, not the pair check:
        // a file is only ever opened at a line starting `--- a/`, so this line is never a
        // candidate. Mutation C proved it — weakening the pair check to a prefix check left
        // this test green, which is why its message no longer credits the pair check.
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("Real.java", "int b = 1;\n", "int b = 1;\n++ b/scale;\n")));

        List<DiffRenderer.FileSummary> files = DiffRenderer.summarize(diff);
        assertEquals(1, files.size(),
            () -> "exactly one real file, whatever the added line looks like: " + diff);
        assertEquals("Real.java", files.get(0).filePath(),
            () -> "a file is opened only at a `--- a/` line: " + diff);
        assertEquals(1, files.get(0).added(), () -> "the look-alike line IS an addition: " + diff);
    }

    @Test
    @DisplayName("a '--- a/X' line NOT followed by its own '+++ b/X' does not open a file")
    void anUnpairedMinusLineDoesNotOpenAFile() {
        // THIS is the case the pair check buys, and it took two attempts to reach — both
        // recorded, because the clause is narrower than it looks. The mutation only bites
        // where a `--- a/X` body line is followed by a `+++ b/` line naming a DIFFERENT
        // path, which needs a REPLACED line rather than a deleted one: a removed
        // `-- a/Ghost.java` whose replacement is an added `++ b/Other.java`. With only a
        // deletion the next line is ordinary context, and a prefix check declines it too.
        String oldText = "-- a/Ghost.java\nkeep\n";
        String newText = "++ b/Other.java\nkeep\n";
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("Real.java", oldText, newText)));

        List<DiffRenderer.FileSummary> files = DiffRenderer.summarize(diff);
        assertEquals(1, files.size(),
            () -> "only Real.java is a file here; Ghost.java is a line of its content: " + diff);
        assertEquals("Real.java", files.get(0).filePath(), () -> "wrong file opened: " + diff);
        assertEquals(1, files.get(0).removed(),
            () -> "the look-alike line IS the removal being reported: " + diff);
        assertEquals(1, files.get(0).added(),
            () -> "and its replacement IS the addition being reported: " + diff);
    }

    @Test
    @DisplayName("under the cap nothing is truncated, and `truncated` is absent rather than false")
    void asmallDiffIsNotCapped() {
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("A.java", "a\n", "b\n")));
        Map<String, Object> data = new LinkedHashMap<>();
        DiffView.of(diff, false).putInto(data);

        assertEquals(diff, data.get("diff"), "a diff under the cap arrives whole");
        assertNotNull(data.get("diffSummary"), "the summary is unconditional");
        assertNull(data.get("truncated"),
            "a caller reading a boolean to learn nothing happened is doing the tool's work");
    }

    @Test
    @DisplayName("over the cap the text is cut, truncated says so, and the summary still covers all")
    void aLargeDiffIsCappedAndSaysSo() {
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("Big.java", "x\n", longBody()),
            new DiffRenderer.FileDiff("Tail.java", "y\n", "z\n")));
        assertTrue(diff.length() > DiffView.DEFAULT_MAX_CHARS,
            () -> "the fixture must actually exceed the cap, else this measures nothing; "
                + "length was " + diff.length());

        Map<String, Object> data = new LinkedHashMap<>();
        DiffView.of(diff, false).putInto(data);

        assertEquals(DiffView.DEFAULT_MAX_CHARS, String.valueOf(data.get("diff")).length(),
            "the text is cut AT the cap");
        assertEquals(Boolean.TRUE, data.get("truncated"));
        assertEquals(diff.length(), data.get("diffLength"),
            "the caller is told how much there was, not merely that there was more");

        // THE CLAUSE THAT MAKES THE CAP SAFE: Tail.java's hunk sits far past the cut, so a
        // summary built from the TRUNCATED text would lose it entirely.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) data.get("diffSummary");
        assertEquals(2, summary.size(),
            () -> "diffSummary must account for every file, including those below the cut: "
                + summary);
        assertEquals("Tail.java", summary.get(1).get("filePath"));
    }

    @Test
    @DisplayName("fullDiff returns the whole text")
    void fullDiffIsTheEscape() {
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("Big.java", "x\n", longBody())));
        Map<String, Object> data = new LinkedHashMap<>();
        DiffView.of(diff, true).putInto(data);

        assertEquals(diff, data.get("diff"), "fullDiff means all of it");
        assertNull(data.get("truncated"), "nothing was cut, so nothing is claimed cut");
    }

    // ================= the WIRING, on both bases =================

    @Test
    @DisplayName("AbstractRefactoringTool's response carries diffSummary")
    void theNonApplyingBaseIsWired() throws Exception {
        Path source = projectPath.resolve("src/main/java/com/example/NestedPromotionTargets.java");
        assertTrue(Files.exists(source), "fixture must exist");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", source.toString());
        args.put("line", lineOf(source, "class Standalone {"));
        args.put("column", columnOf(source, "class Standalone {") + "class ".length());
        args.put("targetPackage", "com.example");
        args.put("auto_apply", false);

        MoveClassTool tool = new MoveClassTool(() -> service, new RefactoringChangeCache());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "staging failed: " + describe(r));
        assertSummarised(r, "move kind=class (AbstractRefactoringTool)");
    }

    @Test
    @DisplayName("AbstractApplyingRefactoringTool's response carries diffSummary")
    void theApplyingBaseIsWired() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "add_final");
        args.put("filePath", projectPath.resolve(
            "src/main/java/com/example/NestedPromotionTargets.java").toString());
        args.put("auto_apply", false);

        ApplyCleanupTool tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "staging failed: " + describe(r));
        assertSummarised(r, "apply_cleanup (AbstractApplyingRefactoringTool)");
    }

    @Test
    @DisplayName("and the cap is REACHED through a real response, not only in the rule above")
    void theCapIsReachableInProduction() {
        // Without this the truncation branch is a published behaviour that nothing in the
        // product can be shown to produce — the "declared, never exercised" shape this
        // sprint has refused repeatedly. A whole-project sweep is the honest way to a diff
        // past the cap: 188 fixture sources, no filePath, so the tool decides the scope.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "add_final");
        args.put("auto_apply", false);

        ApplyCleanupTool tool = new ApplyCleanupTool(() -> service, new RefactoringChangeCache());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "the sweep failed: " + describe(r));

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        Object length = data.get("diffLength");
        assertNotNull(length, () -> "a whole-project sweep must exceed the cap, else this "
            + "test measures nothing; the response carried keys " + data.keySet()
            + " and a diff of " + String.valueOf(data.get("diff")).length() + " characters");
        assertEquals(Boolean.TRUE, data.get("truncated"));
        assertEquals(DiffView.DEFAULT_MAX_CHARS, String.valueOf(data.get("diff")).length(),
            "a real response is cut at the same cap as the rule");

        // AND THE SUMMARY OUTLIVES THE CUT, which is the whole point of having one: it
        // accounts for more files than the truncated text even mentions.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) data.get("diffSummary");
        assertTrue(summary.size() > DiffRenderer.summarize(
                String.valueOf(data.get("diff"))).size(),
            () -> "the summary must cover files the cut text does not: " + summary.size()
                + " rows against " + DiffRenderer.summarize(
                    String.valueOf(data.get("diff"))).size() + " visible in the cut diff");
    }

    /**
     * The clause both base tests share: the response carries a {@code diffSummary} whose rows
     * account for the diff the SAME response carries, and says it is unverified.
     *
     * <p><strong>The count is compared against a number this test derives itself</strong>, and
     * that is a C3 repair rather than the original design. The first version asserted
     * {@code assertEquals(DiffRenderer.summarize(diff).size(), summary.size())} under a comment
     * claiming <em>"a summary of some other change would pass"</em> a mere presence check. Both
     * reviews found it independently and both were right: {@code summary} was PRODUCED by
     * {@code DiffView.putInto} calling {@code DiffRenderer.summarize} on that same text, so the
     * two sides were byte-identical inputs to one deterministic function. It could not fail,
     * and {@code assertNotNull(raw)} above already proved everything it proved.</p>
     *
     * <p>Counting the {@code --- a/} openers in the text is independent of the function under
     * test. Its one divergence is named rather than left to be discovered: {@code summarize}
     * requires a {@code --- a/} to be FOLLOWED by a {@code +++ b/} before it opens a file, so a
     * body line that merely looks like an opener would be counted here and not there. That
     * exact shape is owned by {@code anUnpairedMinusLineDoesNotOpenAFile}; a real staged diff
     * from either base does not contain it, and if one ever does, this disagreeing is the
     * finding rather than the flake.</p>
     */
    private static void assertSummarised(ToolResponse r, String where) {
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        Object raw = data.get("diffSummary");
        assertNotNull(raw, () -> where + " answered without a diffSummary: " + data.keySet());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> summary = (List<Map<String, Object>>) raw;
        String diff = String.valueOf(data.get("diff"));
        assertFalse(summary.isEmpty(),
            () -> where + " changed something, so its summary cannot be empty: " + diff);

        int counted = 0;
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("--- a/")) {
                counted++;
            }
        }
        final int openers = counted;
        assertEquals(openers, summary.size(),
            () -> where + "'s summary must have one row per file the diff opens — counted "
                + openers + " '--- a/' openers against " + summary.size() + " rows: " + diff);

        for (Map<String, Object> row : summary) {
            assertNotNull(row.get("filePath"), () -> "a row with no file: " + row);
            assertNotNull(row.get("hunks"), () -> "a row with no hunk count: " + row);
            assertTrue(diff.contains(String.valueOf(row.get("filePath"))),
                () -> where + ": the summary names a file the diff does not: " + row);
        }

        // F4: A STAGED RESPONSE SAYS IT IS UNVERIFIED. Both bases return from their staged
        // branch before the compile gate runs, and the APPLIED branch writes compileVerified
        // while this one wrote no verification claim at all — so a caller comparing the two
        // found the key on one and absent on the other, which reads as verified by omission.
        // Asserted HERE because both base tests share this helper, so neither base can gain
        // the claim without the other being checked for it.
        assertEquals(Boolean.FALSE, data.get("compileVerified"),
            () -> where + " staged a change without saying it is unverified: " + data.keySet());
        assertNotNull(data.get("verificationNote"),
            () -> where + " claims compileVerified:false and does not say why, which is the "
                + "bare-boolean shape this codebase answers with a reason: " + data.keySet());
    }

    /** Enough changed lines to pass the cap; built, not pasted, so the number is visible. */
    private static String longBody() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 2000; i++) {
            sb.append("line ").append(i).append(" of a diff that exceeds the cap\n");
        }
        return sb.toString();
    }

    private static int lineOf(Path file, String needle) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).contains(needle)) return i;
        }
        throw new AssertionError("fixture no longer contains '" + needle + "'");
    }

    private static int columnOf(Path file, String needle) throws Exception {
        List<String> lines = Files.readAllLines(file);
        for (String line : lines) {
            int at = line.indexOf(needle);
            if (at >= 0) return at;
        }
        throw new AssertionError("fixture no longer contains '" + needle + "'");
    }

    private static String describe(ToolResponse r) {
        return r.isSuccess() ? String.valueOf(r.getData())
            : r.getError().getCode() + " / " + r.getError().getMessage();
    }
}
