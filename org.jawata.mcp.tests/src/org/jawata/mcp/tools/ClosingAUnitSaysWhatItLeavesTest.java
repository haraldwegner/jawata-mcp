package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.DescribedUnits;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g D30 — CLOSING A UNIT SAYS WHAT IT IS CLOSING OVER.
 *
 * <p>Marking a unit done RETIRES it: it will not be offered again while its text is unchanged.
 * So a unit closed with members nobody described is a gap that is now invisible — and the agent
 * closing it is the one party in a position to notice. Before this the answer was
 * {@code unit} and {@code recorded} and nothing else, so the queue could be walked to zero with
 * members undescribed and every response along the way looking like success.</p>
 *
 * <p>It REPORTS rather than refuses, which the measure permits. Refusing would break the crawl
 * on every unit holding a member whose job is legitimately anchored elsewhere, and a gate that
 * blocks the ordinary case is one that gets worked around.</p>
 */
class ClosingAUnitSaysWhatItLeavesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("compile-clean");
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> service, store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    /**
     * Close a unit with a hash of the SHAPE the server hands out.
     *
     * <p>This passed {@code "whatever-this-test-was-handed"} until C14, and the call SUCCEEDED —
     * which is how an audit found that the closing verb accepted a hash no {@code action=next}
     * ever emitted. The fixture was the evidence, so it is corrected here rather than worked
     * around, and {@link #aNeverOfferedHashIsRefused} now pins the refusal it should always have
     * met.</p>
     */
    private Map<String, Object> close(ExperienceTool t, String unit) {
        Map<String, Object> data = closeWith(t, unit, DescribedUnits.hash(contentOf(unit)));
        return data;
    }

    private Map<String, Object> closeWith(ExperienceTool t, String unit, String contentHash) {
        ToolResponse r = t.execute(doneCall(unit, contentHash));
        assertTrue(r.isSuccess(), "got " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return data;
    }

    private ObjectNode doneCall(String unit, String contentHash) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "done");
        a.put("unit", unit);
        a.put("contentHash", contentHash);
        return a;
    }

    private String contentOf(String unit) {
        try {
            return Files.readString(Path.of(unit));
        } catch (IOException e) {
            throw new AssertionError("the fixture unit must be readable: " + unit, e);
        }
    }

    /**
     * C14 blocker 1 — a hash this server could not have handed out is REFUSED, where it used to
     * close the unit and retire it.
     *
     * <p>The control matters more than the refusal: without it, a verb that refused EVERY hash
     * would pass. {@link #close} drives the accepted path on every other case in this class, so
     * the pair is live.</p>
     */
    @Test
    @DisplayName("a contentHash the server never handed out is refused, and a real one is not")
    void aNeverOfferedHashIsRefused() {
        String unit = aUnitWithMembers();

        ToolResponse refused = tool.execute(doneCall(unit, "whatever-this-test-was-handed"));
        assertFalse(refused.isSuccess(),
            "a hash of a shape this server never emits must not close a unit — closing RETIRES"
                + " it, so accepting one records a description of text nobody wrote");
        assertTrue(String.valueOf(refused.getError()).contains("64 hex"),
            () -> "and the refusal must say what it expected: " + refused.getError());

        assertTrue(tool.execute(doneCall(unit, DescribedUnits.hash(contentOf(unit)))).isSuccess(),
            "PROOF OF LIFE: a hash of the right shape still closes the unit, so the refusal above"
                + " discriminates rather than rejecting everything");
    }

    private String aUnitWithMembers() {
        return service.getAllJavaFiles().stream()
            .map(Object::toString)
            .filter(p -> !p.contains("/test/"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "PROOF OF LIFE: the fixture must hold a source unit to close"));
    }

    @Test
    @DisplayName("closing a unit counts the members it leaves undescribed, and names them")
    void it_counts_and_names_what_it_leaves() {
        Map<String, Object> out = close(tool, aUnitWithMembers());

        int closingOver = assertInstanceOf(Integer.class, out.get("closingOver"),
            () -> "the count must be a number when it could be taken: " + out);
        assertTrue(closingOver > 0,
            () -> "PROOF OF LIFE: this unit declares members and none has a job, so the count"
                + " must be above zero or the assertion below is vacuous: " + out);

        @SuppressWarnings("unchecked")
        List<String> named = (List<String>) out.get("undescribedMembers");
        assertFalse(named == null || named.isEmpty(),
            () -> "a count alone tells an agent it left something; the names tell it what: " + out);
        assertTrue(named.get(0).contains("#"),
            () -> "the members are named as anchors a job can be recorded against: " + named);
        assertTrue(out.containsKey("closingOverNote"),
            () -> "and the answer says why it matters — the unit is now retired: " + out);
    }

    /**
     * THE CONTROL. Without it, a response that said "you are leaving members" unconditionally
     * would pass the case above, and the count would carry no information.
     */
    @Test
    @DisplayName("a unit whose members all have a job closes over nothing, and names nobody")
    void a_fully_described_unit_closes_over_nothing() {
        String unit = aUnitWithMembers();
        @SuppressWarnings("unchecked")
        List<String> leftBehind = (List<String>) close(tool, unit).get("undescribedMembers");
        assertFalse(leftBehind == null || leftBehind.isEmpty(), "PROOF OF LIFE: it starts incomplete");

        for (String member : leftBehind) {
            ObjectNode a = json.createObjectNode();
            a.put("kind", "record");
            a.put("type", "job");
            a.put("symbol", member);
            a.put("summary", "Answers one question this fixture's sample type exists to answer,"
                + " stated so a later reader need not open it.");
            assertTrue(tool.execute(a).isSuccess(), "the job must be recordable for " + member);
        }

        Map<String, Object> out = close(tool, unit);
        assertEquals(0, out.get("closingOver"),
            () -> "with every member described, nothing is being closed over: " + out);
        assertFalse(out.containsKey("undescribedMembers"),
            () -> "and no names are offered, rather than an empty list to interpret: " + out);
    }

    /**
     * A failure to LOOK is not an answer that nothing is missing.
     *
     * <p>With no project the members cannot be derived, and the tempting answer is zero. Zero
     * means "I checked and there are none", which is the one thing this does not know — and
     * reporting it would turn an unasked question into a reassurance. This project's own
     * deepest recorded bug class, in the smallest possible place.</p>
     */
    @Test
    @DisplayName("with no project the count is refused by name, never reported as zero")
    void an_uncountable_unit_says_so_rather_than_zero() {
        ExperienceTool storeOnly = new ExperienceTool(() -> null, store);
        // This unit deliberately does not exist on disk — the case is about having no project at
        // all — so its hash cannot be read from a file. A SHA of any text has the shape the
        // server hands out, which is what the C14 form gate checks; the gate's own refusal is
        // pinned separately by aNeverOfferedHashIsRefused.
        Map<String, Object> out = closeWith(storeOnly, "/anywhere/Thing.java",
            DescribedUnits.hash("a unit this test never put on disk"));

        Object closingOver = out.get("closingOver");
        assertInstanceOf(String.class, closingOver,
            () -> "an uncountable unit must not answer with a number: " + out);
        assertTrue(String.valueOf(closingOver).contains("not counted"),
            () -> "and must say it did not look: " + out);
        assertTrue(String.valueOf(closingOver).contains("not an answer that nothing is missing"),
            () -> "in the words that stop it being read as an all-clear: " + out);
    }
}
