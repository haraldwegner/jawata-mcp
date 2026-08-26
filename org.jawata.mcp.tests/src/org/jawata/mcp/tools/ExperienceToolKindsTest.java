package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A VERB THE SWITCH HANDLES AND THE SCHEMA OMITS IS UNDISCOVERABLE.
 *
 * <h2>What was broken</h2>
 *
 * <p>v3.14.0 shipped {@code review_sweep} and {@code delete}. Both dispatched.
 * Neither appeared in {@code ExperienceTool}'s {@code KINDS} list — which is the
 * ONLY thing an agent can see, because it is what {@code tools/list} publishes
 * and what the unknown-kind error names. So both verbs worked and no agent
 * reading the schema could learn they existed.</p>
 *
 * <p>It bit hardest on {@code review_sweep}, which {@code UsageLedger}'s own
 * javadoc calls its only reader: <em>"the only readers are the review sweep's
 * two lists."</em> The sprint spent itself on the rule that <em>a journal nobody
 * reads is a table, not a record</em> — and then gave the reader no door handle.</p>
 *
 * <h2>Why every existing test stayed green</h2>
 *
 * <p>Because they call the verbs BY NAME, and a name dispatches whether or not
 * it is published. The gap lives in the difference between what the switch
 * accepts and what the schema advertises, and nothing that names a verb can see
 * it. It took a dogfood run to find. These tests read the PUBLISHED schema
 * rather than the private constant, because the published schema is what the
 * agent actually gets.</p>
 */
class ExperienceToolKindsTest {

    private ObjectMapper mapper;
    private ExperienceStore store;
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

    /** The enum as an agent receives it — through {@code getInputSchema}, not the field. */
    @SuppressWarnings("unchecked")
    private List<String> advertised() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> props = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) props.get("kind");
        return (List<String>) kind.get("enum");
    }

    private ToolResponse call(String kind) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        return tool.execute(a);
    }

    private static boolean isUnknownKind(ToolResponse r) {
        return !r.isSuccess()
            && r.getError() != null
            && r.getError().getMessage() != null
            && r.getError().getMessage().contains("Unknown kind");
    }

    /**
     * THE REGRESSION. Both verbs execute AND are advertised — asserted together,
     * because either half alone passes for the wrong reason: a verb that is
     * advertised but not dispatched satisfies the second, and v3.14.0 itself
     * satisfied the first.
     */
    @Test
    void the_two_verbs_v3_14_0_added_are_dispatched_and_advertised() {
        for (String verb : List.of("review_sweep", "delete")) {
            assertFalse(isUnknownKind(call(verb)),
                () -> verb + " must dispatch — the switch handles it");
            assertTrue(advertised().contains(verb),
                () -> verb + " dispatches but is NOT in the published schema enum, so no"
                    + " agent reading tools/list can find it. Advertised: " + advertised());
        }
    }

    /**
     * The other direction: nothing advertised may be undispatched. A published
     * verb that falls through to {@code default} tells an agent a capability
     * exists which does not — the exact claim the {@code KINDS} javadoc says the
     * retired ML kinds were deleted to stop making.
     *
     * <p>Several kinds legitimately fail here for want of their own arguments;
     * that is fine and is not what is being asserted. The one answer that means
     * "this verb does not exist" is the unknown-kind branch.</p>
     */
    @Test
    void every_advertised_kind_actually_dispatches() {
        for (String kind : advertised()) {
            assertFalse(isUnknownKind(call(kind)),
                () -> "'" + kind + "' is advertised in the schema but falls through to the"
                    + " default branch — it is published and does not exist");
        }
    }

    /**
     * The javadoc on {@code KINDS} claims one list serves both the schema and the
     * error text. Pin it: an unknown kind's message must name the new verbs too,
     * or an agent that guessed wrong is handed a list that is missing exactly the
     * verbs it could not have guessed.
     */
    @Test
    void the_unknown_kind_error_lists_the_new_verbs_as_well() {
        ToolResponse r = call("no_such_kind_exists");
        assertTrue(isUnknownKind(r), () -> "precondition: this must be the unknown branch");
        String message = r.getError().getMessage();
        assertTrue(message.contains("review_sweep") && message.contains("delete"),
            () -> "the allowed-list in the error omits the new verbs: " + message);
    }
}
