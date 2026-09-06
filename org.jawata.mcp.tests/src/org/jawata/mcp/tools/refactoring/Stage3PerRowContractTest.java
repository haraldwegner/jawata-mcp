package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.jawata.mcp.tools.RefactorToPatternTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE CONTRACT CLAUSES STAGE 3 CARRIED IN PROSE — asked of all nine rows, and deliberately
 * NOT asking the one that is already asked elsewhere.
 *
 * <p>A C2 audit's blocker B4 found Stages 3 and 5 without a per-row contract test while Stages
 * 4, 6 and 7 each carry one. That is right about the blast radius and about row 8, and it is
 * WRONG about the position clause for this stage's other eight rows — {@link
 * EveryRowIsCallableFromItsFindingTest} has asserted that per row since C3, including which
 * rows confine to a member and which refuse, bound to the list {@code apply_cleanup} publishes.
 * Re-driving those eight from a position here would be a second implementation of a job this
 * repository already does, which is the shape its own architecture review refuses. So this
 * class covers exactly the gap:</p>
 *
 * <ol>
 *   <li><b>the blast radius, for all nine rows</b> — nothing measured it at all;</li>
 *   <li><b>row 8's position form</b> — the ninth row lives on {@code refactor_to_pattern},
 *       so the class above cannot see it and its silence about row 8 is not a pass;</li>
 *   <li><b>the table's own size, and both doors' accounting</b> — so a row cannot be dropped
 *       to make a loop pass.</li>
 * </ol>
 *
 * <h2>Why the sweep form is the RIGHT call for the radius here</h2>
 *
 * <p>{@code apply_cleanup} is a sweep: given a {@code filePath} and no position it rewrites
 * that whole file. That is the WIDEST call each of these eight rows accepts, so a radius
 * measured on it bounds the narrowed call too — if the file-scoped sweep stays inside its
 * file, the member-scoped one cannot escape it. Measuring the narrow form instead would have
 * been the weaker claim wearing the stronger one's clothes.</p>
 *
 * <h2>What the existing per-row tests could NOT see</h2>
 *
 * <p>{@link RowParity#sweepRow} stages ONE fixture and pins the planned diff; {@link
 * RowParity#recipeRow} applies row 8 and pins ONE file. Neither reads a file the row never
 * claimed, so a row that also rewrote an unrelated fixture passes its golden, its unit test
 * and its fork slice alike. A blast radius is only a claim until something compares it
 * against the tree.</p>
 */
class Stage3PerRowContractTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        // DISPOSE BEFORE RELOADING — Stage 4's and Stage 7's copies of this loop record why:
        // every copy lands on the same directory and the helper replaces its service without
        // disposing the previous one, so a loop that reloads per row leaves live stale
        // workspaces behind.
        if (service != null) {
            service.dispose();
        }
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private ApplyCleanupTool cleanupDoor() {
        return new ApplyCleanupTool(() -> service, cache);
    }

    private RefactorToPatternTool patternDoor() {
        return new RefactorToPatternTool(() -> service, cache);
    }

    private String read(Path p) throws Exception {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "(absent)";
    }

    private Map<String, String> snapshotOfPackage() throws Exception {
        Map<String, String> all = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> files = Files.walk(pkg)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                all.put(pkg.relativize(file).toString(), read(file));
            }
        }
        return all;
    }

    private int lineOf(Path file, String marker) throws Exception {
        String[] lines = read(file).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: " + file.getFileName() + " no longer contains "
            + marker + " — the fixture moved and this table is addressing nothing");
    }

    /**
     * One row: the file it acts on, and the files it is allowed to change.
     *
     * @param label the Fowler number and name, so a failure names the row
     * @param fixture the file the row rewrites, which is also the sweep's scope
     * @param touches every file the row may write, create or delete
     */
    private record Row(String label, String fixture, List<String> touches) {}

    /** Stage 3's eight rows on {@code apply_cleanup}, keyed by the kind that reaches each. */
    private static Map<String, Row> sweepRows() {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("consolidate_conditional", new Row("7 Consolidate Conditional Expression",
            "ConsolidateTargets.java", List.of("ConsolidateTargets.java")));
        rows.put("control_flag_to_break", new Row("44 Replace Control Flag with Break",
            "ControlFlagTargets.java", List.of("ControlFlagTargets.java")));
        rows.put("loop_to_pipeline", new Row("50 Replace Loop with Pipeline",
            "PipelineTargets.java", List.of("PipelineTargets.java")));
        rows.put("guard_clauses", new Row("52 Replace Nested Conditional with Guard Clauses",
            "GuardClauseTargets.java", List.of("GuardClauseTargets.java")));
        rows.put("return_modified_value", new Row("60 Return Modified Value",
            "ReturnModifiedValueTargets.java", List.of("ReturnModifiedValueTargets.java")));
        rows.put("slide_declaration", new Row("62 Slide Statements",
            "SlideTargets.java", List.of("SlideTargets.java")));
        rows.put("split_loop", new Row("63 Split Loop",
            "SplitLoopTargets.java", List.of("SplitLoopTargets.java")));
        rows.put("remove_dead_code", new Row("34 Remove Dead Code",
            "DeadCodeTargets.java", List.of("DeadCodeTargets.java")));
        return rows;
    }

    /**
     * Row 8, which is on a different door and needs three names.
     *
     * <p>It moved off {@code apply_cleanup} in the middle of Stage 3, on Harald's word: a sweep
     * accepts no per-call input, so on it this row could only emit {@code if (condition1())},
     * which is worse than the code it replaces. The names are the caller's whole contribution,
     * which is why no default can invent them.</p>
     */
    private static final Row ROW_8 = new Row("8 Decompose Conditional",
        "DecomposeConditionalTargets.java", List.of("DecomposeConditionalTargets.java"));

    /**
     * The two kinds on {@code apply_cleanup} that are NOT Stage 3 rows.
     *
     * <p>They are the "2" in the door's 2 to 10. Naming them is what lets the check below be
     * an equality rather than a containment, so a kind arriving on this door with no row and
     * no exemption fails here instead of passing quietly through a subset check.</p>
     */
    private static final List<String> PRE_EXISTING = List.of("add_final", "redundant_modifiers");

    @Test
    @DisplayName("the table accounts for every row Stage 3 shipped, and for the cleanup door")
    void theTableAccountsForEveryRow() {
        assertEquals(8, sweepRows().size(),
            "Stage 3 puts eight of its nine rows on apply_cleanup — 7, 44, 50, 52, 60, 62, 63"
                + " and 34. A row dropped from this table would make the loops below pass over"
                + " less than the stage");

        // DERIVED on the door's side, hand-written on the stage's side. WHICH rows Stage 3
        // shipped is a fact about the sprint and lives nowhere in the code; what the door
        // publishes is code. A containment would accept both a dropped row and a kind nobody
        // wrote a row for.
        TreeSet<String> expected = new TreeSet<>(sweepRows().keySet());
        expected.addAll(PRE_EXISTING);
        assertEquals(expected, new TreeSet<>(cleanupDoor().publishedKinds()),
            "the eight Stage 3 rows plus " + PRE_EXISTING + " ARE apply_cleanup's ten kinds;"
                + " a difference means either a row is missing from the table above or a kind"
                + " reached the door with no row and no exemption");

        // Row 8 gets CONTAINMENT and not equality, and the asymmetry is honest rather than
        // lazy: refactor_to_pattern publishes eleven kinds of which exactly one is a Stage 3
        // row, so there is no exemption list to write here — the other ten belong to earlier
        // sprints and to Stage 6's lane.
        assertTrue(patternDoor().publishedKinds().contains("decompose_conditional"),
            "row 8 ships as refactor_to_pattern kind=decompose_conditional; if the door stops"
                + " publishing it the row is unreachable: " + patternDoor().publishedKinds());
    }

    @Test
    @DisplayName("row 8 runs from a FILE POSITION — the row the sibling class cannot reach")
    void rowEightRunsFromAFilePosition() throws Exception {
        Path file = pkg.resolve(ROW_8.fixture());
        Map<String, String> was = snapshotOfPackage();

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "decompose_conditional");
        // NO symbol — that absence is the assertion. A finding names a file and a line.
        args.put("filePath", file.toString());
        int line = lineOf(file, "if (date.isBefore");
        args.put("line", line);
        args.put("column", read(file).split("\n", -1)[line].indexOf("if ("));
        args.put("conditionName", "notSummer");
        args.put("thenName", "applyWinterCharge");
        args.put("elseName", "applySummerCharge");

        ToolResponse r = patternDoor().execute(args);
        assertTrue(r.isSuccess(), ROW_8.label() + " must accept a caret — an agent holding a"
            + " long_method finding has a file and a line before it has anything else. Got: "
            + r.getError());
        // PROOF OF WORK, not merely proof of success: a row that resolved the position,
        // decided there was nothing to do and reported success would satisfy isSuccess()
        // while leaving the clause unexercised.
        assertTrue(!was.equals(snapshotOfPackage()),
            ROW_8.label() + " reported success and changed nothing anywhere");
    }

    @Test
    @DisplayName("no row writes, creates or deletes anything outside the files it declares")
    void noRowTouchesAnythingOutsideItsDeclaredFiles() throws Exception {
        List<String> problems = new ArrayList<>();

        for (Map.Entry<String, Row> entry : sweepRows().entrySet()) {
            setUp();
            Row row = entry.getValue();
            ObjectNode args = mapper.createObjectNode();
            args.put("kind", entry.getKey());
            args.put("filePath", pkg.resolve(row.fixture()).toString());
            problems.addAll(radiusOf(row, () -> cleanupDoor().execute(args)));
        }

        setUp();
        Path file = pkg.resolve(ROW_8.fixture());
        int line = lineOf(file, "if (date.isBefore");
        ObjectNode eight = mapper.createObjectNode();
        eight.put("kind", "decompose_conditional");
        eight.put("filePath", file.toString());
        eight.put("line", line);
        eight.put("column", read(file).split("\n", -1)[line].indexOf("if ("));
        eight.put("conditionName", "notSummer");
        eight.put("thenName", "applyWinterCharge");
        eight.put("elseName", "applySummerCharge");
        problems.addAll(radiusOf(ROW_8, () -> patternDoor().execute(eight)));

        assertTrue(problems.isEmpty(),
            "a row's blast radius is a CLAIM, and these are the files each row declares:\n  "
                + String.join("\n  ", problems));
    }

    /** Snapshot the whole package, run the row, and report every touch it did not declare. */
    private List<String> radiusOf(Row row, java.util.function.Supplier<ToolResponse> run)
            throws Exception {
        List<String> problems = new ArrayList<>();
        // THE WHOLE PACKAGE, so the question can be ASKED at all. Each row's own tests and
        // its golden read only the files that row names.
        Map<String, String> was = snapshotOfPackage();

        ToolResponse r = run.get();
        if (!r.isSuccess()) {
            return List.of(row.label() + ": apply refused — " + r.getError());
        }
        Map<String, String> now = snapshotOfPackage();
        if (was.equals(now)) {
            return List.of(row.label() + ": reported success and changed NOTHING, so the"
                + " blast-radius comparison proves nothing about it");
        }
        for (Map.Entry<String, String> touched : now.entrySet()) {
            if (row.touches().contains(touched.getKey())) {
                continue;
            }
            String previously = was.get(touched.getKey());
            if (previously == null) {
                problems.add(row.label() + ": CREATED " + touched.getKey()
                    + ", which it does not declare — a blast radius has to be declared,"
                    + " not discovered");
            } else if (!previously.equals(touched.getValue())) {
                problems.add(row.label() + ": REWROTE " + touched.getKey()
                    + ", which is outside its target");
            }
        }
        for (String gone : was.keySet()) {
            if (!now.containsKey(gone) && !row.touches().contains(gone)) {
                problems.add(row.label() + ": DELETED " + gone + ", outside its target");
            }
        }
        return problems;
    }
}
