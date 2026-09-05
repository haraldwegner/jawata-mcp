package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.DataTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 5, row 22 — Introduce Special Case, reached as {@code data kind=special_case}.
 *
 * <p>Fowler's Null Object: {@code Customer.NullCustomer} answers neutrally so clients stop
 * checking for absence. The fixture is {@code SpecialCaseTargets.java}, which carries the
 * canonical case beside each shape the operation must refuse.</p>
 *
 * <p><b>What is asserted is the NEUTRALITY, not merely the class.</b> A generated subclass
 * that compiled but returned the parent's values would pass a test that only checked the
 * type exists — and would be useless, because a special case that answers like a real one
 * removes no check. Each override's value is asserted per return type.</p>
 */
class IntroduceSpecialCaseToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private DataTool tool;
    private Path targets;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new DataTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        targets = service.getProjectRoot()
            .resolve("src/main/java/com/example/SpecialCaseTargets.java");
    }

    private int lineOf(String needle) throws Exception {
        String[] lines = Files.readString(targets, StandardCharsets.UTF_8).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("the fixture no longer declares: " + needle);
    }

    private ToolResponse at(String declaration, int column) throws Exception {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "special_case");
        args.put("filePath", targets.toString());
        args.put("line", lineOf(declaration));
        args.put("column", column);
        return tool.execute(args);
    }

    @Test
    @DisplayName("the canonical case: a null object whose every answer is neutral")
    void generatesANeutralSpecialCase() throws Exception {
        ToolResponse r = at("public static class Customer {", 30);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String after = Files.readString(targets, StandardCharsets.UTF_8);
        assertTrue(after.contains("public static class NullCustomer extends Customer"),
            "the special case must extend the type it stands in for:\n" + after);
        assertTrue(after.contains("public static final NullCustomer INSTANCE"),
            "and offer one shared instance, since it holds no state:\n" + after);

        // THE NEUTRALITY, per return type. A subclass that compiled but answered like a real
        // Customer would remove no check from any client, and a test that only asserted the
        // class exists would pass on it.
        assertTrue(after.contains("return \"\";"),
            "a String answer is the empty string:\n" + after);
        assertTrue(after.contains("return 0;"),
            "a numeric answer is zero:\n" + after);
        assertTrue(after.contains("return false;"),
            "a boolean answer is false:\n" + after);
        assertTrue(after.contains("// the absent case does nothing"),
            "and a void method does nothing rather than inheriting behaviour:\n" + after);
    }

    @Test
    @DisplayName("REFUSES a final class, naming why it cannot be subclassed")
    void refusesAFinalClass() throws Exception {
        ToolResponse r = at("public static final class SealedRecordId {", 36);
        assertFalse(r.isSuccess(), "a final class cannot be subclassed");
        assertTrue(String.valueOf(r.getError()).contains("is FINAL"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES an interface — a special case for one is an implementation")
    void refusesAnInterface() throws Exception {
        ToolResponse r = at("public interface Billable {", 25);
        assertFalse(r.isSuccess(), "an interface takes an implementation, not a subclass");
        assertTrue(String.valueOf(r.getError()).contains("is an INTERFACE"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a class whose every constructor is private")
    void refusesAnUnreachableConstructor() throws Exception {
        ToolResponse r = at("public static class Registry {", 30);
        assertFalse(r.isSuccess(), "a subclass cannot reach a private constructor");
        assertTrue(String.valueOf(r.getError()).contains("no constructor a subclass can reach"),
            "the refusal must name that reason: " + r.getError());
    }

    @Test
    @DisplayName("REFUSES a name the type already declares")
    void refusesANameCollision() throws Exception {
        assertTrue(at("public static class Customer {", 30).isSuccess(), "first run generates");

        // Second run on the same type: NullCustomer now exists.
        ToolResponse again = at("public static class Customer {", 30);
        assertFalse(again.isSuccess(), "generating over the existing special case would either"
            + " fail to compile or silently replace it");
        assertTrue(String.valueOf(again.getError()).contains("already declares a nested"),
            "the refusal must name the clash: " + again.getError());
    }

    @Test
    @DisplayName("the kind is routed and published by the door")
    void theDoorRoutesIt() {
        assertTrue(tool.delegates().containsKey("special_case"),
            "row 22 must be reachable as data kind=special_case");
        assertEquals("special_case", tool.delegates().get("special_case").kindName(),
            "the routing key and the delegate's own name are two spellings of one fact");
        assertTrue(tool.publishedKinds().contains("special_case"),
            "and a client reading tools/list must see it — the enum is the routing table");
    }
}
