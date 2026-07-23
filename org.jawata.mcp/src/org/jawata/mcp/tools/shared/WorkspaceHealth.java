package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.models.ToolResponse;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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

    /** One thing wrong with one project, and what to do about it. */
    public record Problem(String projectKey, String projectPath, String problem, String remedy) {
        public Map<String, Object> describe() {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("projectKey", projectKey);
            row.put("projectPath", projectPath);
            row.put("problem", problem);
            row.put("remedy", remedy);
            return row;
        }
    }

    private WorkspaceHealth() {
    }

    /** Everything wrong with this workspace. Empty when it is healthy. */
    public static List<Problem> diagnose(IJdtService service) {
        List<Problem> problems = new ArrayList<>();
        if (service == null) {
            return problems;
        }
        for (LoadedProject lp : service.allProjects()) {
            Problem problem = diagnose(lp);
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }

    private static Problem diagnose(LoadedProject lp) {
        String key = String.valueOf(lp.projectKey());
        String path = String.valueOf(lp.projectRoot());
        try {
            IJavaProject jp = lp.javaProject();
            if (jp == null) {
                return new Problem(key, path,
                    "registered, but it has no Java project handle",
                    "Reload it: load_project(projectPath=\"" + path + "\"), or drop it: "
                        + "project(action=remove, projectKey=\"" + key + "\").");
            }
            if (!Files.isDirectory(lp.projectRoot())) {
                return new Problem(key, path,
                    "registered, but its directory NO LONGER EXISTS on disk",
                    "The project was moved or deleted. Drop it: project(action=remove, "
                        + "projectKey=\"" + key + "\") — or load_project at its new location.");
            }
            // CLOSED FIRST. JDT reports a closed project as non-existent (an IJavaProject
            // "exists" only while its IProject is open with the Java nature), so checking
            // exists() first would tell the user their project is GONE when it is merely
            // closed — sending them to look for a directory that is sitting right there.
            if (jp.getProject() == null || !jp.getProject().isOpen()) {
                return new Problem(key, path,
                    "registered, but the project is CLOSED — nothing in it can be read",
                    "Reload it: load_project(projectPath=\"" + path + "\"), or drop it: "
                        + "project(action=remove, projectKey=\"" + key + "\").");
            }
            if (!jp.exists()) {
                return new Problem(key, path,
                    "registered, but the Java project does not exist in the workspace "
                        + "(the directory is there, but it is not a usable Java project — a "
                        + "missing Java nature, or a broken .classpath)",
                    "Reload it: load_project(projectPath=\"" + path + "\").");
            }
            // It is open and present. Can we actually walk it? This is the check that catches
            // a broken classpath — the state in which everything "works" and finds nothing.
            jp.getPackageFragmentRoots();
            // jawata-mcp#4 (Sprint 27a Stage 8): a project can pass every check
            // above and still be UNBUILDABLE — an unresolved build path leaves
            // the project open and walkable while the Java builder REFUSES to
            // run, so get_diagnostics answers 0 errors ("compiles clean") when
            // the truth is "was never compiled". JDT records exactly this state
            // as BUILDPATH_PROBLEM markers; a gate that could not look must say
            // so, never answer green.
            org.eclipse.core.resources.IMarker[] buildPath = jp.getProject().findMarkers(
                org.eclipse.jdt.core.IJavaModelMarker.BUILDPATH_PROBLEM_MARKER, true,
                org.eclipse.core.resources.IResource.DEPTH_INFINITE);
            for (org.eclipse.core.resources.IMarker m : buildPath) {
                if (m.getAttribute(org.eclipse.core.resources.IMarker.SEVERITY, -1)
                        == org.eclipse.core.resources.IMarker.SEVERITY_ERROR) {
                    return new Problem(key, path,
                        "its BUILD PATH is broken: " + m.getAttribute(
                            org.eclipse.core.resources.IMarker.MESSAGE,
                            "unresolved build path entry")
                        + " — the Java builder refuses to run, so a 0-error compile"
                        + " gate on this project is VACUOUS (nothing was compiled)",
                        "Fix the build path entry the message names, then "
                            + "refresh_workspace(projectKey=\"" + key + "\").");
                }
            }
            return null;
        } catch (Exception e) {
            return new Problem(key, path,
                "present, but it cannot be read: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage(),
                "Its classpath is probably broken. Run compile_workspace(projectKey=\"" + key
                    + "\") to see the errors, fix the build, then refresh_workspace.");
        }
    }

    /**
     * Refuse a reference-rewriting refactoring while any project cannot be read.
     *
     * @param what the refactoring, in the tool's own words (e.g. "rename")
     * @return the refusal, fully qualified, or empty when the workspace is sound
     */
    public static Optional<ToolResponse> refuseIfUnhealthy(IJdtService service, String what) {
        List<Problem> problems = diagnose(service);
        if (problems.isEmpty()) {
            return Optional.empty();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        for (Problem p : problems) {
            rows.add(p.describe());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unhealthyProjects", rows);
        data.put("count", rows.size());
        data.put("refused", what);

        StringBuilder detail = new StringBuilder();
        for (Problem p : problems) {
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
