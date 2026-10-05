package org.jawata.mcp.learn;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Which step of a plan has actually been passed, keyed by the plan
 * (Sprint 28g S9a M2).
 *
 * <h2>Why the index is DERIVED and never accepted</h2>
 *
 * <p>This type exists because the obvious shape does not hold. The sibling
 * {@code org.jawata.mcp.refactoring.Plan} keeps its progress in
 * {@code appliedThrough(int)}, and its own javadoc says it is
 * <em>"immutable except for appliedThrough(), which the loop advances as steps
 * commit"</em> — so the EXECUTOR writes the progress. Its callers show the hole:
 * {@code PlanRefactoringTool} writes {@code -1} to reset and writes {@code i}
 * after its own gate, and {@code Plan} cannot distinguish either from a caller
 * that simply writes {@code 5}. A setter takes an index, so the only thing it
 * can validate is the index's RANGE; it cannot see whether a gate passed.</p>
 *
 * <p>So there is no setter here. {@link #open} admits plans and
 * {@link #advanceIfGatePassed} is the only thing that moves one — <em>the sole
 * mutator of a plan's PROGRESS</em>, which is narrower than "the sole mutator"
 * and is the claim that matters. It takes the gate's own {@link GateOutcome} —
 * which tool was called, with what arguments, whether it answered at all, and
 * how many errors it reported — and works the index out itself. <em>The current step
 * is never ahead of a passing gate</em> is therefore a property of this type
 * rather than a convention its callers are trusted to keep.</p>
 *
 * <h2>It keeps nothing</h2>
 *
 * <p>Deliberately in memory only, and that is a decision rather than an
 * omission. The eviction journal this package already runs exists because an MCP
 * session's END IS UNOBSERVABLE — nothing tells the resident a client went away,
 * so the only available signal is the session falling out of the LRU. A plan's
 * end is not like that: running out of steps IS the end, and it happens inside
 * this object. Copying the journal's shape would import a problem the new
 * subject does not have — and it would import it into the same TABLE, since the
 * learner-event store has one insert target and no injection point that could
 * redirect a second journal elsewhere, on a database file shared across every
 * resident on the machine.</p>
 *
 * <h2>Three properties this does NOT have, named rather than left to be found</h2>
 *
 * <p><b>There is no way back.</b> A plan that is rolled back or undone is a NEW
 * plan — the caller opens another. A reset mutator was the alternative and is
 * refused for the reason this class exists: a reset takes an index, or nothing,
 * and so cannot see a gate — which is exactly the hole in
 * {@code Plan#appliedThrough(-1)} that three of its four call sites use. One
 * mutator that always demands evidence beats two where the second demands
 * none.</p>
 *
 * <p><b>An empty declaration is a weak gate, deliberately.</b> A step declaring
 * no gate arguments is satisfied by any call to its tool — including one scoped
 * elsewhere entirely, since {@code compile_workspace} honours a
 * {@code projectKey} and will truthfully report zero errors for a project the
 * plan has nothing to do with. A step that cares declares its scope; the empty
 * form is the caller saying the name alone is enough.</p>
 *
 * <p><b>A {@link PlanId} is an identifier, not a capability.</b> A public record
 * has a public canonical constructor, so one can be written rather than minted,
 * and the key carries no session — so nothing here stops one caller advancing
 * another's plan. Correct for a cooperative driver, wrong as a security
 * boundary, and written down so it is not mistaken for one.</p>
 *
 * <p>Bounded like its sibling at {@link #MAX_PLANS} plans, least-recently-used
 * evicted. Its eviction does nothing but evict: no listener, no callback — unlike
 * {@code SessionLedger}, whose eviction invokes one, because there a session's
 * disappearance IS the only end signal and here it is not. A plan evicted
 * mid-flight and then advanced is REFUSED, and the refusal says the plan is
 * unknown; silently treating it as a fresh plan at step -1 is the one answer that
 * would be wrong.</p>
 */
public final class PlanLedger {

    /** At most this many plans are remembered; the least recently touched goes first. */
    static final int MAX_PLANS = 16;

    /**
     * What one attempt to advance did.
     *
     * <p>A record rather than a {@code boolean} because a bare false is an empty
     * result on failure: the caller could not tell a wrong tool from a failing
     * gate from a plan nobody opened, and those three send a reader to three
     * different places.</p>
     *
     * @param advanced       whether the plan moved
     * @param currentStep    the step the plan is on AFTER this attempt; -1 means
     *     nothing has passed yet, and a refused attempt leaves it where it was
     * @param refusedBecause why it did not move, or null when it did
     */
    public record Advance(boolean advanced, int currentStep, String refusedBecause) {

        static Advance moved(int currentStep) {
            return new Advance(true, currentStep, null);
        }

        static Advance refused(int currentStep, String because) {
            return new Advance(false, currentStep, because);
        }
    }

    /** One plan: its declared steps, and how far it has actually got. */
    private static final class Entry {

        private final List<GatedStep> steps;

        /**
         * The field this whole type exists to protect. Private, with no accessor
         * on this class and no mutator anywhere but
         * {@link PlanLedger#advanceIfGatePassed}.
         */
        private int currentStep = -1;

        Entry(List<GatedStep> steps) {
            this.steps = steps;
        }
    }

    private final LinkedHashMap<PlanId, Entry> plans =
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<PlanId, Entry> eldest) {
                return size() > MAX_PLANS;
            }
        };

    /**
     * Remember a plan's declared steps and hand back its identity.
     *
     * <p>Refuses an empty step list: a plan with nothing to gate has no state
     * this object could protect, and accepting one would create a plan that is
     * at once unstartable and indistinguishable from a finished one.</p>
     *
     * @throws IllegalArgumentException if the steps are empty, or if their
     *     indices are not 0..n-1 in order — a step list whose positions disagree
     *     with its own indices cannot be walked by a derived index, which is the
     *     only way this type advances
     */
    public synchronized PlanId open(List<GatedStep> steps) {
        Objects.requireNonNull(steps, "a plan with no steps is not a plan");
        if (steps.isEmpty()) {
            throw new IllegalArgumentException("a plan with no steps has nothing to gate");
        }
        for (int i = 0; i < steps.size(); i++) {
            GatedStep step = Objects.requireNonNull(steps.get(i), "a null step is not a step");
            if (step.index() != i) {
                throw new IllegalArgumentException("step at position " + i
                    + " declares index " + step.index()
                    + "; a derived index can only walk a list whose positions are its indices");
            }
        }
        PlanId id = PlanId.mint();
        plans.put(id, new Entry(List.copyOf(steps)));
        return id;
    }

    /**
     * Move the plan on by one step, if and only if this call IS that step's gate
     * and the gate passed.
     *
     * <p>The sole mutator. Every condition below is checked against the gate's
     * own outcome rather than against anything a caller asserts, and the index is
     * {@code currentStep + 1} computed here — never a value passed in.</p>
     *
     * @param id   the plan
     * @param gate what the gate call actually did
     * @return what happened, and why not when it did not
     */
    public synchronized Advance advanceIfGatePassed(PlanId id, GateOutcome gate) {
        Objects.requireNonNull(gate, "advancing needs the gate's outcome, not a promise about it");
        Entry entry = id == null ? null : plans.get(id);
        if (entry == null) {
            return Advance.refused(-1, "no plan is open under that identity");
        }
        int next = entry.currentStep + 1;
        if (next >= entry.steps.size()) {
            return Advance.refused(entry.currentStep,
                "the plan has no step after " + entry.currentStep
                    + "; all " + entry.steps.size() + " have passed");
        }
        GatedStep step = entry.steps.get(next);
        if (!step.tool().equals(gate.tool())) {
            return Advance.refused(entry.currentStep,
                "step " + next + " is gated on " + step.tool() + ", not on " + gate.tool());
        }
        if (!declaredArgumentsAreSatisfied(step.gateArguments(), gate.arguments())) {
            return Advance.refused(entry.currentStep,
                "step " + next + " declares gate arguments this call does not carry: "
                    + step.gateArguments());
        }
        if (!gate.succeeded()) {
            // SEPARATE from the error-count refusal below, and not a nicety: a
            // refused call reports ZERO errors, so without this clause the next
            // one would read that zero as a pass. Different diagnoses, too — one
            // sends a reader to the tree, the other to the call.
            return Advance.refused(entry.currentStep,
                "the gate for step " + next + " did not answer at all, so its"
                    + " zero error count says nothing about the tree");
        }
        if (gate.errorCount() > 0) {
            return Advance.refused(entry.currentStep,
                "the gate for step " + next + " reported " + gate.errorCount()
                    + " error(s), so it did not pass");
        }
        entry.currentStep = next;
        return Advance.moved(next);
    }

    /**
     * Whether the call's arguments carry what the step declared.
     *
     * <p>SUBSET, not equality, and the reason is measured rather than chosen: a
     * live call carries control fields a step cannot predict, so exact equality
     * would refuse every real gate call. Every field the step declares must be
     * PRESENT and deep-equal; extra fields in the call are allowed.</p>
     *
     * <p>An absent field and an explicit JSON null are NOT equal here, which is
     * what makes a declaration mean what it says: declaring a null requires the
     * caller to send one.</p>
     */
    private static boolean declaredArgumentsAreSatisfied(JsonNode declared, JsonNode actual) {
        if (declared.isEmpty()) {
            // Declared explicitly empty: the tool name alone is the gate.
            return true;
        }
        if (actual == null || !actual.isObject()) {
            return false;
        }
        Iterator<String> names = declared.fieldNames();
        while (names.hasNext()) {
            String name = names.next();
            JsonNode mine = actual.get(name);
            if (mine == null || !mine.equals(declared.get(name))) {
                return false;
            }
        }
        return true;
    }

    /**
     * The step this plan has passed, or an empty answer when no such plan is
     * open.
     *
     * <p>An {@code OptionalInt} rather than an {@code int} on purpose: -1 already
     * means <em>nothing has passed yet</em>, so returning it for an unknown plan
     * as well would collapse two different answers into one value — the same
     * absent-versus-zero conflation this stage exists to avoid everywhere
     * else.</p>
     */
    public synchronized OptionalInt currentStep(PlanId id) {
        Entry entry = id == null ? null : plans.get(id);
        return entry == null ? OptionalInt.empty() : OptionalInt.of(entry.currentStep);
    }

    /** The step whose gate would advance this plan next, or empty when it is finished or unknown. */
    public synchronized Optional<GatedStep> nextStep(PlanId id) {
        Entry entry = id == null ? null : plans.get(id);
        if (entry == null) {
            return Optional.empty();
        }
        int next = entry.currentStep + 1;
        return next >= entry.steps.size() ? Optional.empty() : Optional.of(entry.steps.get(next));
    }

    /** This plan's declared steps, in order (a copy — safe to iterate). */
    public synchronized List<GatedStep> steps(PlanId id) {
        Entry entry = id == null ? null : plans.get(id);
        return entry == null ? List.of() : new ArrayList<>(entry.steps);
    }

    /** How many plans this ledger currently holds. */
    public synchronized int planCount() {
        return plans.size();
    }
}
