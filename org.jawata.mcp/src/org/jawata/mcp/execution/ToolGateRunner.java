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
 *   <li><b>The tool threw.</b> A gate that crashed knows less than one that refused.</li>
 *   <li><b>The tool REFUSED</b> — no project loaded, an invalid parameter, a precondition unmet. It
 *       answered about itself and not about the tree, so it has no error count to give.</li>
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
        if (!tools.hasTool(step.tool())) {
            // Case 1. A misspelled gate is the quietest way to gate nothing at all.
            return didNotAnswer(step, "no tool named '" + step.tool() + "' is registered");
        }

        ToolResponse response;
        try {
            response = tools.callTool(step.tool(), step.gateArguments());
        } catch (ToolRegistry.ToolNotFoundException e) {
            // Case 1 again, arriving as an exception — which is why the check above is not the
            // only guard: the registry is mutable, and a tool present when the plan was read can
            // be gone when the gate runs.
            return didNotAnswer(step, "the tool was gone by the time the gate ran");
        } catch (RuntimeException e) {
            // Case 2. Deliberately broad: a gate's crash must not become the hire's crash, because
            // the run itself already happened and its record is still owed to a reader.
            log.log(Level.WARNING, "gate " + step.tool() + " threw", e);
            return didNotAnswer(step, "it threw " + e.getClass().getSimpleName());
        }
        if (response == null) {
            return didNotAnswer(step, "it returned nothing at all");
        }
        if (!response.isSuccess()) {
            // Case 3. It answered about ITSELF, not about the tree.
            return didNotAnswer(step, "it refused");
        }

        OptionalLong errors = ResponseFacts.errorCount(response);
        if (errors.isEmpty()) {
            // Case 4. The one that would otherwise pass silently.
            return didNotAnswer(step, "it succeeded but published no error count this runner reads");
        }
        // SATURATED rather than wrapped: a count above two billion is not a number any gate really
        // reports, and an int overflow would turn a catastrophic one into a small one or a negative.
        long count = errors.getAsLong();
        int reported = count > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) count;
        return new GateOutcome(step.tool(), step.gateArguments(), reported, true);
    }

    /**
     * A gate that did not answer — zero errors and {@code succeeded: false}, which is the pair a
     * reader must not collapse.
     *
     * <p>The reason is LOGGED and not carried, because {@link GateOutcome} has nowhere to put it:
     * it reports whether a gate answered and not why it did not. So a proposal's record can say
     * DID NOT ANSWER and cannot say which of the four it was, and the log is the only place that
     * distinction survives. Recorded as a finding rather than fixed here, since widening a shipped
     * record reaches every caller of it.</p>
     */
    private static GateOutcome didNotAnswer(GatedStep step, String why) {
        log.info(() -> "gate " + step.tool() + " did not answer: " + why);
        return new GateOutcome(step.tool(), step.gateArguments(), 0, false);
    }
}
