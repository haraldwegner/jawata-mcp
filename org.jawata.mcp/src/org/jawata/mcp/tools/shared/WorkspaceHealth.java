package org.jawata.mcp.tools.shared;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.lang.LoadedWorkspace;

/**
 * Is this workspace fit to be analysed — and, if not, exactly what is wrong with it.
 *
 * <p><b>Why this is a guard and not a warning.</b> A refactoring rewrites references, and it
 * finds those references through the Java model. A project the model cannot read has, as far
 * as the refactoring is concerned, <b>no references in it at all</b>. So a
 * {@code rename_symbol} across a workspace with one unreadable project renames what it can
 * see, reports success — and leaves every call site in the unreadable project pointing at a
 * name that no longer exists. The code is broken and the tool said it worked.</p>
 *
 * <p>That is not a reporting problem, it is a correctness problem, and no amount of warning
 * text fixes it: a warning is something an agent may route around. So the reference-rewriting
 * tools REFUSE while the workspace is unhealthy, with no override — an override is simply the
 * mechanism by which the guard gets bypassed.</p>
 *
 * <p><b>And the refusal is qualified.</b> A bare error code tells you that something is wrong
 * and leaves you to find out what. Every refusal here names the project, says what is wrong
 * with it, and gives the remedy — so it is fixed at a glance, not investigated.</p>
 */
public final class WorkspaceHealth {

    private WorkspaceHealth() {
    }

    /**
     * Refuse a reference-rewriting refactoring while any project cannot be read.
     *
     * @param workspace the language's loaded workspace, or null when none is loaded
     * @param what      the refactoring, in the tool's own words (e.g. "rename")
     * @return the refusal, fully qualified, or empty when the workspace is sound
     */
    public static Optional<ToolResponse> refuseIfUnhealthy(LoadedWorkspace workspace, String what) {
        List<LoadedWorkspace.ProblemInfo> problems = workspace == null ? List.of() : workspace.problems();
        if (problems.isEmpty()) {
            return Optional.empty();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (LoadedWorkspace.ProblemInfo p : problems) {
            rows.add(p.describe());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unhealthyProjects", rows);
        data.put("count", rows.size());
        data.put("refused", what);

        StringBuilder detail = new StringBuilder();
        for (LoadedWorkspace.ProblemInfo p : problems) {
            detail.append("\n  • ").append(p.projectKey()).append(" (").append(p.projectPath())
                .append(")\n      problem: ").append(p.problem())
                .append("\n      fix:     ").append(p.remedy());
        }

        return Optional.of(ToolResponse.error("WORKSPACE_UNHEALTHY",
            "REFUSING to " + what + ": " + problems.size() + " project(s) in this workspace "
                + "cannot be read." + detail
                + "\n\nWHY THIS IS REFUSED RATHER THAN WARNED ABOUT: a refactoring rewrites "
                + "references, and it finds them through the Java model. A project the model "
                + "cannot read contains, as far as this refactoring is concerned, NO references "
                + "at all. It would rename what it can see, report success, and leave every "
                + "call site in the unreadable project pointing at a name that no longer "
                + "exists — broken code, reported as a clean refactor.",
            "Fix the project(s) above (each line says how), then retry. This is a workspace "
                + "fault and it belongs to the person who owns the workspace — an agent must "
                + "NOT work around it, must not fall back to grep, and must not proceed as "
                + "though the unreadable project did not exist. Read-only analysis still works; "
                + "it will just tell you what it could not examine.",
            data));
    }
}
