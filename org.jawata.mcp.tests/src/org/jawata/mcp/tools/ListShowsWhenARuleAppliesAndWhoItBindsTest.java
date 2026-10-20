package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * S24-3 — THE SCREEN WHERE HE ACCEPTS A RULE MUST SHOW WHEN IT APPLIES AND WHO IT BINDS.
 *
 * <p>The rottenmeier seat's proposals are curated through {@code experience list}. That row carried the
 * rule's sentence, lane, version and date, and neither its {@code situation} nor its {@code operation}
 * (the seat or activity it binds), although the engine refuses a proposal without both. He judged
 * seven rules without seeing the condition of any of them, and said so.</p>
 */
class ListShowsWhenARuleAppliesAndWhoItBindsTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> listRules() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "list");
        args.put("lane", "rules");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), String.valueOf(r));
        return (List<Map<String, Object>>) ((Map<String, Object>) r.getData()).get("entries");
    }

    private void proposal(String operation) {
        ObjectNode fact = mapper.createObjectNode();
        fact.put("kind", "record");
        fact.put("type", "domain_fact");
        fact.put("summary", "a source the rule is drawn from");
        assertTrue(tool.execute(fact).isSuccess());
        String source = store.all().get(0).id();
        ObjectNode rule = mapper.createObjectNode();
        rule.put("kind", "promote_rule");
        rule.put("status", "candidate");
        rule.set("ids", mapper.createArrayNode().add(source));
        rule.put("summary", "Start the release; do not ask first.");
        rule.put("situation", "he has told you to release and you are about to ask him a question");
        rule.put("operation", operation);
        assertTrue(tool.execute(rule).isSuccess());
    }

    @Test
    void a_proposed_rule_is_listed_with_its_condition_and_its_binding() {
        proposal("release");

        Map<String, Object> row = listRules().get(0);

        assertEquals("he has told you to release and you are about to ask him a question", row.get("situation"));
        assertEquals("release", row.get("operation"));
    }

    @Test
    void a_rule_that_binds_every_seat_says_so_rather_than_showing_nothing() {
        proposal("all");

        Map<String, Object> row = listRules().get(0);

        assertEquals("all", row.get("operation"),
            "the store keeps 'all' as no operation; the screen must still say which seats it binds");
        assertFalse(String.valueOf(row.get("situation")).isBlank());
    }
}
