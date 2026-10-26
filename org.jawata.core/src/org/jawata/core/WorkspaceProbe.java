package org.jawata.core;

import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.IJavaProject;
import org.jawata.spi.lang.LoadedWorkspace.ProblemInfo;

/**
 * Is this Java workspace fit to be analysed, and if not, exactly what is wrong with each project (moved here from the tools' health
 * guard, Sprint 28k Stage 9, so the Java service can answer {@code LoadedWorkspace.problems} itself).
 *
 * <p>A refactoring rewrites references and finds them through the Java model; a project the model cannot read has, as far as the
 * refactoring is concerned, no references in it at all. Every problem here therefore names the project, says what is wrong with it and
 * gives the remedy, so it is fixed at a glance and not investigated.</p>
 */
public final class WorkspaceProbe {

    private WorkspaceProbe() {
    }

    /**
     * @param service the Java workspace, or null when none is loaded
     * @return every project in it that cannot be read; empty when all can, or when there is no workspace
     */
    public static List<ProblemInfo> problemsIn(IJdtService service) {
        List<ProblemInfo> problems = new ArrayList<>();
        if (service == null) {
            return problems;
        }
        for (LoadedProject lp : service.allProjects()) {
            ProblemInfo problem = problemOf(lp);
            if (problem != null) {
                problems.add(problem);
            }
        }
        return problems;
    }

    private static ProblemInfo problemOf(LoadedProject lp) {
        String key = String.valueOf(lp.projectKey());
        String path = String.valueOf(lp.projectRoot());
        try {
            IJavaProject jp = lp.javaProject();
            if (jp == null) {
                return new ProblemInfo(key, path,
                    "registered, but it has no Java project handle",
                    "Reload it: load_project(projectPath=\"" + path + "\"), or drop it: "
                        + "project(action=remove, projectKey=\"" + key + "\").");
            }
            if (!Files.isDirectory(lp.projectRoot())) {
                return new ProblemInfo(key, path,
                    "registered, but its directory NO LONGER EXISTS on disk",
                    "The project was moved or deleted. Drop it: project(action=remove, "
                        + "projectKey=\"" + key + "\") — or load_project at its new location.");
            }
            // CLOSED FIRST. JDT reports a closed project as non-existent (an IJavaProject
            // "exists" only while its IProject is open with the Java nature), so checking
            // exists() first would tell the user their project is GONE when it is merely
            // closed — sending them to look for a directory that is sitting right there.
            if (jp.getProject() == null || !jp.getProject().isOpen()) {
                return new ProblemInfo(key, path,
                    "registered, but the project is CLOSED — nothing in it can be read",
                    "Reload it: load_project(projectPath=\"" + path + "\"), or drop it: "
                        + "project(action=remove, projectKey=\"" + key + "\").");
            }
            if (!jp.exists()) {
                return new ProblemInfo(key, path,
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
                    return new ProblemInfo(key, path,
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
            return new ProblemInfo(key, path,
                "present, but it cannot be read: " + e.getClass().getSimpleName() + ": "
                    + e.getMessage(),
                "Its classpath is probably broken. Run compile_workspace(projectKey=\"" + key
                    + "\") to see the errors, fix the build, then refresh_workspace.");
        }
    }
}
