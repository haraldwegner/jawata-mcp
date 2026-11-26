package org.jawata.mcp.agent;

import java.util.List;

/**
 * What happens when a brain ends its turn (Sprint 28h, S8, D18, D19). The brain's end of turn is a message on the brain channel; the
 * agent passes it on to the host only when no step is waiting for its gate. Not passing it on is how the loop refuses to advance, and
 * it acts before the person has seen anything, which a check that only comments afterwards cannot do.
 *
 * <p>A step that is still pending is never passed over in silence. While the person has granted autonomy nobody is at the keyboard, so
 * the agent resumes the brain on that step, as many times as the {@link AttemptLedger} admits, and then reports it with the one cheap
 * thing the person can do. While no grant stands the person is at the keyboard, so the turn ends with the pending step named.</p>
 *
 * <p>It holds no step state: {@link StepLedger} answers whether a step is pending, and the attempt count is the ledger's.</p>
 */
public final class TurnBoundary {

    /** What the agent does with a brain's end of turn. */
    public enum Kind {
        /** No step is waiting: the turn ends and the host is told so. */
        FORWARD,
        /** A step is waiting and the loop may pay for another try: the brain is prompted again with {@link Verdict#text()}. */
        RESUME,
        /** A step is waiting and nobody resumes it: the turn ends and {@link Verdict#text()} tells the person which step and why. */
        REPORT
    }

    /**
     * @param kind what to do
     * @param text the prompt to resume with, or the report to show; empty when the turn is simply forwarded
     */
    public record Verdict(Kind kind, String text) {
        static final Verdict FORWARD = new Verdict(Kind.FORWARD, "");
    }

    private final StepLedger steps;
    private final AttemptLedger attempts;

    public TurnBoundary(StepLedger steps, AttemptLedger attempts) {
        this.steps = steps;
        this.attempts = attempts;
    }

    /** A boundary that never finds a step pending, for a face with no step state behind it. */
    public static TurnBoundary open() {
        return new TurnBoundary(session -> List.of(), new AttemptLedger(1));
    }

    /**
     * The person sent a message in {@code session}: the tries a stuck step of that session has used start again, because their word is
     * what the ceiling waited for. Another session's tries stay as they were.
     */
    public void personSpoke(PrincipalSession session) {
        attempts.personSpoke(session.id());
    }

    /** Where the plan stands for a session whose autonomy has just been switched on. */
    public enum Standing {
        /** A step is open, and nobody is working on it: {@link Opening#step()} is the next one. */
        STEP,
        /** A plan is known, and no step of it is open. */
        NONE_OPEN,
        /** No plan is known at all. */
        NO_PLAN,
        /** A step is open and a hire is working on it: {@link Opening#step()} is that step. */
        WORKING
    }

    /**
     * @param standing where the plan stands
     * @param step     the next open step, or the first one a hire is working on; null unless {@code standing} is {@link Standing#STEP} or
     *                 {@link Standing#WORKING}
     */
    public record Opening(Standing standing, StepLedger.Pending step) {
    }

    /**
     * The next open step of {@code session}'s plan, found the way {@link #atTurnEnd} finds a waiting one (a step whose hire is running is not
     * open), but spending no try and asking nothing of the grant: it is what a turn started by switching autonomy on is told to take.
     */
    public Opening openingOf(PrincipalSession session) {
        List<StepLedger.Pending> pending = steps.pending(session);
        if (pending.isEmpty() && !steps.planKnown(session)) {
            return new Opening(Standing.NO_PLAN, null);
        }
        return pending.stream().filter(step -> !step.inFlight()).findFirst()
            .map(step -> new Opening(Standing.STEP, step))
            .orElseGet(() -> pending.isEmpty() ? new Opening(Standing.NONE_OPEN, null) : new Opening(Standing.WORKING, pending.get(0)));
    }

    /** Decides what to do with the end of the turn a brain just made in {@code session}. */
    public Verdict atTurnEnd(PrincipalSession session) {
        // A step whose hire is running is waiting because someone is working on it, which is the normal state of a lead with hires out (Stage 24,
        // M11): the turn ends and no try is spent. Only a step nobody is working on is a step left behind.
        List<StepLedger.Pending> waiting = steps.pending(session).stream().filter(step -> !step.inFlight()).toList();
        if (waiting.isEmpty()) {
            return Verdict.FORWARD;
        }
        StepLedger.Pending step = waiting.get(0);
        String named = "step " + step.index() + " of plan " + step.planId() + " (its gate is " + step.gateTool() + ")";
        if (!session.autonomy()) {
            return new Verdict(Kind.REPORT, "The turn ended with " + named + " still waiting. Nobody resumes it while you are at the"
                + " keyboard: say so to continue it, or run " + step.gateTool() + " yourself.");
        }
        AttemptLedger.Admission admission = attempts.admit(session.id(), step.planId() + "#" + step.index());
        if (!admission.allowed()) {
            return new Verdict(Kind.REPORT, "The turn ended with " + named + " still waiting after " + admission.used() + " resumptions,"
                + " which is all the loop may spend on one step without your word. The free check comes first: run " + step.gateTool()
                + " yourself. Another resumption waits for you to say so.");
        }
        return new Verdict(Kind.RESUME, "[jawata] " + named + " has not passed. Carry on until it does, and run " + step.gateTool()
            + " to show that it has. This is resumption " + admission.used() + " of " + admission.ceiling() + ".");
    }
}
