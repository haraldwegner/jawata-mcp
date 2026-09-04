package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractTool;
import org.jawata.mcp.tools.ExtractTool;
import org.jawata.mcp.tools.InlineTool;
import org.jawata.mcp.tools.MoveTool;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TWO CONTRACT CLAUSES THAT ARE THE SAME QUESTION TWELVE TIMES.
 *
 * <p>The per-row contract opens with "a staged change with an undo handle" and later asks
 * that each row be "callable straight from the finding that names it, by symbol name and by
 * file position". A C6 audit counted them: the first was asserted for 3 rows of 12 and the
 * second for 2, and it was right.</p>
 *
 * <p>The answer is not twenty-four more copies in twelve files. Both clauses ask ONE
 * question of each row, so each is one loop over a table of rows — and a failure names the
 * row, which is what "asserted per row, not per stage" actually demands. Twelve
 * near-identical assertions spread across twelve files is how one of them ends up subtly
 * different from its neighbours without anyone noticing.</p>
 *
 * <h2>The two rows that are exceptions, and why they are not omissions</h2>
 *
 * <ul>
 *   <li><b>Row 58 cannot stage.</b> It is composed: its second step is built against the
 *       workspace the first produced, so there is no single change to preview. It REFUSES
 *       {@code auto_apply:false} with that reason, and the refusal is asserted below —
 *       an exception that is exercised is not an exception that was forgotten.</li>
 *   <li><b>Five rows have no name form</b> because their target has no name: a statement
 *       range (49, 64's boundary), a local (58), a statement (25, 26). The contract's own
 *       wording allows for this — "every other value one the finding already carries" — and
 *       the list below is exhaustive rather than convenient.</li>
 * </ul>
 */
class Stage6PerRowContractTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ObjectMapper mapper;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        // DISPOSE THE PREVIOUS COPY BEFORE TAKING ANOTHER. The helper REPLACES its service on
        // every load without disposing the old one — it disposes only the last, in afterEach —
        // and every copy lands on the SAME directory, which the next one overwrites.
        //
        // COUNTED, not estimated, because the first version of this comment guessed and was
        // wrong by more than half. The twelve-row loop calls this method TWICE per row, before
        // staging and again before the apply, so it loads 1 + 24 = 25 projects and leaves 24
        // live. The name-form loop calls it once per row: 1 + 7 = 8, leaving 7. Across the
        // class's four test methods that is 25 + 1 + 8 + 1 = 35, and a class run logs exactly
        // 35 "Loading project:" lines.
        //
        // Why it matters: those stale projects live in the JVM-shared Eclipse workspace. A
        // name form resolves at WORKSPACE scope, so it can be answered by one of them; the
        // positional path never can, because it is handed the file. Seen once, in the C6 gate
        // run: row 5 failed with "No compilation unit at .../ReadingFunctions.java" under four
        // concurrent shards, while the same commit ran green on that shard alone and green on
        // a second full run. TestProjectHelper.afterEach carries a comment naming the same
        // problem — "leaking them ... the substrate of the load-dependent lookup-failure
        // flakes" — and closed it for the single-load case only. This is the per-iteration
        // half, fixed here rather than in the shared helper because that file is read by every
        // test in the suite.
        if (service != null) {
            service.dispose();
        }
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        mapper = new ObjectMapper();
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private String read(Path p) throws Exception {
        return Files.exists(p) ? Files.readString(p, StandardCharsets.UTF_8) : "(absent)";
    }

    private int lineOf(Path file, String marker) throws Exception {
        String[] lines = read(file).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(marker)) {
                return i;
            }
        }
        throw new AssertionError("PROOF OF LIFE: " + file.getFileName() + " lost " + marker);
    }

    /** Every .java file in the fixture package, by name, with its content. */
    private Map<String, String> snapshotOfPackage() throws Exception {
        Map<String, String> all = new LinkedHashMap<>();
        try (java.util.stream.Stream<Path> files = Files.list(pkg)) {
            for (Path file : files.filter(f -> f.toString().endsWith(".java")).toList()) {
                all.put(file.getFileName().toString(), read(file));
            }
        }
        return all;
    }

    /** One row: which door, the arguments that drive it, and the files it should touch. */
    private record Row(String label, String door, ObjectNode args, List<String> touches) {}

    /** One row addressed by NAME instead of by coordinates. */
    private record Named(String label, String door, String kind, String symbol, String extra,
                         String extraValue, String gone) {}

    /**
     * The five rows whose target HAS no name — a statement range (49, 64's boundary), a
     * statement (25, 26), a local (58). Declared here rather than left implicit, because
     * {@link #theTwoTablesAccountForEveryRow()} compares the UNION of this list and the named
     * half against the row table, and separately checks that the two halves share no row. A
     * membership missing from here is therefore a row the union fails to cover.
     *
     * <p><b>THIS LIST IS HAND-WRITTEN AND HAS AN EXPIRY.</b> Name-addressability is a fact
     * about each KIND, and today no door publishes it: it lives as prose in three schema
     * strings ({@code ExtractTool}, {@code InlineTool}, {@code MoveTool}) and
     * {@code FqnTarget.materializePosition} never reads {@code kind} at all. When the seam
     * stage gives {@code KindDelegate} a name-addressability method beside
     * {@code isStructural()}, this list becomes derivable — the named half is
     * {@code rows()} filtered by it and this one is the complement — and it should be
     * DELETED rather than kept in step by hand. Until then it is a fourth copy of a fact
     * nothing owns, and that is the reason it carries this paragraph.</p>
     */
    private static final List<String> NO_NAME_FORM = List.of("25", "26", "49", "58", "64");

    /** A row's identity is its Fowler number; the prose after it differs between the tables. */
    private static String rowNumber(String label) {
        return label.substring(0, label.indexOf(' '));
    }

    private AbstractTool doorOf(String name) {
        return switch (name) {
            case "extract" -> new ExtractTool(() -> service, cache);
            case "inline" -> new InlineTool(() -> service, cache);
            default -> new MoveTool(() -> service, cache);
        };
    }

    private ObjectNode at(String kind, String fixture, String marker, int column)
            throws Exception {
        Path file = pkg.resolve(fixture);
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        args.put("filePath", file.toString());
        args.put("line", lineOf(file, marker));
        args.put("column", column);
        return args;
    }

    /** Every Stage 6 row, positionally addressed, with the files each one rewrites. */
    private List<Row> rows() throws Exception {
        List<Row> rows = new ArrayList<>();
        ObjectNode field = at("field", "MoveFieldSource.java",
            "public static final int SHARED_LIMIT", 19);
        field.put("targetType", "com.example.MoveFieldTarget");
        rows.add(new Row("23 Move Field", "move", field,
            List.of("MoveFieldSource.java", "MoveFieldTarget.java", "MoveFieldUser.java")));

        rows.add(new Row("17 Inline Class", "inline",
            at("class", "InlineMe.java", "public class InlineMe", 13),
            List.of("InlineMe.java", "InlineAbsorber.java")));

        rows.add(new Row("38 Remove Subclass", "inline",
            at("subclass", "PlainCharge.java", "public class PlainCharge", 13),
            List.of("PlainCharge.java", "Charge.java", "PlainChargeUser.java")));

        ObjectNode staticMove = at("method", "StaticHome.java",
            "public static int roundUp", 22);
        staticMove.put("targetType", "com.example.StaticDestination");
        rows.add(new Row("24 Move Function (static)", "move", staticMove,
            List.of("StaticHome.java", "StaticDestination.java", "StaticCaller.java")));

        rows.add(new Row("25 Move Statements into Function", "move",
            at("statements_into_function", "AuditedCaller.java",
                "Audited.audits = Audited.audits + 1;", 8),
            List.of("AuditedCaller.java", "Audited.java")));

        rows.add(new Row("26 Move Statements to Callers", "move",
            at("statements_to_callers", "Audited.java", "exits = exits + 1;", 8),
            List.of("Audited.java", "AuditedCaller.java")));

        Path twice = pkg.resolve("InlineCodeTwice.java");
        ObjectNode inlineCode = mapper.createObjectNode();
        inlineCode.put("kind", "method");
        inlineCode.put("filePath", twice.toString());
        inlineCode.put("startLine", lineOf(twice, "int scaled = base * 3;"));
        inlineCode.put("startColumn", 8);
        inlineCode.put("endLine", lineOf(twice, "int shifted = scaled + 7;"));
        inlineCode.put("endColumn", 8 + "int shifted = scaled + 7;".length());
        inlineCode.put("methodName", "scale");
        inlineCode.put("replaceDuplicates", true);
        rows.add(new Row("49 Replace Inline Code", "extract", inlineCode,
            List.of("InlineCodeTwice.java")));

        ObjectNode combine = mapper.createObjectNode();
        combine.put("kind", "combine_functions");
        combine.put("filePath", pkg.resolve("ReadingFunctions.java").toString());
        combine.put("newTypeName", "ReadingCharge");
        combine.putArray("functions").add("baseCharge").add("taxThreshold");
        // ReadingCharge.java is the class this row CREATES. It was missing from this list
        // until the blast-radius check started reading the applied state — a row's output
        // file is part of what it touches, and leaving it out made the list a reading list.
        rows.add(new Row("5 Combine Functions", "extract", combine,
            List.of("ReadingFunctions.java", "ReadingCaller.java", "ReadingCharge.java")));

        ObjectNode command = at("function_to_command", "Scoring.java",
            "public static int score", 25);
        command.put("newTypeName", "ScoreCommand");
        rows.add(new Row("48 Replace Function with Command", "extract", command,
            List.of("Scoring.java", "ScoringCaller.java", "ScoreCommand.java")));

        Path twoPhase = pkg.resolve("TwoPhase.java");
        ObjectNode split = at("split_phase", "TwoPhase.java", "public int priceOrder", 16);
        split.put("boundaryLine", lineOf(twoPhase, "int discount = discountLevel * 100;"));
        rows.add(new Row("64 Split Phase", "extract", split, List.of("TwoPhase.java")));

        rows.add(new Row("36 Remove Middle Man", "inline",
            at("middle_man", "MiddleManPerson.java", "public class MiddleManPerson", 13),
            List.of("MiddleManPerson.java", "MiddleManCaller.java")));
        // ROW 58 IS HERE TOO, and its exception is only to STAGING. A C6 audit found it
        // absent from this table altogether, so "nothing outside the target touched" — the
        // clause promoted from a deviation to an assertion one round earlier — covered
        // eleven of twelve without anything saying so. The staging loop below skips it by
        // name and asserts its refusal separately; every other clause applies unchanged.
        rows.add(new Row("58 Replace Temp with Query", "extract",
            at("temp_to_query", "TempHolder.java", "int basePrice = quantity * 7;", 12),
            List.of("TempHolder.java")));

        return rows;
    }

    /**
     * The rows that publish a name form. SEVEN of the twelve — a C6 audit found the comment
     * here claiming seven over a list of six, which is the arithmetic nobody checks because
     * both halves read plausibly. The other FIVE are {@link #NO_NAME_FORM}, whose targets have
     * no name at all; the contract's "every other value one the finding already carries" is
     * what covers them. This list is exhaustive rather than convenient, and 7 + 5 = 12 is
     * checked by {@link #theTwoTablesAccountForEveryRow()} rather than asserted in prose.
     */
    private List<Named> namedRows() {
        return List.of(
            new Named("17 Inline Class", "inline", "class", "com.example.InlineMe",
                null, null, "InlineMe.java"),
            new Named("38 Remove Subclass", "inline", "subclass", "com.example.PlainCharge",
                null, null, "PlainCharge.java"),
            new Named("36 Remove Middle Man", "inline", "middle_man",
                "com.example.MiddleManPerson", null, null, null),
            new Named("23 Move Field", "move", "field",
                "com.example.MoveFieldSource#SHARED_LIMIT",
                "targetType", "com.example.MoveFieldTarget", null),
            new Named("24 Move Function", "move", "method",
                "com.example.StaticHome#roundUp",
                "targetType", "com.example.StaticDestination", null),
            new Named("48 Function to Command", "extract", "function_to_command",
                "com.example.Scoring#score", "newTypeName", "ScoreCommand", null),
            // ROW 5 WAS MISSING FROM THIS LIST while the comment above said seven — a C6
            // audit did the arithmetic nobody else had. It belongs here: combine_functions
            // needs only a filePath, and a typeName materialises one, so an agent holding
            // the class's name can reach it without opening the file to count lines.
            new Named("5 Combine Functions", "extract", "combine_functions",
                "com.example.ReadingFunctions", "newTypeName", "ReadingQueries", null));
    }

    @Test
    @DisplayName("every row stages without touching a file, applies, and undoes back to where it started")
    void everyRowStagesAppliesAndUndoes() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Row row : rows()) {
            // A FRESH PROJECT PER ROW. Sharing one would let an earlier row's rewrite
            // become a later row's input, and a failure would then be about the order.
            setUp();
            Map<String, String> before = new LinkedHashMap<>();
            for (String file : row.touches()) {
                before.put(file, read(pkg.resolve(file)));
            }
            // THE WHOLE PACKAGE, so "nothing outside the target touched" can be ASKED. That
            // clause of the per-row contract was asserted for no row until a C6 audit
            // counted: every check here read only each row's own touches list, so a row that
            // also rewrote an unrelated fixture passed. Snapshotting everything makes the
            // touches list a CLAIM about the blast radius rather than a reading list.
            Map<String, String> wholePackage = snapshotOfPackage();

            ObjectNode staged = rows().stream()
                .filter(r -> r.label().equals(row.label())).findFirst().orElseThrow()
                .args().deepCopy();
            staged.put("auto_apply", false);
            // Row 58 is composed: its second step is built against the workspace the first
            // produced, so there is no single change to preview. Its REFUSAL is asserted in
            // its own test below; skipping it here is the declared exception, not silence.
            ToolResponse stagedResponse = row.label().startsWith("58 ")
                ? null : doorOf(row.door()).execute(staged);
            if (stagedResponse == null) {
                // no staging clause for this row; every other clause still applies
            } else if (!stagedResponse.isSuccess()) {
                problems.add(row.label() + ": staging refused — " + stagedResponse.getError());
            } else {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) stagedResponse.getData();
                if (data.get("changeId") == null) {
                    problems.add(row.label() + ": staged with no changeId: " + data);
                }
                for (String file : row.touches()) {
                    if (!before.get(file).equals(read(pkg.resolve(file)))) {
                        problems.add(row.label() + ": STAGING WROTE " + file
                            + " — a preview that mutates is worse than no preview");
                    }
                }
            }

            setUp();
            ToolResponse applied = doorOf(row.door()).execute(row.args());
            if (!applied.isSuccess()) {
                problems.add(row.label() + ": apply refused — " + applied.getError());
                continue;
            }
            // NOTHING OUTSIDE THE TARGET — measured on the APPLIED state, which is the
            // only moment it means anything. Written first after the undo, where it compared
            // a restored tree against itself and passed for every row: a check that runs
            // when the thing it inspects has been put back is not a weak check, it is no
            // check. Asked of the whole package rather than of the row's own list, so the
            // list cannot make itself right by being short; a file the row CREATES counts as
            // touched and must be named.
            Map<String, String> after = snapshotOfPackage();
            for (Map.Entry<String, String> file : after.entrySet()) {
                if (row.touches().contains(file.getKey())) {
                    continue;
                }
                String was = wholePackage.get(file.getKey());
                if (was == null) {
                    problems.add(row.label() + ": CREATED " + file.getKey()
                        + ", which is not in its touches list — a row's blast radius has to"
                        + " be declared, not discovered");
                } else if (!was.equals(file.getValue())) {
                    problems.add(row.label() + ": REWROTE " + file.getKey()
                        + ", which is outside its target");
                }
            }
            for (String gone : wholePackage.keySet()) {
                if (!after.containsKey(gone) && !row.touches().contains(gone)) {
                    problems.add(row.label() + ": DELETED " + gone + ", outside its target");
                }
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) applied.getData();
            Object undoId = data.get("undoChangeId");
            if (undoId == null) {
                problems.add(row.label() + ": applied with no undo handle: " + data);
                continue;
            }
            ObjectNode undo = mapper.createObjectNode();
            undo.put("action", "undo");
            undo.put("undoChangeId", String.valueOf(undoId));
            ToolResponse reverted = new org.jawata.mcp.tools.RefactoringTool(
                () -> service, cache, new org.jawata.mcp.domain.NoOpAdvisor()).execute(undo);
            if (!reverted.isSuccess()) {
                problems.add(row.label() + ": undo failed — " + reverted.getError());
                continue;
            }
            for (String file : row.touches()) {
                if (!before.get(file).equals(read(pkg.resolve(file)))) {
                    problems.add(row.label() + ": undo did not restore " + file);
                }
            }
        }
        assertTrue(problems.isEmpty(),
            "the per-row contract's first clause is 'a staged change with an undo handle',"
                + " and an undo that does not RESTORE is a handle rather than an undo:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("row 58 REFUSES to stage, because a recipe has no half-applied state to preview")
    void theComposedRowRefusesToStage() throws Exception {
        Path holder = pkg.resolve("TempHolder.java");
        String before = read(holder);
        ObjectNode args = at("temp_to_query", "TempHolder.java",
            "int basePrice = quantity * 7;", 12);
        args.put("auto_apply", false);

        ToolResponse r = new ExtractTool(() -> service, cache).execute(args);

        // The exception to the loop above, EXERCISED. An exception nobody drives is
        // indistinguishable from a row somebody forgot to put in the table.
        assertTrue(!r.isSuccess(), "a recipe cannot stage");
        assertTrue(String.valueOf(r.getError()).contains("COMPOSED"), String.valueOf(r.getError()));
        assertEquals(before, read(holder), "and it must not have written anything");
    }

    @Test
    @DisplayName("every row with a NAMED target runs from its name, with no coordinates")
    void everyNamedRowRunsFromItsName() throws Exception {
        List<Named> named = namedRows();

        List<String> problems = new ArrayList<>();
        for (Named row : named) {
            setUp();
            ObjectNode args = mapper.createObjectNode();
            args.put("kind", row.kind());
            // NO filePath, NO line, NO column — that absence is the assertion.
            args.put("symbol", row.symbol());
            if (row.extra() != null) {
                args.put(row.extra(), row.extraValue());
            }
            // Row 5 also names WHICH functions belong together — the one design decision it
            // refuses to guess. A finding carries that set, so supplying it here is the
            // clause, not an exception to it.
            if ("combine_functions".equals(row.kind())) {
                args.putArray("functions").add("baseCharge").add("taxThreshold");
            }
            ToolResponse r = doorOf(row.door()).execute(args);
            if (!r.isSuccess()) {
                problems.add(row.label() + " (" + row.symbol() + "): " + r.getError());
                continue;
            }
            if (row.gone() != null && Files.exists(pkg.resolve(row.gone()))) {
                problems.add(row.label() + ": ran, but did not do the work — "
                    + row.gone() + " is still there");
            }
        }
        assertTrue(problems.isEmpty(),
            "these rows publish a name form and must accept one; an agent that knows a"
                + " symbol's name should go straight to it:\n  "
                + String.join("\n  ", problems));
    }

    @Test
    @DisplayName("both tables assert their own size, and together they account for all twelve rows")
    void theTwoTablesAccountForEveryRow() throws Exception {
        // THE CHECK THAT WAS A COMMENT. Both tables in this file are hand-written, and until
        // this test existed neither said how long it was — every other test loops over
        // whatever it is handed, so a row deleted from either one left the suite green over a
        // shorter list. The comment read "7 + 5 = 12 is the check" and no code performed it.
        List<String> all = rows().stream().map(r -> rowNumber(r.label())).toList();
        List<String> byName = namedRows().stream().map(n -> rowNumber(n.label())).toList();

        assertEquals(12, all.size(),
            "Stage 6 shipped twelve rows and this table drives all of them: " + all);
        assertEquals(7, byName.size(),
            "seven rows can be run by naming the symbol, with no line and column: " + byName);
        assertEquals(5, NO_NAME_FORM.size(),
            "five rows target something with no name: " + NO_NAME_FORM);
        assertEquals(all.size(), byName.size() + NO_NAME_FORM.size(),
            "7 + 5 = 12 — the arithmetic the file's own comment claimed and never ran");

        // AND THE PARTITION, which no size assertion can see: three lists can have the right
        // lengths while naming different rows. A row is in exactly one of the two halves, and
        // between them they cover the table with nothing left over and nothing invented.
        assertEquals(List.of(), all.stream().filter(n -> java.util.Collections.frequency(all, n) > 1).distinct().toList(),
            "a row number appears once; a duplicate would let one row stand in for a missing one");
        assertEquals(List.of(), byName.stream().filter(NO_NAME_FORM::contains).toList(),
            "a row cannot both publish a name form and have no name");
        // ONE set equality, not two filters. The earlier form asserted the two directions
        // separately — nothing left over, then nothing invented — and the second of those was
        // DEAD: JUnit stops at the first failure, so it was only ever reached once the
        // preceding assertions had passed, and those entail it. Twelve distinct rows, two
        // disjoint halves summing to twelve, every row covered: the halves cannot then name
        // anything the table does not. It read as a distinct check and could never report one.
        assertEquals(new java.util.TreeSet<>(all),
            new java.util.TreeSet<>(java.util.stream.Stream
                .concat(byName.stream(), NO_NAME_FORM.stream()).toList()),
            "the two halves must name exactly the rows this file drives — nothing left over,"
                + " nothing invented");
    }

    /**
     * M7 (Stage 6a): the tables RECONCILED against the doors, now that a door answers for its
     * own routing table.
     *
     * <p>C6 closed with both tables asserting their own SIZE and their sum, and those stay —
     * they are what catches a row going missing. This is the other direction, which no size
     * assertion can see: a row naming a kind no door routes. The count would still be right,
     * the partition would still hold, and the row would silently stop exercising anything.
     * That is not hypothetical for this file: every row's kind is a bare string, in an
     * {@code ObjectNode} for the positional half and a record field for the named half, and
     * nothing until now compared either against what the door dispatches on.</p>
     *
     * <p><b>Containment, not equality, and the reason is measured rather than preferred.</b>
     * The tables hold twelve Stage-6 rows against twenty-two published kinds — {@code extract}
     * eleven, {@code inline} five, {@code move} six — so equality is false in both directions:
     * most kinds are not Stage-6 rows at all. The rows are a declared SUBSET, which is exactly
     * what this asserts.</p>
     */
    @Test
    @DisplayName("every kind either table names is one its own door actually routes")
    void everyRowNamesAKindItsDoorPublishes() throws Exception {
        java.util.Set<String> routed = new java.util.LinkedHashSet<>();
        for (String door : List.of("extract", "inline", "move")) {
            ((org.jawata.mcp.tools.KindedTool) doorOf(door)).delegates().keySet()
                .forEach(kind -> routed.add(door + " kind=" + kind));
        }
        assertEquals(22, routed.size(),
            "the three doors route twenty-two kinds between them — extract 11, inline 5,"
                + " move 6. A change to that number is a change to what Stage 6 shipped and"
                + " belongs in C9's arithmetic, not here: " + routed);

        List<String> named = new ArrayList<>();
        for (Row row : rows()) {
            com.fasterxml.jackson.databind.JsonNode kind = row.args().get("kind");
            assertNotNull(kind, row.label() + " drives its door with no kind argument at all");
            named.add(row.door() + " kind=" + kind.asText());
        }
        for (Named row : namedRows()) {
            named.add(row.door() + " kind=" + row.kind());
        }

        assertEquals(List.of(),
            named.stream().filter(k -> !routed.contains(k)).distinct().toList(),
            "a row naming a kind its door does not route exercises nothing, and every other"
                + " assertion in this file would still pass. Routed: " + routed);
        // PROOF OF LIFE: an empty list satisfies the filter above vacuously, and this method
        // reads both tables, so a bug that emptied either one has to show up here.
        assertEquals(19, named.size(),
            "twelve positionally-driven rows and seven addressed by name: " + named);
    }
}
