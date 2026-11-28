package org.jawata.mcp.learn;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

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
 * <h2>What it keeps</h2>
 *
 * <p>A plan a pm enters with {@link #openSprint} is KEPT, when the ledger is built with {@link #PlanLedger(java.nio.file.Path)}: the entered
 * plan, its name, its marks and the person's steps, and the gate outcomes that moved it, are written to the plan file after every entry, every
 * advance and every close of such a plan (a temporary file beside it, moved over it), and are read when the ledger is built, so an entered plan
 * and its passed steps survive an engine restart. A restored plan has its id, its passed steps and its closed flag, and its owner is EMPTY: the
 * session that entered it is gone, and an empty owner is listed for every caller by {@link #pendingFor}. Restoring replays the stored outcomes
 * through the same check {@link #advanceIfGatePassed} applies, so a restored plan is only where its gates took it. A plan file that cannot be
 * read, or that does not replay, is not dropped silently: it is kept beside as {@code <file>.corrupt}, the ledger starts empty, and the log says
 * so at WARNING. The plan file is the workspace's own; the learner-event store, which every resident on the machine shares, is not used.</p>
 *
 * <p>A plan a hire opens with {@link #open} is in memory only, and that is a decision rather than an
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
 * <p>Bounded like its sibling, but only ENDED plans are dropped (S13, the audit
 * of the seat allowlist): past {@link #MAX_PLANS} plans the oldest ended ones go
 * first, and a plan whose hire is still running is kept, because its tool list is
 * what narrows its session at the choke and dropping it would silently unbound a
 * brain that is still working. {@link #HARD_LIMIT} bounds the ledger when plans
 * are opened and never closed, and then the oldest goes whatever its state. Its
 * eviction does nothing but evict: no listener, no callback — unlike
 * {@code SessionLedger}, whose eviction invokes one, because there a session's
 * disappearance IS the only end signal and here it is not. A plan evicted and then
 * advanced is REFUSED, and the refusal says the plan is unknown; silently treating
 * it as a fresh plan at step -1 is the one answer that would be wrong.</p>
 */
public final class PlanLedger {

    /** Past this many plans the oldest ENDED ones are dropped; plans still running are kept. */
    static final int MAX_PLANS = 16;

    /** Past this many plans the oldest goes whatever its state: the bound for plans nobody closed. */
    static final int HARD_LIMIT = MAX_PLANS * 8;

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

        /** Set when the plan's work has ended, by {@link PlanLedger#close}; a closed plan is remembered and never pending. */
        private boolean closed;

        /** The gate outcomes that moved the plan, in step order, whoever ran the gate. */
        private final List<GateOutcome> passed = new ArrayList<>();

        /** The session that opened the plan, or empty when nobody said: a plan's pending step is that session's to answer for (S10). */
        private String owner = "";

        /** The number openSprint gave the plan when it was entered, counting up; 0 for a plan restored from the plan file. Entry order is never read off the access-ordered map. */
        private long sequence;

        /** The tools the hired seat may call, or null when no list was given (S13): the choke refuses a call outside it. */
        private java.util.Set<String> allowed;

        /** What a sprint plan marks beside its chain (its name, titles, checkpoints and the person's steps), or null for a plan opened without them. */
        private PlanMarks marks;

        Entry(List<GatedStep> steps) {
            this.steps = steps;
        }
    }

    /**
     * What a hired session may call (S13, the audit of C6, finding F2): the tools its seat lists, and the gate of the step it is working, which
     * a brain has to be able to run to show the step passed whatever its seat lists.
     *
     * @param tools         the seat's own list; an empty list allows no tool but the gate
     * @param gate          the tool whose answer would move the plan, when the current step gates the tree and not the seat's answer
     * @param gateArguments the arguments the step declared for that gate: the exemption covers the gate called as declared, not the gate's
     *                      tool with any arguments, so a seat that may not change code cannot reach a code-changing tool by way of a gate
     */
    public record ToolAllowance(java.util.Set<String> tools, Optional<String> gate, JsonNode gateArguments) {

        /** Whether the session may call {@code tool} with {@code arguments}. */
        public boolean permits(String tool, JsonNode arguments) {
            return tools.contains(tool)
                || gate.map(tool::equals).orElse(false) && declaredArgumentsAreSatisfied(gateArguments, arguments);
        }
    }

    /** The prefix of a step that gates the seat's ANSWER, not the tree: it can pass only once the brain has answered. */
    public static final String ANSWER_GATE_PREFIX = "answer_gate:";

    private final LinkedHashMap<PlanId, Entry> plans = new LinkedHashMap<>(16, 0.75f, true);

    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(PlanLedger.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    /** Where the entered sprint plans are kept, or null for a ledger that keeps its plans in memory only. */
    private final Path planFile;

    /** How many sprint plans {@link #openSprint} has entered into this ledger: the source of each plan's sequence number. */
    private long entered;

    /** A ledger that keeps its plans in memory only and writes nothing. */
    public PlanLedger() {
        this.planFile = null;
    }

    /** A ledger that also keeps the sprint plans entered through {@link #openSprint} in {@code planFile}, and restores them from it when it is built. */
    public PlanLedger(Path planFile) {
        this.planFile = planFile;
        try {
            load();
        } catch (RuntimeException unrestorable) {
            plans.clear();
            Path kept = planFile.resolveSibling(planFile.getFileName() + ".corrupt");
            try {
                Files.move(planFile, kept, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException | RuntimeException notMoved) {
                LOG.warn("the unrestorable sprint plan file {} could not be moved to {}: {}", planFile, kept, notMoved.toString());
            }
            LOG.warn("the sprint plan file {} could not be restored, so the ledger starts empty: {}", planFile, unrestorable.toString());
        }
    }

    /**
     * Keeps the ledger bounded without forgetting a running plan: past {@link #MAX_PLANS} the oldest ENDED plans go, and past
     * {@link #HARD_LIMIT} the oldest goes whatever its state (plans nobody closed would otherwise be kept for good).
     */
    private void dropEndedPlansPastTheLimit() {
        java.util.Iterator<Map.Entry<PlanId, Entry>> oldestFirst = plans.entrySet().iterator();
        while (plans.size() > MAX_PLANS && oldestFirst.hasNext()) {
            if (oldestFirst.next().getValue().closed) {
                oldestFirst.remove();
            }
        }
        oldestFirst = plans.entrySet().iterator();
        while (plans.size() > HARD_LIMIT && oldestFirst.hasNext()) {
            oldestFirst.next();
            oldestFirst.remove();
        }
    }

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
        dropEndedPlansPastTheLimit();
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
        if (entry.closed) {
            return Advance.refused(entry.currentStep, "the plan is closed: the work it gated has ended");
        }
        Advance result = advance(gate, entry);
        if (result.advanced() && entry.marks != null) {
            persist();
        }
        return result;
    }

    private Advance advance(GateOutcome gate, Entry entry) {
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
        entry.passed.add(gate);
        return Advance.moved(next);
    }

    /** The gate outcomes that moved this plan so far, in step order; empty for an unknown plan. */
    public synchronized List<GateOutcome> passedOutcomes(PlanId id) {
        Entry entry = id == null ? null : plans.get(id);
        return entry == null ? List.of() : List.copyOf(entry.passed);
    }

    /**
     * A hired session called a tool itself: when it is the gate of the step it is working, with the declared arguments, its answer is judged
     * like the engine's own run of that gate and moves the plan when it passes (28h z128). Any other call changes nothing.
     *
     * @param sessionId the caller's session id; a hired brain's is its plan's value
     * @param judge     reads the answer of the call as an outcome of the step
     */
    public synchronized void offerOwnCall(String sessionId, String tool, JsonNode arguments,
            java.util.function.Function<GatedStep, GateOutcome> judge) {
        if (sessionId == null) {
            return;
        }
        for (Map.Entry<PlanId, Entry> plan : plans.entrySet()) {
            if (!plan.getKey().value().equals(sessionId)) {
                continue;
            }
            Entry entry = plan.getValue();
            int next = entry.currentStep + 1;
            if (!entry.closed && next < entry.steps.size() && entry.steps.get(next).tool().equals(tool)
                    && declaredArgumentsAreSatisfied(entry.steps.get(next).gateArguments(), arguments)) {
                advanceIfGatePassed(plan.getKey(), judge.apply(entry.steps.get(next)));
            }
            return;
        }
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

    /**
     * Ends a plan: the work it gated has finished, whether every step passed or not. The plan stays remembered, so its record can still be
     * read, but it no longer advances and no longer counts as pending.
     *
     * @return whether a plan that was open is now closed; false for an unknown plan and for one already closed
     */
    public synchronized boolean close(PlanId id) {
        Entry entry = id == null ? null : plans.get(id);
        if (entry == null || entry.closed) {
            return false;
        }
        entry.closed = true;
        if (entry.marks != null) {
            persist();
        }
        return true;
    }

    /**
     * One plan as a reader of the ledger sees it: how far it got, whether its work has ended, and its steps. A copy; nothing in it can move
     * the plan.
     */
    public record PlanView(PlanId id, int passedStep, boolean closed, List<GatedStep> steps) {
    }

    /** Every plan the ledger holds, open or closed, oldest first: the one answer a board is drawn from. */
    public synchronized List<PlanView> view() {
        List<PlanView> all = new ArrayList<>();
        for (Map.Entry<PlanId, Entry> plan : plans.entrySet()) {
            all.add(new PlanView(plan.getKey(), plan.getValue().currentStep, plan.getValue().closed, List.copyOf(plan.getValue().steps)));
        }
        return all;
    }

    /** How many plans this ledger currently holds. */
    public synchronized int planCount() {
        return plans.size();
    }

    /** A plan that still has a step to pass: its identity, the step it has passed, and the step whose gate comes next. */
    public record Pending(PlanId id, int passedStep, GatedStep next) {
    }

    /**
     * The plans that still have a step to pass, oldest first. A plan that has passed every step is not listed, and neither is one the
     * ledger no longer holds: this is the one answer to "is a step pending?", read from the state the gates move and from nowhere else.
     */
    public synchronized List<Pending> pending() {
        List<Pending> open = new ArrayList<>();
        for (Map.Entry<PlanId, Entry> plan : plans.entrySet()) {
            int next = plan.getValue().currentStep + 1;
            if (!plan.getValue().closed && next < plan.getValue().steps.size()) {
                open.add(new Pending(plan.getKey(), plan.getValue().currentStep, plan.getValue().steps.get(next)));
            }
        }
        return open;
    }

    /** Records which session opened the plan, so its pending step is that session's to answer for and no other caller's turn waits on it. */
    public synchronized void ownedBy(PlanId id, String owner) {
        Entry entry = plans.get(id);
        if (entry != null) {
            entry.owner = owner == null ? "" : owner;
        }
    }

    /** The session that opened this plan, as {@link #ownedBy} recorded it; empty for a plan nobody was recorded for and for one the ledger does not hold. */
    public synchronized String ownerOf(PlanId id) {
        Entry entry = id == null ? null : plans.get(id);
        return entry == null ? "" : entry.owner;
    }

    /** Records the tools the seat that opened the plan may call, so the choke can refuse a call outside them (S13); an empty list allows none. */
    public synchronized void allowTools(PlanId id, java.util.Collection<String> tools) {
        Entry entry = plans.get(id);
        if (entry != null) {
            entry.allowed = tools == null ? java.util.Set.of() : java.util.Set.copyOf(tools);
        }
    }

    /**
     * What the session may call, or empty when it is no open plan's, the plan was given no list, or the plan has ended. {@code sessionId} is the
     * caller's session id; a hired brain's is its plan's value (S3, D30).
     */
    public synchronized Optional<ToolAllowance> allowance(String sessionId) {
        if (sessionId == null) {
            return Optional.empty();
        }
        for (Map.Entry<PlanId, Entry> plan : plans.entrySet()) {
            if (!plan.getKey().value().equals(sessionId)) {
                continue;
            }
            Entry entry = plan.getValue();
            if (entry.closed || entry.allowed == null) {
                return Optional.empty();
            }
            int next = entry.currentStep + 1;
            boolean gatesTheTree = next < entry.steps.size() && !entry.steps.get(next).tool().startsWith(ANSWER_GATE_PREFIX);
            return Optional.of(new ToolAllowance(entry.allowed,
                gatesTheTree ? Optional.of(entry.steps.get(next).tool()) : Optional.empty(),
                gatesTheTree ? entry.steps.get(next).gateArguments() : null));
        }
        return Optional.empty();
    }

    /**
     * The pending plans {@code owner} answers for: those it opened and those no session was recorded for. A plan another session opened is not
     * listed, so a hire running for someone else does not hold this session's turn at its end (S10).
     */
    public synchronized List<Pending> pendingFor(String owner) {
        List<Pending> mine = new ArrayList<>();
        for (Pending open : pending()) {
            String opened = plans.get(open.id()).owner;
            if (opened.isEmpty() || opened.equals(owner)) {
                mine.add(open);
            }
        }
        return mine;
    }

    /** Refused because an open plan already holds the name: it says which one. */
    public static final class PlanExists extends IllegalStateException {

        private final transient PlanId holder;

        PlanExists(PlanId holder) {
            super("an open plan already holds that name: " + holder.value());
            this.holder = holder;
        }

        /** The identity of the open plan that holds the name. */
        public PlanId planId() {
            return holder;
        }
    }

    /**
     * Enters a sprint plan a pm made: the steps with a check become the chain whose gates the plan walks (indices 0..n-1, as {@link #open} requires),
     * the person's steps are kept beside it with the number of chain steps before each, and the checkpoint marks are kept. Progress still moves only
     * through {@link #advanceIfGatePassed}.
     *
     * @param name  the plan's name; compared after stripping, and an open plan already holding it refuses the entry
     * @param steps the plan's steps in order, at least one with a check
     * @return the new plan's identity
     * @throws IllegalArgumentException if the name is blank, the list is empty, or no step carries a check
     * @throws PlanExists               if an open plan already holds the name
     */
    public synchronized PlanId openSprint(String name, List<PlannedStep> steps) {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a sprint plan needs a name that is not blank");
        }
        if (steps == null || steps.isEmpty()) {
            throw new IllegalArgumentException("a sprint plan with no steps has nothing to gate");
        }
        for (Map.Entry<PlanId, Entry> plan : plans.entrySet()) {
            if (!plan.getValue().closed && plan.getValue().marks != null && plan.getValue().marks.name().equals(name.strip())) {
                throw new PlanExists(plan.getKey());
            }
        }
        List<GatedStep> chain = new ArrayList<>();
        List<String> titles = new ArrayList<>();
        List<Integer> checkpoints = new ArrayList<>();
        List<PlanMarks.Reserved> reserved = new ArrayList<>();
        for (PlannedStep step : steps) {
            if (step.person()) {
                reserved.add(new PlanMarks.Reserved(step.title(), chain.size()));
            } else {
                if (step.checkpoint()) {
                    checkpoints.add(chain.size());
                }
                chain.add(new GatedStep(chain.size(), step.tool(), step.arguments(), step.expect()));
                titles.add(step.title());
            }
        }
        if (chain.isEmpty()) {
            throw new IllegalArgumentException("a sprint plan of only the person's steps has nothing to gate");
        }
        PlanId id = PlanId.mint();
        Entry entry = new Entry(List.copyOf(chain));
        entry.marks =new PlanMarks(name.strip(), titles, checkpoints, reserved);
        entry.sequence = ++entered;
        plans.put(id, entry);
        dropEndedPlansPastTheLimit();
        persist();
        return id;
    }

    /**
     * What a sprint plan marks beside its chain: its name, the titles of its chain steps, the checkpoints and the person's steps.
     *
     * @param id the plan
     * @return the marks, or empty for an unknown plan and for a plan opened with {@link #open}, which has none
     */
    public synchronized Optional<PlanMarks> marks(PlanId id) {
        Entry entry = id == null ? null : plans.get(id);
        return entry == null ? Optional.empty() : Optional.ofNullable(entry.marks);
    }

    /**
     * Writes every entered sprint plan to the plan file, when the ledger has one: a temporary file next to it, moved over it. A write that
     * fails is logged and changes nothing in memory, so the plan stays valid.
     */
    private void persist() {
        if (planFile == null) {
            return;
        }
        try {
            ObjectNode root = JSON.createObjectNode();
            ArrayNode all = root.putArray("plans");
            for (Map.Entry<PlanId, Entry> plan : plans.entrySet()) {
                Entry entry = plan.getValue();
                if (entry.marks == null) {
                    continue;
                }
                ObjectNode stored = all.addObject();
                stored.put("id", plan.getKey().value());
                stored.put("name", entry.marks.name());
                stored.put("closed", entry.closed);
                ArrayNode steps = stored.putArray("steps");
                for (GatedStep step : entry.steps) {
                    ObjectNode storedStep = steps.addObject();
                    storedStep.put("title", entry.marks.titles().get(step.index()));
                    storedStep.put("tool", step.tool());
                    storedStep.set("arguments", step.gateArguments());
                    storedStep.set("expect", step.expect());
                    storedStep.put("checkpoint", entry.marks.checkpoints().contains(step.index()));
                }
                ArrayNode person = stored.putArray("person");
                for (PlanMarks.Reserved reserved : entry.marks.reserved()) {
                    person.addObject().put("title", reserved.title()).put("after", reserved.after());
                }
                ArrayNode passed = stored.putArray("passed");
                for (GateOutcome outcome : entry.passed) {
                    ObjectNode storedOutcome = passed.addObject();
                    storedOutcome.put("tool", outcome.tool());
                    storedOutcome.set("arguments", outcome.arguments());
                    storedOutcome.put("errorCount", outcome.errorCount());
                    storedOutcome.put("succeeded", outcome.succeeded());
                }
            }
            Path folder = planFile.toAbsolutePath().getParent();
            if (folder != null) {
                Files.createDirectories(folder);
            }
            Path temporary = planFile.resolveSibling(planFile.getFileName() + ".tmp");
            Files.write(temporary, JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(root));
            try {
                Files.move(temporary, planFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, planFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException failed) {
            LOG.warn("the sprint plans could not be written to {}: {}", planFile, failed.toString());
        }
    }

    /**
     * Restores the sprint plans the plan file holds. Each plan's passed steps are replayed, in order, through {@link #advance} — the check
     * {@link #advanceIfGatePassed} applies — so a restored plan is only where its gates took it, and a replay that is refused fails the load.
     */
    private void load() {
        if (!Files.isRegularFile(planFile)) {
            return;
        }
        JsonNode root;
        try {
            root = JSON.readTree(Files.readAllBytes(planFile));
        } catch (IOException unreadable) {
            throw new IllegalStateException("the sprint plan file " + planFile + " cannot be read", unreadable);
        }
        for (JsonNode stored : root.path("plans")) {
            List<GatedStep> chain = new ArrayList<>();
            List<String> titles = new ArrayList<>();
            List<Integer> checkpoints = new ArrayList<>();
            for (JsonNode step : stored.path("steps")) {
                if (step.path("checkpoint").asBoolean()) {
                    checkpoints.add(chain.size());
                }
                JsonNode expect = step.path("expect");
                chain.add(new GatedStep(chain.size(), step.path("tool").asText(), step.path("arguments"), expect.isObject() ? expect : null));
                titles.add(step.path("title").asText());
            }
            List<PlanMarks.Reserved> reserved = new ArrayList<>();
            for (JsonNode person : stored.path("person")) {
                reserved.add(new PlanMarks.Reserved(person.path("title").asText(), person.path("after").asInt()));
            }
            Entry entry = new Entry(List.copyOf(chain));
            entry.marks = new PlanMarks(stored.path("name").asText(), titles, checkpoints, reserved);
            for (JsonNode outcome : stored.path("passed")) {
                JsonNode arguments = outcome.path("arguments");
                GateOutcome replayed = new GateOutcome(outcome.path("tool").asText(), arguments.isObject() ? arguments : null,
                    outcome.path("errorCount").asLong(), outcome.path("succeeded").asBoolean());
                Advance moved = advance(replayed, entry);
                if (!moved.advanced()) {
                    throw new IllegalStateException("the sprint plan " + stored.path("id").asText() + " cannot be restored: " + moved.refusedBecause());
                }
            }
            entry.closed = stored.path("closed").asBoolean();
            plans.put(new PlanId(stored.path("id").asText()), entry);
        }
    }

    /**
     * Where the sprint plan owned by {@code ownerPlanId} stands: how many chain steps have passed, the next step, the next checkpoint and what is
     * left for the person.
     *
     * @param ownerPlanId the owner id the plan was recorded under by {@link #ownedBy}
     * @return the standing, or empty when no plan is owned by that id, or when the owner has only plans without sprint marks (plans a hire opened, not entered with plan_steps enter)
     */
    public synchronized Optional<Standing> standingOf(String ownerPlanId) {
        if (ownerPlanId == null || ownerPlanId.isBlank()) {
            return Optional.empty();
        }
        Entry newest = null;
        for (Entry candidate : plans.values()) {
            if (candidate.marks != null && candidate.owner.equals(ownerPlanId) && (newest == null || candidate.sequence > newest.sequence)) {
                newest = candidate;
            }
        }
        if (newest == null) {
            return Optional.empty();
        }
        int passed = newest.currentStep + 1;
        int total = newest.steps.size();
        boolean open = passed < total;
        Optional<Standing.Next> next = open
            ? Optional.of(new Standing.Next(passed, newest.marks.titles().get(passed), newest.steps.get(passed).tool()))
            : Optional.empty();
        Optional<Standing.NextCheckpoint> nextCheckpoint = Optional.empty();
        for (int checkpoint : newest.marks.checkpoints()) {
            if (checkpoint >= passed) {
                nextCheckpoint = Optional.of(new Standing.NextCheckpoint(checkpoint, newest.marks.titles().get(checkpoint)));
                break;
            }
        }
        boolean checkpointReached = newest.marks.checkpoints().contains(newest.currentStep);
        List<String> forPerson = new ArrayList<>();
        boolean personNext = false;
        Optional<String> personStep = Optional.empty();
        for (PlanMarks.Reserved reserved : newest.marks.reserved()) {
            forPerson.add(reserved.title());
            personNext |= open && reserved.after() == passed;
            if (personStep.isEmpty() && open && reserved.after() == passed) {
                personStep = Optional.of(reserved.title());
            }
        }
        Optional<String> reachedCheckpoint = checkpointReached ? Optional.of(newest.marks.titles().get(newest.currentStep)) : Optional.empty();
        return Optional.of(new Standing(passed, total, next, nextCheckpoint, List.copyOf(forPerson), checkpointReached, personNext, open,
            reachedCheckpoint, personStep));
    }
}
