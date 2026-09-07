package org.jawata.mcp.refactoring;

import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.core.IJdtService;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * THE ONE WAY TO APPLY A CHANGE, gate included.
 *
 * <p>Before this existed the compile gate lived inside
 * {@code AbstractApplyingRefactoringTool.executeWithService} and had exactly one call
 * site, so which guarantees an operation received were decided by which superclass its
 * author had picked — a choice made for an unrelated reason. jawata has two refactoring
 * bases: one for an operation that prepares a single change, one for an operation that
 * drives an LTK refactoring or a recipe itself. The first was gated. The second was not.</p>
 *
 * <p>That was defensible while the second base only ever drove real LTK refactorings,
 * whose own {@code checkFinalConditions} is a safety net. Sprint 28d-rescue Stage 6 removed
 * the net and kept the base: six operations hand-assemble multi-file composite changes and
 * wrap them in {@link PreparedRefactoring}, which answers OK to both LTK condition checks
 * on purpose because the caller has already checked what it can. They were applied
 * unverified and reported {@code applied: true} — while {@code MoveFieldTool}'s own javadoc
 * said a cross-file edit that bypassed the gate "would be the one place in the product
 * where a rewrite is applied unverified". It was that place. So were five others.</p>
 *
 * <p>An architect watch at C6 found it. The bandage would have been a second
 * {@code CompileVerify} call in the other base; this is the design fix, because the gate
 * now hangs off the APPLY STEP rather than off a class hierarchy, and a new operation
 * cannot pick a base and thereby pick a weaker contract.</p>
 *
 * <h2>Where it is called, and where it deliberately is not</h2>
 *
 * <p>The heading above said "the one way to apply a change" when this class landed, and a
 * C6 audit was right that the sentence was FALSE: two apply paths still called
 * {@link ChangeEngine#perform} directly — the staged front door
 * ({@code apply_refactoring}), which made staging the way around the gate, and
 * {@link RecipeEngine}, so every composed operation applied unverified. Both were routed
 * through here rather than the claim being softened; a claim about a single way in is
 * worth nothing unless it is true, and the check for it is a reference count.</p>
 *
 * <p>{@code CopyClassTool} still calls the engine directly. That is deliberate and it is
 * not a mutation of existing code: it writes ONE new compilation unit and changes nothing
 * that anybody already compiles, so there is no before-state for introduced errors to be
 * measured against. Every path that REWRITES existing source goes through here.</p>
 */
public final class GatedApply {

    private GatedApply() {
    }

    /** What the gate does with errors the change introduced. */
    public enum Mode {
        /** Any introduced error → the change is undone and refused. The default. */
        UNDO,
        /**
         * Errors are REPORTED and the change stands — for sweeps whose output legitimately
         * lands in a workspace that was already red. A SYNTAX error is undone even here:
         * that is the operation writing something that is not Java, which no pre-existing
         * state excuses.
         */
        REPORT
    }

    /**
     * The outcome of a gated apply.
     *
     * @param outcome    the engine's own result; null when the gate refused
     * @param introduced compile errors this change added, empty when it added none
     * @param failure    why it was refused, or null when it went through
     * @param undone     on a refusal, whether the workspace was restored
     */
    public record Result(ChangeEngine.ApplyOutcome outcome, List<String> introduced,
                         String failure, boolean undone) {

        public boolean refused() {
            return failure != null;
        }
    }

    /**
     * Read the errors already present, apply, read them again, and undo when the change
     * added any.
     *
     * <p>Errors present BEFORE do not count against the change — only what it INTRODUCED —
     * because a refactoring is not responsible for a workspace it did not break.</p>
     */
    public static Result perform(Change change, IJdtService service, Mode mode) {
        Map<String, Set<String>> before = CompileVerify.errorMessagesByFile(
            service, ChangeEngine.affectedFilePaths(change, service));

        ChangeEngine.ApplyOutcome outcome = ChangeEngine.perform(change, service);
        if (outcome.validationError() != null) {
            return new Result(outcome, List.of(), outcome.validationError(), true);
        }

        // SETTLE THE MODEL FIRST — jawata-mcp#69. A change that CREATES a file leaves the
        // Java model briefly not knowing about it, and a modified file re-parsed in that
        // window resolves the new type to nothing. The gate then reports an error the change
        // did not cause and undoes correct work. Reading errors before this is reading them
        // from a model that has not caught up with the disk.
        CompileVerify.settle(service, outcome.modifiedFilePaths());
        List<String> introduced = CompileVerify.introducedErrors(before,
            CompileVerify.errorMessagesByFile(service, outcome.modifiedFilePaths()));
        boolean anySyntax = introduced.stream()
            .anyMatch(m -> m.contains(CompileVerify.SYNTAX_PREFIX));
        if (introduced.isEmpty() || (mode == Mode.REPORT && !anySyntax)) {
            return new Result(outcome, introduced, null, false);
        }

        // READ THE CREATED FILES BEFORE UNDOING THEM. This is the last moment they exist,
        // and when the introduced error is "refers to the missing type X" about a type the
        // change just created, what the reader needs is the created file's own package and
        // imports. Gathering it after the undo would be gathering it from nothing.
        String created = CompileVerify.createdFileContext(service, before,
            outcome.modifiedFilePaths());

        boolean undone = false;
        if (outcome.undoChange() != null) {
            undone = ChangeEngine.perform(outcome.undoChange(), service)
                .validationError() == null;
        }
        return new Result(outcome, introduced,
            "produced code that does not compile (" + introduced.size()
                + " new error(s), e.g. " + introduced.get(0) + "). "
                + (undone
                    ? "The change was UNDONE — no files remain modified."
                    : "UNDO FAILED — the broken change IS on disk; restore from VCS.")
                + created,
            undone);
    }
}
