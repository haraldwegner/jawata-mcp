package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28g S9a M2 — the plan's progress is never ahead of a passing gate.
 *
 * <p>The claim under test is not that the ledger behaves well when asked
 * politely; it is that <em>there is no way to put a plan on a step whose gate
 * did not pass</em>. So the cases below come in pairs: a refusal and the control
 * that reaches the same branch and passes, because a refusal test on its own
 * proves that something declined and never that the right thing did — a shape
 * this repository has shipped six times.</p>
 */
class PlanLedgerAdvancesOnlyPastItsOwnGateTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final PlanLedger ledger = new PlanLedger();

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("fixture json is not json: " + text, e);
        }
    }

    /** A two-step plan: compile with a declared scope, then run the tests. */
    private PlanId twoSteps() {
        return ledger.open(List.of(
            new GatedStep(0, "compile_workspace", json("{\"scope\":\"main\"}")),
            new GatedStep(1, "run_tests", json("{}"))));
    }

    @Test
    @DisplayName("a passing gate moves the plan on by exactly one step")
    void aPassingGateAdvances() {
        PlanId plan = twoSteps();
        assertEquals(-1, ledger.currentStep(plan).orElseThrow(), "nothing has passed yet");

        PlanLedger.Advance first = ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace", json("{\"scope\":\"main\"}"), 0, true));

        assertAll(
            () -> assertTrue(first.advanced(), first.refusedBecause()),
            () -> assertEquals(0, first.currentStep()),
            () -> assertEquals(0, ledger.currentStep(plan).orElseThrow(),
                "and the ledger agrees with what it just reported"),
            () -> assertEquals("run_tests", ledger.nextStep(plan).orElseThrow().tool(),
                "by one step, not past the rest"));
    }

    @Test
    @DisplayName("a FAILING gate leaves the step where it was — with the control that the same call passes at zero errors")
    void aFailingGateDoesNotAdvance() {
        PlanId plan = twoSteps();
        JsonNode declared = json("{\"scope\":\"main\"}");

        PlanLedger.Advance refused = ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace", declared, 3, true));

        assertAll(
            () -> assertFalse(refused.advanced(), "three errors is not a pass"),
            () -> assertEquals(-1, refused.currentStep(), "and it stayed where it was"),
            () -> assertEquals(-1, ledger.currentStep(plan).orElseThrow()),
            () -> assertTrue(refused.refusedBecause().contains("3 error"),
                "the refusal says what it saw: " + refused.refusedBecause()));

        // THE CONTROL. Without it a ledger that never advances at all would pass
        // every assertion above, and the refusal would be measuring nothing.
        PlanLedger.Advance passed = ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace", declared, 0, true));
        assertTrue(passed.advanced(), "the same call with a passing gate must advance");
        assertEquals(0, ledger.currentStep(plan).orElseThrow());
    }

    @Test
    @DisplayName("a gate call carrying DIFFERENT arguments does not advance; the step's own arguments do, and extra fields are allowed")
    void theDeclaredArgumentsAreCheckedAgainstTheCall() {
        PlanId plan = twoSteps();

        PlanLedger.Advance wrong = ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace", json("{\"scope\":\"test\"}"), 0, true));
        assertAll(
            () -> assertFalse(wrong.advanced(),
                "the step declared scope main; this call compiled something else"),
            () -> assertEquals(-1, ledger.currentStep(plan).orElseThrow()),
            () -> assertTrue(wrong.refusedBecause().contains("gate arguments"),
                wrong.refusedBecause()));

        PlanLedger.Advance missing = ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace", json("{}"), 0, true));
        assertFalse(missing.advanced(), "a call declaring nothing does not satisfy a declaration");

        // SUBSET, not equality: a live call carries control fields a step cannot
        // predict, so exact equality would refuse every real gate call.
        PlanLedger.Advance withExtras = ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace",
                json("{\"scope\":\"main\",\"summary\":true,\"projectKey\":\"jawata-mcp\"}"), 0, true));
        assertTrue(withExtras.advanced(),
            "every field the step declared is present and equal: " + withExtras.refusedBecause());
    }

    @Test
    @DisplayName("an absent field is not an explicit null — declaring a null requires the caller to send one")
    void anAbsentFieldIsNotANull() {
        PlanId plan = ledger.open(List.of(
            new GatedStep(0, "compile_workspace", json("{\"scope\":null}"))));

        assertFalse(ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace", json("{}"), 0, true)).advanced(),
            "omitting the field is not sending null");
        assertTrue(ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace", json("{\"scope\":null}"), 0, true)).advanced(),
            "sending it is");
    }

    @Test
    @DisplayName("a REFUSED gate reports zero errors and must not advance — zero from a call that never ran is not a pass")
    void aRefusedGateDoesNotAdvance() {
        PlanId plan = twoSteps();
        JsonNode declared = json("{\"scope\":\"main\"}");

        // The shape this is about: EventTap reads an error count out of the
        // response's data, a refused response carries none, so the count is
        // ZERO. compile_workspace refusing with no projects loaded looks, to a
        // count alone, exactly like a clean build.
        PlanLedger.Advance refused = ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace", declared, 0, false));

        assertAll(
            () -> assertFalse(refused.advanced(),
                "a gate that did not answer has not passed, whatever it counted"),
            () -> assertEquals(-1, ledger.currentStep(plan).orElseThrow()),
            () -> assertTrue(refused.refusedBecause().contains("did not answer"),
                "and the refusal says WHICH case this is, because a reader sent to the"
                    + " tree instead of to the call looks in the wrong place: "
                    + refused.refusedBecause()));

        // THE CONTROL, and it is the whole discriminator: the SAME zero count,
        // from a call that did answer, advances. Without it this case would pass
        // against a ledger that never advances at all.
        assertTrue(ledger.advanceIfGatePassed(plan,
            new GateOutcome("compile_workspace", declared, 0, true)).advanced(),
            "the same zero, from a gate that ran, is a pass");
    }

    @Test
    @DisplayName("a call for a tool the step is not gated on does not advance, and the refusal names both")
    void theWrongToolDoesNotAdvance() {
        PlanId plan = twoSteps();

        PlanLedger.Advance refused = ledger.advanceIfGatePassed(plan,
            new GateOutcome("run_tests", json("{}"), 0, true));

        assertFalse(refused.advanced(), "run_tests is step 1's gate, not step 0's");
        assertAll(
            () -> assertTrue(refused.refusedBecause().contains("compile_workspace"),
                refused.refusedBecause()),
            () -> assertTrue(refused.refusedBecause().contains("run_tests"),
                "and names what was called instead: " + refused.refusedBecause()));
    }

    @Test
    @DisplayName("an explicitly EMPTY declaration means the tool name alone is the gate")
    void anEmptyDeclarationIsTheNameAlone() {
        PlanId plan = ledger.open(List.of(new GatedStep(0, "run_tests", json("{}"))));

        assertTrue(ledger.advanceIfGatePassed(plan,
            new GateOutcome("run_tests", json("{\"scope\":{\"kind\":\"class\"}}"), 0, true)).advanced(),
            "nothing was declared, so anything the tool was called with satisfies it");

        // And the OTHER state is unrepresentable rather than silently equal to it:
        // a step whose arguments were never declared cannot be constructed.
        assertThrows(NullPointerException.class,
            () -> new GatedStep(0, "run_tests", null),
            "null would mean NOBODY SAID, which must not collapse into the name-alone case");
    }

    @Test
    @DisplayName("the step is writable through ONE member and nothing else — the shape an external write breaks")
    void theStepIsWritableThroughOneMemberOnly() throws Exception {
        // KEYED ON THE SIGNATURE, not the name. A name-keyed set collapses overloads, so
        // a smuggled `open(PlanId, int)` or `advanceIfGatePassed(PlanId, int)` would leave
        // it GREEN while a second public mutator existed — which is the one shape this
        // assertion is here to catch.
        Set<String> published = Arrays.stream(PlanLedger.class.getDeclaredMethods())
            .filter(m -> Modifier.isPublic(m.getModifiers()))
            .filter(m -> !m.isSynthetic())
            .map(m -> m.getName() + Arrays.stream(m.getParameterTypes())
                .map(Class::getSimpleName)
                .collect(Collectors.joining(",", "(", ")")))
            .collect(Collectors.toCollection(TreeSet::new));

        assertEquals(
            new TreeSet<>(Set.of("open(List)", "advanceIfGatePassed(PlanId,GateOutcome)",
                "currentStep(PlanId)", "nextStep(PlanId)", "steps(PlanId)", "planCount()")),
            published,
            "a member ARRIVING here is how an external write gets in, so the set is pinned"
                + " rather than merely counted — and pinned by SIGNATURE, so an overload"
                + " cannot hide behind a name that is already listed");

        Class<?> entry = Arrays.stream(PlanLedger.class.getDeclaredClasses())
            .filter(c -> "Entry".equals(c.getSimpleName()))
            .findFirst().orElseThrow(() -> new AssertionError("the plan's state has no holder"));
        Field step = entry.getDeclaredField("currentStep");
        assertAll(
            () -> assertTrue(Modifier.isPrivate(entry.getModifiers()),
                "the holder is not reachable from outside this class"),
            () -> assertTrue(Modifier.isPrivate(step.getModifiers()),
                "nor is the field"));

        // And behaviourally: every reader is asked, repeatedly, and the plan does
        // not move. A reader that quietly advanced would pass the shape check.
        PlanId plan = twoSteps();
        for (int i = 0; i < 3; i++) {
            ledger.currentStep(plan);
            ledger.nextStep(plan);
            ledger.steps(plan);
            ledger.planCount();
        }
        assertEquals(-1, ledger.currentStep(plan).orElseThrow(), "no reader moved it");
    }

    @Test
    @DisplayName("the declared gate arguments read back exactly as declared")
    void theDeclarationReadsBackAsDeclared() {
        JsonNode declared = json("{\"scope\":\"main\",\"clean\":true}");
        PlanId plan = ledger.open(List.of(new GatedStep(0, "compile_workspace", declared)));

        assertAll(
            () -> assertEquals(declared, ledger.steps(plan).get(0).gateArguments()),
            () -> assertEquals(declared, ledger.nextStep(plan).orElseThrow().gateArguments(),
                "the step about to be gated carries the same declaration"));
    }

    @Test
    @DisplayName("an identity no plan was opened under is refused, and its step is ABSENT rather than -1")
    void anUnknownIdentityIsRefused() {
        twoSteps();
        PlanId foreign = PlanId.mint();

        PlanLedger.Advance refused = ledger.advanceIfGatePassed(foreign,
            new GateOutcome("compile_workspace", json("{\"scope\":\"main\"}"), 0, true));

        assertAll(
            () -> assertFalse(refused.advanced()),
            () -> assertTrue(refused.refusedBecause().contains("no plan"), refused.refusedBecause()),
            () -> assertTrue(ledger.currentStep(foreign).isEmpty(),
                "ABSENT, not -1: -1 already means nothing has passed yet, and one value"
                    + " cannot carry both answers"),
            () -> assertTrue(ledger.nextStep(foreign).isEmpty()),
            () -> assertEquals(List.of(), ledger.steps(foreign)));
    }

    @Test
    @DisplayName("a finished plan does not advance again, and says how many passed")
    void aFinishedPlanDoesNotAdvanceAgain() {
        PlanId plan = ledger.open(List.of(new GatedStep(0, "run_tests", json("{}"))));
        assertTrue(ledger.advanceIfGatePassed(plan,
            new GateOutcome("run_tests", json("{}"), 0, true)).advanced());

        PlanLedger.Advance again = ledger.advanceIfGatePassed(plan,
            new GateOutcome("run_tests", json("{}"), 0, true));

        assertFalse(again.advanced());
        assertEquals(0, again.currentStep(), "still on the last step it passed");
        assertTrue(again.refusedBecause().contains("all 1"), again.refusedBecause());
    }

    @Test
    @DisplayName("a plan with no steps is refused at open, not discovered later")
    void aPlanWithNoStepsIsRefusedAtOpen() {
        assertAll(
            () -> assertThrows(IllegalArgumentException.class, () -> ledger.open(List.of()),
                "nothing to gate"),
            () -> assertThrows(IllegalArgumentException.class,
                () -> ledger.open(List.of(new GatedStep(1, "run_tests", json("{}")))),
                "a step whose index disagrees with its position cannot be walked by a derived"
                    + " index, which is the only way this ledger advances"));
    }

    @Test
    @DisplayName("M2's other half: a recorded call answers WITH WHICH ARGUMENTS, where before it could only answer whether the tool ran")
    void aRecordedCallCarriesItsArguments() {
        SessionLedger calls = new SessionLedger();
        EventTap tap = new EventTap(calls, null);
        JsonNode sent = json("{\"query\":\"PlanLedger\",\"kind\":\"Class\"}");

        tap.onCall(CallerIdentity.supplied("s1"), "search_symbols", sent, ToolResponse.success(Map.of()), 7L);

        List<SessionLedger.CallRecord> recorded = calls.calls(CallerIdentity.supplied("s1"));
        assertEquals(1, recorded.size(), "proof of life: the tap really recorded the call");
        assertAll(
            () -> assertEquals(sent, recorded.get(0).arguments(),
                "the arguments the call was made with, not null and not empty"),
            () -> assertEquals("search_symbols", recorded.get(0).tool()));
    }
}
