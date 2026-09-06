package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.DataTool;
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
 * THE TWO CONTRACT CLAUSES STAGE 5 CARRIED IN PROSE, ASKED OF ALL NINE ROWS AT ONCE.
 *
 * <p>The per-row contract asks that each row be "callable straight from the finding that names
 * it, <b>by symbol name and by file position</b>", and that "nothing outside the target [is]
 * touched". Stages 4, 6 and 7 each carry a class asserting both over their own rows; Stage 5
 * never got one, which a C2 audit found as its blocker B4 — the mechanism behind three further
 * blockers, because a clause nothing measures per row is met by whichever row happens to work.
 * This is the cure, in the shape {@link Stage7PerRowContractTest} already carries.</p>
 *
 * <h2>What the existing per-row tests could NOT see, and it is not a small gap</h2>
 *
 * <p>Every Stage 5 row has a {@code ToolTest}, a {@code ParityTest} and a {@code ForkSliceTest},
 * and none of the three can ask this question. {@link RowParity#appliedRow} pins exactly the
 * files the row DECLARES — so a row that also rewrote an unrelated fixture would pass its own
 * golden, its own unit test and its own fork slice, because not one of them reads a file the row
 * never claimed. The blast radius is only a claim while nothing compares it against the tree.</p>
 *
 * <h2>Stage 5 has no exempt rows on the position axis</h2>
 *
 * <p>{@link Stage6PerRowContractTest} carries a {@code NO_NAME_FORM} list because five of its
 * rows target something with no name. Every Stage 5 row targets a FIELD, a METHOD, a TYPE or a
 * LOCAL, and all four have a position, so all nine owe the position form — which is the form a
 * finding actually carries, since a finding has a file and a line before it has a resolved
 * symbol. The NAME form is exercised where it exists by the row's own tests
 * ({@code runsFromItsSymbolName}, {@code runsFromItsTypeName}), added when a C5 audit found it
 * implemented for three rows and driven by nothing.</p>
 *
 * <h2>Three rows CREATE or DELETE rather than rewrite, and the radius has to say so</h2>
 *
 * <p>Rows 54, 45 and 10 reach a second file — the one holding the usages they migrate — and the
 * caller never named it. A blast-radius check that only looked at the file the caret was in
 * would pass them while they rewrote anything they liked elsewhere, so the comparison below
 * treats a created, rewritten or deleted file all as a touch and demands each be declared.</p>
 */
class Stage5PerRowContractTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        // DISPOSE BEFORE RELOADING — Stage 4's and Stage 7's copies of this loop record why:
        // the helper replaces its service without disposing the previous one and every copy
        // lands on the same directory, so a loop that reloads per row leaves live stale
        // workspaces behind, and a workspace-scoped lookup can be answered by one of them.
        if (service != null) {
            service.dispose();
        }
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private DataTool door() {
        return new DataTool(() -> service, cache);
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
     * One row: the caret that reaches it, whatever else it cannot infer, and its blast radius.
     *
     * @param label the Fowler number and name, so a failure names the row
     * @param fixture the file the target is declared in
     * @param marker the declaration line, matched as text so a fixture edit fails loudly
     * @param caret the identifier the caret sits on within that line
     * @param extra values no finding carries and no default can invent — row 65's second name
     * @param touches every file the row may write, create or delete
     */
    private record Row(String label, String fixture, String marker, String caret,
                       Map<String, String> extra, List<String> touches) {}

    /** All nine Stage 5 rows, keyed by the kind that reaches each. */
    private static Map<String, Row> rows() {
        Map<String, Row> rows = new LinkedHashMap<>();

        rows.put("hide_delegate", new Row("16 Hide Delegate",
            "HideDelegateTargets.java", "return john.getDepartment().getManager();", "john",
            Map.of(), List.of("HideDelegateTargets.java")));

        rows.put("special_case", new Row("22 Introduce Special Case",
            "SpecialCaseTargets.java", "public static class Customer {", "Customer",
            Map.of(), List.of("SpecialCaseTargets.java")));

        rows.put("replace_primitive", new Row("54 Replace Primitive with Object",
            "ReplacePrimitiveTargets.java", "public String carrier", "carrier",
            Map.of(), List.of("ReplacePrimitiveTargets.java", "ReplacePrimitiveUser.java")));

        rows.put("encapsulate_collection", new Row("9 Encapsulate Collection",
            "EncapsulateCollectionTargets.java", "getScores(", "getScores",
            Map.of(), List.of("EncapsulateCollectionTargets.java")));

        // The ONE row that needs a value beyond its address, and the reason is the
        // refactoring's own: saying what the second value MEANS is the whole content of the
        // change, so `newName` is required with no default rather than generated.
        rows.put("split_variable", new Row("65 Split Variable",
            "SplitVariableTargets.java", "double temp = 2 * (height + width);", "temp",
            Map.of("newName", "area"), List.of("SplitVariableTargets.java")));

        rows.put("replace_derived_variable", new Row("45 Replace Derived Variable with Query",
            "DerivedVariableTargets.java", "public int grossAmount;", "grossAmount",
            Map.of(), List.of("DerivedVariableTargets.java", "DerivedVariableUser.java")));

        rows.put("remove_setting_method", new Row("37 Remove Setting Method",
            "SettingMethodTargets.java", "public void setCarrier(String carrier)", "setCarrier",
            Map.of(), List.of("SettingMethodTargets.java")));

        rows.put("encapsulate_record", new Row("10 Encapsulate Record",
            "EncapsulateRecordTargets.java", "public static class Coordinate", "Coordinate",
            Map.of(), List.of("EncapsulateRecordTargets.java", "EncapsulateRecordUser.java")));

        rows.put("reference_to_value", new Row("2 Change Reference to Value",
            "ReferenceToValueTargets.java", "public static class Money", "Money",
            Map.of(), List.of("ReferenceToValueTargets.java")));

        return rows;
    }

    /**
     * The one kind on this door that is NOT a Stage 5 row.
     *
     * <p>{@code encapsulate_field} is the operation the door was renamed FROM in Stage 1 — the
     * "1" in the door's 1 to 10. Naming it here is what lets the equality below be an equality
     * rather than a containment, so an eleventh kind arriving on this door fails this test
     * instead of passing quietly through a subset check.</p>
     */
    private static final String PRE_EXISTING = "encapsulate_field";

    @Test
    @DisplayName("the table accounts for every row Stage 5 shipped, and for the whole door")
    void theTableAccountsForEveryRow() {
        assertEquals(9, rows().size(),
            "Stage 5 ships nine refactorings — 16, 22, 54, 9, 65, 45, 37, 10 and 2. A row"
                + " dropped from this table would make both loops below pass over less than"
                + " the stage");

        // DERIVED on the door's side, hand-written on the stage's side, which is the only
        // direction that can fail usefully: WHICH rows Stage 5 shipped is a fact about the
        // sprint and lives nowhere in the code, while what the door publishes is code. A
        // containment would accept both a dropped row and a kind nobody wrote a row for.
        TreeSet<String> expected = new TreeSet<>(rows().keySet());
        expected.add(PRE_EXISTING);
        assertEquals(expected, new TreeSet<>(door().publishedKinds()),
            "the nine Stage 5 rows plus " + PRE_EXISTING + " ARE this door's ten kinds; a"
                + " difference means either a row is missing from the table above or a kind"
                + " reached the door with no row and no exemption");
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
            args.put("kind", entry.getKey());
            // NO symbol, NO typeName — that absence is the assertion. A finding names a file
            // and a line; this is the call it can make without resolving anything first.
            args.put("filePath", file.toString());
            int line = lineOf(file, row.marker());
            args.put("line", line);
            args.put("column", read(file).split("\n", -1)[line].indexOf(row.caret()));
            row.extra().forEach(args::put);

            ToolResponse r = door().execute(args);
            if (!r.isSuccess()) {
                problems.add(row.label() + ": " + r.getError());
                continue;
            }
            // PROOF OF WORK, not merely proof of success. A row that resolved the position,
            // decided there was nothing to do and reported success would satisfy isSuccess()
            // while leaving the clause unexercised. Measured over the whole package, because
            // three of these rows reach a file the caret was never in.
            if (was.equals(snapshotOfPackage())) {
                problems.add(row.label() + ": reported success and changed nothing anywhere");
            }
        }
        assertTrue(problems.isEmpty(),
            "all nine rows publish filePath/line/column and resolve through the shared"
                + " FqnTarget helper, so all nine must ACCEPT a caret — an agent holding a"
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
            // THE WHOLE PACKAGE, so the question can be ASKED at all. Each row's own tests
            // and its golden read only the files that row names, so a row that also rewrote
            // an unrelated fixture passes every one of them.
            Map<String, String> was = snapshotOfPackage();

            Path file = pkg.resolve(row.fixture());
            ObjectNode args = mapper.createObjectNode();
            args.put("kind", entry.getKey());
            args.put("filePath", file.toString());
            int line = lineOf(file, row.marker());
            args.put("line", line);
            args.put("column", read(file).split("\n", -1)[line].indexOf(row.caret()));
            row.extra().forEach(args::put);

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
        }
        assertTrue(problems.isEmpty(),
            "a row's blast radius is a CLAIM, and these are the files each row declares:\n  "
                + String.join("\n  ", problems));
    }
}
