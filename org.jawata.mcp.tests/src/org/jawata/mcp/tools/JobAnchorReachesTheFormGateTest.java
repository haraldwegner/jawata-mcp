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

    /**
     * The member whose name the bad summary restates, word for word.
     *
     * <p><b>It must RESOLVE, and until C9 it did not.</b> This constant used to name
     * {@code com.example.Clean#computeTotalOrderValue}, a member the {@code compile-clean}
     * fixture does not declare — the javadoc below recorded that as an open finding, because
     * the record verb never asked whether an anchor exists. It asks now, so a fictional
     * anchor is refused before any of this class's three cases can reach the branch it is
     * about, and all three would fail for a reason none of them is testing.</p>
     *
     * <p>The replacement is a real member of {@code simple-maven}, chosen for the one
     * property this class cannot do without: its name carries FOUR words, so a summary
     * restating it clears {@code StoryTemplate.MIN_CLAIM_WORDS}. Nothing in
     * {@code compile-clean} does — it declares {@code greet()}, {@code add(int,int)}, a
     * constructor and one field — and ADDING such a member there was refused deliberately:
     * twelve test classes read that fixture, several of them counting members, names or
     * diagnostics, and this sprint has had a shared fixture move a counted population four
     * times. Reading from {@code simple-maven} adds nothing to it.</p>
     */
    private static final String ANCHOR = "com.example.ComplexMethods#methodWithExceptionHandling";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
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
        ToolResponse response = recordJob(ANCHOR, "Method with exception handling.");

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
     * <p><b>An earlier version of this javadoc claimed a SECOND thing, it was false, and it
     * is now TRUE — which is the part worth reading.</b> It said that using the same anchor
     * "proves the anchor is admissible, so the refusal cannot be about the symbol being
     * unresolvable". The C7 audit found that false: the anchor then named
     * {@code com.example.Clean#computeTotalOrderValue}, a member no fixture declared, and the
     * store took it anyway — the record verb never asked whether an anchor resolves. So the
     * claim guarded a failure mode that could not occur, which is the vacuous-assertion shape
     * this sprint has paid for repeatedly, in a javadoc rather than in an assertion.</p>
     *
     * <p>C7 raised the unverified anchor as a finding of its own, and a fresh-context C9
     * implementation audit made it blocking: deliverable 7 says a job whose anchor does not
     * resolve is refused by name, and nothing resolved one. The record verb resolves it now
     * ({@link JobAnchorResolvesTest} is where that rule itself is proved), so this anchor is
     * one the workspace can find — and with that, the sentence quoted above finally says
     * something true. A refusal here cannot be about the symbol, because an unresolvable
     * symbol would have failed the two cases beside it as well.</p>
     */
    @Test
    @DisplayName("a job that says what the member is FOR is admitted on that same anchor")
    void a_job_that_says_what_the_member_is_for_is_admitted() {
        ToolResponse response = recordJob(ANCHOR,
            "The one place a parse failure becomes a number the caller can branch on,"
                + " so the recovery rule lives here rather than at each call site.");

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
        ToolResponse response = recordJob(ANCHOR, "Methods with the exception handling.");

        assertTrue(response.isSuccess(),
            "'Methods with the exception handling.' is not word-equal to"
                + " 'methodWithExceptionHandling' — 'methods' differs and 'the' is added —"
                + " so no branch refuses it: " + response.getError());
    }
}
