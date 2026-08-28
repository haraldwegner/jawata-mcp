package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d — the {@code encapsulation} sweep kind: the Sprint 22a composed
 * audit, promoted from a question you must already know to ask into one a sweep
 * asks for you.
 *
 * <p>PROOF OF LIFE BEFORE ZERO, and {@code filesExamined} before any number.
 * The fixture is the one the on-demand audit already used
 * ({@code EncapsulationAuditTest}): {@code Account.balance} is private and
 * written only inside {@code Account}, through a public setter that
 * {@code External} calls — the leak a direct write search reports as
 * internal-only. {@code Vault} is this sprint's addition and is a NEAR MISS, not
 * an empty class: its non-final field is written after construction too, but
 * only by a private method, so nothing outside can reach it.</p>
 */
class EncapsulationDetectorTest {

    private static final String LEAKING = "src/main/java/com/example/Account.java";
    private static final String SEALED = "src/main/java/com/example/Vault.java";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("encapsulation");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findingsIn(String filePath) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "encapsulation");
        args.put("filePath", filePath);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "encapsulation must dispatch; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(1, ((Number) data.get("filesExamined")).intValue(),
            () -> "the scan must have examined " + filePath + "; response: " + data);
        return (List<Map<String, Object>>) data.get("findings");
    }

    // ------------------------------------------------------------ proof of life

    @Test
    @DisplayName("PROOF OF LIFE: fires on the setter leak a direct write search cannot see — 1 finding")
    void firesOnTheSetterFalsePass() {
        List<Map<String, Object>> findings = findingsIn(LEAKING);
        assertEquals(1, findings.size(),
            () -> "Account has one field, and it leaks through setBalance; got: " + findings);
        assertEquals("Account#balance", String.valueOf(findings.get(0).get("symbol")));

        String message = String.valueOf(findings.get(0).get("message"));
        assertTrue(message.contains("encapsulated in name only"),
            () -> "the finding must name what is wrong: " + message);
        assertTrue(message.contains("setBalance"),
            () -> "it must name the mutator that carries the leak: " + message);
        assertTrue(message.contains("com.example.External"),
            () -> "and the external type that reaches through it — the half find_field_writes "
                + "cannot see: " + message);
        assertTrue(message.contains("Encapsulate Field"),
            () -> "every finding must carry the pointed refactoring: " + message);
    }

    // ------------------------------------------------------------- the zero

    @Test
    @DisplayName("ZERO on a class whose mutable field is only reachable from inside")
    void staysSilentWhenNothingOutsideCanReachTheWrite() {
        // Only meaningful because firesOnTheSetterFalsePass() shows the same
        // detector, on the same service, reporting one.
        assertEquals(1, findingsIn(LEAKING).size(),
            "proof of life must hold before the zero counts");
        assertEquals(List.of(), findingsIn(SEALED),
            "Vault.owner is final (no mutator can exist) and Vault.attempts is written only by "
                + "a private method, so its poke-set is empty");
    }

    @Test
    @DisplayName("a constructor is not a mutator — otherwise every instantiated class would leak")
    void constructionIsNotMutation() {
        // Vault.limit is private, NOT final, and assigned by the constructor —
        // which External calls (External.build). If constructors counted as
        // mutators here (as they deliberately still do for the on-demand analyze
        // tool), External would be in limit's poke-set and this file could not be
        // silent. Generalised: every field any constructor initialises in any
        // externally instantiated class would be flagged, and the kind would
        // carry no information at all.
        assertEquals(List.of(), findingsIn(SEALED),
            "a constructor write must not put its callers in the poke-set");
    }

    // ------------------------------------------------------------- registration

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("registered: `encapsulation` is in find_quality_issue's kind enum")
    void registeredAsAKind() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        List<String> kinds = (List<String>) kind.get("enum");
        assertTrue(kinds.contains("encapsulation"),
            () -> "kind enum must carry encapsulation; got: " + kinds);
        // The count lives in PrincipleDetectorKindsTest — the roster's one home.
    }

    @SuppressWarnings("unchecked")
    @Test
    @DisplayName("a fowler family sweep reaches the promoted kind — the gap promotion closes")
    void answersAFamilySweep() {
        // The audit always existed; what did not was a way to meet it without
        // already suspecting the type. This is that way.
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "fowler");
        args.put("limit", 2000);
        ToolResponse r = org.jawata.mcp.fixtures.Sweeps.run(tool, args);
        assertTrue(r.isSuccess(), () -> "fowler sweep must succeed; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(((List<String>) data.get("kinds")).contains("encapsulation"),
            () -> "the fowler family must list encapsulation; got: " + data.get("kinds"));
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        long mine = findings.stream().filter(f -> "encapsulation".equals(f.get("kind"))).count();
        assertEquals(1, mine,
            () -> "the sweep must carry the finding itself, not just the kind name; got "
                + findings.size() + " findings total");
    }
}
