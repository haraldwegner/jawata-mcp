package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.field.ClientDirectory;
import org.jawata.mcp.learn.EventTap;
import org.jawata.mcp.learn.SessionLedger;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c D14 (v13) — {@code origin_client}: which client recorded an entry.
 *
 * <p>Deferred at v12 with the reason written into the rung ("a column only
 * round-trips if the insert, the export projection, importEntries and
 * importFrom all carry it — this sprint has already shipped that gap three
 * times"), delivered at v13 on Harald's ruling, WITH all its carriage.</p>
 *
 * <p>The stamp is applied by the EventTap, not the tool: a tool sees only its
 * arguments, and the session — the thing that knows the client — arrives at
 * the tap with every completed call. These tests drive the SAME wiring the
 * application builds (tap → stamper closure → directory + store), because a
 * test that calls {@code setOriginClient} directly would prove the UPDATE
 * statement and nothing about the path production actually takes.</p>
 */
class OriginClientTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ClientDirectory directory;
    private EventTap tap;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
        directory = new ClientDirectory();
        tap = new EventTap(new SessionLedger(), null);
        // The application's own wiring, reproduced shape-for-shape
        // (JawataApplication: tap.setOriginStamper over directory + store).
        tap.setOriginStamper((sessionId, entryId) ->
            store.setOriginClient(entryId, directory.clientOf(sessionId).value()));
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    /** Record through the tool, then feed the completed call to the tap — the
     *  order production sees. */
    private String recordVia(String sessionId) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", "the heron ledger loses a fill when the amend races the cancel");
        a.put("situation", "when an amend and a cancel race on one heron ledger slot");
        a.put("verdict", "failed_avoid");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess());
        tap.onCall(sessionId, "experience", a, r, 5L);
        return (String) ((Map<?, ?>) r.getData()).get("id");
    }

    private String originOf(String id) {
        List<StoredEntry> rows = store.byIds(List.of(id));
        assertEquals(1, rows.size());
        return rows.get(0).facets().originClient();
    }

    @Test
    void a_recorded_entry_is_stamped_with_its_sessions_client() {
        directory.record("sess-1", "Claude Code 2.1");
        assertEquals("claude_code", originOf(recordVia("sess-1")),
            "the closed-vocabulary token, never the raw client string");
    }

    /**
     * NULL and 'unknown' are different facts. An unattributed session stamps
     * 'unknown' (the stamper RAN, the client was not identified); a row the tap
     * never saw stays NULL (nothing ever stamped it). Collapsing them would
     * turn "we don't know who" and "nobody asked" into one claim.
     */
    @Test
    void an_unattributed_session_stamps_unknown_and_an_untapped_row_stays_null() {
        assertEquals("unknown", originOf(recordVia("sess-never-initialized")));

        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "lesson");
        a.put("summary", "a second heron entry recorded with no tap in the path");
        a.put("situation", "when a record arrives through a surface with no session");
        a.put("verdict", "worked");
        String id = (String) ((Map<?, ?>) tool.execute(a).getData()).get("id");
        assertNull(originOf(id), "no tap ran, so nothing may claim an origin");
    }

    /** Only `record` is stamped: an import must not re-attribute rows to the
     *  importing session — the origin is who WROTE it, not who moved it. */
    @Test
    void an_import_keeps_the_original_attribution() {
        directory.record("sess-cursor", "cursor-agent");
        String id = recordVia("sess-cursor");
        assertEquals("cursor", originOf(id));

        List<Map<String, Object>> exported = store.exportEntries(null, null);
        store.wipe();
        directory.record("sess-other", "Claude Code");
        ObjectNode im = mapper.createObjectNode();
        im.put("kind", "import");
        im.set("entries", mapper.valueToTree(exported));
        ToolResponse r = tool.execute(im);
        assertTrue(r.isSuccess());
        // The completed IMPORT call reaches the tap too, as it does live.
        tap.onCall("sess-other", "experience", im, r, 5L);

        assertEquals("cursor", originOf(id),
            "the round trip carries the ORIGINAL client; the importing session's"
                + " identity is a fact about the move, not about the authorship");
    }

    /** The production READER: stats groups by origin. A column nothing reads is
     *  not delivered, however correctly it is written. */
    @Test
    void stats_reports_the_store_by_origin_client() {
        directory.record("sess-1", "Claude Code");
        recordVia("sess-1");
        Map<String, Object> stats = store.stats();
        @SuppressWarnings("unchecked")
        Map<String, Object> byOrigin = (Map<String, Object>) stats.get("by_origin_client");
        assertEquals(1L, ((Number) byOrigin.get("claude_code")).longValue(),
            () -> "the stamped entry must be countable by who recorded it: " + byOrigin);
    }
}
