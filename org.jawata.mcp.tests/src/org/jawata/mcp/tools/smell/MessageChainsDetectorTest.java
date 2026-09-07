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
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 17 — Message Chains detector (fixture com.example.MessageChainTargets). */
class MessageChainsDetectorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;
    private JdtServiceImpl service;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Set<String> symbols(Integer threshold) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "message_chains");
        if (threshold != null) {
            args.put("threshold", threshold.intValue());
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "message_chains must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("flags the length-4 chain, not the length-2 one")
    void flags_long_chain() {
        Set<String> hits = symbols(null);
        // QUALIFIED AT S8b STEP 9, NEGATIVES INCLUDED — `hits` is a Set, so a bare name left
        // here makes assertFalse trivially true. Each literal is the address this run printed.
        assertTrue(hits.contains("com.example.MessageChainTargets#longChain"),
            "length-4 chain should be flagged: " + hits);
        assertFalse(hits.contains("com.example.MessageChainTargets#shortChain"),
            "length-2 chain must NOT be flagged: " + hits);
    }

    @Test
    @DisplayName("raising the threshold spares the length-4 chain")
    void threshold_respected() {
        assertFalse(symbols(5).contains("com.example.MessageChainTargets#longChain"),
            "length-4 chain clears a threshold of 5");
    }

    /**
     * jawata-mcp#70 — THE CURE'S OWN DOOR ACCEPTS THE ADDRESS THIS FINDING CARRIES.
     *
     * <p>Found by dogfooding the released v4.1.0 against this repository's own sources, which
     * is why it is asserted through the DOOR rather than on the finding's fields: the fields
     * looked fine. The finding named the enclosing method, qualified, and every other routed
     * door resolves exactly that. Hide Delegate does not — it acts on ONE chain and a method
     * may hold several — so it answered <i>"no two-deep call chain at that position"</i> about
     * a chain the detector had been looking straight at.</p>
     *
     * <p>The detector emitted the literal {@code -1} for its column while holding the node's
     * offset, and {@code CodeAddress.arguments()} then dropped the line as well, correctly, on
     * its own rule that half a position is worse than none. So the rendered cure carried no
     * coordinates at all.</p>
     *
     * <p><b>What makes this a control rather than a restatement:</b> it asserts the door does
     * not refuse for the ADDRESS, and says nothing about whether the refactoring applies to
     * this fixture. A design refusal — the server is an interface, the name is taken — means
     * the door resolved the chain and then judged it, which is the whole claim. Reverting the
     * detector's column turns it red on the address reason.</p>
     */
    @Test
    @DisplayName("the door the cure names accepts the address the finding carries")
    @SuppressWarnings("unchecked")
    void theCuresDoorAcceptsThisFindingsAddress() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "message_chains");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "message_chains must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");

        Map<String, Object> chain = findings.stream()
            .filter(f -> "com.example.MessageChainTargets#longChain".equals(f.get("symbol")))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "PROOF OF LIFE: the fixture's length-4 chain must still be found; got: "
                    + findings.stream().map(f -> f.get("symbol")).toList()));

        // THE ADDRESS THE PRODUCT WOULD RENDER, built the way the product builds it — through
        // the one factory that converts a finding's 1-based coordinates to a door's 0-based
        // ones. Reading the raw row and subtracting here would be a second copy of that rule.
        org.jawata.mcp.models.CodeAddress address = org.jawata.mcp.models.CodeAddress.of(
            new org.jawata.mcp.domain.Finding("message_chains",
                String.valueOf(chain.get("filePath")),
                ((Number) chain.get("line")).intValue(),
                ((Number) chain.get("column")).intValue(),
                "warning", String.valueOf(chain.get("message")),
                String.valueOf(chain.get("symbol"))));
        Map<String, Object> rendered = address.arguments();
        assertTrue(rendered.containsKey("line") && rendered.containsKey("column"),
            "Hide Delegate needs a position and the detector holds one, so the rendered cure "
                + "must carry it; got: " + rendered);

        ObjectNode call = mapper.createObjectNode();
        call.put("kind", "hide_delegate");
        rendered.forEach((k, v) -> {
            if (v instanceof Number n) {
                call.put(k, n.intValue());
            } else {
                call.put(k, String.valueOf(v));
            }
        });
        ToolResponse door = new org.jawata.mcp.tools.DataTool(
            () -> service,
            new org.jawata.mcp.refactoring.RefactoringChangeCache()).execute(call);

        String refusal = door.isSuccess() ? "" : String.valueOf(door.getError().getMessage());
        assertFalse(refusal.contains("no two-deep call chain at that position"),
            "the door must be able to FIND the chain the finding reported — this is the "
                + "address failing, not the refactoring declining: " + refusal);
    }
}
