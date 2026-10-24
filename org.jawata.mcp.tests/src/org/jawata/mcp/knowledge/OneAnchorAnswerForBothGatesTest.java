package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S14 / D32 second clause, and D40d — ONE answer to "does this anchor still
 * resolve", asked by the write gate AND by the staleness sweep.
 *
 * <h2>The defect this closes was already written down, and deliberately left</h2>
 *
 * <p>{@code ExperienceRetrieval#resolvePointer}'s own comment records a C9 round measuring it:
 * {@code ExperienceTool#resolvesViaJdt} was a SECOND resolver of the same question, it was the
 * one the staleness sweep ran on, and it took {@code symbolFqn.substring(0, indexOf('#'))} and
 * asked about the TYPE only. So a job anchored at a member that had been DELETED was refused at
 * the write gate by the member-aware resolver and <em>judged healthy forever</em> by the
 * curation queue — the one place whose whole job is to find exactly that row. The round left it
 * alone with its reason stated: closing it changes what the sweep marks stale across a live
 * store, <em>"which is a change with its own test and its own checkpoint."</em> This is that
 * test.</p>
 *
 * <h2>Why the PAIR is one test method and not two</h2>
 *
 * <p>D32 asks for the discriminating case <em>"tested as a pair"</em>, and the reason is that
 * the defect is a DISAGREEMENT. Two separate tests, each green, is the state the product was
 * already in: the gate's test passed, the sweep's test passed, and nothing asserted that they
 * answer the same anchor the same way. One method over ONE anchor cannot drift that way.</p>
 *
 * <h2>The fixture is the shared project, deliberately unchanged</h2>
 *
 * <p>{@code com.example.HelloWorld} already carries every case this needs — a no-argument
 * method, a field, TWO constructors (the overload shape, anchored bare), and no member called
 * {@code noSuchMember}. Nothing is added to {@code simple-maven}: it is monotonically growing
 * and shared, and this sprint has recorded it moving a counted population four separate times.
 * A row written here would be the fifth.</p>
 *
 * <p><b>And the closed-workspace case uses the real service, emptied by the product's own
 * {@code removeProject}.</b> The first version stubbed {@code IJdtService} — sixteen
 * methods, fifteen of them throwing — and a stub asserts what its author believes an
 * unopened workspace does. The verb puts a real service into that state, and three tests
 * in {@code org.jawata.core} already drive it that way.</p>
 */
class OneAnchorAnswerForBothGatesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The type is here; this member never existed. The case the two gates disagreed on. */
    private static final String MEMBER_GONE = "com.example.HelloWorld#noSuchMember";
    /** The sibling that still resolves — the control, and the mass-stale breaker's release. */
    private static final String STILL_HERE = "com.example.HelloWorld#printGreeting";

    private static ExperienceEntry job(String summary, String anchor) {
        return ExperienceEntry.of(
            SymbolFact.of("job", summary, Confidence.MEDIUM)
                // ASSERTED, not auto-derived: refresh() supersedes an asserted anchor and
                // merely CLEARS an auto one, and `isAssertedAnchor` reads the fact map's own
                // `symbol` key. A row seeded the other way would be cleared rather than
                // staled, and the assertion below would be about the wrong branch.
                .symbol(anchor)
                .build())
            .build();
    }

    /**
     * THE PAIR. One anchor, both gates, in one method — a job at an existing type whose member
     * is gone must be REFUSED by the write gate and QUEUED by the sweep.
     *
     * <p>The seeded row goes in through the STORE rather than through {@code record}, and that
     * is the honest shape rather than a shortcut: the write gate now refuses this anchor, so a
     * row like it can only have been written while the member still existed. That is exactly
     * the row the sweep exists to find.</p>
     */
    @Test
    @DisplayName("D32: the member is gone — the write gate REFUSES it and the sweep QUEUES it, on one anchor")
    void theGateRefusesItAndTheSweepQueuesIt() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceTool tool = new ExperienceTool(() -> service, store, List::of);

            // PROOF OF LIFE FIRST. If the fixture's own anchor does not resolve, every
            // assertion below is about a broken lookup rather than about a missing member,
            // and the two read identically from a red test.
            ExperienceRetrieval retrieval = new ExperienceRetrieval(store,
                org.jawata.mcp.java.JavaSymbolAnchors.over(() -> service));
            assertEquals(ExperienceRetrieval.AnchorAnswer.RESOLVES,
                retrieval.anchorAnswer(STILL_HERE),
                "proof of life: the sibling anchor must resolve, or this test measures nothing");

            String goneId = store.put(job("Answers the gauge's last reading.", MEMBER_GONE));
            store.put(job("Prints the configured greeting to stdout.", STILL_HERE));

            ToolResponse refused = tool.execute(recordArgs(MEMBER_GONE));
            Map<String, Object> swept = tool.autoRefresh();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> staled =
                (List<Map<String, Object>>) swept.getOrDefault("staled", List.of());
            List<String> staledSymbols = staled.stream()
                .map(e -> String.valueOf(e.get("symbol"))).toList();

            assertAll(
                // HALF ONE — the write gate.
                () -> assertFalse(refused.isSuccess(),
                    () -> "the write gate must refuse an anchor whose member is gone, got: "
                        + refused.getData()),
                // HALF TWO — the sweep, on the SAME anchor. This is the half that was false:
                // the sweep's own resolver stripped `#noSuchMember` and asked about
                // com.example.HelloWorld, which is there, so this row was never queued.
                () -> assertTrue(staledSymbols.contains(MEMBER_GONE),
                    () -> "the sweep must queue the same anchor the gate refuses — staled: "
                        + staledSymbols),
                // READ OFF THE STORE, not off the report. The report is what the sweep SAYS
                // it did; this is what the store HOLDS. This sprint has already recorded a
                // case where every report-reading assertion stayed green over an action that
                // never happened, and only the assertions reading the store could tell.
                () -> assertFalse(store.byIds(List.of(goneId)).get(0).isLive(),
                    "and queuing it means the row leaves recall"),
                // THE CONTROL that makes half two a measurement. Without it, a sweep that
                // staled EVERYTHING would pass — and a workspace that answers "gone" to
                // every anchor is the exact state the mass-stale breaker exists for, so a
                // test with only the broken row would measure the breaker instead.
                () -> assertFalse(staledSymbols.contains(STILL_HERE),
                    () -> "the sibling that resolves must NOT be queued — staled: "
                        + staledSymbols),
                () -> assertFalse(swept.containsKey("workspace_suspect"),
                    () -> "and the mass-stale breaker must not have fired, or the sweep "
                        + "changed no status at all and half two proved nothing: " + swept));
        }
    }

    /**
     * The three answers, each by its own name, against the one object both gates ask.
     *
     * <p>D32 asks for three values — resolves, is gone, could not check. Before this they
     * existed as {@code resolved}/{@code stale}/neither-flag-set, and the write gate's own
     * comment records TWO C9 rounds spent reading those two keys correctly, the first of which
     * read {@code resolved} alone and told an author "does not exist" about a lookup that had
     * merely failed. A named value is what stops the third reader repeating it.</p>
     */
    @Test
    @DisplayName("D32: three NAMED answers — resolves, gone, and could-not-check")
    void theAnswerHasThreeNamedValues() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceRetrieval loaded = new ExperienceRetrieval(store,
                org.jawata.mcp.java.JavaSymbolAnchors.over(() -> service));
            ExperienceRetrieval serviceless = new ExperienceRetrieval(store, () -> null);
            assertAll(
                () -> assertEquals(ExperienceRetrieval.AnchorAnswer.RESOLVES,
                    loaded.anchorAnswer(STILL_HERE), "a member that is there"),
                () -> assertEquals(ExperienceRetrieval.AnchorAnswer.GONE,
                    loaded.anchorAnswer(MEMBER_GONE),
                    "the type is here and the member is not — MEMBER-AWARE, which is the half"
                        + " the sweep's own resolver never asked"),
                () -> assertEquals(ExperienceRetrieval.AnchorAnswer.GONE,
                    loaded.anchorAnswer("com.example.NoSuchTypeAtAll"),
                    "and a type that is not there is gone too, so GONE is not member-only"),
                () -> assertEquals(ExperienceRetrieval.AnchorAnswer.UNKNOWN,
                    serviceless.anchorAnswer(STILL_HERE),
                    "with nothing to ask, the answer is that it could not be answered — and"
                        + " this is the SAME anchor that RESOLVES above, so the difference is"
                        + " the absence of a lookup and not a property of the anchor"),
                () -> assertEquals(ExperienceRetrieval.AnchorAnswer.UNKNOWN,
                    loaded.anchorAnswer(null),
                    "a row with no anchor is not a row whose anchor went missing"));
        }
    }

    /**
     * A BARE OVERLOADED MEMBER RESOLVES, which is the form the catalogue actually writes.
     *
     * <p>{@code HelloWorld} declares two constructors. A resolution demanding a signature
     * would answer GONE for {@code #HelloWorld} and the sweep would queue every overloaded
     * member in the store for re-anchoring — and D30's own gate refuses a bare key carrying
     * several rows, so the two rules have to agree about what a bare key means.</p>
     */
    @Test
    @DisplayName("D32: a bare overloaded member resolves — the catalogue writes no parameter list")
    void aBareOverloadedMemberResolves() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceRetrieval retrieval = new ExperienceRetrieval(store,
                org.jawata.mcp.java.JavaSymbolAnchors.over(() -> service));
            assertAll(
                () -> assertEquals(ExperienceRetrieval.AnchorAnswer.RESOLVES,
                    retrieval.anchorAnswer("com.example.HelloWorld#HelloWorld"),
                    "two constructors share this bare key and it must resolve"),
                () -> assertEquals(ExperienceRetrieval.AnchorAnswer.RESOLVES,
                    retrieval.anchorAnswer("com.example.HelloWorld#greeting"),
                    "a FIELD is a member too — a job may describe one"),
                () -> assertEquals(ExperienceRetrieval.AnchorAnswer.RESOLVES,
                    retrieval.anchorAnswer("com.example.HelloWorld"),
                    "and a bare TYPE anchor still resolves, which is the case the two"
                        + " resolvers always agreed on"));
        }
    }

    /**
     * D40d — A CLOSED PROJECT IS A FAILURE TO LOOK, NOT A MISSING SYMBOL.
     *
     * <p>D40d's measure is <em>"the answer distinguishes the two"</em>. The state is a service
     * in hand with nothing loaded: there {@code findType} answers null without throwing, which
     * is byte-for-byte what it answers for a type that really is gone. Read as absence it
     * reports the WHOLE STORE stale — the gate refusing correct rows, the sweep queueing
     * everything — on a workspace that simply has not been opened.</p>
     *
     * <p>The stub answers an empty project set and a null type, which is that state exactly. A
     * resolution that did not ask about the project set first would reach {@code findType},
     * get null, and answer GONE; this asserts UNKNOWN. The two differ by one branch, which is
     * what makes the assertion a measurement rather than a restatement.</p>
     */
    @Test
    @DisplayName("D40d: nothing loaded answers COULD-NOT-CHECK, never GONE")
    void aClosedWorkspaceCannotLookRatherThanFindNothing() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceRetrieval retrieval = new ExperienceRetrieval(store,
                org.jawata.mcp.java.JavaSymbolAnchors.over(() -> service));

            // PROOF OF LIFE while the project IS loaded. Without it, UNKNOWN below could be
            // UNKNOWN for any reason at all — a bad anchor, a broken fixture — and the
            // assertion would pass while measuring nothing about an emptied workspace.
            assertEquals(ExperienceRetrieval.AnchorAnswer.RESOLVES,
                retrieval.anchorAnswer(STILL_HERE),
                "proof of life: loaded, this anchor resolves");

            // THE WORKSPACE IS EMPTIED BY THE PRODUCT'S OWN VERB, not by a stub. A
            // hand-written IJdtService would be sixteen methods asserting what I believe an
            // unopened workspace does; removeProject puts the service into that state for
            // real, and three tests in org.jawata.core already drive it this way.
            for (String key : List.copyOf(service.projectKeys())) {
                service.removeProject(key);
            }
            assertTrue(service.projectKeys().isEmpty(),
                "precondition: the workspace must really be empty, or the rest is vacuous");

            Map<String, Object> pointer = retrieval.resolvePointer(STILL_HERE);
            assertAll(
                () -> assertEquals(ExperienceRetrieval.AnchorAnswer.UNKNOWN,
                    ExperienceRetrieval.answerOf(pointer),
                    () -> "an emptied workspace could not look; it did not find nothing — and"
                        + " this is the SAME anchor that resolved moments ago: " + pointer),
                () -> assertFalse(Boolean.TRUE.equals(pointer.get("stale")),
                    () -> "and `stale` must be unset, because that flag is what every reader"
                        + " of this map treats as a positive finding of absence: " + pointer),
                () -> assertTrue(String.valueOf(pointer.get("note")).contains("failure to look"),
                    () -> "and the note must SAY which of the two it is, since that is the"
                        + " whole deliverable: " + pointer));
        }
    }

    private static ObjectNode recordArgs(String symbol) {
        ObjectNode args = JSON.createObjectNode();
        args.put("kind", "record");
        args.put("type", "job");
        args.put("summary", "Answers the gauge's last sampled reading in degrees.");
        args.put("symbol", symbol);
        return args;
    }
}
