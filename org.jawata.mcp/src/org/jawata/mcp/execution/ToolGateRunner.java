package org.jawata.mcp.execution;

import java.util.OptionalLong;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.jawata.mcp.learn.GateOutcome;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.ResponseFacts;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ToolRegistry;

/**
 * Runs a hired plan's declared gate by calling the real jawata tool it names (Sprint 28g S16).
 *
 * <p>{@link HiredRun} deliberately leaves this to its caller — it decides WHEN a gate runs and not
 * what a gate IS. Until now every implementation was a test double, so the hire path could be
 * driven and could not actually gate anything. This is the production one.</p>
 *
 * <h2>FOUR WAYS A GATE CAN FAIL TO ANSWER, and all four report as not having answered</h2>
 *
 * <p>This is the whole content of the class, and it is why it is not three lines. A gate that did
 * not answer reports ZERO ERRORS exactly as a clean one does, so reading that zero as a pass
 * certifies a step nothing checked and lets the ledger advance a plan on it. The four:</p>
 *
 * <ol>
 *   <li><b>The step names a tool that does not exist.</b> A plan declaring {@code compile_wrokspace}
 *       must not pass; it has been gated by nothing.</li>
 *   <li><b>The tool threw</b> — and it does NOT arrive here as an exception, which is worth saying
 *       because this class first claimed it did. Measured by a mutation that stayed green:
 *       {@link ToolRegistry#callTool} catches {@code Exception} AND {@code Error} around its own
 *       dispatch, taps the failure, and returns {@code internalError}. So a crashing gate reaches
 *       this runner as case 3, a refusal — and the {@code RuntimeException} catch below guards only
 *       the registry's own machinery before that dispatch, which nothing in the suite can reach.</li>
 *   <li><b>The tool REFUSED</b> — no project loaded, an invalid parameter, a precondition unmet, or
 *       a crash converted by the line above. It answered about ITSELF and not about the tree, so it
 *       has no error count to give. <b>And it is not enough that it usually publishes none:</b> the
 *       four-argument {@code ToolResponse.error} sets the response's own data, so a refusal carrying
 *       a diagnosis map can publish {@code errorCount: 0} — which case 4 would then read as a clean
 *       gate. That shape is what makes this branch a claim of its own rather than a duplicate.</li>
 *   <li><b>The tool succeeded in a shape this runner cannot read.</b> Measured: the error count
 *       lives under {@code errorCount} for the compile, diagnostics, refresh and syntax tools and
 *       under {@code failed} for the test runner. A response carrying neither is a response nothing
 *       here can speak for, and {@link ResponseFacts#errorCount} reports that as ABSENT precisely so
 *       this line can refuse rather than read it as zero.</li>
 * </ol>
 *
 * <p>Only the fifth case — a tool that ran, succeeded, and published a count — answers, and the
 * count is the gate's verdict. Whether that verdict passes the plan is the LEDGER's call, not
 * this class's: it reports what the gate said.</p>
 *
 * <h2>Why it calls through the registry rather than the tool object</h2>
 *
 * <p>{@link ToolRegistry#callTool} is the same path a client's request takes, so a gate run is
 * recorded, steered and tapped exactly as the equivalent hand-call would be. Reaching past it to
 * {@code Tool#execute} would make an unattended run the one caller whose gates leave no trace,
 * which is the opposite of what a hired run is for.</p>
 */
public final class ToolGateRunner implements HiredRun.GateRunner {

    private static final Logger log = Logger.getLogger(ToolGateRunner.class.getName());

    private final ToolRegistry tools;

    public ToolGateRunner(ToolRegistry tools) {
        if (tools == null) {
            throw new IllegalArgumentException("a gate runner with no tool registry can run no"
                + " gate, and would report every step as unanswered — which is honest and useless");
        }
        this.tools = tools;
    }

    @Override
    public GateOutcome run(GatedStep step) {
        if (step == null) {
            throw new IllegalArgumentException("a gate outcome must say which step it is about");
        }
        ToolResponse response;
        try {
            response = tools.callTool(step.tool(), step.gateArguments());
        } catch (ToolRegistry.ToolNotFoundException e) {
            // Case 1, and this is the ONLY place it can be caught — a misspelled gate is the
            // quietest way to gate nothing at all, and so is a tool that was present when the
            // plan was read and gone when the gate ran.
            //
            // A `tools.hasTool(...)` pre-check stood here and was DELETED (and `hasTool` with it,
            // S16-9, once nothing but its own tests called it), because a mutation
            // disabling it changed nothing any test could see. Measured rather than reasoned:
            // `hasTool` is `containsKey` on the same map `callTool` does `get` on, so the two
            // cannot disagree; and the null check is `callTool`'s FIRST statement, before the
            // log, the disk sync, the precedent charge and the tap, so a pre-check saves no
            // work and suppresses no recording either. It was one fact expressed twice.
            return didNotAnswer(step, "no tool named '" + step.tool() + "' answered the gate");
        } catch (RuntimeException e) {
            // Case 2, and it is a BACKSTOP rather than the handler a reader would assume.
            //
            // A mutation that made this rethrow stayed GREEN, which measured what it actually
            // guards: `callTool` catches Exception and Error around its own dispatch and returns
            // `internalError`, so a tool that throws arrives below as a refusal and never here.
            // What is left unprotected is the registry's own machinery BEFORE that dispatch — the
            // precedent charge, the acceptance check, the in-flight ticket — and no test in this
            // suite can reach it.
            //
            // It stays because the costs are not symmetric: an unreachable branch costs a reader
            // one paragraph, while letting a RuntimeException out of here loses the record of a
            // run that already happened, which is the one thing a hired run must not do.
            log.log(Level.WARNING, "gate " + step.tool() + " threw", e);
            return didNotAnswer(step, "it threw " + e.getClass().getSimpleName());
        }
        if (response == null) {
            return didNotAnswer(step, "it returned nothing at all");
        }
        if (!response.isSuccess()) {
            // Case 3. It answered about ITSELF, not about the tree — and this is where a crashing
            // tool lands too, as `internalError`. Load-bearing even when the refusal publishes a
            // count, which the four-argument `ToolResponse.error` allows: `ResponseFacts` reads a
            // response's data without asking whether the call succeeded, correctly, since it
            // reports what was published and leaves the judgement to the caller. This line IS
            // that judgement for a gate.
            return didNotAnswer(step, "it refused" + (response.getError() == null ? ""
                : ": " + response.getError().getCode() + " — " + response.getError().getMessage()));
        }

        OptionalLong errors = ResponseFacts.errorCount(response);
        if (errors.isEmpty()) {
            // Case 4. The one that would otherwise pass silently.
            return didNotAnswer(step, "it succeeded but published no error count this runner reads");
        }
        // Passed through as a long, which is what GateOutcome stores. An earlier version saturated
        // it to Integer.MAX_VALUE on the belief the record held an int; reading the record is what
        // corrected that. Narrowing a count nothing asked to be narrowed would have invented a
        // ceiling and lost information above it, for no reader's benefit.
        return new GateOutcome(step.tool(), step.gateArguments(), errors.getAsLong(), true);
    }

    /**
     * A gate that did not answer — zero errors and {@code succeeded: false}, which is the pair a
     * reader must not collapse — carrying WHICH of the four reasons it was (S16-10).
     *
     * <p>Until {@link GateOutcome#notAnsweredBecause()} existed the reason was only logged, so a
     * proposal's record could say DID NOT ANSWER and never which, and the log was the one place the
     * distinction survived.</p>
     */
    private static GateOutcome didNotAnswer(GatedStep step, String why) {
        log.info(() -> "gate " + step.tool() + " did not answer: " + why);
        return new GateOutcome(step.tool(), step.gateArguments(), 0, false, why);
    }
}
