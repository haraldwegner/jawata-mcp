package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
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
 * THE TWO CONTRACT CLAUSES STAGE 4 CARRIED IN PROSE, ASKED OF ALL TEN ROWS AT ONCE.
 *
 * <p>The per-row contract asks that each row be "callable straight from the finding that
 * names it, <b>by symbol name and by file position</b>", and that "nothing outside the target
 * [is] touched". A C4 audit counted both over the stage's thirty test files: the position half
 * was exercised by 3 rows of 10, and the blast radius by none. It was right on both, and this
 * class is the answer.</p>
 *
 * <h2>Why one class and not twenty more assertions in ten files</h2>
 *
 * <p>Both clauses ask ONE question of each row, so each is one loop over a table — and a
 * failure names the row, which is what "asserted per row, not per stage" actually demands.
 * Ten near-identical assertions spread over ten files is how one of them ends up subtly
 * different from its neighbours with nothing to say so. This is
 * {@link Stage6PerRowContractTest}'s shape, deliberately: Stage 6 built it after an audit
 * made the same count against ITS rows, and the cure was written down there and then not
 * applied here. Copying the shape is the cheapest way to stop that being true a third time.</p>
 *
 * <h2>Stage 4 has no exempt rows, and that is a fact about its rows rather than luck</h2>
 *
 * <p>{@code Stage6PerRowContractTest} carries a {@code NO_NAME_FORM} list because five of its
 * rows target something with no name — a statement range, a local, a statement. Every Stage 4
 * row targets a METHOD or a TYPE, and both have a name AND a position, so all ten owe both
 * forms and none has an excuse. That is asserted below rather than left as an observation:
 * the table's own size is checked, so a row cannot be quietly dropped to make the loop pass.</p>
 *
 * <p><b>The position form is not a courtesy.</b> Every one of the ten delegates resolves its
 * target through the shared {@code FqnTarget} helper, which reads {@code filePath}/{@code
 * line}/{@code column}, and every one publishes those parameters in the door's schema. Seven
 * of them therefore shipped a reachable, advertised entry point that no test entered. A
 * published path nothing drives is the same state as a forgotten one from the outside, which
 * is the reasoning {@code Stage6PerRowContractTest} records and this class inherits.</p>
 */
class Stage4PerRowContractTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        // DISPOSE BEFORE RELOADING. The helper replaces its service on every load without
        // disposing the previous one and every copy lands on the same directory, so a loop
        // that reloads per row leaves a trail of live stale workspaces. A NAME form resolves
        // at workspace scope and can be answered by one of them; the positional path never
        // can, because it is handed the file. Stage 6's own copy of this loop was the first
        // place that bit, as a lookup failure under four concurrent shards.
        if (service != null) {
            service.dispose();
        }
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private ChangeMethodSignatureTool door() {
        return new ChangeMethodSignatureTool(() -> service, cache);
    }

    private String read(Path p) throws Exception {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "(absent)";
    }

    /**
     * Every {@code .java} file at or below the fixture package, keyed by its path RELATIVE to
     * that package. Recursive rather than flat, because row 28 folds a parameter whose type
     * lives in {@code com.example.service} — a flat listing would not see a rewrite there at
     * all, and "nothing outside the target" would be asserted over a scope chosen to exclude
     * the one row most able to break it.
     */
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
     * @param extras every other argument, which the contract says a finding already carries
     * @param touches every file the row may write, create or delete
     */
    private record Row(String label, String fixture, String marker, String caret,
                       Map<String, Object> extras, List<String> touches) {}

    /** How the row is addressed BY NAME: the argument key and its value. */
    private record NameForm(String key, String value) {}

    private static Map<String, Object> extras(Object... pairs) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((String) pairs[i], pairs[i + 1]);
        }
        return map;
    }

    /**
     * All ten Stage 4 rows. The kind is the map key, so a kind cannot appear twice and a row
     * cannot be listed without one.
     */
    private static Map<String, Row> rows() {
        Map<String, Row> rows = new LinkedHashMap<>();
        rows.put("introduce_parameter_object", new Row("21 Introduce Parameter Object",
            "ParameterObjectTargets.java", "public String describeDelivery(", "describeDelivery",
            extras("className", "DeliveryAddress"),
            List.of("ParameterObjectTargets.java", "ParameterObjectDesk.java",
                "DeliveryAddress.java")));

        rows.put("replace_query_with_parameter", new Row("55 Replace Query with Parameter",
            "QueryParameterTargets.java", "public int heatingPlan(", "heatingPlan",
            extras("queryCall", "defaultTemperature", "parameterName", "wantedTemperature"),
            List.of("QueryParameterTargets.java", "QueryParameterDesk.java")));

        rows.put("parameterize_function", new Row("27 Parameterize Function",
            "ParameterizeTargets.java", "public double tenPercentRaise(", "tenPercentRaise",
            extras("literal", "1.1", "parameterName", "factor"),
            List.of("ParameterizeTargets.java", "ParameterizeDesk.java")));

        rows.put("separate_query_from_modifier", new Row("61 Separate Query from Modifier",
            "CommandQueryTargets.java", "public int recordFailure(", "recordFailure",
            extras("queryName", "failureCount"),
            List.of("CommandQueryTargets.java", "CommandQueryDesk.java")));

        rows.put("replace_parameter_with_query", new Row("53 Replace Parameter with Query",
            "DerivedParameterTargets.java", "public double discounted(", "discounted",
            extras("parameter", "rate"),
            List.of("DerivedParameterTargets.java", "DerivedParameterDesk.java")));

        rows.put("replace_exception_with_precheck", new Row("47 Replace Exception with Precheck",
            "PrecheckTargets.java", "public double readingAt(", "readingAt",
            extras(),
            List.of("PrecheckTargets.java")));

        rows.put("preserve_whole_object", new Row("28 Preserve Whole Object",
            "WholeObjectTargets.java", "public boolean withinRange(", "withinRange",
            extras("parameters", List.of("low", "high")),
            List.of("WholeObjectTargets.java", "WholeObjectDesk.java")));

        rows.put("remove_flag_argument", new Row("35 Remove Flag Argument",
            "FlagArgumentTargets.java", "public double price(", "price",
            extras("parameter", "premium", "whenTrue", "atPremium", "whenFalse", "atStandard"),
            List.of("FlagArgumentTargets.java", "FlagArgumentDesk.java")));

        rows.put("replace_command_with_function", new Row("41 Replace Command with Function",
            "CommandObjectTargets.java", "public static class Discount", "Discount",
            extras(),
            List.of("CommandObjectTargets.java", "CommandObjectDesk.java")));

        rows.put("replace_error_code_with_exception", new Row("46 Replace Error Code with Exception",
            "ErrorCodeTargets.java", "public int readingAt(", "readingAt",
            extras("errorValue", "-1", "exceptionType", "IllegalStateException"),
            List.of("ErrorCodeTargets.java")));

        return rows;
    }

    /**
     * How each row is addressed by NAME. Row 41 targets a TYPE and so names {@code typeName};
     * the other nine target a method and name {@code symbol}. That difference is a property of
     * what each row acts on, not an inconsistency, and it is written out rather than derived so
     * that a row moving from one to the other has to be noticed here.
     */
    private static Map<String, NameForm> nameForms() {
        Map<String, NameForm> byKind = new LinkedHashMap<>();
        byKind.put("introduce_parameter_object",
            new NameForm("symbol", "com.example.ParameterObjectTargets#describeDelivery"));
        byKind.put("replace_query_with_parameter",
            new NameForm("symbol", "com.example.QueryParameterTargets#heatingPlan"));
        byKind.put("parameterize_function",
            new NameForm("symbol", "com.example.ParameterizeTargets#tenPercentRaise"));
        byKind.put("separate_query_from_modifier",
            new NameForm("symbol", "com.example.CommandQueryTargets#recordFailure"));
        byKind.put("replace_parameter_with_query",
            new NameForm("symbol", "com.example.DerivedParameterTargets#discounted"));
        byKind.put("replace_exception_with_precheck",
            new NameForm("symbol", "com.example.PrecheckTargets#readingAt"));
        byKind.put("preserve_whole_object",
            new NameForm("symbol", "com.example.WholeObjectTargets#withinRange"));
        byKind.put("remove_flag_argument",
            new NameForm("symbol", "com.example.FlagArgumentTargets#price"));
        byKind.put("replace_command_with_function",
            new NameForm("typeName", "com.example.CommandObjectTargets.Discount"));
        byKind.put("replace_error_code_with_exception",
            new NameForm("symbol", "com.example.ErrorCodeTargets#readingAt"));
        return byKind;
    }

    private void putExtras(ObjectNode args, Row row) {
        for (Map.Entry<String, Object> extra : row.extras().entrySet()) {
            if (extra.getValue() instanceof List<?> values) {
                com.fasterxml.jackson.databind.node.ArrayNode array =
                    args.putArray(extra.getKey());
                values.forEach(v -> array.add(String.valueOf(v)));
            } else {
                args.put(extra.getKey(), String.valueOf(extra.getValue()));
            }
        }
    }

    @Test
    @DisplayName("every row runs from a FILE POSITION, with no symbol and no typeName")
    void everyRowRunsFromAFilePosition() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Map.Entry<String, Row> entry : rows().entrySet()) {
            setUp();
            Row row = entry.getValue();
            Path file = pkg.resolve(row.fixture());
            String before = read(file);

            ObjectNode args = mapper.createObjectNode();
            args.put("kind", entry.getKey());
            // NO symbol, NO typeName — that absence is the assertion. A finding names a file
            // and a line; this is the call it can make without resolving anything first.
            args.put("filePath", file.toString());
            int line = lineOf(file, row.marker());
            args.put("line", line);
            args.put("column", read(file).split("\n", -1)[line].indexOf(row.caret()));
            putExtras(args, row);

            ToolResponse r = door().execute(args);
            if (!r.isSuccess()) {
                problems.add(row.label() + ": " + r.getError());
                continue;
            }
            // PROOF OF WORK, not merely proof of success. A row that resolved the position,
            // decided there was nothing to do and reported success would satisfy isSuccess()
            // while leaving the clause unexercised.
            if (before.equals(read(file))) {
                problems.add(row.label() + ": reported success and changed nothing in "
                    + row.fixture());
            }
        }
        assertTrue(problems.isEmpty(),
            "all ten rows publish filePath/line/column and resolve through the shared"
                + " FqnTarget helper, so all ten must ACCEPT a caret — an agent holding a"
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
            // THE WHOLE TREE, so the question can be ASKED. Every existing Stage 4 check reads
            // only the files its own row names, so a row that also rewrote an unrelated fixture
            // passed all thirty of them. Snapshotting everything turns the touches list from a
            // reading list into a CLAIM about the blast radius.
            Map<String, String> was = snapshotOfPackage();

            ObjectNode args = mapper.createObjectNode();
            args.put("kind", entry.getKey());
            NameForm name = nameForms().get(entry.getKey());
            args.put(name.key(), name.value());
            putExtras(args, row);

            ToolResponse r = door().execute(args);
            if (!r.isSuccess()) {
                problems.add(row.label() + ": apply refused — " + r.getError());
                continue;
            }

            // Measured on the APPLIED state, which is the only moment it means anything. A
            // version of this written after an undo compares a restored tree against itself
            // and passes for every row: that is not a weak check, it is no check.
            Map<String, String> now = snapshotOfPackage();

            // PROOF OF WORK, which the sibling loop has and this one lacked until a round-2
            // audit said so. A row that reported success and wrote nothing satisfies every
            // comparison below, because a tree nothing touched is trivially inside any
            // declared radius. Measured latent rather than live at the time — all ten rows
            // do write — but a check that a no-op passes is the shape this file exists to
            // stop.
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
            "'nothing outside the target touched' is a clause of the per-row contract and was"
                + " asserted by nothing in this stage until now:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("the table drives all ten rows, and every one of them owes BOTH address forms")
    void theTableAccountsForEveryRow() {
        // THE PROOF OF LIFE FOR THE TWO LOOPS ABOVE: both iterate whatever they are handed, so
        // a row deleted from the table leaves them green over a shorter list. Stage 6's copy of
        // this file shipped that exact hole — a comment claiming arithmetic that no code ran.
        Map<String, Row> rows = rows();
        assertEquals(10, rows.size(),
            "Stage 4 shipped ten rows and this table must drive all of them: " + rows.keySet());

        // Every row owes BOTH forms, which is Stage 4's difference from Stage 6 rather than an
        // oversight: Stage 6 exempts five rows whose target is a statement range or a local and
        // so has no name, while every target here is a method or a type and has both.
        assertEquals(rows.keySet(), nameForms().keySet(),
            "every row must be addressable by name AND by position; a kind in one table and"
                + " not the other is a row that owes a form nothing supplies");

        // And the kinds must be the door's OWN, so a renamed kind cannot leave this file
        // testing a string the product no longer publishes.
        ChangeMethodSignatureTool door =
            new ChangeMethodSignatureTool(() -> null, new RefactoringChangeCache());
        List<String> unpublished = rows.keySet().stream()
            .filter(kind -> !door.publishedKinds().contains(kind)).toList();
        assertTrue(unpublished.isEmpty(),
            "this table names kinds the door does not publish: " + unpublished);
    }
}
