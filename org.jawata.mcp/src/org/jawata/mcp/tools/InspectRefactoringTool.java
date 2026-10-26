package org.jawata.mcp.tools;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.lang.LoadedWorkspace;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 14b — returns the cached diff/summary of a staged change (or an
 * undo handle) WITHOUT applying anything. Non-consuming.
 */
public class InspectRefactoringTool extends WorkspaceTool<LoadedWorkspace> {

    private final RefactoringChangeCache changeCache;

    public InspectRefactoringTool(Supplier<? extends LoadedWorkspace> workspace,
                                  RefactoringChangeCache changeCache) {
        super(workspace);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "inspect_refactoring";
    }

    @Override
    public String getDescription() {
        return "Inspect a cached refactoring change without applying it. "
            + "USAGE: inspect_refactoring(changeId=\"<staged changeId or undoChangeId>\") "
            + "OUTPUT: { kind, summary, diff, diffSummary, filePaths } — kind is STAGED or "
            + "UNDO. The diff text is capped and truncated:true says so; diffSummary always "
            + "accounts for every file. Pass fullDiff:true for the whole text. "
            + "WORKFLOW: stage a refactor with auto_apply: false, inspect the diff here, "
            + "then apply_refactoring(changeId) to commit. Non-consuming: the cached "
            + "change stays available. No files are modified by this call.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of(
            "type", "object",
            "properties", Map.of(
                "changeId", Map.of(
                    "type", "string",
                    "description", "A staged changeId or an undoChangeId."
                ),
                // D7f: this door is where the full text is FETCHED from, so the escape is
                // published here even though the tool applies nothing and so takes none of
                // withAutoApply's other flags.
                "fullDiff", Map.of(
                    "type", "boolean",
                    "description", "Default false: the diff text is capped and truncated:true "
                        + "says so. Set true for the whole cached diff."
                )
            ),
            "required", List.of("changeId")
        );
    }

    @Override
    protected ToolResponse executeWithWorkspace(LoadedWorkspace workspace, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "changeId");
        if (missing != null) {
            return missing;
        }
        String changeId = getStringParam(arguments, "changeId");

        Optional<RefactoringChangeCache.Entry> entry = changeCache.peek(changeId);
        if (entry.isEmpty()) {
            return ToolResponse.invalidParameter("changeId",
                "No cached change with id '" + changeId + "'. It may have expired (1 h TTL) "
                    + "or already been consumed by refactoring(action=apply) / refactoring(action=undo).");
        }

        RefactoringChangeCache.Entry cached = entry.get();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "inspect_refactoring");
        data.put("changeId", cached.id());
        data.put("kind", cached.kind().name());
        data.put("summary", cached.summary());
        // D7f: the same cap and the same summary as an apply-shaped response. This door is
        // where the full text is fetched from, so `fullDiff:true` matters most here — the
        // cache holds the whole diff and the cap only decides what the default answer
        // carries.
        org.jawata.mcp.change.DiffView.of(cached.diff(),
            org.jawata.mcp.change.DiffView.fullRequested(arguments)).putInto(data);
        data.put("filePaths", cached.filePaths());
        return ToolResponse.success(data, ResponseMeta.builder()
            .suggestedNextTools(List.of(
                "refactoring(action=apply) to commit a STAGED change",
                "refactoring(action=undo) to execute an UNDO handle"))
            .build());
    }
}
