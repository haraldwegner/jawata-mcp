package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S21 — the transcript lane, driven through the tool the way a hook drives it.
 *
 * <p>Each case is one line of the spec's Measure list: the discussion is stored and found again
 * as a quote with a pointer to its turn; the rows hold his words, the agent's words and one line
 * per tool call; the switch leaves no rows; no export, review or wipe touches the lane; and "all"
 * never reaches it.</p>
 */
class TranscriptLaneTest {

    private static final String SESSION = "4526476c-40e7-4ce7-b547-322dc393449c";

    private H2ExperienceStore store;
    private ObjectMapper json;
    private ExperienceTool tool;
    private TranscriptStore lane;
    private String savedSwitch;

    @BeforeEach
    void setUp() {
        savedSwitch = System.getProperty(TranscriptStore.ENABLED_PROPERTY);
        System.clearProperty(TranscriptStore.ENABLED_PROPERTY);
        store = H2ExperienceStore.openMemory();
        json = new ObjectMapper();
        tool = new ExperienceTool(() -> null, store);
        lane = new TranscriptStore(store);
        tool.setTranscriptStore(lane);
    }

    @AfterEach
    void tearDown() {
        if (savedSwitch == null) {
            System.clearProperty(TranscriptStore.ENABLED_PROPERTY);
        } else {
            System.setProperty(TranscriptStore.ENABLED_PROPERTY, savedSwitch);
        }
        store.close();
    }

    private ObjectNode append(int turn, String his, String agent, String... toolLines) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "transcript_append");
        a.put("session", SESSION);
        a.put("key", "record-" + turn);
        a.put("ts", "2026-10-17T10:" + String.format("%02d", turn) + ":00Z");
        a.put("client", "claude-code");
        a.put("his", his);
        a.put("agent", agent);
        ArrayNode tools = a.putArray("tools");
        for (String t : toolLines) {
            tools.add(t);
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> answer(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "the call must be answered: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode recall(String cue, String... lanes) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "recall");
        a.put("symptom", cue);
        ArrayNode l = a.putArray("lanes");
        for (String s : lanes) {
            l.add(s);
        }
        return a;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entries(Map<String, Object> answer) {
        return (List<Map<String, Object>>) answer.get("entries");
    }

    private void seedDiscussion() {
        assertEquals("stored", answer(append(1,
            "We need the complete transcript in the store, so never forget anything after compacting.",
            "Understood: I will keep the discussion in its own table.",
            "Read src/notes.md ok")).get("appended"));
        assertEquals("stored", answer(append(2,
            "No size cap, just compression. The works council needs a switch to turn it off.",
            "The switch is a deployment setting; off captures nothing.",
            "Bash mvn test failed")).get("appended"));
        assertEquals("stored", answer(append(3,
            "Fast retrieval matters most, on three occasions only.",
            "After a compaction, before attributing to you, and on demand.")).get("appended"));
    }

    // ---- stored, and found again as a quote with the turn it came from -------------------

    @Test
    void aSentenceFromEarlierIsFoundAsAQuoteWithItsTurn() {
        seedDiscussion();

        Map<String, Object> found = answer(recall("works council switch to turn it off", "transcript"));
        List<Map<String, Object>> rows = entries(found);
        assertFalse(rows.isEmpty(), () -> "PROOF OF LIFE: the sentence is found: " + found);
        Map<String, Object> first = rows.get(0);
        assertEquals("transcript", first.get("type"));
        assertEquals(2, first.get("turn"), () -> "the pointer names the turn it was said in: " + first);
        assertEquals(SESSION, first.get("session"));
        assertTrue(String.valueOf(first.get("summary")).contains("works council needs a switch"),
            () -> "the quote is his words: " + first);
        assertEquals("he", first.get("who"));
        assertTrue(String.valueOf(first.get("situation")).contains("2026-10-17"),
            () -> "the answer says when: " + first);
        assertEquals(Map.of("transcript", 3L), found.get("scanned"),
            "the answer says how many turns it searched");
    }

    @Test
    void theTextFormHoldsTheQuoteAndHowManyTurnsWereSearched() {
        seedDiscussion();
        ObjectNode a = recall("compression size cap", "transcript");
        a.put("format", "text");
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess());
        String text = String.valueOf(r.getData());
        assertTrue(text.contains("No size cap"), () -> "the text carries the quote: " + text);
        assertTrue(text.contains("SEARCHED: transcript 3 rows"), () -> "and the count: " + text);
    }

    // ---- the rows hold his words, the agent's and one line per tool call ----------------

    @Test
    void aToolLineIsFoundAndSaidToBeTheAgentsRun() {
        seedDiscussion();
        Map<String, Object> found = answer(recall("mvn test failed", "transcript"));
        Map<String, Object> first = entries(found).get(0);
        assertEquals("tool", first.get("who"), () -> "a tool line is not his and not the agent's words: " + first);
        assertEquals(2, first.get("turn"));
    }

    @Test
    void repeatingATurnStoresItOnceAndALongerCaptureCompletesIt() {
        assertEquals("stored", answer(append(7, "first part", "")).get("appended"));
        assertEquals("duplicate", answer(append(7, "first part", "")).get("appended"));
        assertEquals("completed", answer(append(7, "first part and the rest of it", "an answer")).get("appended"));
        assertEquals("duplicate", answer(append(7, "first part", "")).get("appended"),
            "a shorter capture never replaces a longer one");
        assertEquals(1L, lane.count());
    }

    // ---- nothing is asked unless the lane is named ---------------------------------------

    @Test
    void allDoesNotReachTheDiscussionAndNominateRefusesIt() {
        seedDiscussion();
        Map<String, Object> viaAll = answer(recall("works council switch", "all"));
        assertFalse(String.valueOf(viaAll).contains("works council needs a switch"),
            () -> "'all' is every lane of knowledge, never the discussion: " + viaAll);

        ObjectNode nominate = json.createObjectNode();
        nominate.put("kind", "nominate");
        nominate.put("question", "what did we say about the switch?");
        nominate.putArray("lanes").add("transcript");
        ToolResponse r = tool.execute(nominate);
        assertFalse(r.isSuccess(), "the discussion is recalled for words, never nominated");
        assertTrue(String.valueOf(r.getError()).contains("recall"), () -> "and the refusal says where to ask: " + r.getError());
    }

    @Test
    void aCallNamingTheTranscriptAndAnotherLaneKeepsBothAnswers() {
        seedDiscussion();
        SymbolFact f = SymbolFact.of("domain_fact",
            "The switch for the works council is a deployment setting", Confidence.MEDIUM).build();
        store.put(ExperienceEntry.of(f).status(ExperienceEntry.ACCEPTED)
            .symptoms(List.of("works council switch")).build());

        Map<String, Object> both = answer(recall("works council switch", "domain", "transcript"));
        List<String> types = entries(both).stream().map(e -> String.valueOf(e.get("type"))).toList();
        assertTrue(types.contains("transcript"), () -> "the quote arrives: " + both);
        assertTrue(types.contains("domain_fact"), () -> "and so does the domain fact: " + both);
        Map<?, ?> scanned = (Map<?, ?>) both.get("scanned");
        assertTrue(scanned.containsKey("transcript") && scanned.containsKey("domain"),
            () -> "each lane says what it searched: " + scanned);
    }

    // ---- the switch --------------------------------------------------------------------

    @Test
    void withTheSwitchOffNothingIsStoredAndNoRowsRemain() {
        seedDiscussion();
        assertEquals(3L, lane.count());

        System.setProperty(TranscriptStore.ENABLED_PROPERTY, "false");
        assertEquals("disabled", answer(append(4, "after the switch", "nothing is kept")).get("appended"));
        assertEquals(0L, lane.count(), "the rows that were there are gone, not hidden");

        Map<String, Object> asked = answer(recall("works council switch", "transcript"));
        assertTrue(entries(asked).isEmpty());
        assertTrue(String.valueOf(asked.get("transcript_note")).contains("OFF"),
            () -> "an off lane SAYS it is off; it does not answer as if it had found nothing: " + asked);

        System.clearProperty(TranscriptStore.ENABLED_PROPERTY);
        assertEquals(0L, lane.count(), "switching it back on does not bring the discussion back");
    }

    @Test
    void aResidentStartedWithTheSwitchOffRemovesWhatWasLeftBehind() {
        seedDiscussion();
        System.setProperty(TranscriptStore.ENABLED_PROPERTY, "off");
        new TranscriptStore(store);
        System.clearProperty(TranscriptStore.ENABLED_PROPERTY);
        assertEquals(0L, new TranscriptStore(store).count());
    }

    // ---- no export, review, import or wipe touches the lane ------------------------------

    @Test
    void theVerbsThatWalkTheKnowledgeNeverReachTheDiscussion() {
        seedDiscussion();
        store.put(ExperienceEntry.of(SymbolFact.of("domain_fact", "one fact of knowledge", Confidence.MEDIUM).build())
            .status(ExperienceEntry.ACCEPTED).build());

        ObjectNode export = json.createObjectNode();
        export.put("kind", "export");
        String exported = String.valueOf(answer(export));
        assertFalse(exported.contains("works council"), () -> "export carries no transcript: " + exported);

        ObjectNode sweep = json.createObjectNode();
        sweep.put("kind", "review_sweep");
        assertFalse(String.valueOf(answer(sweep)).contains("works council"));

        ObjectNode prune = json.createObjectNode();
        prune.put("kind", "prune");
        prune.put("days", 0);
        answer(prune);

        ObjectNode wipe = json.createObjectNode();
        wipe.put("kind", "wipe");
        answer(wipe);

        assertEquals(3L, lane.count(), "wiping the knowledge leaves the discussion where it was");
        assertEquals(0L, store.count(), "and the knowledge is gone, so the wipe really ran");
    }

    @Test
    void statsSaysWhetherTheLaneIsOnAndHowManyTurnsItHolds() {
        seedDiscussion();
        ObjectNode stats = json.createObjectNode();
        stats.put("kind", "stats");
        Map<?, ?> block = (Map<?, ?>) answer(stats).get("transcript");
        assertEquals(true, block.get("enabled"));
        assertEquals(true, block.get("installed"));
        assertEquals(3L, block.get("turns"));
        assertEquals(0L, block.get("failedWrites"), "and how many turns it failed to keep");
    }

    // ---- before something is attributed to him -------------------------------------------

    @Test
    void aQuoteIsHisOnlyIfHeSaidIt() {
        seedDiscussion();
        ObjectNode verify = json.createObjectNode();
        verify.put("kind", "transcript_verify");

        verify.put("quote", "No size cap, just compression.");
        Map<String, Object> said = answer(verify);
        assertEquals(true, said.get("found"), () -> "his words are found: " + said);
        assertEquals(2, ((Map<?, ?>) said.get("entry")).get("turn"));

        verify.put("quote", "The switch is a deployment setting; off captures nothing.");
        Map<String, Object> agents = answer(verify);
        assertEquals(false, agents.get("found"),
            () -> "the agent's own sentence is not his, and is not found among his words: " + agents);

        verify.put("quote", "We should rewrite everything in Rust tonight.");
        Map<String, Object> never = answer(verify);
        assertEquals(false, never.get("found"));
        assertEquals(true, never.get("checked"), "a quote not found was checked and not found");
    }

    @Test
    void aLaneThatCannotCheckSaysSoInsteadOfSayingNotFound() {
        seedDiscussion();
        System.setProperty(TranscriptStore.ENABLED_PROPERTY, "false");
        ObjectNode verify = json.createObjectNode();
        verify.put("kind", "transcript_verify");
        verify.put("quote", "No size cap, just compression.");
        Map<String, Object> off = answer(verify);
        assertEquals(false, off.get("checked"),
            () -> "an off lane did not check; 'not found' would be a lie: " + off);
    }

    /**
     * The first audit of C21: the check cut to the 40 best turns across every speaker and only then
     * kept his, so enough later agent turns echoing a ruling pushed the ruling out and the stop hook
     * told the agent it had invented words he really said.
     */
    @Test
    void hisSentenceIsStillFoundWhenTheAgentEchoedItInMoreTurnsThanTheCheckReads() {
        String ruling = "No size cap, just compression, and the works council gets a switch.";
        assertEquals("stored", answer(append(1, ruling, "Noted.")).get("appended"));
        // More echoes than the check reads in full: a check that cuts before it knows who spoke
        // loses his sentence here, and one that decides the speaker first does not.
        java.time.Instant start = java.time.Instant.parse("2026-10-18T00:00:00Z");
        for (int turn = 2; turn <= TranscriptStore.MAX_QUOTE_CANDIDATES + 20; turn++) {
            assertEquals(TranscriptStore.Appended.STORED, lane.append(new TranscriptStore.Turn(
                SESSION, "echo-" + turn, start.plusSeconds(turn), "claude-code", "turn",
                "ok", "As you ruled: " + ruling, List.of())));
        }
        ObjectNode verify = json.createObjectNode();
        verify.put("kind", "transcript_verify");
        verify.put("quote", ruling);
        Map<String, Object> said = answer(verify);
        assertEquals(true, said.get("found"), () -> "his sentence is found past the echoes: " + said);
        assertEquals(1, ((Map<?, ?>) said.get("entry")).get("turn"));
    }

    @Test
    void aLaneWithNothingHeldOrNothingToLookForCannotVouchEitherWay() {
        ObjectNode verify = json.createObjectNode();
        verify.put("kind", "transcript_verify");
        verify.put("quote", "No size cap, just compression.");
        Map<String, Object> empty = answer(verify);
        assertEquals(false, empty.get("checked"),
            () -> "an empty lane holds no evidence either way, so it does not accuse: " + empty);

        seedDiscussion();
        verify.put("quote", "to be or no");
        Map<String, Object> plain = answer(verify);
        assertEquals(false, plain.get("checked"),
            () -> "a quote with no word long enough to look up was not checked: " + plain);
    }

    @Test
    void aStoreThatHeldTurnsBeforeThePostingsOfHisWordsExistedBuildsThemWhenTheLaneOpens()
            throws Exception {
        seedDiscussion();
        try (java.sql.Statement s = store.sharedConnection().createStatement()) {
            s.execute("DROP TABLE transcript_his_term");
        }
        new TranscriptStore(store);
        ObjectNode verify = json.createObjectNode();
        verify.put("kind", "transcript_verify");
        verify.put("quote", "No size cap, just compression.");
        Map<String, Object> said = answer(verify);
        assertEquals(true, said.get("found"),
            () -> "turns stored by an earlier build of this version are still his: " + said);
    }

    @Test
    void theCountOfAMergedAnswerIsTheCountOfItsRows() {
        seedDiscussion();
        Map<String, Object> base = new java.util.LinkedHashMap<>();
        base.put("count", 0);
        base.put("result", ExperienceRetrieval.RESULT_ABSENCE);
        base.put("message", "nothing in the knowledge lanes");
        Map<String, Object> merged = TranscriptAnswer.merge(base, lane, "works council switch", 5);
        assertFalse(entries(merged).isEmpty());
        assertEquals(entries(merged).size(), merged.get("count"),
            () -> "a match with quote rows does not say count 0: " + merged);
    }

    // ---- the thread after a compaction -----------------------------------------------------

    @Test
    void recentGivesHisLastMessagesNewestFirstWithTheirTurns() {
        seedDiscussion();
        ObjectNode a = json.createObjectNode();
        a.put("kind", "recall");
        a.putArray("lanes").add("transcript");
        a.put("recent", 2);
        List<Map<String, Object>> rows = entries(answer(a));
        assertEquals(2, rows.size());
        assertEquals(3, rows.get(0).get("turn"), "newest first");
        assertEquals(2, rows.get(1).get("turn"));
        assertEquals("he", rows.get(0).get("who"));
    }

    @Test
    void recentWithAnotherLaneIsRefusedBecauseItCarriesNoCue() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "recall");
        a.putArray("lanes").add("transcript").add("experience");
        a.put("recent", 2);
        ToolResponse r = tool.execute(a);
        assertFalse(r.isSuccess());
    }

    @Test
    void aResidentWithoutTheLaneSaysItIsNotInstalled() {
        ExperienceTool bare = new ExperienceTool(() -> null, store);
        ObjectNode a = recall("works council", "transcript");
        ToolResponse r = bare.execute(a);
        assertTrue(r.isSuccess());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(String.valueOf(data.get("transcript_note")).contains("NOT INSTALLED"),
            () -> "a missing lane is said, not answered as an empty one: " + data);
        assertNotEquals(0, ((Map<?, ?>) data).size());
    }

    @Test
    void theLaneIsPublishedBeforeTheSearchLikeEveryOther() {
        assertTrue(LaneScope.known().contains("transcript"), LaneScope::known);
        assertFalse(LaneScope.everyLane().contains(KnowledgeLane.TRANSCRIPT));
        assertTrue(LaneScope.parse("transcript").contains(KnowledgeLane.TRANSCRIPT));
    }
}
