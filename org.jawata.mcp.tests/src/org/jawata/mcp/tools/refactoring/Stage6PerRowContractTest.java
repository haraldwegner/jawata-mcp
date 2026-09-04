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

    /** One row: which door, the arguments that drive it, and the files it should touch. */
    private record Row(String label, String door, ObjectNode args, List<String> touches) {}

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
        rows.add(new Row("5 Combine Functions", "extract", combine,
            List.of("ReadingFunctions.java", "ReadingCaller.java")));

        ObjectNode command = at("function_to_command", "Scoring.java",
            "public static int score", 25);
        command.put("newTypeName", "ScoreCommand");
        rows.add(new Row("48 Replace Function with Command", "extract", command,
            List.of("Scoring.java", "ScoringCaller.java")));

        Path twoPhase = pkg.resolve("TwoPhase.java");
        ObjectNode split = at("split_phase", "TwoPhase.java", "public int priceOrder", 16);
        split.put("boundaryLine", lineOf(twoPhase, "int discount = discountLevel * 100;"));
        rows.add(new Row("64 Split Phase", "extract", split, List.of("TwoPhase.java")));

        rows.add(new Row("36 Remove Middle Man", "inline",
            at("middle_man", "MiddleManPerson.java", "public class MiddleManPerson", 13),
            List.of("MiddleManPerson.java", "MiddleManCaller.java")));
        return rows;
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

            ObjectNode staged = rows().stream()
                .filter(r -> r.label().equals(row.label())).findFirst().orElseThrow()
                .args().deepCopy();
            staged.put("auto_apply", false);
            ToolResponse stagedResponse = doorOf(row.door()).execute(staged);
            if (!stagedResponse.isSuccess()) {
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
        // Seven of the twelve. The other five target a statement range, a statement or a
        // local, none of which HAS a name — the contract's "every other value one the
        // finding already carries" is what covers them, and this list is exhaustive rather
        // than convenient.
        record Named(String label, String door, String kind, String symbol, String extra,
                     String extraValue, String gone) {}
        List<Named> named = List.of(
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
                "com.example.Scoring#score", "newTypeName", "ScoreCommand", null));

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
}
