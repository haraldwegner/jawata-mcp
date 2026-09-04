package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 14b — commits a Change previously staged with
 * {@code auto_apply: false}. One-shot: the staged entry is consumed.
 */
public class ApplyRefactoringTool extends AbstractTool {

    private final RefactoringChangeCache changeCache;

    public ApplyRefactoringTool(Supplier<IJdtService> serviceSupplier,
                                RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "apply_refactoring";
    }

    @Override
    public String getDescription() {
        return "Commit a refactoring Change that was staged with auto_apply: false. "
            + "USAGE: apply_refactoring(changeId=\"<uuid from the staging call>\") "
            + "OUTPUT: { filesModified, undoChangeId, summary } — files are written to disk. "
            + "WORKFLOW: 1. Call a refactor tool with auto_apply: false to stage and get a changeId "
            + "2. Optionally inspect_refactoring(changeId) to review the diff "
            + "3. apply_refactoring(changeId) to commit "
            + "4. compile_workspace to verify; undo_refactoring(undoChangeId) to revert if broken. "
            + "Staged changes expire after 1 hour and are consumed by this call (one-shot).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "changeId", Map.of(
                    "type", "string",
                    "description", "Id returned by a refactor tool called with auto_apply: false."
                )
            ),
            "required", List.of("changeId")
        );
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "changeId");
        if (missing != null) {
            return missing;
        }
        String changeId = getStringParam(arguments, "changeId");

        Optional<RefactoringChangeCache.Entry> entry =
            changeCache.take(changeId, RefactoringChangeCache.Kind.STAGED);
        if (entry.isEmpty()) {
            return ToolResponse.invalidParameter("changeId",
                "No staged change with id '" + changeId + "'. It may have expired (1 h TTL), "
                    + "already been applied, or the id belongs to an undo handle. "
                    + "Re-run the originating refactor tool with auto_apply: false to re-stage.");
        }

        // THROUGH THE GATE. This was a bare ChangeEngine.perform, which made the staged
        // path the way around the compile verification the direct path performs — stage
        // with auto_apply:false, commit here, and nothing checked the result. A C6 audit
        // found it one round after the gate was added to both refactoring bases, and it
        // was right that the gate's own "one way to apply a change" javadoc was false
        // while this stood. The operations that hand-assemble composite changes are the
        // ones it mattered most for: they wrap them in PreparedRefactoring, which answers
        // OK to both LTK condition checks on purpose.
        org.jawata.mcp.refactoring.GatedApply.Result gated =
            org.jawata.mcp.refactoring.GatedApply.perform(entry.get().change(), service,
                org.jawata.mcp.refactoring.GatedApply.Mode.UNDO);
        ChangeEngine.ApplyOutcome outcome = gated.outcome();
        if (outcome.validationError() != null) {
            return ToolResponse.error(
                "REFACTORING_FAILED",
                "apply_refactoring failed: " + outcome.validationError(),
                "No files were modified. The staged change has been consumed — re-run the "
                    + "originating refactor tool to rebuild it.");
        }
        if (gated.refused()) {
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("introducedErrors", gated.introduced());
            detail.put("undone", gated.undone());
            return ToolResponse.error(
                "REFACTORING_BROKE_COMPILE",
                "apply_refactoring " + gated.failure(),
                "The staged change is wrong for this code shape. It has been consumed; do "
                    + "not re-stage the identical call. The workspace was "
                    + (gated.undone() ? "left as it was." : "NOT restored — check git status."),
                detail);
        }

        String undoChangeId = null;
        if (outcome.undoChange() != null) {
            undoChangeId = changeCache.put(
                RefactoringChangeCache.Kind.UNDO, outcome.undoChange(),
                "undo: " + entry.get().summary(), "", outcome.modifiedFilePaths());
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "apply_refactoring");
        data.put("filesModified", outcome.modifiedFilePaths());
        data.put("undoChangeId", undoChangeId);
        data.put("summary", entry.get().summary());
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(outcome.modifiedFilePaths().size())
            .returnedCount(outcome.modifiedFilePaths().size())
            .suggestedNextTools(List.of(
                "compile_workspace to verify the refactoring",
                "undo_refactoring with the undoChangeId if verification fails"))
            .build());
    }
}
