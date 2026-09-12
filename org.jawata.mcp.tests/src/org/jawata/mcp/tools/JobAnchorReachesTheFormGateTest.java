package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 7 — the job rule's ANCHOR half, reached THROUGH THE DOOR.
 *
 * <p>{@code JobFormTest} proves the rule; this proves the rule is wired to the verb an
 * agent actually calls. The two are not the same claim, and the gap between them was
 * real: {@code EntryForm.check} has two overloads, and every production caller took the
 * five-argument one, which delegates with {@code anchor = null}. So {@code checkDerived}
 * returned at its own null-guard and the restatement branch was dead on every write the
 * product accepts — while {@code JobFormTest}, which calls the six-argument form
 * directly, stayed green throughout.</p>
 *
 * <p><b>That is why a unit test could not catch it: the test and the wire did not call
 * the same method.</b> A test written one level below the defect is not a weaker test,
 * it is a test of something else. This class therefore drives {@link ExperienceTool}
 * itself, which is the only level at which the argument list is observable.</p>
 *
 * <p>It is the sprint's recurring shape once more — declared-but-unreachable, after a
 * lane constant nothing assigned, a store folder with no way to switch it on, an
 * allowlist naming eight doors while a ninth existed, and a ledger with no verb.</p>
 */
class JobAnchorReachesTheFormGateTest {

    /** The member whose name the bad summary restates, word for word. */
    private static final String ANCHOR = "com.example.Clean#computeTotalOrderValue";

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

    private ToolResponse recordJob(String symbol, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", symbol);
        a.put("summary", summary);
        return tool.execute(a);
    }

    /**
     * THE REGRESSION. Revert the anchor argument at the record verb and this is the test
     * that goes red; nothing else in the suite does.
     *
     * <p>The summary is chosen so ONLY this branch can answer it. Four words clears
     * {@code StoryTemplate.MIN_CLAIM_WORDS}, which is what refuses the obvious
     * "Compute." and is the reason a shorter case would prove nothing — a refusal test
     * proves that SOMETHING declined, never that the branch you aimed at did. It carries
     * no {@code (}, so the signature branch cannot answer either. And its words ARE the
     * member's words, which is the one thing left.</p>
     */
    @Test
    @DisplayName("a job that restates its anchor's own name is refused THROUGH the verb")
    void a_job_that_restates_its_anchors_own_name_is_refused_through_the_verb() {
        ToolResponse response = recordJob(ANCHOR, "Compute total order value.");

        assertFalse(response.isSuccess(),
            "the summary's words ARE the member's words, so the row costs a row and"
                + " answers no question; got " + response.getData());
        String message = String.valueOf(response.getError());
        assertTrue(message.contains("restates the member's own name"),
            "and it must be THIS branch that says so — the wording is the branch's own."
                + " A refusal from the four-word rule or the signature rule would mean"
                + " the anchor still never reached checkDerived: " + message);
        assertFalse(message.contains("at least 4"),
            "NOT the claim-length rule, which every short string hits and which is what"
                + " made two earlier controls read as passes: " + message);
    }

    /**
     * THE CONTROL. Without it a verb that refused every job would satisfy the test above.
     *
     * <p><b>An earlier version of this javadoc claimed a SECOND thing and it was false</b> —
     * that using the same anchor "proves the anchor is admissible, so the refusal cannot be
     * about the symbol being unresolvable". The C7 audit found it: {@link #ANCHOR} names
     * {@code computeTotalOrderValue}, and the {@code compile-clean} fixture declares only
     * {@code greet()}, {@code add(int,int)}, a constructor and one field. <b>That member does
     * not exist and the store takes it anyway</b> — the record verb never asks whether an
     * anchor resolves. So the claim guarded a failure mode that cannot occur, which is the
     * vacuous-assertion shape this sprint has paid for repeatedly, in a javadoc rather than
     * in an assertion.</p>
     *
     * <p>The surviving claim is the real one, and the unverified anchor is raised at C7 as a
     * finding of its own: deliverable 1 says every anchor resolves through
     * {@code PointerResolver}, and that resolver is not on this write path at all.</p>
     */
    @Test
    @DisplayName("a job that says what the member is FOR is admitted on that same anchor")
    void a_job_that_says_what_the_member_is_for_is_admitted() {
        ToolResponse response = recordJob(ANCHOR,
            "The one place an order's line items become a figure the invoice can show,"
                + " so a rounding rule lives here rather than at each caller.");

        assertTrue(response.isSuccess(),
            "a summary that adds what the identifier does not carry is the whole bar,"
                + " and this anchor is the one the refusal case uses: " + response.getError());
    }

    /**
     * The bar is stated rather than implied, because it is NARROWER than the cataloguer
     * seat's prose suggests and a reader who assumed otherwise would mis-describe the
     * product. The comparison is word-list EQUALITY, so a summary that merely adds an
     * article and conjugates the verb — the shape the seat prints as its headline
     * failure — is ADMITTED.
     *
     * <p>Asserted so the gap is a measured fact with a test behind it rather than a
     * claim in a commit message. If the rule is ever widened to catch this shape, this
     * test is where that decision announces itself.</p>
     */
    @Test
    @DisplayName("the bar is word-equality: a conjugated restatement still gets in")
    void the_bar_is_word_equality_and_a_conjugated_restatement_still_gets_in() {
        ToolResponse response = recordJob(ANCHOR, "Computes the total order value.");

        assertTrue(response.isSuccess(),
            "'Computes the total order value.' is not word-equal to"
                + " 'computeTotalOrderValue' — 'computes' differs and 'the' is added —"
                + " so no branch refuses it: " + response.getError());
    }
}
