package org.jawata.mcp.models;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Sprint 28a (D11) — <b>a wrong-workspace question answers itself</b>. A machine
 * with several jawata servers (one per workspace) gives the agent no way to tell
 * which serves what: every server used to introduce itself with the same static
 * instructions, and a symbol that lives in the OTHER workspace came back as a
 * bare empty result — observed live 2026-08-11, and again 2026-08-16 when a
 * search against the wrong workspace returned {@code results: []} with nothing
 * naming the place to look.
 *
 * <p>This holder carries the one fact that disambiguates: WHICH workspace this
 * server serves, and its projects. Installed once at boot from
 * {@code workspace.json} (before the message loop starts, so the initialize
 * handshake never races the async project load), preferring the LIVE loaded
 * project keys once they exist. Three surfaces read it: the initialize
 * {@code instructions} (the agent sees the roster before choosing a server),
 * every {@code SYMBOL_NOT_FOUND} hint, and the empty-search steering line.</p>
 */
public final class WorkspaceIdentity {

    private static final int MAX_NAMED_PROJECTS = 12;

    private static volatile String workspaceName;
    private static volatile List<String> configuredProjects = List.of();
    private static volatile Supplier<List<String>> liveProjectKeys;

    private WorkspaceIdentity() {}

    /**
     * Install the boot-time identity: the manager-written workspace name (may be
     * null on manual launches) and the configured project roots. Call before the
     * message loop starts; safe to call at most once per process in production.
     */
    public static void install(String name, List<Path> projectRoots) {
        workspaceName = name == null || name.isBlank() ? null : name;
        configuredProjects = projectRoots == null ? List.of()
            : projectRoots.stream()
                .map(p -> p.getFileName() == null ? p.toString() : p.getFileName().toString())
                .toList();
    }

    /**
     * The live loaded-project keys, preferred over the boot list once non-empty —
     * they reflect later {@code load_project}/{@code project(action=add|remove)}
     * calls the boot file never sees. The supplier may return an empty list while
     * the async load is still running; the boot list covers that window.
     */
    public static void installLiveKeys(Supplier<List<String>> supplier) {
        liveProjectKeys = supplier;
    }

    /** Test hook — a static holder that cannot be cleared poisons every later test. */
    static void reset() {
        workspaceName = null;
        configuredProjects = List.of();
        liveProjectKeys = null;
    }

    /** True once {@link #install} gave this server something to say about itself. */
    public static boolean installed() {
        return workspaceName != null || !configuredProjects.isEmpty();
    }

    /**
     * The self-introduction appended to the initialize instructions, or null when
     * nothing was installed (plain-classpath tests, bare manual launches).
     */
    public static String describe() {
        if (!installed()) {
            return null;
        }
        return "THIS SERVER'S WORKSPACE" + (workspaceName == null ? "" : " ('" + workspaceName + "')")
            + ": " + projectSummary()
            + ". A machine can run several jawata servers, one per workspace — pick the one"
            + " whose projects match your question; the others cannot see this code.";
    }

    /**
     * The one-line redirect for a symbol this workspace does not contain — appended
     * to SYMBOL_NOT_FOUND hints and empty-search steering. Null when not installed.
     */
    public static String elsewhereHint() {
        if (!installed()) {
            return null;
        }
        return "This is the" + (workspaceName == null ? "" : " '" + workspaceName + "'")
            + " workspace (" + projectSummary() + ") — a symbol that lives in another"
            + " project tree is served by that tree's own jawata server, not this one.";
    }

    private static String projectSummary() {
        List<String> live = null;
        Supplier<List<String>> supplier = liveProjectKeys;
        if (supplier != null) {
            try {
                live = supplier.get();
            } catch (Exception e) {
                live = null; // a broken supplier must never break an error response
            }
        }
        List<String> names = live != null && !live.isEmpty() ? live : configuredProjects;
        if (names.isEmpty()) {
            return "no projects loaded yet";
        }
        String shown = names.stream().limit(MAX_NAMED_PROJECTS)
            .collect(java.util.stream.Collectors.joining(", "));
        int more = names.size() - MAX_NAMED_PROJECTS;
        return names.size() + " project(s): " + shown + (more > 0 ? " … and " + more + " more" : "");
    }
}
