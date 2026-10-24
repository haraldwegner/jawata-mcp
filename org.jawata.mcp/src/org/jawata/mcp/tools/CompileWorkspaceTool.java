package org.jawata.mcp.tools;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.java.JavaBuildVerdict;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 12 (v1.6.0) — {@code compile_workspace}: trigger an incremental
 * build over every loaded project in the workspace and return aggregated
 * problem markers.
 *
 * <p>Closes the {@code refactor → compile → fix} loop the agent previously
 * had to drive via per-file {@code get_diagnostics} (incomplete: misses
 * cascading errors in untouched files and project-level errors like broken
 * {@code Require-Bundle} or unresolved Tycho deps) or shelling out to
 * Maven / Gradle (slow, suffers classpath drift between IDE and CLI).</p>
 *
 * <p>Reads the rows of the language's {@link BuildVerdict} after the build —
 * the one place the host's problem markers are read (Sprint 28k E10b) — not
 * AST reconcile. The incremental builder writes project-level problems — Java
 * compiler errors, classpath errors, manifest errors — that AST reconcile alone
 * misses.</p>
 */
public class CompileWorkspaceTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(CompileWorkspaceTool.class);

    /** The host's reconciler, which refreshes and builds; read at call time (28k E6). */
    private final Supplier<Reconciler> reconciler;

    public CompileWorkspaceTool(Supplier<IJdtService> serviceSupplier, Supplier<Reconciler> reconciler) {
        super(serviceSupplier);
        this.reconciler = reconciler;
    }

    @Override
    public String getName() {
        return "compile_workspace";
    }

    /**
     * A GATE: this tool's answer is evidence a claim was verified (S9a M1).
     *
     * <p>Declared here rather than held as a name in {@code learn/}, which is where it
     * lived in three byte-identical sets at once. See {@link Tool#isGate()}.</p>
     */
    @Override
    public boolean isGate() {
        return true;
    }

    @Override
    public String getDescription() {
        return """
            Run an incremental Java build over every loaded project (or one
            specific project when projectKey is set) and return aggregated
            problem markers — compiler errors, warnings, and project-level
            errors (broken Require-Bundle, unresolved classpath, manifest
            errors) in one call.

            USAGE:
              compile_workspace()
              compile_workspace(projectKey="core")
              compile_workspace(minSeverity="WARNING")
              compile_workspace(includeTaskMarkers=true)
              compile_workspace(clean=true)
              compile_workspace(scope="test")

            Inputs:
            - projectKey — optional. Compile just that project; default is the
              full workspace.
            - minSeverity — "ERROR" (default) or "WARNING". Markers below the
              threshold are dropped from the result.
            - includeTaskMarkers — default false. When true, TODO/FIXME markers
              are included in addition to PROBLEM markers.
            - clean — default false. When true, CLEAN_BUILD precedes FULL_BUILD
              so JDT's incremental compile cache is invalidated. Use when a
              record's canonical constructor shape changes or any other
              signature-shape edit that the incremental builder would otherwise
              skip recompiling consumers for (bugs.md #8).
            - scope — "main" | "test" | "both" (default "both"). Filters
              diagnostics by the TEST ATTRIBUTE the importer records on each
              source root in the model — every layout, including flat PDE
              bundles, not a path convention. Project-level files cross-cut
              (visible in both scopes); an all-test project's files are test
              (jawata-mcp#9, fixed Sprint 28).
            - summary — default false. When true, return counts only
              (errorCount/warningCount + byProject) with NO diagnostics array —
              the consumable shape when a broken classpath yields thousands of
              errors (v2.7.1).
            - limit / offset — page the diagnostics array (default limit 100,
              offset 0). errorCount/warningCount/count always reflect the FULL
              set; `truncated` + `hint` flag a capped page (v2.7.1).

            Result:
              { operation, projectsCompiled, errorCount, warningCount, count,
                offset, limit, returnedCount, truncated,
                diagnostics: [{filePath, line, column, severity, message,
                              sourceProject}] }

            Compilation errors are a normal RESULT (returned in `diagnostics`).
            The tool itself only fails on missing projectKey or aborted build.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("minSeverity", Map.of(
            "type", "string",
            "enum", List.of("ERROR", "WARNING"),
            "description", "Drop diagnostics below this severity. Default: ERROR."));
        properties.put("includeTaskMarkers", Map.of(
            "type", "boolean",
            "description", "Include TODO/FIXME markers (default false)."));
        properties.put("clean", Map.of(
            "type", "boolean",
            "description", "When true, CLEAN_BUILD precedes FULL_BUILD so JDT's incremental compile cache is invalidated (bugs.md #8). Default false."));
        properties.put("scope", Map.of(
            "type", "string",
            "enum", List.of("main", "test", "both"),
            "description", "Filter diagnostics by the source root's test attribute in the model (every layout, incl. flat PDE bundles — not a path convention). Project-level files cross-cut; an all-test project's files are test. Default 'both' (jawata-mcp#9)."));
        properties.put("summary", Map.of(
            "type", "boolean",
            "description", "Counts only (errorCount/warningCount + byProject), NO diagnostics array — for workspaces with thousands of errors (v2.7.1). Default false."));
        properties.put("limit", Map.of(
            "type", "integer",
            "description", "Max diagnostics returned (default 100); counts always reflect the full set (v2.7.1)."));
        properties.put("offset", Map.of(
            "type", "integer",
            "description", "Skip the first N diagnostics (pagination; default 0) (v2.7.1)."));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    /** v2.7.1: default page size — mirrors find_quality_issue's v2.6.1 cap. */
    private static final int DEFAULT_DIAGNOSTICS_LIMIT = 100;

    private static final Set<String> VALID_SCOPES = Set.of("main", "test", "both");

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String minSeverityRaw = getStringParam(arguments, "minSeverity", "ERROR");
        boolean includeTaskMarkers = getBooleanParam(arguments, "includeTaskMarkers", false);
        boolean cleanFirst = getBooleanParam(arguments, "clean", false);
        String scope = getStringParam(arguments, "scope", "both");

        if (!VALID_SCOPES.contains(scope)) {
            return ToolResponse.invalidParameter("scope",
                "Must be 'main', 'test', or 'both'; got '" + scope + "'");
        }

        Diagnostic.Severity minSeverity;
        switch (minSeverityRaw.toUpperCase()) {
            case "ERROR" -> minSeverity = Diagnostic.Severity.ERROR;
            case "WARNING" -> minSeverity = Diagnostic.Severity.WARNING;
            default -> {
                return ToolResponse.invalidParameter("minSeverity",
                    "Must be 'ERROR' or 'WARNING'; got '" + minSeverityRaw + "'");
            }
        }

        // AbstractTool.execute already validated `projectKey` if present and
        // wrapped the service in a ScopedJdtService; however, ScopedJdtService
        // delegates allProjects() to the underlying multi-project service, so
        // we need to choose the project set ourselves. When projectKey is set,
        // operate on just that one; otherwise walk every loaded project.
        String projectKey = getStringParam(arguments, "projectKey");
        Collection<LoadedProject> projects;
        if (projectKey != null && !projectKey.isBlank()) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
                // bugs.md #11 (Sprint 14): same dropped-vs-typo distinction
                // as AbstractTool.execute. AbstractTool doesn't fire for this
                // tool because compile_workspace handles its own projectKey
                // (it needs unscoped allProjects() when none is set).
                Optional<Long> dropped = service.wasRecentlyDropped(projectKey);
                if (dropped.isPresent()) {
                    return ToolResponse.projectKeyDropped(projectKey, dropped.get());
                }
                return ToolResponse.invalidParameter("projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use project(action=list).");
            }
            projects = List.of(scoped.get());
        } else {
            projects = service.allProjects();
        }

        // v3.2.1 (dogfood #2): compiling an EMPTY workspace used to answer
        // success/0 errors/0 compiled — a clean-looking no-op that read as a
        // green build while the workspace had silently failed to load. An
        // empty workspace is a refusal with the reason, never a green answer.
        if (projects.isEmpty()) {
            return ToolResponse.error("NO_PROJECTS_LOADED",
                "No projects are loaded — nothing was compiled, so this is NOT a green build.",
                "Check health_check (a failed workspace auto-load reports its cause there), "
                    + "then load_project the intended tree.");
        }

        try {
            int errorCount = 0;
            int warningCount = 0;
            List<Map<String, Object>> diagnostics = new ArrayList<>();
            // Sprint 28k (E6, C1 architect F1): ONE rebuild over the selected projects; only what the host's
            // reconciler answers as built is counted, and every other selected project is named below.
            List<String> names = projects.stream().map(p -> p.javaProject().getProject().getName()).toList();
            Set<String> built;
            try {
                built = reconciler.get().rebuild(new BuildScope(names),
                    cleanFirst ? BuildKind.CLEAN_FULL : BuildKind.FULL, new NullProgressMonitor());
            } catch (Exception e) {
                log.warn("Build failed: {}", e.getMessage(), e);
                built = Set.of();
            }
            int compiled = built.size();
            List<Map<String, Object>> notBuilt = new ArrayList<>();
            // Sprint 28k (E10b): the compile check exists once — the problem and task rows come from the language's verdict,
            // read here after the build, instead of this tool sweeping the host's markers itself.
            BuildVerdict verdict = new JavaBuildVerdict(service);
            BuildScope selected = new BuildScope(names);
            Map<String, List<Diagnostic>> problemRows = byProject(verdict.diagnostics(selected, new NullProgressMonitor()));
            Map<String, List<Diagnostic>> taskRows = includeTaskMarkers
                ? byProject(verdict.tasks(selected, new NullProgressMonitor())) : Map.of();

            for (LoadedProject loaded : projects) {
                IProject project = loaded.javaProject().getProject();
                if (!built.contains(project.getName())) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("project", loaded.projectKey());
                    row.put("problem", "the host's reconciler did not build it (its build failed, or the host does not hold it)");
                    row.put("remedy", "read the server log for the build failure, then refresh_workspace");
                    notBuilt.add(row);
                }
                // Pick up any filesystem changes the agent / user made
                // since the project was loaded — without this, an
                // incremental build won't see new or edited source files
                // (Eclipse-linked folders need an explicit refreshLocal
                // to repopulate the resource tree). We use FULL_BUILD
                // because INCREMENTAL_BUILD's delta tracking lags fresh
                // refreshLocal additions in Tycho-test-style headless
                // Equinox runtimes — and a full build over a workspace
                // already loaded into memory is fast enough that the
                // simpler / more reliable mode wins.
                //
                // Sprint 14 (bugs.md #8): when clean=true, CLEAN_BUILD
                // precedes FULL_BUILD so JDT discards its incremental
                // compile cache. Required when a record's canonical
                // constructor shape changes (or any other signature edit
                // that the incremental builder would skip recompiling
                // consumers for).
                // Sprint 28k (E6): the host refreshed and built above — headless as described here,
                // Eclipse through its own build.
                //
                // Continue collecting markers whatever was built — partial results are still useful.

                for (Diagnostic row : problemRows.getOrDefault(project.getName(), List.of())) {
                    if (row.severity().compareTo(minSeverity) < 0) continue;
                    if (!inScope(row.scope(), scope)) continue;
                    diagnostics.add(describe(row, loaded, service));
                    if (row.severity() == Diagnostic.Severity.ERROR) errorCount++;
                    else if (row.severity() == Diagnostic.Severity.WARNING) warningCount++;
                }

                for (Diagnostic task : taskRows.getOrDefault(project.getName(), List.of())) {
                    if (!inScope(task.scope(), scope)) continue;
                    diagnostics.add(describe(task, loaded, service));
                }
            }

            // v2.7.1 (dogfood 2026-07-10): a classpath-broken workspace produced
            // 17,383 diagnostics in one 4.25 MB response — unconsumable for any
            // MCP client. Bound the array the same way find_quality_issue was
            // bounded in v2.6.1: summary → counts only; otherwise page with
            // limit/offset. The counts always reflect the FULL set.
            int total = diagnostics.size();
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "compile_workspace");
            data.put("projectsCompiled", compiled);
            // jawata-mcp#4 (Sprint 27a Stage 8): an UNBUILDABLE project may
            // contribute zero compile markers because the builder refused to
            // run — a green gate that never looked. Say so loudly and count
            // each such project as an error, so 0/0 always means "compiled
            // clean" and never "could not compile".
            List<org.jawata.mcp.tools.shared.WorkspaceHealth.Problem> unbuildable =
                org.jawata.mcp.tools.shared.WorkspaceHealth.diagnose(service);
            if (!unbuildable.isEmpty() || !notBuilt.isEmpty()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (var p : unbuildable) {
                    rows.add(p.describe());
                }
                rows.addAll(notBuilt);
                data.put("couldNotCompile", rows);
                data.put("couldNotCompileWarning", "The project(s) above CANNOT be"
                    + " built — their compile results here are vacuous, not clean."
                    + " Fix each per its remedy, then refresh_workspace.");
                errorCount += unbuildable.size() + notBuilt.size();
            }
            data.put("errorCount", errorCount);
            data.put("warningCount", warningCount);

            if (getBooleanParam(arguments, "summary", false)) {
                Map<String, Integer> byProject = new LinkedHashMap<>();
                for (Map<String, Object> d : diagnostics) {
                    byProject.merge(String.valueOf(d.get("sourceProject")), 1, Integer::sum);
                }
                data.put("summary", true);
                data.put("count", total);
                data.put("byProject", byProject);
                return ToolResponse.success(data, ResponseMeta.builder()
                    .totalCount(total)
                    .returnedCount(0)
                    .build());
            }

            int offset = Math.max(0, getIntParam(arguments, "offset", 0));
            int limit = getIntParam(arguments, "limit", DEFAULT_DIAGNOSTICS_LIMIT);
            if (limit <= 0) {
                limit = DEFAULT_DIAGNOSTICS_LIMIT;
            }
            int from = Math.min(offset, total);
            int to = Math.min(from + limit, total);
            List<Map<String, Object>> page = new ArrayList<>(diagnostics.subList(from, to));
            boolean truncated = to < total || from > 0;
            data.put("count", total);
            data.put("offset", offset);
            data.put("limit", limit);
            data.put("returnedCount", page.size());
            data.put("truncated", truncated);
            data.put("diagnostics", page);
            if (truncated) {
                data.put("hint", "showing diagnostics " + from + "–" + to + " of " + total
                    + " — use summary:true for counts only, or offset/limit to page");
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total)
                .returnedCount(page.size())
                .build());
        } catch (Exception e) {
            log.warn("compile_workspace threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * jawata-mcp#9, fixed for real (Sprint 28 Stage 3): main-vs-test comes
     * from the MODEL — the {@code test} attribute the importer records on
     * every source entry — read through
     * {@link org.jawata.core.project.SourceRootClassifier}. No path
     * convention, no name pattern.
     *
     * <p>What stood here before, and why it could not work: a
     * {@code src/main}/{@code src/test} path match (says nothing about flat
     * PDE-bundle layouts), a {@code projectName.endsWith(".tests")} check that
     * was UNREACHABLE live — loaded projects are named
     * {@code jawata-<dir>-<session>}, never by bundle name, so the branch had
     * a green unit test feeding it hand-written names and never fired in any
     * real workspace — and a {@code .java} catch-all that then swept every
     * flat-layout file into MAIN. Result: {@code scope=main} returned
     * jawata's own test bundles and {@code scope=test} returned nothing.</p>
     *
     * <p>Cross-cutting resources (under no source root) stay visible in both
     * scopes — except in a project whose every source root is test code,
     * which IS the test half, manifests included (the pinned C8-F4 semantics,
     * now derived from the model instead of a name).</p>
     */
    private static boolean inScope(Diagnostic.TestScope where, String scope) {
        if ("both".equals(scope)) return true;
        return switch (where) {
            case CROSS_CUTTING -> true;
            case TEST -> "test".equals(scope);
            case MAIN -> "main".equals(scope);
        };
    }

    /** The rows grouped by the project they are in, in order; a row naming no project is kept under "null" so it is not lost. */
    static Map<String, List<Diagnostic>> byProject(List<Diagnostic> rows) {
        Map<String, List<Diagnostic>> grouped = new LinkedHashMap<>();
        for (Diagnostic row : rows) {
            grouped.computeIfAbsent(String.valueOf(row.project()), k -> new ArrayList<>()).add(row);
        }
        return grouped;
    }

    static Map<String, Object> describe(Diagnostic row, LoadedProject loaded, IJdtService service) {
        Map<String, Object> entry = new LinkedHashMap<>();
        if (row.filePath() != null) {
            entry.put("filePath", service.getPathUtils().formatPath(row.filePath()));
        }
        if (row.line() > 0) entry.put("line", row.line() - 1); // markers are 1-based; tools are 0-based
        if (row.detail().charStart() >= 0) entry.put("charStart", row.detail().charStart());
        if (row.detail().charEnd() >= 0) entry.put("charEnd", row.detail().charEnd());
        entry.put("severity", row.severity().name());
        entry.put("message", row.message());
        entry.put("sourceProject", loaded.projectKey());
        return entry;
    }
}
