package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import org.jawata.mcp.models.CallerIdentity;

/**
 * Sprint 26 Stage 4 (D4 + D5 + the D3 defect rule): the server-side lane's
 * scripted seat-session fixtures — clean, gate-skipping, outcome-less, and
 * STANCE-ADOPTED (no command signal exists anywhere in these fixtures: the
 * ledger correlation is mode-agnostic BY CONSTRUCTION and these tests prove
 * it) — plus the three message shapes and the both-or-refuse defect rule.
 */
class ServerChecksTest {

    private H2ExperienceStore store;
    private LearnerEventStore events;
    private SessionLedger ledger;
    private ServerChecks checks;
    private final List<String> filedDefects = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
        ledger = new SessionLedger();
        // S9a M1: WHICH tools are gates is no longer a literal in this package — it was, in
        // three byte-identical copies at once. The tools declare it and the registry
        // answers, so a test that exercises these checks has to wire the gates, exactly as
        // the application does at registration. Without this the checks fail CLOSED and a
        // gated seat session reads as ungated, which is the direction that makes the
        // omission a red test rather than a silent pass.
        checks = new ServerChecks(events, (s, d) -> filedDefects.add(s + "|" + d),
            org.jawata.mcp.fixtures.ShippedGates.registry());
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    private void call(String session, String tool, boolean ok, int files) {
        ledger.record(CallerIdentity.supplied(session), new SessionLedger.CallRecord(tool, ok, files,
            System.currentTimeMillis(), null));
    }

    /**
     * S9a M3: a seat outcome now goes through ACCEPTANCE, which runs BEFORE the
     * tool and REFUSES rather than appending a sentence afterwards. The helper
     * still answers with a message or null, so the fixtures below read as they
     * did — what changed is that the message is a refusal the caller receives
     * instead of the tool's answer. {@link #seatOutcomeRefusal} is the test that
     * pins the refusal itself.
     */
    private String seatOutcome(String session, String op) throws Exception {
        return refusalMessage(seatOutcomeResponse(session, op));
    }

    private ToolResponse seatOutcomeResponse(String session, String op) throws Exception {
        return checks.acceptance(CallerIdentity.supplied(session), "experience",
            mapper.readTree("{\"kind\":\"record\",\"operation\":\"" + op
                + "\",\"summary\":\"outcome\"}"),
            ledger);
    }

    /** The refusal's message, or null when acceptance let the call through. */
    private static String refusalMessage(ToolResponse refused) {
        return refused == null ? null : refused.getError().getMessage();
    }

    // ---------- D4: the four seat-session fixtures ----------

    @Test
    void clean_seat_session_passes_silently() throws Exception {
        call("s1", "compile_workspace", true, 0);
        assertNull(seatOutcome("s1", "seat:javadocs"), "gates ran — no flag");
    }

    @Test
    @DisplayName("a gate that RAN AND FAILED does not satisfy the check — it never answered")
    void a_failed_gate_call_does_not_count_as_a_gate_having_run() throws Exception {
        // Before this, the predicate read the tool NAME only, so this session was
        // ACCEPTED: it had called compile_workspace, and that the call blew up was
        // recorded on the row and never read.
        call("s-failed", "compile_workspace", false, 0);
        assertNotNull(refusalMessage(seatOutcomeResponse("s-failed", "seat:cover")),
            "a gate that failed is not a gate that passed");

        // THE CONTROL, and it is what makes the assertion above about the OUTCOME rather
        // than about the tool name: the identical call that SUCCEEDED is let through.
        call("s-ok", "compile_workspace", true, 0);
        assertNull(seatOutcomeResponse("s-ok", "seat:cover"),
            "the same gate, having answered, is accepted");
    }

    @Test
    @DisplayName("gate-skipping (stance-adopted — no command anywhere): flagged + journaled")
    void gate_skipping_seat_session_is_flagged() throws Exception {
        call("s2", "search_symbols", true, 0);
        String warning = seatOutcome("s2", "seat:cover");
        assertNotNull(warning);
        assertTrue(warning.contains("NOT passed"));
        assertEquals(1L, events.countByKind()
            .getOrDefault(ServerChecks.KIND_SEAT_GATE_SKIP, 0L));
    }

    @Test
    void outcome_less_seat_shaped_session_is_flagged_unjournaled_at_eviction() {
        ledger.setEvictionListener(checks::onSessionEvicted);
        call("busy", "find_quality_issue", true, 0);
        call("busy", "rename_symbol", true, 2);
        // Force eviction of "busy" by flooding the LRU.
        for (int i = 0; i <= 64; i++) {
            call("filler-" + i, "health_check", true, 0);
        }
        assertEquals(1L, events.countByKind()
            .getOrDefault(ServerChecks.KIND_SEAT_UNJOURNALED, 0L),
            "seat-shaped + no outcome + evicted = unjournaled run");
    }

    // ---------- D5: the three message shapes ----------

    @Test
    void all_three_message_shapes_without_audit_evidence_are_flagged() throws Exception {
        String[] shapes = {
            "DECISION: ship or hold the release?",
            "What shipped … ⏸ awaiting \"continue\" for Stage 3",
            "SPRINT 27 CLOSED — the result summary"
        };
        int flagged = 0;
        for (int i = 0; i < shapes.length; i++) {
            String session = "shape-" + i;
            call(session, "experience", true, 0); // the record call itself
            String block = checks.onCompleted(CallerIdentity.supplied(session), "experience",
                mapper.readTree(mapper.createObjectNode()
                    .put("kind", "record").put("operation", "notes")
                    .put("summary", shapes[i]).toString()),
                ledger);
            if (block != null && block.contains("THE DECISION TEST")) {
                flagged++;
            }
        }
        assertEquals(3, flagged, "each shape gets the decision test attached");
        assertEquals(3L, events.countByKind()
            .getOrDefault(ServerChecks.KIND_UNAUDITED_ASK, 0L));
    }

    @Test
    void audited_ask_passes_silently() throws Exception {
        // Audit evidence arrives FIRST (a communication-audit record).
        call("aud", "experience", true, 0);
        checks.onCompleted(CallerIdentity.supplied("aud"), "experience",
            mapper.readTree("{\"kind\":\"record\",\"operation\":\"communication-audit\","
                + "\"summary\":\"audited\"}"),
            ledger);
        call("aud", "experience", true, 0);
        String block = checks.onCompleted(CallerIdentity.supplied("aud"), "experience",
            mapper.readTree(mapper.createObjectNode()
                .put("kind", "record").put("operation", "notes")
                .put("summary", "DECISION: proceed?").toString()),
            ledger);
        assertNull(block, "audit evidence in the session — no flag");
    }

    @Test
    void an_ask_shaped_call_pattern_gets_the_test_preemptively() throws Exception {
        for (String t : List.of("find_quality_issue", "compile_workspace",
                "find_references", "run_tests")) {
            call("ask", t, true, 0);
        }
        String block = checks.onCompleted(CallerIdentity.supplied("ask"), "compile_workspace",
            mapper.readTree("{}"), ledger);
        assertNotNull(block);
        assertTrue(block.contains("THE DECISION TEST"));
    }

    // ---------- D3: the both-or-refuse defect rule ----------

    @Test
    void fallback_after_a_jawata_error_relaxes_AND_files_a_defect_together() throws Exception {
        call("d1", "rename_symbol", false, 0); // the jawata tool error
        checks.onCompleted(CallerIdentity.supplied("d1"), "experience",
            mapper.readTree("{\"kind\":\"record\",\"operation\":\"jawata-fallback-slip\","
                + "\"summary\":\"declared fallback after rename failed\"}"),
            ledger);
        Map<String, Long> counts = events.countByKind();
        assertEquals(1L, counts.getOrDefault(ServerChecks.KIND_RELAX_LABEL, 0L));
        assertEquals(1L, counts.getOrDefault(ServerChecks.KIND_DEFECT_FILED, 0L));
        assertEquals(1, filedDefects.size(), "the defect reached the store filer");
        assertTrue(filedDefects.get(0).contains("auto-filed"));
    }

    @Test
    void the_policy_dial_moves_both_directions_on_seeded_streams() throws Exception {
        PolicyDial dial = checks.dial();
        assertEquals(PolicyDial.Level.STEER, dial.level("fallback"), "cold start");
        // Seeded slip stream (no prior errors) → TIGHTENS to BLOCK.
        for (int i = 0; i < PolicyDial.TIGHTEN_AT; i++) {
            call("t" + i, "search_symbols", true, 0);
            checks.onCompleted(CallerIdentity.supplied("t" + i), "experience",
                mapper.readTree("{\"kind\":\"record\",\"operation\":\"jawata-fallback-slip\","
                    + "\"summary\":\"slip\"}"),
                ledger);
        }
        assertEquals(PolicyDial.Level.BLOCK, dial.level("fallback"), "slips tighten");
        // Seeded relax stream (error-then-fallback) → LOOSENS back past STEER.
        for (int i = 0; i < PolicyDial.TIGHTEN_AT + PolicyDial.RELAX_AT + 3; i++) {
            String s = "r" + i;
            call(s, "rename_symbol", false, 0);
            checks.onCompleted(CallerIdentity.supplied(s), "experience",
                mapper.readTree("{\"kind\":\"record\",\"operation\":\"jawata-fallback-slip\","
                    + "\"summary\":\"after error\"}"),
                ledger);
        }
        assertEquals(PolicyDial.Level.SILENT, dial.level("fallback"), "relaxes loosen");
    }

    @Test
    void a_fallback_without_a_prior_error_files_nothing() throws Exception {
        call("d2", "search_symbols", true, 0);
        checks.onCompleted(CallerIdentity.supplied("d2"), "experience",
            mapper.readTree("{\"kind\":\"record\",\"operation\":\"jawata-fallback-slip\","
                + "\"summary\":\"plain slip\"}"),
            ledger);
        assertEquals(0, filedDefects.size());
        assertEquals(0L, events.countByKind()
            .getOrDefault(ServerChecks.KIND_RELAX_LABEL, 0L));
    }
    @Test
    @DisplayName("S9a M4: a check on a RESIDENT-MINTED identity says the history may be shared; on a client-supplied one it does not")
    void theSharedHistoryCaveatIsAboutTheOriginAndNotAboutEveryAnswer() throws Exception {
        // This is the one place anything READS CallerIdentity#origin, so without
        // this case the origin would be a field nothing decides anything by —
        // carried, stored, and read by nobody.
        CallerIdentity minted = CallerIdentity.minted("http");
        CallerIdentity theirs = CallerIdentity.supplied("s-caveat");

        // Both have a non-gate call behind them, so BOTH checks fire. That is
        // what makes the two answers comparable: the caveat has to be the only
        // difference between them.
        for (CallerIdentity who : List.of(minted, theirs)) {
            ledger.record(who, new SessionLedger.CallRecord("search_symbols", true, 0,
                System.currentTimeMillis(), null));
        }
        com.fasterxml.jackson.databind.JsonNode args = mapper.readTree(
            "{\"kind\":\"record\",\"operation\":\"seat:cover\",\"summary\":\"outcome\"}");

        String onMinted = refusalMessage(checks.acceptance(minted, "experience", args, ledger));
        String onTheirs = refusalMessage(checks.acceptance(theirs, "experience", args, ledger));

        assertAll(
            () -> assertNotNull(onMinted, "proof of life: the check fired for the minted identity"),
            () -> assertNotNull(onTheirs, "proof of life: and for the client-supplied one too"),
            () -> assertTrue(onMinted.contains("sent no session id"),
                "a minted identity is shared by every header-less caller of that transport,"
                    + " so a conclusion drawn from its history may land on the wrong agent: "
                    + onMinted),
            () -> assertFalse(onTheirs.contains("sent no session id"),
                "and a client's own identity gets NO caveat — otherwise the sentence would be"
                    + " decoration rather than a fact about this caller: " + onTheirs));
    }
    @Test
    @DisplayName("S9a M3: a seat outcome over a gate that never ran is REFUSED through the refusal channel, not appended as steering")
    void seatOutcomeRefusal() throws Exception {
        call("m3", "search_symbols", true, 0);

        ToolResponse refused = seatOutcomeResponse("m3", "seat:cover");

        assertNotNull(refused, "acceptance must not let an ungated seat outcome through");
        assertAll(
            () -> assertFalse(refused.isSuccess(),
                "a REFUSAL, where this used to be a successful record with a remark on it"),
            () -> assertEquals("SEAT_GATE_NOT_RUN", refused.getError().getCode(),
                "a code a caller can branch on, not a sentence it has to match"),
            () -> assertNotNull(refused.getError().getHint(), "and it says what to do instead"),
            () -> assertTrue(refused.getError().getMessage().contains("REFUSED"),
                "the message says which it is: " + refused.getError().getMessage()));

        // THE CONTROL: the same call with a gate behind it is accepted. Without it
        // this case would pass against an acceptance path that refuses everything.
        call("m3-ok", "compile_workspace", true, 0);
        assertNull(seatOutcomeResponse("m3-ok", "seat:cover"),
            "a session that DID run its gate is let through untouched");
    }
}
