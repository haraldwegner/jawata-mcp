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

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.learn.GateOutcome;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.LearnerEvent;
import org.jawata.mcp.learn.PlanId;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.runtime.RuntimeArtifactStore;

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

    public HiredRun(HostProcesses host, RuntimeArtifactStore artifacts, PlanLedger plans,
            LearnerEventStore journal) {
        this(host, artifacts, plans, journal, null);
    }

    public HiredRun(HostProcesses host, RuntimeArtifactStore artifacts, PlanLedger plans,
            LearnerEventStore journal, SelfEndpoint self) {
        this.host = Objects.requireNonNull(host, "a hired run needs a process boundary");
        this.artifacts = Objects.requireNonNull(artifacts, "a proposal with nowhere to land"
            + " is not a proposal");
        this.plans = Objects.requireNonNull(plans, "the gate verdict is the ledger's");
        this.journal = journal;
        this.self = self;
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
     * @param base  the command's shape (working directory, environment); its argv is the dialect's
     * @param brief what the brain is asked to do
     * @param steps the plan's declared steps and their gates
     * @param gates how to run one
     */
    public Proposal issue(SeatHire seat, HostCommand base, String brief, List<GatedStep> steps,
            GateRunner gates) throws IOException, InterruptedException {
        return issue(seat.dialect(self), base, seat.prompt(brief), seat.tools(), seat.ceilings(),
            steps, gates, seat.answerGates());
    }

    /**
     * Issue a brief with no tool list — the form that asks the brain for nothing in particular.
     *
     * <p>An empty list is NOT "no tools": a brain keeps whatever it is configured with, and the
     * dialects say so in their own words. Prefer {@link #issue(SeatHire, HostCommand, String, List,
     * GateRunner)} wherever a seat is what is being hired.</p>
     *
     * @param dialect  how this brain speaks
     * @param base     the command's shape; its argv is the dialect's
     * @param brief    what the brain is asked to do
     * @param ceilings what the run may consume
     * @param steps    the plan's declared steps and their gates
     * @param gates    how to run one
     */
    public Proposal issue(BrainDialect dialect, HostCommand base, String brief,
            HiredBrain.Ceilings ceilings, List<GatedStep> steps, GateRunner gates)
            throws IOException, InterruptedException {
        return issue(dialect, base, brief, List.of(), ceilings, steps, gates, List.of());
    }

    private Proposal issue(BrainDialect dialect, HostCommand base, String brief,
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
        TreeAttribution census = base.workingDirectory() == null
            ? null
            : TreeAttribution.before(host, base.workingDirectory());

        HiredBrain.Outcome outcome = HiredBrain.hire(host, dialect, base, brief, tools, ceilings);

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
        List<GateOutcome> ran = new ArrayList<>();
        List<String> answerRefusals = new ArrayList<>();
        PlanLedger.Advance last = null;
        for (GatedStep step : planned) {
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
                journal.append(new LearnerEvent(plan.value(), LearnerEvent.KIND_HIRED_RUN,
                    dialect.name(), "{\"artifactId\":\"" + artifactId + "\",\"stepsPassed\":"
                        + passed + ",\"ofSteps\":" + planned.size() + "}"));
            } catch (RuntimeException e) {
                // the proposal is already on disk; the journal row is bookkeeping
            }
        }
        return new Proposal(plan, artifactId, record, outcome, List.copyOf(ran), passed, changed,
            List.copyOf(answerRefusals));
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
        p.put("applied", false);
        return p;
    }

    private static String render(String brief, BrainDialect dialect, HiredBrain.Outcome outcome,
            List<GatedStep> steps, List<GateOutcome> ran, int passed, String refusedBecause,
            TreeAttribution.Attribution changed) {
        StringBuilder out = new StringBuilder();
        out.append("# Proposal from an unattended run\n\n");
        out.append("**NOTHING HERE WAS APPLIED.** A hired run proposes; a human decides.\n\n");
        out.append("- brain: ").append(dialect.name()).append('\n');
        // IN THE FILE A HUMAN READS, not only in the manifest. The reader of an
        // unattended run's proposal is deciding whether to trust it, and how tightly the
        // brain was bounded is part of that judgement — a brain that could not be handed
        // a tool list had more latitude than one that was, and the record must not leave
        // them looking identical.
        out.append("- tools: ").append(dialect.toolAttachment().report()).append('\n');
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
