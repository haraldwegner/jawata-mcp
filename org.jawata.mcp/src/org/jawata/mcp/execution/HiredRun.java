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
     */
    public record Proposal(PlanId plan, String artifactId, Path record,
                           HiredBrain.Outcome outcome, List<GateOutcome> gates,
                           int stepsPassed) {

        /** Did every declared step get past its own gate? */
        public boolean complete() {
            return stepsPassed == gates.size() && !gates.isEmpty();
        }
    }

    private final HostProcesses host;
    private final RuntimeArtifactStore artifacts;
    private final PlanLedger plans;

    /** Nullable: a run without a journal still proposes, and says so by its absence. */
    private final LearnerEventStore journal;

    public HiredRun(HostProcesses host, RuntimeArtifactStore artifacts, PlanLedger plans,
            LearnerEventStore journal) {
        this.host = Objects.requireNonNull(host, "a hired run needs a process boundary");
        this.artifacts = Objects.requireNonNull(artifacts, "a proposal with nowhere to land"
            + " is not a proposal");
        this.plans = Objects.requireNonNull(plans, "the gate verdict is the ledger's");
        this.journal = journal;
    }

    /**
     * Issue the brief, drive the brain, run each step's gate, journal it, and write
     * the record.
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
        PlanId plan = plans.open(steps);

        // THE BRIEF IS ISSUED. Everything after this happened without supervision,
        // which is why every part of it is written down.
        // THE EMPTY TOOL LIST IS DELIBERATE AND IS NOT "no tools". Nothing yet holds a
        // seat's declared tool set — that arrives with the brain-as-data work on the seat
        // definition — so this run asks for nothing in particular and the brain keeps
        // whatever it is configured with. What this method DOES owe a reader is which of
        // the three reaches it got, and that is reported below from the dialect rather
        // than inferred from this argument: a list handed to a brain that cannot be told
        // is silently dropped, so the argument is no evidence that anything was attached.
        HiredBrain.Outcome outcome = HiredBrain.hire(host, dialect, base, brief, List.of(), ceilings);

        // THE GATES RUN, and the LEDGER rules on each. Stopping at the first
        // refusal is deliberate: a plan's steps are ordered, so a step whose gate
        // did not pass makes every later verdict a statement about a tree that was
        // never reached.
        List<GateOutcome> ran = new ArrayList<>();
        PlanLedger.Advance last = null;
        for (GatedStep step : steps) {
            GateOutcome gate = gates.run(step);
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
        Files.writeString(record, render(brief, dialect, outcome, steps, ran, passed,
            last.advanced() ? null : last.refusedBecause()), StandardCharsets.UTF_8);
        artifacts.writeManifest(artifactId, provenance(plan, dialect, outcome, steps, passed));

        // JOURNALLED. Its own try: a run that happened must not be lost because
        // recording it failed.
        if (journal != null) {
            try {
                journal.append(new LearnerEvent(plan.value(), LearnerEvent.KIND_HIRED_RUN,
                    dialect.name(), "{\"artifactId\":\"" + artifactId + "\",\"stepsPassed\":"
                        + passed + ",\"ofSteps\":" + steps.size() + "}"));
            } catch (RuntimeException e) {
                // the proposal is already on disk; the journal row is bookkeeping
            }
        }
        return new Proposal(plan, artifactId, record, outcome, List.copyOf(ran), passed);
    }

    /** What the manifest carries, so a reader of the artifact alone can judge the run. */
    private static Map<String, Object> provenance(PlanId plan, BrainDialect dialect,
            HiredBrain.Outcome outcome, List<GatedStep> steps, int passed) {
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
        // ABSENT rather than zero when nothing reported one — see GateOutcome and
        // HiredBrain.Outcome. A manifest reading 0.0 for a reaped run is the lie
        // the Rust reference tells.
        p.put("costUsd", outcome.costUsd().isPresent() ? outcome.costUsd().getAsDouble() : null);
        p.put("sawTerminalCost", outcome.sawTerminalCost());
        p.put("timedOut", outcome.timedOut());
        p.put("stoppedBy", outcome.stoppedBy().orElse(null));
        p.put("exitCode", outcome.exitCode().isPresent() ? outcome.exitCode().getAsInt() : null);
        p.put("applied", false);
        return p;
    }

    private static String render(String brief, BrainDialect dialect, HiredBrain.Outcome outcome,
            List<GatedStep> steps, List<GateOutcome> ran, int passed, String refusedBecause) {
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
                out.append("DID NOT ANSWER — its zero error count says nothing");
            } else {
                out.append(g.errorCount() == 0 ? "passed" : g.errorCount() + " error(s)");
            }
            out.append('\n');
        }
        return out.toString();
    }
}
