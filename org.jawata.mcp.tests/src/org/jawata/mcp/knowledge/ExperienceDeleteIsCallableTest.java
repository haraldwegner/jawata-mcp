package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S16-7 — a verb has to be callable with the parameters the tool PUBLISHES.
 *
 * <p>{@code delete} required a parameter literally named {@code ids} as a non-empty array, and the
 * published input schema declared none (it listed {@code id} for promote and {@code selected_ids}
 * for decide). A client builds its call from the schema, so every client call failed with
 * INVALID_PARAMETER: three of them sit in the field pile. The consequence was concrete — an agent
 * could not remove a malformed entry it had itself written.</p>
 *
 * <p>The first test reads the schema, which is what a client reads; the second makes the call the
 * schema describes and checks the right rows go and the others stay, because a declared parameter
 * that the verb ignored would pass the first and do harm in the second.</p>
 */
class ExperienceDeleteIsCallableTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String put(H2ExperienceStore store, String summary) {
        return store.put(SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
            .symbol("com.example.Target").build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void the_published_schema_declares_ids_as_an_array_of_strings() {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            Map<String, Object> schema = new ExperienceTool(() -> null, store).getInputSchema();
            Map<String, Object> props = (Map<String, Object>) schema.get("properties");

            Map<String, Object> ids = (Map<String, Object>) props.get("ids");
            assertNotNull(ids, "delete and promote_rule read `ids`; a client can only send what is"
                + " declared, so it has to be declared: " + props.keySet());
            assertEquals("array", ids.get("type"));
            assertEquals(Map.of("type", "string"), ids.get("items"));
        }
    }

    @Test
    void delete_by_ids_removes_exactly_the_named_entry() {
        try (H2ExperienceStore store = H2ExperienceStore.openMemory()) {
            String keep = put(store, "keep this one");
            String drop = put(store, "delete this one");

            ObjectNode call = JSON.createObjectNode();
            call.put("kind", "delete");
            call.putArray("ids").add(drop);
            ToolResponse response = new ExperienceTool(() -> null, store).execute(call);

            assertTrue(response.isSuccess(), "the call the schema describes must work: " + response);
            assertTrue(store.get(keep).isPresent(), "the entry that was not named is still there");
            assertFalse(store.get(drop).isPresent(), "the entry that was named is gone");
            assertEquals(1L, store.count());
            assertEquals(List.of(keep), store.all().stream().map(StoredEntry::id).toList());
        }
    }
}
