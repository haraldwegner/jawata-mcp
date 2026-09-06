package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.HierarchyTool;
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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TWO CONTRACT CLAUSES STAGE 7 CARRIED IN PROSE, ASKED OF ALL FIVE ROWS AT ONCE.
 *
 * <p>The per-row contract asks that each row be "callable straight from the finding that names
 * it, <b>by symbol name and by file position</b>", and that "nothing outside the target [is]
 * touched". Stage 6's audit counted both over its rows and found them unexercised; Stage 4's did
 * the same one stage later, and the cure — {@link Stage4PerRowContractTest} — was written there
 * and then not carried here. <b>This is the third stage to need it, so copying the shape is the
 * cheapest way to stop that being true a fourth time</b>, and it is written BEFORE the checkpoint
 * rather than after an auditor asks.</p>
 *
 * <h2>Stage 7 has no exempt rows</h2>
 *
 * <p>{@link Stage6PerRowContractTest} carries a {@code NO_NAME_FORM} list because five of its
 * rows target something with no name — a statement range, a local. Every Stage 7 row targets a
 * TYPE or a CONSTRUCTOR, and both have a name AND a position, so all five owe both forms. The
 * table's own size is asserted, so a row cannot be quietly dropped to make a loop pass.</p>
 *
 * <h2>Two rows CREATE rather than rewrite, and the radius has to say so</h2>
 *
 * <p>Rows 59 and 56 generate files beside the class they were pointed at and leave that class
 * untouched. A blast-radius check that only looked for REWRITES would pass them while they
 * created anything they liked, so the comparison below treats a created file as a touch and
 * demands it be declared.</p>
 */
class Stage7PerRowContractTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        // DISPOSE BEFORE RELOADING — Stage 4's copy of this loop records why: the helper
        // replaces its service without disposing the previous one and every copy lands on the
        // same directory, so a loop that reloads per row leaves live stale workspaces behind.
        // A NAME form resolves at workspace scope and can be answered by one of them.
        if (service != null) {
            service.dispose();
        }
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private HierarchyTool door() {
        return new HierarchyTool(() -> service, cache);
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
        throw new AssertionError("PROOF OF LIFE: " + file.getFileName() + " no longer declares "
            + marker + " — the fixture moved and this table is addressing nothing");
    }

    /**
     * One row: both ways of addressing it, and the files it is allowed to change.
     *
     * @param label the Fowler number and name, so a failure names the row
     * @param fixture the file the target is declared in
     * @param marker the declaration line, matched as text so a fixture edit fails loudly
     * @param caret the identifier the caret sits on within that line
     * @param nameKey {@code typeName} or {@code symbol}, per what the row targets
     * @param nameValue the fully-qualified target
     * @param touches every file the row may write, create or delete
     */
    private record Row(String label, String fixture, String marker, String caret,
                       String nameKey, String nameValue, List<String> touches) {}

    /** All five Stage 7 rows, keyed by the direction that reaches each. */
    private static Map<String, Row> rows() {
        Map<String, Row> rows = new LinkedHashMap<>();

        rows.put("pull_up_constructor_body", new Row("29 Pull Up Constructor Body",
            "ConstructorBodyTargets.java", "public Contractor(", "Contractor",
            "symbol", "com.example.ConstructorBodyTargets.Contractor#Contractor",
            List.of("ConstructorBodyTargets.java")));

        rows.put("replace_type_code_with_subclasses", new Row(
            "59 Replace Type Code with Subclasses",
            "Enrolment.java", "public class Enrolment", "Enrolment",
            "typeName", "com.example.Enrolment",
            List.of("EnrolmentProvisional.java", "EnrolmentConfirmed.java",
                "EnrolmentSeniorTutor.java")));

        rows.put("replace_superclass_with_delegate", new Row(
            "57 Replace Superclass with Delegate",
            "Petty.java", "public class Petty", "Petty",
            "typeName", "com.example.Petty",
            List.of("Petty.java")));

        rows.put("replace_subclass_with_delegate", new Row(
            "56 Replace Subclass with Delegate",
            "Zeroed.java", "public class Zeroed", "Zeroed",
            "typeName", "com.example.Zeroed",
            List.of("ZeroedBehaviour.java")));

        rows.put("collapse_hierarchy", new Row("4 Collapse Hierarchy",
            "TierBanded.java", "public class TierBanded", "TierBanded",
            "typeName", "com.example.TierBanded",
            List.of("TierBanded.java", "TierBase.java", "TierGold.java", "TierSilver.java",
                "TierDesk.java")));

        return rows;
    }

    @Test
    @DisplayName("the table accounts for every row Stage 7 shipped")
    void theTableAccountsForEveryRow() {
        assertEquals(5, rows().size(),
            "Stage 7 ships five refactorings — 29, 59, 57, 56 and 4. A row dropped from this"
                + " table would make both loops below pass over less than the stage");
        assertTrue(door().publishedKinds().containsAll(rows().keySet()),
            "and every direction this table names must be one the door actually publishes,"
                + " or the loops address nothing: " + door().publishedKinds());
    }

    @Test
    @DisplayName("every row runs from a FILE POSITION, with no symbol and no typeName")
    void everyRowRunsFromAFilePosition() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Row> entry : rows().entrySet()) {
            setUp();
            Row row = entry.getValue();
            Path file = pkg.resolve(row.fixture());
            Map<String, String> was = snapshotOfPackage();

            ObjectNode args = mapper.createObjectNode();
            args.put("direction", entry.getKey());
            // NO symbol, NO typeName — that absence is the assertion. A finding names a file
            // and a line; this is the call it can make without resolving anything first.
            args.put("filePath", file.toString());
            int line = lineOf(file, row.marker());
            args.put("line", line);
            args.put("column", read(file).split("\n", -1)[line].indexOf(row.caret()));

            ToolResponse r = door().execute(args);
            if (!r.isSuccess()) {
                problems.add(row.label() + ": " + r.getError());
                continue;
            }
            // PROOF OF WORK, not merely proof of success. A row that resolved the position,
            // decided there was nothing to do and reported success would satisfy isSuccess()
            // while leaving the clause unexercised. Measured over the whole package, because
            // two of these rows leave the file they were POINTED AT untouched by design.
            if (was.equals(snapshotOfPackage())) {
                problems.add(row.label() + ": reported success and changed nothing anywhere");
            }
        }
        assertTrue(problems.isEmpty(),
            "all five rows publish filePath/line/column and resolve through the shared"
                + " FqnTarget helper, so all five must ACCEPT a caret — an agent holding a"
                + " finding has a file and a line before it has a resolved symbol:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("no row writes, creates or deletes anything outside the files it declares")
    void noRowTouchesAnythingOutsideItsDeclaredFiles() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Row> entry : rows().entrySet()) {
            setUp();
            Row row = entry.getValue();
            // THE WHOLE TREE, so the question can be ASKED. Each row's own tests read only the
            // files that row names, so a row that also rewrote an unrelated fixture would pass
            // every one of them.
            Map<String, String> was = snapshotOfPackage();

            ObjectNode args = mapper.createObjectNode();
            args.put("direction", entry.getKey());
            args.put(row.nameKey(), row.nameValue());

            ToolResponse r = door().execute(args);
            if (!r.isSuccess()) {
                problems.add(row.label() + ": apply refused — " + r.getError());
                continue;
            }

            // Measured on the APPLIED state, which is the only moment it means anything.
            Map<String, String> now = snapshotOfPackage();
            if (was.equals(now)) {
                problems.add(row.label() + ": reported success and changed NOTHING, so the"
                    + " blast-radius comparison below proves nothing about it");
                continue;
            }
            for (Map.Entry<String, String> file : now.entrySet()) {
                if (row.touches().contains(file.getKey())) {
                    continue;
                }
                String previously = was.get(file.getKey());
                if (previously == null) {
                    problems.add(row.label() + ": CREATED " + file.getKey()
                        + ", which it does not declare — a blast radius has to be declared,"
                        + " not discovered");
                } else if (!previously.equals(file.getValue())) {
                    problems.add(row.label() + ": REWROTE " + file.getKey()
                        + ", which is outside its target");
                }
            }
            for (String gone : was.keySet()) {
                if (!now.containsKey(gone) && !row.touches().contains(gone)) {
                    problems.add(row.label() + ": DELETED " + gone + ", outside its target");
                }
            }
        }
        assertTrue(problems.isEmpty(),
            "a row's blast radius is a CLAIM, and these are the files each row declares:\n  "
                + String.join("\n  ", problems));
    }
}
