package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.RemoveFlagArgumentTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 4, row 35 — Remove Flag Argument, as
 * {@code change_method_signature kind=remove_flag_argument}.
 *
 * <p>The row performs the SAFE half of Fowler's mechanics — two named delegates and the call sites
 * pointed at them, with the original untouched — so the assertions come in pairs: what the callers
 * now read like, and that nothing about the behaviour moved.</p>
 */
class RemoveFlagArgumentToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private org.jawata.mcp.tools.RefactoringTool lifecycle;
    private Path targets;
    private Path desk;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        org.jawata.mcp.refactoring.RefactoringChangeCache cache =
            new org.jawata.mcp.refactoring.RefactoringChangeCache();
        tool = new ChangeMethodSignatureTool(() -> service, cache);
        lifecycle = new org.jawata.mcp.tools.RefactoringTool(() -> service, cache);
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        targets = pkg.resolve("FlagArgumentTargets.java");
        desk = pkg.resolve("FlagArgumentDesk.java");
    }

    private ObjectNode argsFor(String method, String parameter, String whenTrue,
                               String whenFalse) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "remove_flag_argument");
        args.put("symbol", "com.example.FlagArgumentTargets#" + method);
        args.put("parameter", parameter);
        args.put("whenTrue", whenTrue);
        args.put("whenFalse", whenFalse);
        return args;
    }

    @Test
    @DisplayName("each caller goes to the name matching the literal it passed, and the original "
        + "method is STILL THERE")
    void pointsEachCallerAtItsOwnName() throws Exception {
        ToolResponse r = tool.execute(argsFor("price", "premium", "atPremium", "atStandard"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        String callers = Files.readString(desk, StandardCharsets.UTF_8);
        Assertions.assertAll(
            () -> assertEquals(2, callers.split("targets\\.atPremium\\(base\\)", -1).length - 1,
                "BOTH callers that passed true must move, not just the first:\n" + callers),
            () -> assertEquals(1, callers.split("targets\\.atStandard\\(base\\)", -1).length - 1,
                "and the one that passed false must go to the OTHER name — a rewrite that sent"
                    + " every caller to one name would satisfy the assertion above:\n" + callers),
            () -> assertFalse(callers.contains("targets.price("),
                "no caller may be left on the flag-taking method:\n" + callers),

            // THE HALF THAT DID NOT HAPPEN, asserted so a reader cannot take it as done. The
            // branch still runs where it did; only the call sites read differently.
            () -> assertTrue(after.contains("public double price(double base, boolean premium)"),
                "the original must be UNCHANGED — this row performs Fowler's first step, and"
                    + " splitting the body is a separate decision:\n" + after),
            () -> assertTrue(after.contains("return price(base, true);")
                    && after.contains("return price(base, false);"),
                "and both new methods must DELEGATE with their literal, which is what makes the"
                    + " change behaviour-preserving without reading the body:\n" + after));
    }

    @Test
    @DisplayName("REFUSES when a caller passes a VARIABLE, because it has no name to become")
    void refusesACallerThatPassesAVariable() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("fare", "express", "expressFare", "stoppingFare"));

        assertFalse(r.isSuccess(), "one caller passes the decision on rather than making it");
        assertEquals(RemoveFlagArgumentTool.Refusal.CALLER_PASSES_A_VARIABLE,
            r.getError().getReason(), "got: " + r.getError());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "a refusal modifies nothing");
    }

    @Test
    @DisplayName("REFUSES a flag every caller passes the SAME way, because one generated method "
        + "would have no caller")
    void refusesAOneSidedFlag() throws Exception {
        ToolResponse r = tool.execute(argsFor("always", "rounded", "roundedUp", "exact"));

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "the flag is a constant these callers agree on, not"
                + " a choice between two things"),
            () -> assertEquals(RemoveFlagArgumentTool.Refusal.FLAG_IS_ONE_SIDED,
                r.getError().getReason(),
                "and it must NOT be the variable refusal — both callers pass a literal here: "
                    + r.getError()),
            () -> assertTrue(String.valueOf(r.getError()).contains("change_signature"),
                "and it must name the operation that DOES fit — removing the parameter — rather"
                    + " than leaving the caller to guess: " + r.getError()));
    }

    @Test
    @DisplayName("REFUSES a non-boolean flag, and says an enum is the same idea with more names")
    void refusesANonBooleanFlag() throws Exception {
        ToolResponse r = tool.execute(argsFor("tiered", "tier", "highTier", "lowTier"));

        assertFalse(r.isSuccess(), "an int selects more than two behaviours");
        assertEquals(RemoveFlagArgumentTool.Refusal.NOT_A_BOOLEAN_FLAG,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a name the class already declares at that arity")
    void refusesAColliding3Name() throws Exception {
        ToolResponse r = tool.execute(argsFor("levied", "exempt", "untaxed", "taxed"));

        assertFalse(r.isSuccess(), "generating it would not compile");
        assertEquals(RemoveFlagArgumentTool.Refusal.NAME_TAKEN,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a method nobody calls, because the point is what the CALL SITES read")
    void refusesAMethodWithNoCallers() throws Exception {
        ToolResponse r = tool.execute(argsFor("uncalled", "doubled", "doubled2", "single"));

        assertFalse(r.isSuccess(), "two named methods for nobody is not this refactoring");
        assertEquals(RemoveFlagArgumentTool.Refusal.NO_CALL_SITES,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES without BOTH names, because naming the two cases IS the change")
    void refusesWithoutNames() throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "remove_flag_argument");
        args.put("symbol", "com.example.FlagArgumentTargets#price");
        args.put("parameter", "premium");
        args.put("whenTrue", "atPremium");
        ToolResponse r = tool.execute(args);

        assertFalse(r.isSuccess(), "a generated priceFalse would deliver none of the point");
        assertEquals(RemoveFlagArgumentTool.Refusal.NAMES_REQUIRED,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a parameter the method does not declare")
    void refusesAnUnknownParameter() throws Exception {
        ToolResponse r = tool.execute(argsFor("price", "nosuch", "a", "b"));

        assertFalse(r.isSuccess(), "there is no such flag");
        assertEquals(RemoveFlagArgumentTool.Refusal.PARAMETER_NOT_FOUND,
            r.getError().getReason(), "got: " + r.getError());
    }

    @Test
    @DisplayName("auto_apply=false STAGES: the file is untouched until the change is applied")
    void stagesTheChangeAndAppliesItOnDemand() throws Exception {
        String before = Files.readString(desk, StandardCharsets.UTF_8);
        ObjectNode args = argsFor("price", "premium", "atPremium", "atStandard");
        args.put("auto_apply", false);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals(before, Files.readString(desk, StandardCharsets.UTF_8),
            "STAGING must not write");

        Object changeId = ((java.util.Map<?, ?>) r.getData()).get("changeId");
        assertNotNull(changeId, "the response carries no changeId: " + r.getData());
        ObjectNode apply = new ObjectMapper().createObjectNode();
        apply.put("action", "apply");
        apply.put("changeId", String.valueOf(changeId));
        assertTrue(lifecycle.execute(apply).isSuccess());
        assertTrue(Files.readString(desk, StandardCharsets.UTF_8).contains("targets.atPremium("),
            "and the staged change must be the real one");
    }

    @Test
    @DisplayName("the undo handle RESTORES both files")
    void undoRestoresEverything() throws Exception {
        String before = Files.readString(targets, StandardCharsets.UTF_8);
        String beforeDesk = Files.readString(desk, StandardCharsets.UTF_8);
        ToolResponse r = tool.execute(argsFor("price", "premium", "atPremium", "atStandard"));
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertFalse(before.equals(Files.readString(targets, StandardCharsets.UTF_8)),
            "the CONTROL: with nothing changed, an undo that restores nothing would pass");

        Object handle = ((java.util.Map<?, ?>) r.getData()).get("undoChangeId");
        assertNotNull(handle, "the response carries no undoChangeId: " + r.getData());
        ObjectNode undo = new ObjectMapper().createObjectNode();
        undo.put("action", "undo");
        undo.put("undoChangeId", String.valueOf(handle));
        assertTrue(lifecycle.execute(undo).isSuccess());
        assertEquals(before, Files.readString(targets, StandardCharsets.UTF_8),
            "the method's own file is restored");
        assertEquals(beforeDesk, Files.readString(desk, StandardCharsets.UTF_8),
            "and so are the callers'");
    }

    @Test
    @DisplayName("the door routes the kind")
    void theDoorRoutesIt() {
        assertTrue(tool.publishedKinds().contains("remove_flag_argument"),
            "a kind that is dispatched and not published is invisible in tools/list");
        assertTrue(tool.delegates().get("remove_flag_argument")
                instanceof RemoveFlagArgumentTool,
            "and the routing table must reach this delegate");
    }
}
