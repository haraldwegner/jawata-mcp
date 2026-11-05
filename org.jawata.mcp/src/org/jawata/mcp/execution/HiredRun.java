package org.jawata.mcp.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.access.SessionFolders;
import org.jawata.mcp.execution.AcpBrainSession.Program;
import org.jawata.mcp.knowledge.ExperienceRetrieval;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.knowledge.SeatRules;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.LearnerEvent;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.step.GateOutcome;
import org.jawata.mcp.step.PlanId;

/**
 * One hired run, start to finish (Sprint 28g S9a M5a): the brief is issued, the
 * gates run, the run is journalled, and a PROPOSAL RECORD lands on disk.
 *
 * <h2>Why it proposes and never applies</h2>
 *
 * <p>{@link HiredBrain} drives a coding-agent CLI with nobody in the chair. A run
 * that applied its own work would be a change no human saw and no gate stopped,
 * so the deliverable is a RECORD: what was asked, what came back, what it cost,
 * which steps got past their gates, and which did not. A human reads that and
 * decides.</p>
 *
 * <h2>The record is an ARTIFACT, not a file this class invents</h2>
 *
 * <p>It is written through {@link RuntimeArtifactStore}, which already does this
 * exact job for heap dumps and flight recordings — ids, a directory, a manifest
 * carrying provenance, listing, a time-to-live and pruning. Writing a second
 * on-disk record format here would have bought nothing and cost the proposal its
 * place in the tooling that already lists and sweeps artifacts.</p>
 *
 * <h2>The gate verdict is the ledger's, not this class's</h2>
 *
 * <p>Each step's gate outcome goes to {@link PlanLedger#advanceIfGatePassed},
 * which decides whether the plan moved. This class does not form its own opinion
 * about whether a gate passed — that is the whole point of the ledger deriving
 * the step from the evidence, and it is why this is the ledger's first production
 * caller.</p>
 *
 * <p>Running a gate is NOT this class's job either: it does not know how to call
 * a tool. The caller supplies a {@link GateRunner}, which is the seam where the
 * real registry goes and where a test puts a stand-in.</p>
 */
public final class HiredRun {

    /** How a step's gate is actually run. The caller owns this; this class does not. */
    @FunctionalInterface
    public interface GateRunner {

        /**
         * Run this step's declared gate and report what it did — including, in
         * {@link GateOutcome#succeeded()}, whether it answered at all. A refused
         * gate reports zero errors, and reading that as a pass is the defect the
         * outcome's fourth component exists to prevent.
         */
        GateOutcome run(GatedStep step);
    }

    /**
     * What a hired run produced.
     *
     * @param plan        the ledger's identity for this run
     * @param artifactId  the artifact the record was written as
     * @param record      the file a human reads
     * @param outcome     what the brain said, spent and did
     * @param gates       each step's gate outcome, in order
     * @param stepsPassed how far the LEDGER let the plan advance — not a count
     *     this class formed
     * @param changed     every file the run changed in its working directory, or the stated
     *     reason no claim can be made (Sprint 28g S16, C16). It is on the Proposal and not only
     *     in the record so a caller deciding what to show a human need not re-parse the file this
     *     class just wrote — and so the two cannot disagree about what the run touched
     */
    public record Proposal(PlanId plan, String artifactId, Path record,
                           HiredBrain.Outcome outcome, List<GateOutcome> gates,
                           int stepsPassed, TreeAttribution.Attribution changed, List<String> answerGateRefusals) {

        /** Did every declared step get past its own gate? */
        public boolean complete() {
            return stepsPassed == gates.size() && !gates.isEmpty();
        }

        public Proposal(PlanId plan, String artifactId, Path record, HiredBrain.Outcome outcome, List<GateOutcome> gates, int stepsPassed, TreeAttribution.Attribution changed) {
            this(plan, artifactId, record, outcome, gates, stepsPassed, changed, List.of());
        }
    }

    private final HostProcesses host;
    private final RuntimeArtifactStore artifacts;
    private final PlanLedger plans;

    /** Nullable: a run without a journal still proposes, and says so by its absence. */
    private final LearnerEventStore journal;

    /**
     * Nullable: the hiring resident's own endpoint, handed to a seat's dialect so the brain talks
     * to THIS resident and nothing else (see {@link SelfEndpoint}). Absent on stdio, where the
     * resident has no endpoint a second process could reach.
     */
    private final SelfEndpoint self;

    /** Nullable: where the accepted rules a hired seat is bound by are read from (D45). */
    private final Supplier<ExperienceStore> rules;

    /** Nullable: the folder each hired session works in, which narrows its workspace under the access policy (S13, ACL4). */
    private final SessionFolders folders;

    /** Nullable: the area's jobs for a brief, burned into the prompt of a seat that changes code (S14, D16). */
    private final Function<String, ExperienceRetrieval.AreaMap> area;

    public HiredRun(HostProcesses host, RuntimeArtifactStore artifacts, PlanLedger plans,
            LearnerEventStore journal) {
        this(host, artifacts, plans, journal, null);
    }

    public HiredRun(HostProcesses host, RuntimeArtifactStore artifacts, PlanLedger plans,
            LearnerEventStore journal, SelfEndpoint self) {
        this(host, artifacts, plans, journal, self, null);
    }

    /**
     * @param rules where the accepted rules live (Sprint 28g D45); nullable, and the supplier may
     *     itself answer null (a degraded resident has no store). Without it a hire carries the
     *     seat's stance and the brief only, exactly as before.
     */
    public HiredRun(HostProcesses host, RuntimeArtifactStore artifacts, PlanLedger plans,
            LearnerEventStore journal, SelfEndpoint self, Supplier<ExperienceStore> rules) {
        this(host, artifacts, plans, journal, self, rules, null);
    }

    /**
     * @param folders where each hired session's working folder is recorded for the access policy (Sprint 28h S13, ACL4); nullable, and
     *     without it a hire is held to the whole manifest as before
     */
    public HiredRun(HostProcesses host, RuntimeArtifactStore artifacts, PlanLedger plans,
            LearnerEventStore journal, SelfEndpoint self, Supplier<ExperienceStore> rules, SessionFolders folders) {
        this(host, artifacts, plans, journal, self, rules, folders, null);
    }

    /**
     * @param area what the catalogue says about the ground a brief is about (Sprint 28h S14, D16): the task's words in, the map of the area's
     *     jobs with their live locations out; nullable, and without it a hire carries no ground, exactly as before
     */
    public HiredRun(HostProcesses host, RuntimeArtifactStore artifacts, PlanLedger plans,
            LearnerEventStore journal, SelfEndpoint self, Supplier<ExperienceStore> rules, SessionFolders folders,
            Function<String, ExperienceRetrieval.AreaMap> area) {
        this.area = area;
        this.folders = folders;
        this.host = Objects.requireNonNull(host, "a hired run needs a process boundary");
        this.artifacts = Objects.requireNonNull(artifacts, "a proposal with nowhere to land"
            + " is not a proposal");
        this.plans = Objects.requireNonNull(plans, "the gate verdict is the ledger's");
        this.journal = journal;
        this.self = self;
        this.rules = rules;
    }

    /**
     * Issue a SEAT's brief, with the brain, the tools and the ceilings the seat itself declared
     * (Sprint 28g S16, D37).
     *
     * <p><b>This is the form a caller should use, and it is three parameters shorter for a reason
     * rather than for brevity.</b> The dialect, the tool list and the ceilings are not independent
     * choices — they are one seat's declaration — and handing them over separately is what makes it
     * possible to run one seat's brain under another seat's budget. A {@link SeatHire} cannot be
     * mismatched against itself.</p>
     *
     * <p>A seat whose declared cost budget cannot fire on the brain it chose is not refused here;
     * {@link SeatHire#budgetReport()} is where that is said, because the seat's written intent is
     * legitimate and this product's reading of a vendor's output must not become a veto over it.</p>
     *
     * <p>The brain is asked {@link SeatHire#prompt(String)} — the seat's own stance, then the brief —
     * and the record's "The brief" section carries exactly that, so a reader sees what the brain was
     * actually given rather than the half of it the hirer wrote.</p>
     *
     * @param seat  the seat being hired: which brain, which tools, under what bounds, which rules
     * @param base  where the brain runs and with what environment; what it runs is the dialect's
     * @param brief what the brain is asked to do
     * @param steps the plan's declared steps and their gates
     * @param gates how to run one
     * @param hiddenTools the resident's code-changing tools the seat did not name, taken away from
     *     a brain that attaches the resident (REL44-6); empty for none
     */
    public Proposal issue(SeatHire seat, HireSite base, String brief, List<GatedStep> steps,
            GateRunner gates, List<String> hiddenTools) throws IOException, InterruptedException {
        return issue(seat, base, brief, steps, gates, hiddenTools, Optional.empty());
    }

    /**
     * The same hire, started through the engine's ACP client when the brain has a program that speaks ACP (S12, D1): the one start path.
     * With none the brain is started the way it always was, by its dialect's own command line.
     *
     * @param acp the program that starts this seat's brain over ACP, or empty
     */
    public Proposal issue(SeatHire seat, HireSite base, String brief, List<GatedStep> steps,
            GateRunner gates, List<String> hiddenTools, Optional<Program> acp) throws IOException, InterruptedException {
        BrainDialect dialect = seat.dialect(self, hiddenTools);
        WriteContainment containment = dialect.writeContainment();
        if (!containment.isContained() && !seat.acceptsUncontainedWrites()) {
            // S16-38. The hire tool refuses this first, with a friendlier answer; this is the
            // check every path that hands the hire a SEAT passes (a scheduler firing a seat), so
            // such a run cannot hire a brain held only by an instruction without the seat saying
            // it may. The overload taking a bare BrainDialect has no seat to ask and is NOT
            // covered: a caller choosing a dialect by hand is choosing its containment by hand.
            throw new IllegalArgumentException("seat '" + seat.seat() + "' would run on "
                + dialect.name() + ", whose writes are not refused by a mechanism — "
                + containment.report() + ". Add accepts_uncontained_writes: true to the seat's"
                + " frontmatter to say it may run on this brain as it is");
        }
        // D45: the accepted rules that bind THIS seat, read now, so one recorded after the build is
        // in this hire's text. A resident with no store, or a store that cannot be read, hires the
        // seat as before — a rule is guidance, and its absence must not stop a hire.
        List<String> binding = SeatRules.bindingOn(rules, seat.seat(), seat.activities());
        // THE BRAIN IS GIVEN THE RESIDENT UNDER THIS HIRE'S OWN SESSION ID (Sprint 28h, S3, D30): its plan's identity, which only exists once
        // the plan is open, so the plan is opened before the brain's command line is built. The resident then keeps this brain's calls in
        // a history of their own instead of the one every caller that sends no session id shares, and a check of "this session" is about
        // this brain and no other agent. The first dialect above only answered the containment question, which does not depend on it.
        List<GatedStep> planned = withAnswerGates(steps, seat.answerGates());
        PlanId plan = plans.open(planned, perStepCeiling(seat.ceilings(), planned.size()));
        owner.ifPresent(opener -> plans.ownedBy(plan, opener));
        // S13 (the audit of C6, finding F2): the seat's tool list is kept where the choke reads it, from the moment the plan opens, so a brain
        // that ignores the permission answer, or never asks, still cannot call outside it. Over ACP an empty list means none. A dialect-started
        // brain is bounded the same way when the seat lists tools; with none listed it keeps its own configuration, as the javadoc of the
        // bare-dialect overload says, so no list is recorded and the seat is not narrowed (a declared residual).
        if (acp.isPresent() || !seat.tools().isEmpty()) {
            plans.allowTools(plan, listForTheChoke(seat.tools()));
        }
        try {
            BrainDialect asThisHire = seat.dialect(self == null ? null : self.forSession(plan.value()), hiddenTools);
            // THE GROUND (S14, D16): only a seat that changes code is bound to the area's jobs. The map is the catalogue's own rows for the
            // brief, asked now, so a job described after the build is in this hire's prompt; a lookup that failed says so in the text.
            ExperienceRetrieval.AreaMap ground = area == null || !seat.changesCode() ? null : groundFor(brief);
            return conduct(asThisHire, base, seat.prompt(brief, binding, ground == null ? "" : ground.text()), seat.tools(), seat.ceilings(), gates,
                planned, plan, seat.model(), acp, ground == null ? List.of() : ground.shown());
        } finally {
            plans.close(plan);
        }
    }

    /**
     * A seat's tool list as the engine's choke reads it: each name as the seat wrote it and also its bare form. The permission path accepts a
     * brain's own name for a tool or the bare one, while the choke sees only the bare name the registry knows, so a seat that wrote
     * {@code mcp__server__inspect} would be allowed by one and refused by the other.
     */
    static List<String> listForTheChoke(List<String> seatTools) {
        List<String> widened = new ArrayList<>();
        for (String name : seatTools) {
            widened.add(name);
            widened.add(RunEffects.bareName(name));
        }
        return widened;
    }

    /**
     * Issue a brief with no tool list — the form that asks the brain for nothing in particular.
     *
     * <p>An empty list is NOT "no tools": a brain keeps whatever it is configured with, and the
     * dialects say so in their own words. Prefer {@link #issue(SeatHire, HireSite, String, List,
     * GateRunner, List)} wherever a seat is what is being hired.</p>
     *
     * @param dialect  how this brain speaks
     * @param base     where the brain runs and with what environment; what it runs is the
     *     dialect's
     * @param brief    what the brain is asked to do
     * @param ceilings what the run may consume
     * @param steps    the plan's declared steps and their gates
     * @param gates    how to run one
     */
    public Proposal issue(BrainDialect dialect, HireSite base, String brief,
            HiredBrain.Ceilings ceilings, List<GatedStep> steps, GateRunner gates)
            throws IOException, InterruptedException {
        return issue(dialect, base, brief, List.of(), ceilings, steps, gates, List.of());
    }

    private Proposal issue(BrainDialect dialect, HireSite base, String brief,
            List<String> tools, HiredBrain.Ceilings ceilings, List<GatedStep> steps,
            GateRunner declaredGates, List<String> answerGates)
            throws IOException, InterruptedException {
        // THE SEAT'S OWN CHECKS OVER ITS ANSWER are steps of the plan, ahead of the hirer's: a
        // seat whose message breaks its own rules has said nothing the later gates can vouch for.
        // They are planned here, before the brain runs, because the plan is opened once; what they
        // check is read off the outcome when they come up.
        List<GatedStep> planned = withAnswerGates(steps, answerGates);
        PlanId plan = plans.open(planned);

        // THE BRIEF IS ISSUED. Everything after this happened without supervision,
        // which is why every part of it is written down.
        //
        // THE TOOL LIST IS CARRIED AND IS NOT EVIDENCE. It reaches the dialect, which attaches it
        // if it CAN — and a dialect whose reach is not PER_RUN drops it silently, which is the
        // measured truth about two of the four brains rather than a defect. So what this method
        // reports below is the ATTACHMENT read off the dialect, never this argument: a list handed
        // to a brain that cannot be told is no evidence that anything was attached.
        // WHAT THE RUN CHANGES IS CENSUSED EITHER SIDE OF IT (Sprint 28g S16, C16's clause "every
        // changed file attributed to the run"). It has to be taken here, around the spawn, because
        // a brain edits through its OWN tools: no gate reports those writes, no tap sees them, and
        // the brain's answer is prose. The directory before and after is the only witness.
        try {
            return conduct(dialect, base, brief, tools, ceilings, declaredGates, planned, plan, null, java.util.Optional.empty(), List.of());
        } finally {
            // THE HIRE HAS ENDED, WHATEVER HOW: its plan ends with it. A plan left open after its hire finished (a gate refused, the brain
            // could not start) would be reported pending at every turn end of every session for as long as the ledger remembered it.
            plans.close(plan);
        }
    }

    /**
     * THE PLAN'S STEP MOVES WHILE THE BRAIN WORKS (Sprint 28h, S12, D18). On the ACP start path the client reports every tool call the brain
     * makes, including its own Edit and Write that no engine gate ever sees; after each COMPLETED call of an editing kind this runs the plan's
     * current step's gate through the same {@link GateRunner} the end-of-hire loop uses and lets the ledger rule on it, so a step whose gate
     * passes is passed at that moment, the per-step ceiling counts from there, and the end-of-hire loop resumes at the ledger's current step
     * instead of at the first.
     *
     * <p>It does nothing while the current step gates the seat's ANSWER (that gate can pass only after the answer exists), and a gate that
     * fails is not recorded here: the end-of-hire loop runs it again and records that attempt, so a brain that edits ten times against a
     * red gate leaves one failed row, not ten. Calls arrive one at a time on the client's read thread, so gates never run beside each other;
     * the method is synchronized anyway so that stays true if that changes.</p>
     */
    private final class LiveGates implements java.util.function.Consumer<com.fasterxml.jackson.databind.JsonNode> {

        private static final java.util.Set<String> EDITING = java.util.Set.of("edit", "move", "delete");

        private final PlanId plan;
        private final GateRunner gates;
        private final Map<String, String> kinds = new java.util.HashMap<>();
        private final List<GateOutcome> passed = new ArrayList<>();

        LiveGates(PlanId plan, GateRunner gates) {
            this.plan = plan;
            this.gates = gates;
        }

        @Override
        public synchronized void accept(com.fasterxml.jackson.databind.JsonNode update) {
            String callId = update.path("toolCallId").asText("");
            String kind = update.path("kind").asText("");
            if (!kind.isEmpty()) {
                kinds.put(callId, kind);
            }
            if (!"completed".equals(update.path("status").asText("")) || !EDITING.contains(kinds.getOrDefault(callId, ""))) {
                return;
            }
            Optional<GatedStep> next = plans.nextStep(plan);
            if (next.isEmpty() || next.get().tool().startsWith(PlanLedger.ANSWER_GATE_PREFIX)) {
                return;
            }
            try {
                GateOutcome gate = gates.run(next.get());
                if (plans.advanceIfGatePassed(plan, gate).advanced()) {
                    passed.add(gate);
                }
            } catch (RuntimeException e) {
                // a gate that could not run is not a pass; the end-of-hire loop runs it again and says what happened
            }
        }

        synchronized List<GateOutcome> passed() {
            return List.copyOf(passed);
        }
    }

    /** The brain session this run continues when its brain is started over ACP (D4); none starts a new one. */
    private Optional<String> resume = Optional.empty();

    /** The session that issued this hire, recorded on the plan it opens so the plan's pending step is that session's to answer for (S10). */
    private Optional<String> owner = Optional.empty();

    /** The seat this run is for and the seat that hired it, written to the journal row so cost and time can be read per seat (28h item c). */
    private String seatName = "";
    private String hirerName = "";

    /** Names the seat that runs and the seat that hired it; a run is built for one hire, so the names belong to that hire alone. */
    public HiredRun labelled(String seat, String hirer) {
        this.seatName = seat == null ? "" : seat;
        this.hirerName = hirer == null ? "" : hirer;
        return this;
    }

    /** The plan id of the hire this run corrects, written to the journal row so the fix and the hire it corrects can be read together (28h item b). */
    private String fixesPlan = "";

    /** Names the earlier hire this run corrects; blank when it corrects none. A run is built for one hire, so the name belongs to that hire alone. */
    public HiredRun fixing(String plan) {
        this.fixesPlan = plan == null ? "" : plan.strip();
        return this;
    }

    /** Names the session that issued this hire; a run is built for one hire, so the owner belongs to that hire alone. */
    public HiredRun ownedBy(Optional<String> issuer) {
        this.owner = issuer;
        return this;
    }

    /**
     * Makes this run continue {@code brainSession}, the session the same seat's earlier hire answered in, when the brain is started over ACP.
     * A run is built for one hire, so the session belongs to that hire alone.
     */
    public HiredRun resuming(Optional<String> brainSession) {
        this.resume = brainSession;
        return this;
    }

    /**
     * The per-step action ceiling for a plan (D18): the seat's own run-wide tool ceiling ({@code max_tool_steps}, which the spec names as the
     * ceiling D18 enforces) spread over the plan's steps, rounded up, so a step cannot spend the whole run's allowance before its gate has
     * passed and the steps together stay near the run's. Zero when the seat set no ceiling. DECIDED here because the spec gives no separate
     * number: a new frontmatter key would be a second place a budget is set (D-SIX), the same reason the model registry's budget was dropped in
     * slice 1.
     */
    static int perStepCeiling(HiredBrain.Ceilings ceilings, int stepCount) {
        if (ceilings == null || ceilings.maxToolSteps() <= 0) {
            return 0;
        }
        return (int) Math.ceil(ceilings.maxToolSteps() / (double) Math.max(1, stepCount));
    }

    private Proposal conduct(BrainDialect dialect, HireSite base, String brief, List<String> tools,
            HiredBrain.Ceilings ceilings, GateRunner declaredGates, List<GatedStep> planned, PlanId plan, String model, Optional<Program> acp,
            List<String> shownGround)
            throws IOException, InterruptedException {
        TreeAttribution census = base.workingDirectory() == null
            ? null
            : TreeAttribution.before(host, base.workingDirectory());

        // THE ONE START PATH (S12): a brain with a program that speaks ACP is run through the engine's ACP client, under this plan's own
        // session id, with the seat's tools as its allowlist (an empty list allows none); any other brain is started by its dialect.
        LiveGates live = acp.isPresent() ? new LiveGates(plan, declaredGates) : null;
        // ACL4: while the brain works, its session (the plan id it presents to the resident) is concerned with the folder it was hired in,
        // not with every project the manifest holds; the binding ends with the run.
        HiredBrain.Outcome outcome;
        final long brainStartedMs = System.currentTimeMillis();
        try (SessionFolders.Binding ignored = folders == null ? null : folders.bind(plan.value(), base.workingDirectory())) {
            outcome = acp.isPresent()
                ? AcpBrainSession.run(host, acp.get(), base, brief, self == null ? null : self.forSession(plan.value()), tools,
                    resume, ceilings, null, live, update -> { })
                : HiredBrain.hire(host, dialect, base, brief, tools, ceilings);
        }

        final long brainRanMs = System.currentTimeMillis() - brainStartedMs;

        // Taken immediately, before the gates run. A gate is a jawata tool and may legitimately
        // rewrite files itself, and folding its edits into the BRAIN's attribution would blame the
        // brain for a cleanup the plan asked for.
        TreeAttribution.Attribution changed = census == null
            ? new TreeAttribution.Attribution(java.util.Optional.empty(),
                "NOT ATTRIBUTABLE: this hire named no working directory, so there was no tree to"
                    + " census. A hire issued through the `hire` tool always names one.")
            : census.after();

        // THE GATES RUN, and the LEDGER rules on each. Stopping at the first
        // refusal is deliberate: a plan's steps are ordered, so a step whose gate
        // did not pass makes every later verdict a statement about a tree that was
        // never reached.
        // Steps the live gates already passed while the brain worked are in the list, in order, and the ledger holds how far the plan got:
        // the loop resumes after that step, so none runs twice. On any other start path nothing ran live and the loop starts at the first.
        List<GateOutcome> ran = new ArrayList<>(live == null ? List.of() : live.passed());
        List<String> answerRefusals = new ArrayList<>();
        int alreadyPassed = plans.currentStep(plan).orElse(-1);
        PlanLedger.Advance last = alreadyPassed >= 0 ? new PlanLedger.Advance(true, alreadyPassed, null) : null;
        for (GatedStep step : planned) {
            if (step.index() <= alreadyPassed) {
                continue;
            }
            GateOutcome gate;
            if (UpwardMessageGate.STEP.equals(step.tool())) {
                List<String> refusals = UpwardMessageGate.refusals(outcome.text());
                answerRefusals.addAll(refusals);
                gate = new GateOutcome(step.tool(), step.gateArguments(), refusals.size(), true);
            } else {
                gate = declaredGates.run(step);
            }
            ran.add(gate);
            last = plans.advanceIfGatePassed(plan, gate);
            if (!last.advanced()) {
                break;
            }
        }

        // THE LEDGER'S VERDICT, TAKEN BY VALUE from the advance it just returned rather
        // than asked for a second time. Re-reading it was a question about shared mutable
        // state whose answer this loop was already holding: the plan store is an LRU
        // bounded at MAX_PLANS, so an eviction between the loop and this line would have
        // answered "no such plan" and rendered 0 of N steps passed for a run whose every
        // gate passed — an empty result reported as a real one, in the one artifact whose
        // whole purpose is that a human reads it and decides.
        //
        // It is still the LEDGER's number and not a count formed here, which is what the
        // step-count mutation proves: `ran.size()` diverges from this on exactly the runs
        // where a gate failed or did not answer.
        //
        // `last` cannot be null — open() refuses an empty step list, so the loop body
        // runs at least once.
        int passed = last.currentStep() + 1;

        String artifactId = artifacts.newArtifactId("proposal");
        Path dir = artifacts.createArtifactDir(artifactId);
        Path record = dir.resolve("proposal.md");
        String rendered = render(brief, dialect, outcome, planned, ran, passed,
            last.advanced() ? null : last.refusedBecause(), changed);
        if (!answerRefusals.isEmpty()) {
            // The gate row says HOW MANY; this says WHICH, in the file a human opens.
            rendered += "\n## The seat's own answer was refused by its gate\n\n"
                + answerRefusals.stream().map(r -> "- " + r).collect(
                    java.util.stream.Collectors.joining("\n")) + "\n";
        }
        Files.writeString(record, rendered, StandardCharsets.UTF_8);
        artifacts.writeManifest(artifactId,
            provenance(plan, dialect, outcome, planned, passed, changed));

        // JOURNALLED. Its own try: a run that happened must not be lost because
        // recording it failed.
        if (journal != null) {
            try {
                // The detail stays small, and carries the two figures a later hire is scored against by
                // reading this row: the tokens this run reported (null when it reported none) and the
                // instructions its answer issued. Everything else is in the artifact.
                java.util.Map<String, Object> detail = new LinkedHashMap<>();
                detail.put("artifactId", artifactId);
                detail.put("stepsPassed", passed);
                detail.put("ofSteps", planned.size());
                // WHO ANSWERED, in the row a verdict is read back from (D48): the vendor is the row's own brain column, and the model is here,
                // so a review's journal row says which vendor's agent and which model gave it. Null when the hire was made without a seat.
                detail.put("model", model);
                // WHAT A COST VIEW AND A CHOICE OF MODEL READ (28h item c): which seat, who hired it, how long the brain ran, and what it cost
                // when it said. A cost the brain did not send is absent from the row and never 0.
                detail.put("seat", seatName);
                detail.put("hirer", hirerName);
                if (!fixesPlan.isEmpty()) {
                    detail.put("fixes", fixesPlan);
                }
                detail.put("elapsedMs", brainRanMs);
                detail.put("costUsd", costForRow(outcome));
                detail.put("tokensSpent", outcome.tokens().isPresent() ? outcome.tokens().get().spent() : null);
                detail.put("instructions", Instructions.rows(Instructions.parse(outcome.text())));
                detail.put("instructionFaults", Instructions.faults(outcome.text()));
                // SHOWN, CHOSEN (28f D8, S14 D16): the area's jobs the prompt carried, and which of them the run then worked in. A hire that
                // was shown none writes no entry, so the row never claims a ground the prompt did not have.
                if (!shownGround.isEmpty()) {
                    java.util.Map<String, Object> ground = new LinkedHashMap<>();
                    ground.put("shown", shownGround);
                    ground.put("chosen", chosenGround(shownGround, changed));
                    detail.put("areaGround", ground);
                }
                journal.append(new LearnerEvent(plan.value(), LearnerEvent.KIND_HIRED_RUN,
                    dialect.name(), new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(detail)));
            } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException e) {
                // the proposal is already on disk; the journal row is bookkeeping
            }
        }
        return new Proposal(plan, artifactId, record, outcome, List.copyOf(ran), passed, changed,
            List.copyOf(answerRefusals));
    }

    private Double costForRow(HiredBrain.Outcome outcome) {
        return outcome.costUsd().isPresent() ? outcome.costUsd().getAsDouble() : null;
    }

    /**
     * The area's map for this brief, or a map that says the lookup failed: a hire is never stopped by the catalogue being unreadable,
     * and the prompt never reads a failure as "nothing is catalogued here".
     */
    private ExperienceRetrieval.AreaMap groundFor(String brief) {
        try {
            ExperienceRetrieval.AreaMap map = area.apply(brief);
            return map == null ? new ExperienceRetrieval.AreaMap("", List.of()) : map;
        } catch (RuntimeException e) {
            return new ExperienceRetrieval.AreaMap("JAWATA MAP — unavailable. The lookup failed (" + e.getClass().getSimpleName()
                + "); this is a failed lookup, not an absence.", List.of());
        }
    }

    /**
     * Which of the members the prompt showed the run TOUCHED: those whose type's source file is among the files the run changed.
     *
     * <p><b>This is "touched", and it is not D16's "chosen".</b> D16 asks whether the injected knowledge changed what was written. The
     * shown members come from the hirer's own brief words, so the brain would edit those files with or without the map, and this number
     * is the same with the ground removed. It says which shown files the run changed; it does not say the map is why. The comparison
     * D16 asks for (the same brief hired with and without the ground) needs a brain that can be told, and is not built.</p>
     *
     * A member address is {@code pkg.Type#member}; a nested type belongs to the file of its outermost type. A run whose changes could not
     * be attributed chose none that can be named, and the answer is empty rather than every member shown.
     *
     * @param shown   the members the prompt showed
     * @param changed what the run changed, or that it could not be told
     * @return the shown members whose file the run changed, in the order shown
     */
    static List<String> chosenGround(List<String> shown, TreeAttribution.Attribution changed) {
        List<String> files = changed.changed().orElse(List.of());
        List<String> chosen = new ArrayList<>();
        for (String address : shown) {
            int hash = address.indexOf('#');
            String type = hash < 0 ? address : address.substring(0, hash);
            int nested = type.indexOf('$');
            String source = "/" + (nested < 0 ? type : type.substring(0, nested)).replace('.', '/') + ".java";
            for (String file : files) {
                if (("/" + file.replace('\\', '/')).endsWith(source)) {
                    chosen.add(address);
                    break;
                }
            }
        }
        return List.copyOf(chosen);
    }

    /**
     * The plan the run is opened with: one step per answer gate the seat declares, then the hirer's
     * own steps, renumbered to follow. A name this build has no check for is refused rather than
     * skipped — a seat that declares a gate and is hired without it has been told something false.
     */
    private static List<GatedStep> withAnswerGates(List<GatedStep> steps, List<String> answerGates) {
        List<GatedStep> planned = new ArrayList<>();
        for (String name : answerGates) {
            if (!"upward_message".equals(name)) {
                throw new IllegalArgumentException("the seat declares answer gate '" + name
                    + "', which this build has no check for. Known: upward_message");
            }
            planned.add(new GatedStep(planned.size(), UpwardMessageGate.STEP,
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
        }
        int shift = planned.size();
        for (GatedStep step : steps) {
            planned.add(new GatedStep(step.index() + shift, step.tool(), step.gateArguments()));
        }
        return List.copyOf(planned);
    }

    /** What the manifest carries, so a reader of the artifact alone can judge the run. */
    private static Map<String, Object> provenance(PlanId plan, BrainDialect dialect,
            HiredBrain.Outcome outcome, List<GatedStep> steps, int passed,
            TreeAttribution.Attribution changed) {
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("kind", "proposal");
        p.put("plan", plan.value());
        p.put("brain", dialect.name());
        // WHICH OF THE THREE REACHES THIS BRAIN HAS. A brain that cannot be told which
        // tools to attach is REPORTED here rather than left out, because an absent field
        // reads as "not applicable" and the honest answer is "not possible, and here is
        // what bounds it instead". Measured 2026-10-08, the four brains answer three
        // different ways and one of them cannot be told at all.
        p.put("toolAttachment", dialect.toolAttachment().report());
        p.put("toolsSettableByTheHirer", dialect.toolAttachment().settableByTheHirer());
        WriteContainment containment = dialect.writeContainment();
        Map<String, Object> writes = new LinkedHashMap<>();
        writes.put("basis", containment.basis().name());
        writes.put("enforcedBy", containment.enforcedBy());
        writes.put("boundedInstead", containment.boundedInstead());
        p.put("writeContainment", writes);
        p.put("writeContainmentWitnessed", writeWitness(containment, outcome.toolsOffered()));
        p.put("stepsPassed", passed);
        p.put("ofSteps", steps.size());
        p.put("toolSteps", outcome.toolSteps());
        // WHICH calls, not only how many: "the seat made no code-changing call" is a claim
        // about names, and a count answers none of it. `unnamedToolSteps` is the part of the
        // count no name accounts for — a dialect that counts what it cannot name — and it is
        // reported because an empty list beside a non-zero count reads as "nothing was called".
        p.put("toolNames", outcome.toolNames());
        p.put("unnamedToolSteps", outcome.unnamedToolSteps());
        // ABSENT rather than zero when nothing reported one — see GateOutcome and
        // HiredBrain.Outcome. A manifest reading 0.0 for a reaped run is the lie
        // the Rust reference tells.
        p.put("costUsd", outcome.costUsd().isPresent() ? outcome.costUsd().getAsDouble() : null);
        p.put("sawTerminalCost", outcome.sawTerminalCost());
        // WHAT THE BRAIN REPORTED SPENDING IN TOKENS, from its own final event, and NULL beside
        // `tokensReported: false` when it reported none. A brain that did not report is not a brain
        // that spent nothing: the PM's score divides these, and a zero would read as an efficient run.
        p.put("tokensReported", outcome.tokens().isPresent());
        p.put("tokens", outcome.tokens().map(TokenUsage::asMap).orElse(null));
        // THE INSTRUCTIONS THIS RUN ISSUED, parsed from its answer, so a later hire of the worker can be
        // scored against them by reading this row (the PM's measure: tokens per instruction that ran as written).
        p.put("instructions", Instructions.rows(Instructions.parse(outcome.text())));
        p.put("timedOut", outcome.timedOut());
        p.put("stoppedBy", outcome.stoppedBy().orElse(null));
        p.put("exitCode", outcome.exitCode().isPresent() ? outcome.exitCode().getAsInt() : null);
        // EVERY CHANGED FILE ATTRIBUTED TO THE RUN, and NULL when nobody could look. The two must
        // not collapse: a manifest reading `changedFiles: []` for a tree that was never censused
        // tells a reader the brain touched nothing, which is the one thing they most need to be
        // right about before accepting a proposal. `changedFilesBasis` is always present and says
        // which it is, including the case the census cannot see.
        p.put("changedFiles", changed.changed().orElse(null));
        p.put("changedFileCount", changed.count().orElse(null));
        p.put("changedFilesBasis", changed.basis());
        p.put("applied", applied(changed));
        // WHAT `applied` AND `changedFiles` ARE ABOUT (S16-21, the architect's disposition): a bare
        // boolean read as "this run wrote nothing" would deny a write the census cannot see. Additive,
        // so nothing that parses the keys above changes meaning.
        p.put("appliedScope", OFF_TREE_SCOPE);
        return p;
    }

    /**
     * What the changed-file census covers, said once so the manifest and the record cannot word it
     * differently. The hire's own census covers the working directory only. The containment probe
     * (build/c16-containment-isolated.sh) is DESIGNED to list one other place for claude, its plans
     * directory, and has been driven only against a stub claude, not a real one; that listing sees what
     * changed in the directory during a cell and not which process changed it, so a second Claude
     * session writing there during a cell would read as a write by the cell. For codex, copilot and cursor an off-tree write is unmeasured.
     */
    static final String OFF_TREE_SCOPE = "the working directory only: the census is taken before and"
        + " after the run and cannot see a write anywhere else. Off-tree writes are unmeasured for"
        + " codex, copilot and cursor, and for claude only where the editors are witnessed absent.";

    /** The built-in tools that edit files; a mechanism that says they are absent is checked against them. */
    private static final java.util.Set<String> EDITORS =
        java.util.Set.of("Edit", "Write", "NotebookEdit", "MultiEdit");

    /**
     * Whether the run applied anything, READ from the changed-file census rather than asserted:
     * true when it named files, false when it named none, and NULL when nobody could look — which
     * must not be told as false, because "nothing applied" for a tree that was never censused is
     * the one claim a reader deciding whether to accept a proposal must not be given falsely.
     */
    static Boolean applied(TreeAttribution.Attribution changed) {
        return changed.changed().map(files -> !files.isEmpty()).orElse(null);
    }

    /**
     * The first line of the record a human reads, following the census in its three states. The
     * old unconditional "NOTHING HERE WAS APPLIED" said so for a run that had written files.
     */
    static String headline(TreeAttribution.Attribution changed) {
        Boolean applied = applied(changed);
        if (applied == null) {
            return "**WHETHER THIS RUN CHANGED ANYTHING IS NOT ESTABLISHED:** " + changed.basis()
                + " A census, where one can be taken, sees only the working directory. A hired run"
                + " proposes; a human decides.";
        }
        if (applied) {
            return "**THIS RUN CHANGED " + changed.count().orElse(0) + " FILE(S) in its working"
                + " directory** (listed under \"What it changed\"), by a census which sees only"
                + " that directory. A hired run proposes; a human decides whether to keep them.";
        }
        return "**NOTHING HERE WAS APPLIED** in the working directory, by a census taken before and"
            + " after the run — which sees only that directory. A hired run proposes; a human"
            + " decides.";
    }

    /**
     * Whether this run's own log bears out the write containment its dialect declares. Only a
     * MECHANISM has a refusal point to witness; for the one that says the editors are absent, the
     * init event's list of offered tools is the witness. A brain that publishes no list can only be
     * believed, and the record says so rather than printing a bare claim.
     */
    public static String writeWitness(WriteContainment containment, java.util.Optional<java.util.List<String>> offered) {
        if (!containment.isContained()) {
            return "not applicable: " + containment.basis() + " has no refusal point to witness";
        }
        if (offered.isEmpty()) {
            return "declared, not witnessed: this brain publishes no list of the tools it offered";
        }
        for (String tool : offered.get()) {
            if (EDITORS.contains(tool)) {
                return "CONTRADICTED: the run's own init list offered the editor " + tool;
            }
        }
        return "WITNESSED: the run's own init list of " + offered.get().size()
            + " tool(s) offered no editor";
    }

    private static String render(String brief, BrainDialect dialect, HiredBrain.Outcome outcome,
            List<GatedStep> steps, List<GateOutcome> ran, int passed, String refusedBecause,
            TreeAttribution.Attribution changed) {
        StringBuilder out = new StringBuilder();
        out.append("# Proposal from an unattended run\n\n");
        out.append(headline(changed)).append("\n\n");
        out.append("- brain: ").append(dialect.name()).append('\n');
        // IN THE FILE A HUMAN READS, not only in the manifest. The reader of an
        // unattended run's proposal is deciding whether to trust it, and how tightly the
        // brain was bounded is part of that judgement — a brain that could not be handed
        // a tool list had more latitude than one that was, and the record must not leave
        // them looking identical.
        out.append("- tools: ").append(dialect.toolAttachment().report()).append('\n');
        // WHAT STOPS IT WRITING, as a mechanism or an instruction, and whether this run's own
        // log bears that out — the line the reader needs to decide how far to trust "no changes".
        out.append("- writes: ").append(dialect.writeContainment().report()).append(" — ")
            .append(writeWitness(dialect.writeContainment(), outcome.toolsOffered())).append('\n');
        out.append("- changes counted in: ").append(OFF_TREE_SCOPE).append('\n');
        out.append("- steps past their gates: ").append(passed).append(" of ")
            .append(steps.size()).append('\n');
        if (refusedBecause != null) {
            // THE LEDGER'S OWN REASON, BESIDE THE NUMBER IT EXPLAINS. It is not derivable
            // from the gate lines further down: a gate can report cleanly and the ledger
            // still refuse — the wrong tool for this step, arguments the step did not
            // declare, or no plan under that identity at all. It sat after the brain's
            // whole transcript until the C9a round-3 audit pointed out that "0 of N with
            // no reason" had merely become "0 of N … transcript … reason".
            out.append("  ^ THE PLAN STOPPED HERE: ").append(refusedBecause).append('\n');
        }
        out.append("- tool steps: ").append(outcome.toolSteps()).append('\n');
        out.append("- tools called: ").append(outcome.toolNames().isEmpty()
            ? "none named"
            : String.join(", ", outcome.toolNames())).append('\n');
        if (outcome.unnamedToolSteps() > 0) {
            out.append("  ^ ").append(outcome.unnamedToolSteps()).append(" of those steps this brain's")
                .append(" dialect counted and could NOT name — they are unclassified, not harmless\n");
        }
        out.append("- cost: ").append(outcome.costUsd().isPresent()
            ? String.valueOf(outcome.costUsd().getAsDouble())
            : "NOT REPORTED (absent, which is not zero)").append('\n');
        if (outcome.stoppedBy().isPresent()) {
            out.append("- stopped by: ").append(outcome.stoppedBy().get()).append('\n');
        }
        if (outcome.timedOut()) {
            out.append("- reaped on its wall clock\n");
        }
        // THE CRASH, IN THE RECORD. This used to live only in the manifest, with the
        // record pointing at it — so a brain that SPOKE and then died rendered exactly
        // like a brain that answered, because saidNothing() can only speak for a run
        // that produced no text. Separating a crash from silence is the whole point of
        // the outcome's shape, and it has to be legible in the file a human reads.
        if (!outcome.clean()) {
            out.append("- IT DID NOT EXIT CLEANLY: ").append(outcome.exitCode().isPresent()
                ? "exit code " + outcome.exitCode().getAsInt()
                : "reaped before it could exit").append('\n');
        }
        if (!outcome.stderrTail().isEmpty()) {
            out.append("- it wrote to stderr, which is where a crash goes\n");
        }
        out.append("\n## The brief\n\n").append(brief).append("\n\n## What it said\n\n");
        out.append(outcome.text().isEmpty()
            ? (outcome.saidNothing() ? "_nothing — and it ran cleanly, so that is silence_"
                : "_nothing on stdout, and it FAILED — see the exit status above_")
            : outcome.text());
        out.append("\n\n## The gates\n\n");
        for (int i = 0; i < ran.size(); i++) {
            GateOutcome g = ran.get(i);
            out.append("- step ").append(i).append(" (").append(steps.get(i).tool()).append("): ");
            if (!g.succeeded()) {
                out.append("DID NOT ANSWER (").append(g.notAnsweredBecause() == null
                    ? "no reason was given" : g.notAnsweredBecause())
                    .append(") — its zero error count says nothing");
            } else {
                out.append(g.errorCount() == 0 ? "passed" : g.errorCount() + " error(s)");
            }
            out.append('\n');
        }
        // WHAT IT CHANGED, in the file a human reads, because that is what they are deciding
        // about. Three distinct renderings and never two of them merged: a measured empty list
        // says the brain touched nothing, a measured list names what to review, and an ABSENT
        // census says nobody looked — which a reader must not mistake for the first.
        out.append("\n## What it changed\n\n");
        if (changed.changed().isEmpty()) {
            out.append("**NOT ATTRIBUTED.** ").append(changed.basis()).append('\n');
        } else if (changed.changed().get().isEmpty()) {
            out.append("Nothing. ").append(changed.basis()).append('\n');
        } else {
            changed.changed().get().forEach(path -> out.append("- ").append(path).append('\n'));
            out.append("\n_").append(changed.basis()).append("_\n");
        }
        return out.toString();
    }
}
