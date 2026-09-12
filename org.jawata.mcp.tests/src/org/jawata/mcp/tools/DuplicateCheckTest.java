package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 8 D3 — IS THIS JOB ALREADY DONE?
 *
 * <p>The engine half of the duplicate gate. The gate judges; this is what it asks. The
 * question is put BY MEANING, which is the only way it can work: a re-derived job is by
 * definition the one whose name and structure differ from the original, so a name-equality
 * check would find only the duplicates that were never a problem.</p>
 *
 * <p>It loads NO project, and that is asserted rather than incidental. The gate fires on a
 * hook, at a moment when a resident may well have nothing loaded and the file being written
 * may not exist yet — so the verb has to answer anyway, and has to SAY that it could not
 * subtract what the file already declares instead of quietly treating that as nothing.</p>
 */
class DuplicateCheckTest {

    private static final String OWNER = "com.example.SourceScan#parse";

    /**
     * A SECOND job, so the ranking has to CHOOSE — without it nothing here could fail.
     *
     * <p>With one job recorded, rank one is that job whatever question is asked, so every
     * assertion naming it was true of any ranking at all, including one that read no
     * comment and no name. The class claims the lane is asked BY MEANING and that claim
     * was carried entirely by its own prose.</p>
     */
    private static final String ROUNDER = "com.example.Money#round";

    private ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
        json = new ObjectMapper();
        recordJob(OWNER,
            "Parse a compilation unit with binding resolution and answer the syntax tree.");
        recordJob(ROUNDER,
            "Round a monetary amount to the nearest cent, half up.");
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    private void recordJob(String symbol, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", symbol);
        a.put("summary", summary);
        assertTrue(tool.execute(a).isSuccess(), "the job must be recordable");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> check(String filePath, String draft) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "duplicate_check");
        if (filePath != null) {
            a.put("filePath", filePath);
        }
        a.put("draft", draft);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "duplicate_check must answer — refused with: "
            + (r.getError() != null
                ? r.getError().getCode() + " / " + r.getError().getMessage()
                : "(no error info)"));
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> nominees(Map<String, Object> data) {
        return (List<Map<String, Object>>) data.get("nominees");
    }

    @Test
    @DisplayName("a draft re-deriving a known job is nominated, and the job is NAMED")
    void aDraftReDerivingAKnownJobIsNominated() {
        Map<String, Object> data = check("/p/src/Other.java", """
            /** Parses a compilation unit, resolving bindings, and returns the syntax tree. */
            private static Tree buildSyntaxTree(Source source) {
                return null;
            }
            """);
        List<Map<String, Object>> hits = nominees(data);
        assertFalse(hits.isEmpty(),
            "the draft says in its own comment that it parses a compilation unit and"
                + " resolves bindings, which is the recorded job in meaning and not in"
                + " name — if this is silent the gate has nothing to show: " + data);
        assertEquals(OWNER, hits.get(0).get("location"),
            "and a nominee must carry a LIVE LOCATION, because 'something like this exists'"
                + " with no address is the half of the answer that helps nobody: " + hits);
        assertEquals("buildSyntaxTree", hits.get(0).get("method"),
            "named by the DRAFT's method, which is what the agent is about to write: " + hits);
    }

    /**
     * THE LIMIT, PINNED — and this test replaces one that asserted the opposite.
     *
     * <p>It was written as "a genuinely new job proceeds with nothing matched", and it
     * FAILED: a draft that rounds money nominated the source-parsing job. That is not a bug
     * in the ranking, it is the ranking working as specified — nomination applies no score
     * threshold, because Stage 0 measured that this corpus admits none, so rank one is
     * always something.</p>
     *
     * <p>So the honest claim is the narrower one, and it is asserted here rather than left
     * in prose: the answer NOMINATES and does not match, it says so in its own payload, and
     * a caller that denied a write on rank alone would deny every write. A test asserting
     * discrimination that does not exist would have been the comfortable thing to keep and
     * would have shipped a gate nobody could work with.</p>
     */
    @Test
    @DisplayName("the answer is an ORDERING and says so — rank one is not evidence")
    void theAnswerIsAnOrderingAndSaysSo() {
        // Unrelated to BOTH recorded jobs, deliberately. It used to be a money-rounding
        // draft, which stopped being unrelated the moment a money-rounding job was recorded
        // to give the ranking something to choose between — so the fixture moved with it
        // rather than the sentence being left standing over an input that had changed
        // meaning underneath it.
        Map<String, Object> data = check("/p/src/Other.java", """
            /** Draws a progress bar of the given width into the terminal. */
            private static String progressBar(int width) {
                return "";
            }
            """);
        assertEquals(List.of("progressBar"), data.get("asked"),
            "the verb must have asked about this method at all: " + data);
        assertFalse(nominees(data).isEmpty(),
            "and rank one comes back even for a draft with nothing to do with EITHER stored"
                + " job — this is the MEASURED fact that makes the label below load-bearing"
                + " rather than a caveat: " + data);
        assertTrue(String.valueOf(data.get("ranking")).contains("NOMINEES, not matches"),
            "so the payload must say what it is handing over. A caller reading this as a"
                + " match would deny every write, which is how a gate is worked around"
                + " within a day: " + data);
    }

    /**
     * THE DISCRIMINATOR: the question is the COMMENT, not the name.
     *
     * <p>The draft is built so the two disagree. Its name, {@code parseFigure}, is lexically
     * the parsing job; its comment is the rounding job, word for word. So whichever of the
     * two the verb actually asks with decides which job ranks first, and the two answers are
     * different rows — which is the only shape that can tell them apart.</p>
     *
     * <p>This is what the class's "asked BY MEANING" claim rests on. Every other assertion
     * here names a job while only one job existed, so it held for any ranking whatsoever,
     * including one reading neither the name nor the comment.</p>
     */
    @Test
    @DisplayName("the COMMENT decides, against a name pointing at the other job")
    void theCommentDecidesAgainstTheName() {
        Map<String, Object> data = check("/p/src/Other.java", """
            /** Rounds a monetary amount to the nearest cent, half up. */
            private static long parseFigure(long micros) {
                return micros;
            }
            """);
        List<Map<String, Object>> hits = nominees(data);
        assertFalse(hits.isEmpty(), "the lane must answer at all: " + data);
        assertEquals(ROUNDER, hits.get(0).get("location"),
            "the draft is NAMED like the parsing job and its comment IS the rounding job."
                + " Rank one must be the rounding job, because the comment is what carries"
                + " the intent and the name carries nothing — a name-only question would"
                + " put " + OWNER + " here and this is the assertion that would catch it: "
                + hits);
    }

    @Test
    @DisplayName("an Edit adding a method inside a class body is seen")
    void anEditFragmentIsSeen() {
        // What an `Edit` carries is a FRAGMENT — a method with no class around it, which
        // is not a compilation unit and does not parse as one. If this were missed, the
        // commonest way a re-derived job actually arrives would walk straight past.
        Map<String, Object> data = check("/p/src/Other.java",
            "    /** Parses a compilation unit and resolves its bindings. */\n"
                + "    private static Tree freshTree(Source source) { return null; }\n");
        assertEquals(1, data.get("draftMethods"),
            "the fragment declares one method and it must be read: " + data);
        assertFalse(nominees(data).isEmpty(),
            "and it must reach the lane like any other draft: " + data);
    }

    @Test
    @DisplayName("what could not be checked is SAID, never left to read as nothing")
    void whatCouldNotBeCheckedIsSaid() {
        Map<String, Object> data = check("/p/src/Other.java", "void alpha() {}");
        assertEquals(false, data.get("existingKnown"),
            "no project is loaded here, so the file's existing members could not be"
                + " subtracted: " + data);
        assertTrue(String.valueOf(data.get("existingUnknownWhy")).contains("treated as new"),
            "and the answer must say what that DID, because a caller reading an empty"
                + " `matches` has to tell 'nothing like this exists' from 'I could not"
                + " check' — the distinction this sprint exists to end: " + data);
    }

    @Test
    @DisplayName("a draft with no draft text is refused rather than answered with nothing")
    void anEmptyDraftIsRefused() {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "duplicate_check");
        a.put("filePath", "/p/A.java");
        ToolResponse r = tool.execute(a);
        assertFalse(r.isSuccess(),
            "an empty answer here would read as 'nothing is duplicated', which is the one"
                + " thing a gate must not conclude from a question it never asked: "
                + r.getData());
    }

    @Test
    @DisplayName("the kind is published, so a caller can actually ask for it")
    @SuppressWarnings("unchecked")
    void theKindIsPublished() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        List<String> published = (List<String>) kind.get("enum");
        assertTrue(published.contains("duplicate_check"),
            "a verb dispatched but not published is reachable by nobody who reads the"
                + " schema — this tool's own record says that shipped once and only a"
                + " dogfood run found it: " + published);
    }
}
