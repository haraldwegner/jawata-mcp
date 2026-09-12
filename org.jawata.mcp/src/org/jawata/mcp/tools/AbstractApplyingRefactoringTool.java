package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 14b — base class implementing the refactoring-tool contract
 * (see {@code docs/sprints/sprint-14b-refactoring-full-apply.md}):
 *
 * <ol>
 *   <li>Subclass builds a JDT {@link Change} in {@link #prepareChange}.</li>
 *   <li>Default ({@code auto_apply: true}): the change is performed, its
 *       undo-Change cached, and the response carries
 *       {@code { filesModified, diff, undoChangeId, summary }}.</li>
 *   <li>{@code auto_apply: false}: the un-performed change is cached and the
 *       response carries {@code { changeId, diff, summary }} for a later
 *       {@code apply_refactoring(changeId)}.</li>
 * </ol>
 *
 * <p>New refactoring tools MUST extend this class (or implement the same
 * contract) — returning raw text edits for hand-application is the pattern
 * this sprint eliminates. PR-review gate.</p>
 */
public abstract class AbstractApplyingRefactoringTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(AbstractApplyingRefactoringTool.class);

    protected final RefactoringChangeCache changeCache;

    protected AbstractApplyingRefactoringTool(Supplier<IJdtService> serviceSupplier,
                                              RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    /**
     * Either a built change ready for the apply pipeline, or an error
     * response the tool returns verbatim (invalid params, symbol not found…).
     */
    protected static final class Preparation {
        final Change change;
        final String summary;
        final ToolResponse error;
        final Map<String, Object> extras;
        final AfterApply afterApply;

        private Preparation(Change change, String summary, ToolResponse error,
                            Map<String, Object> extras, AfterApply afterApply) {
            this.change = change;
            this.summary = summary;
            this.error = error;
            this.extras = extras;
            this.afterApply = afterApply;
        }

        public static Preparation of(Change change, String summary) {
            return new Preparation(change, summary, null, Map.of(), null);
        }

        /**
         * Variant with tool-specific context fields (e.g. oldName/newName/
         * symbolKind) merged into the response alongside the uniform
         * contract keys — extras never overwrite contract keys.
         */
        public static Preparation of(Change change, String summary, Map<String, Object> extras) {
            return new Preparation(change, summary, null,
                extras == null ? Map.of() : extras, null);
        }

        /**
         * Variant carrying work that must happen ONLY IF the change actually lands.
         *
         * @see AfterApply
         */
        public static Preparation of(Change change, String summary, Map<String, Object> extras,
                                     AfterApply afterApply) {
            return new Preparation(change, summary, null,
                extras == null ? Map.of() : extras, afterApply);
        }

        public static Preparation fail(ToolResponse error) {
            return new Preparation(null, null, error, Map.of(), null);
        }
    }

    /**
     * Work a tool wants done AFTER its change has really landed — Sprint 28f Stage 7.
     *
     * <p>Some consequences of a refactoring live outside the code: renaming a member moves
     * the knowledge rows anchored at it. Such a consequence must follow the change and not
     * merely the intention, so it belongs after the compile gate, the parity check and the
     * undo — a rename this pipeline ROLLED BACK must leave every anchor where it was, and
     * the only place that ordering is known is here.</p>
     *
     * <h2>Why the tool supplies it rather than the base class performing it</h2>
     *
     * <p>The obvious design was to give this base class the knowledge store and have it do
     * the following itself. It was rejected on measurement: the constructor would reach
     * every applying tool — 27-plus subtypes — to serve the two that rename anything, and
     * the alternative of a statically registered follower buys that back with global state.
     * <b>The split that avoids both is WHEN versus WHAT.</b> This class knows when a change
     * has truly landed and nothing else; the tool knows what follows from its own
     * refactoring and holds whatever it needs to do it.</p>
     *
     * <p><b>And it makes the failure this sprint keeps meeting impossible by construction.</b>
     * A declaration that something should follow, on one side, and the capability to follow
     * it, on the other, is exactly the shape where the two come apart silently — the
     * allowlist naming eight doors while a ninth existed, {@code KnowledgeLane.CODE} with no
     * branch reaching it. Here the object that DECLARES the follow-up is the object that
     * PERFORMS it, so there is no gap for them to come apart in.</p>
     *
     * <p>The returned map is merged into the response the way {@code extras} is, so what
     * followed is reported rather than done invisibly. Implementations own their own error
     * wording: a failure here must not fail a refactoring that already succeeded, and it
     * must not vanish either.</p>
     */
    @FunctionalInterface
    protected interface AfterApply {
        Map<String, Object> run();
    }

    /**
     * Build the change for this tool's arguments. Implementations validate
     * inputs and return {@link Preparation#fail} for anything that should
     * short-circuit; they never perform the change themselves.
     */
    protected abstract Preparation prepareChange(IJdtService service, JsonNode arguments)
        throws Exception;

    /** How the compile-verify gate treats errors this tool's change introduced. */
    protected enum GateMode {
        /** Any introduced error → the change is undone and refused (the default). */
        STRICT,
        /**
         * Introduced TYPE errors are kept but reported loudly
         * ({@code compileVerified: false} + the error list) — for tools whose
         * contract legitimately leaves follow-up work (a signature change leaves
         * bodies and call sites to adapt). SYNTAX errors are never legitimate
         * output and are undone+refused even here.
         */
        REPORT
    }

    /** Default STRICT; a tool whose semantics include follow-up edits overrides. */
    protected GateMode compileGateMode() {
        return GateMode.STRICT;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // THE GUARD. A refactoring rewrites references, and it finds them through the Java
        // model. A project the model cannot read contains, as far as this refactoring is
        // concerned, NO references at all — so it would rewrite what it can see, report
        // success, and leave every call site in the unreadable project pointing at a name
        // that no longer exists. Broken code, reported as a clean refactor.
        //
        // That is a correctness fault, not a reporting one, so it is REFUSED and not merely
        // warned about — a warning is a thing an agent routes around. There is deliberately
        // no override flag: an override is simply the mechanism by which a guard gets
        // bypassed. Read-only analysis still works; it just says what it could not examine.
        java.util.Optional<ToolResponse> unhealthy =
            org.jawata.mcp.tools.shared.WorkspaceHealth.refuseIfUnhealthy(service, getName());
        if (unhealthy.isPresent()) {
            return unhealthy.get();
        }

        // Sprint 24 (D1): a caller who KNOWS the symbol addresses it by name —
        // resolve that into the position the prepareChange paths already expect.
        // Idempotent: an explicit position, or an already-materialized one, wins.
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        boolean autoApply = getBooleanParam(arguments, "auto_apply", true);
        Preparation preparation;
        try {
            preparation = prepareChange(service, arguments);
        } catch (Exception e) {
            log.warn("{} prepareChange threw: {}", getName(), e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
        if (preparation.error != null) {
            return preparation.error;
        }

        Change change = preparation.change;
        String summary = preparation.summary;
        String diff = ChangeEngine.previewDiff(change, service);

        if (!autoApply) {
            List<String> files = ChangeEngine.affectedFilePaths(change, service);
            String changeId = changeCache.put(
                RefactoringChangeCache.Kind.STAGED, change, summary, diff, files);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", getName());
            data.put("applied", false);
            data.put("changeId", changeId);
            data.put("diff", diff);
            data.put("summary", summary);
            preparation.extras.forEach(data::putIfAbsent);
            return ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "apply_refactoring with this changeId to commit the staged change",
                    "inspect_refactoring with this changeId to re-examine the diff"))
                .build());
        }

        // THE COMPILE-VERIFY GATE (v2.12.1). A refactoring that modified the code can
        // check the code — advising the caller to run compile_workspace afterwards is
        // how broken code gets left behind wearing "applied: true" (it happened, live,
        // on this codebase: an extract-method wrote a dangling reference and reported
        // success). Errors present BEFORE the change don't count against it; only
        // messages the change INTRODUCED do — and then the change is UNDONE, not left
        // for the caller to clean up.
        // Since C6 this is ONE shared step rather than a block that lived only here. It
        // had exactly one call site, so which guarantees an operation got were decided by
        // which base its author extended — and Stage 6 put six multi-file operations on
        // the other one. See GatedApply.
        org.jawata.mcp.refactoring.GatedApply.Result gated =
            org.jawata.mcp.refactoring.GatedApply.perform(change, service,
                compileGateMode() == GateMode.REPORT
                    ? org.jawata.mcp.refactoring.GatedApply.Mode.REPORT
                    : org.jawata.mcp.refactoring.GatedApply.Mode.UNDO);
        ChangeEngine.ApplyOutcome outcome = gated.outcome();
        if (outcome.validationError() != null) {
            return ToolResponse.error(
                "REFACTORING_FAILED",
                getName() + " failed: " + outcome.validationError(),
                "No files were modified. Adjust the input or fix the workspace state and retry.");
        }
        List<String> introduced = gated.introduced();
        if (gated.refused()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("introducedErrors", introduced);
            detail.put("undone", gated.undone());
            detail.put("diff", diff);
            return ToolResponse.error(
                "REFACTORING_BROKE_COMPILE",
                getName() + " " + gated.failure(),
                "The refactoring's transformation is wrong for this code shape. Do not retry "
                    + "the identical call; report it. The workspace was "
                    + (gated.undone() ? "left as it was." : "NOT restored — check git status."),
                detail);
        }

        String undoChangeId = null;
        if (outcome.undoChange() != null) {
            undoChangeId = changeCache.put(
                RefactoringChangeCache.Kind.UNDO, outcome.undoChange(),
                "undo: " + summary, "", outcome.modifiedFilePaths());
        }

        // THE CHANGE HAS LANDED — and only here is that true. The compile gate ran, the
        // parity check passed, a refused change was undone above and returned, and the undo
        // handle is cached. Anything that must follow the change rather than the intention
        // goes here and nowhere earlier: the staged path returns before this, correctly,
        // because nothing has been applied there yet. See AfterApply.
        Map<String, Object> followed = Map.of();
        if (preparation.afterApply != null) {
            try {
                Map<String, Object> ran = preparation.afterApply.run();
                followed = ran == null ? Map.of() : ran;
            } catch (RuntimeException e) {
                // A failure here must not fail a refactoring that already succeeded — and
                // must not vanish either, which is this codebase's recorded top-bug class.
                // The tool owns its own wording; this is the last resort for a throw it did
                // not expect, and it SAYS so in the response rather than in a log nobody
                // reads.
                log.warn("{}: after-apply work threw: {}", getName(), e.getMessage(), e);
                followed = Map.of("afterApplyFailed", String.valueOf(e.getMessage()));
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", getName());
        data.put("applied", true);
        data.put("filesModified", outcome.modifiedFilePaths());
        data.put("compileVerified", introduced.isEmpty());
        if (!introduced.isEmpty()) {
            // REPORT mode: the change stands, but the follow-up work it created is
            // named IN THE RESPONSE — never left for the caller to discover.
            data.put("introducedErrors", introduced);
        }
        data.put("diff", diff);
        data.put("undoChangeId", undoChangeId);
        data.put("summary", summary);
        followed.forEach(data::putIfAbsent);
        preparation.extras.forEach(data::putIfAbsent);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(outcome.modifiedFilePaths().size())
            .returnedCount(outcome.modifiedFilePaths().size())
            .steering(introduced.isEmpty() ? null
                : "APPLIED, but the change leaves " + introduced.size() + " compiler error(s) "
                    + "to fix (see introducedErrors) — that is this refactoring's contract "
                    + "(bodies/call sites adapt AFTER a signature change). Fix them NOW or "
                    + "undo_refactoring; do not leave the workspace red.")
            .suggestedNextTools(List.of(
                "compile_workspace to verify the whole workspace (this gate checked the "
                    + "modified files only)",
                "undo_refactoring with the undoChangeId if verification fails"))
            .build());
    }

    /**
     * MECHANICAL, always.
     *
     * <p>The same declaration as AbstractRefactoringTool, for the same reason: what makes
     * these mechanical is the base they extend, not their names.</p>
     */
    @Override
    public boolean isMechanical() {
        return true;
    }

}
