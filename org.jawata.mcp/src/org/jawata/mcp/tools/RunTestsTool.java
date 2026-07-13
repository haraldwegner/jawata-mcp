package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.execution.ForkedTestRunner;
import org.jawata.mcp.execution.RunnerClasspath;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.junit.JUnitLaunchHelper;
import org.jawata.mcp.tools.shared.FrameworkDetection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Sprint 12 (v1.6.0) — {@code run_tests}: launch JUnit tests via
 * JDT-LTK's launching delegate and return parsed results.
 *
 * <p>Headless. Scope can be one method, one class, or a whole package.
 * Framework defaults to auto-detection from the project's resolved
 * classpath (junit-jupiter-api → junit5; org.junit / junit-4.x →
 * junit4; testng → routed via JUnit 4 compat layer).</p>
 *
 * <p>Sprint 23 (D1) — the JDT-LTK launch path (historically: OSGi NPEs on
 * plain Maven, no streaming, no headless fixture support) is replaced by the
 * {@link ForkedTestRunner} execution spine: ONE runner JVM forked on the
 * project's own compiled output + resolved classpath (project-supplied
 * JUnit-platform jars filtered; the dist {@code tools/} console-standalone
 * supplies launcher + engines), structured results streamed over an event
 * file, full safe-execution contract (timeout, process-tree reaping, env
 * allowlist, memory + concurrency bounds, honest
 * {@code evidenceFinalized=false} on crash/kill/timeout). The project is
 * built first and the run is REFUSED while compile errors exist.</p>
 */
public class RunTestsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(RunTestsTool.class);

    private final org.jawata.mcp.execution.TestSessionRegistry sessions =
        new org.jawata.mcp.execution.TestSessionRegistry();

    private final org.jawata.mcp.coverage.CoverageService coverage =
        new org.jawata.mcp.coverage.CoverageService();

    public RunTestsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "run_tests";
    }

    @Override
    public String getDescription() {
        return """
            Run JUnit / TestNG tests in a forked runner JVM on the project's
            own classpath and return parsed pass/fail/skip results with stack
            traces for failures.

            USAGE:
              run_tests(scope={kind:"method", typeName:"com.example.FooTest", methodName:"testBar"})
              run_tests(scope={kind:"method", filePath:"...", line:42, column:4})
              run_tests(scope={kind:"class",  typeName:"com.example.FooTest"})
              run_tests(scope={kind:"class",  filePath:"...", line:10, column:0})
              run_tests(scope={kind:"package", packageName:"com.example.tests"})

            Async (long suites — progressive results):
              run_tests(action="start", scope=...)        → { sessionId, state }
              run_tests(action="status", sessionId="...") → live per-class progress
                (plannedTests, classesStarted/Finished, testsFinished, recentEvents)
                + the full summary once FINISHED; the summary stays retrievable
                after completion.
              run_tests(action="cancel", sessionId="...") → reaps the runner
                process tree, returns the honest partial result (cancelled=true,
                evidenceFinalized=false).
              action="run" (default) behaves synchronously as above.

            Coverage evidence (JaCoCo on the same runner lifecycle):
              run_tests(coverage=true, scope=...)              → coverageArtifactId
              run_tests(action="coverage_report" [, artifactId]) → totals + provenance
                (git revision + dirty fingerprint, selection, evidence kind,
                completion status) + per-state histogram; STALE classes are
                REFUSED, an unfinalized run reports unknown-run-failed, a run
                with no exec data reports instrumentation-failure — counts are
                never invented.
              run_tests(action="coverage_uncovered", target="com.example.Foo#m")
                → per-symbol states + uncovered/partly-covered lines + branch
                gaps + links into the compiler-aware tools + a smallest-next-
                action hint; same-FQN-in-two-bundles yields separate facts.
              run_tests(action="coverage_artifacts") / (action="coverage_delete",
                artifactId=...) manage stored artifacts explicitly.

            Inputs:
            - scope.kind — "method" | "class" | "package".
            - For kind="method": pass EXACTLY ONE of these combinations
                {typeName, methodName}              (find method by FQN + name)
                OR {filePath, line, column}         (find method at cursor position)
              {filePath, methodName} is NOT a valid combination — methodName must pair
              with typeName, never with filePath.
            - For kind="class": pass EXACTLY ONE of
                {typeName}                          (FQN)
                OR {filePath, line, column}         (find class at cursor position)
            - For kind="package": pass {packageName}.
            - framework — "junit4" | "junit5" | "testng" | "auto" (default).
            - timeoutSeconds — default 120, hard-cap 600.
            - vmArgs — optional list of JVM flags (e.g. ["-Xmx512m"]).
            - projectKey — optional. Restrict to a single loaded project.

            Result: { framework, projectsTested, summary{total, passed, failed,
              skipped, aborted?, timeMs, timedOut?, evidenceFinalized,
              evidenceNote?}, failures[{testClass, testMethod, status, message,
              stackTrace, durationMs}], stdoutTail, stderrTail }
            evidenceFinalized=false means the runner JVM died, was killed, or
            timed out — the counts are the partial events seen, never a
            fabricated total.

            Failure modes:
            - INVALID_PARAMETER — bad scope (no @Test method at position,
              empty package, no test framework on classpath, an unsupported
              scope-field combination such as {filePath, methodName}, compile
              errors in the project, or the concurrent-session limit.
            - INTERNAL_ERROR — the runner toolchain could not be launched.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> scope = new LinkedHashMap<>();
        scope.put("type", "object");
        Map<String, Object> scopeProps = new LinkedHashMap<>();
        scopeProps.put("kind", Map.of("type", "string",
            "enum", List.of("method", "class", "package"),
            "description", "Scope of the test run."));
        scopeProps.put("filePath", Map.of("type", "string",
            "description", "Source file. For kind=method or kind=class; must be paired with {line, column}, never with methodName."));
        scopeProps.put("line", Map.of("type", "integer",
            "description", "Zero-based line. Paired with filePath + column for cursor-position lookup (kind=method or kind=class)."));
        scopeProps.put("column", Map.of("type", "integer",
            "description", "Zero-based column. Paired with filePath + line for cursor-position lookup (kind=method or kind=class)."));
        scopeProps.put("typeName", Map.of("type", "string",
            "description", "Fully-qualified class name. For kind=class: pass alone. For kind=method: pair with methodName."));
        scopeProps.put("methodName", Map.of("type", "string",
            "description", "Test method name. Must be paired with typeName for kind=method. NOT valid with filePath — use {filePath, line, column} for cursor-position lookups instead."));
        scopeProps.put("packageName", Map.of("type", "string",
            "description", "Package FQN (kind=package)."));
        scope.put("properties", scopeProps);
        scope.put("required", List.of("kind"));
        properties.put("scope", scope);
        properties.put("framework", Map.of("type", "string",
            "enum", List.of("junit4", "junit5", "testng", "auto"),
            "description", "Default 'auto' — detected from project classpath."));
        properties.put("timeoutSeconds", Map.of("type", "integer",
            "description", "Default 120; hard-capped at 600."));
        properties.put("vmArgs", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "Optional JVM flags for the forked test runner."));
        properties.put("action", Map.of("type", "string",
            "enum", List.of("run", "start", "status", "cancel"),
            "description", "run (default) = synchronous. start = async, returns a sessionId "
                + "immediately. status/cancel operate on a sessionId (scope not needed)."));
        properties.put("sessionId", Map.of("type", "string",
            "description", "Session handle from action=start; required for status/cancel."));
        properties.put("extraClasspath", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "Launch descriptor: extra jar/dir paths appended to the runner "
                + "classpath at RUNTIME only (e.g. plain-Java projects whose build model "
                + "cannot declare them)."));
        properties.put("coverage", Map.of("type", "boolean",
            "description", "Collect JaCoCo coverage during this run (action=run/start); the "
                + "response carries a coverageArtifactId for the coverage_* query actions."));
        properties.put("evidenceKind", Map.of("type", "string",
            "enum", List.of("unit", "integration", "system", "replay", "manual"),
            "description", "Evidence classification recorded in the coverage artifact "
                + "(default unit)."));
        properties.put("artifactId", Map.of("type", "string",
            "description", "Coverage artifact for the coverage_* actions (default: latest)."));
        properties.put("target", Map.of("type", "string",
            "description", "coverage_uncovered target: 'com.example.Foo' or "
                + "'com.example.Foo#method'."));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        if (arguments == null) {
            return ToolResponse.invalidParameter("scope", "Required object 'scope' is missing.");
        }
        String action = getStringParam(arguments, "action", "run");
        switch (action) {
            case "status", "cancel" -> {
                return handleSessionAction(action, arguments);
            }
            case "coverage_report", "coverage_uncovered", "coverage_artifacts",
                 "coverage_delete" -> {
                return handleCoverageAction(action, arguments);
            }
            case "run", "start" -> { /* fall through to launch */ }
            default -> {
                return ToolResponse.invalidParameter("action",
                    "Must be run, start, status, cancel, coverage_report, "
                        + "coverage_uncovered, coverage_artifacts, or coverage_delete; got '"
                        + action + "'.");
            }
        }
        if (arguments == null || !arguments.has("scope") || !arguments.get("scope").isObject()) {
            return ToolResponse.invalidParameter("scope", "Required object 'scope' is missing.");
        }
        JsonNode scope = arguments.get("scope");
        String kind = scope.has("kind") ? scope.get("kind").asText() : null;
        if (kind == null) {
            return ToolResponse.invalidParameter("scope.kind", "Required field missing.");
        }

        String projectKey = getStringParam(arguments, "projectKey");
        LoadedProject loaded;
        if (projectKey != null && !projectKey.isBlank()) {
            Optional<LoadedProject> scoped = service.getProject(projectKey);
            if (scoped.isEmpty()) {
                Optional<Long> dropped = service.wasRecentlyDropped(projectKey);
                if (dropped.isPresent()) {
                    return ToolResponse.projectKeyDropped(projectKey, dropped.get());
                }
                return ToolResponse.invalidParameter("projectKey",
                    "Unknown projectKey '" + projectKey + "'. Use list_projects.");
            }
            loaded = scoped.get();
        } else {
            loaded = service.allProjects().stream().findFirst().orElse(null);
            if (loaded == null) {
                return ToolResponse.projectNotLoaded();
            }
        }
        IJavaProject javaProject = loaded.javaProject();

        try {
            String frameworkRaw = getStringParam(arguments, "framework", "auto");
            JUnitLaunchHelper.TestRunnerKind runner = FrameworkDetection.detect(frameworkRaw, javaProject);
            if (runner == null) {
                return ToolResponse.invalidParameter("framework",
                    "Could not detect a JUnit/TestNG framework on the project's classpath. "
                        + "Set framework explicitly or add a test dependency.");
            }

            ForkedTestRunner.Spec spec = new ForkedTestRunner.Spec();
            int timeout = getIntParam(arguments, "timeoutSeconds", 120);
            spec.timeoutSeconds = Math.min(600, Math.max(1, timeout));
            if (arguments.has("vmArgs") && arguments.get("vmArgs").isArray()) {
                arguments.get("vmArgs").forEach(n -> spec.vmArgs.add(n.asText()));
            }

            switch (kind) {
                case "method" -> {
                    ToolResponse error = configureMethodScope(spec, scope, service);
                    if (error != null) return error;
                }
                case "class" -> {
                    ToolResponse error = configureClassScope(spec, scope, service);
                    if (error != null) return error;
                }
                case "package" -> {
                    if (!scope.has("packageName") || scope.get("packageName").asText().isBlank()) {
                        return ToolResponse.invalidParameter("scope.packageName",
                            "packageName is required for kind='package'.");
                    }
                    spec.selectPackages.add(scope.get("packageName").asText());
                }
                default -> {
                    return ToolResponse.invalidParameter("scope.kind",
                        "Must be 'method', 'class', or 'package'; got '" + kind + "'.");
                }
            }

            // Evidence needs compiled classes: build, and refuse honestly on
            // compile errors instead of running tests against stale bytes.
            String buildProblem = RunnerClasspath.buildAndCheck(javaProject, new NullProgressMonitor());
            if (buildProblem != null) {
                return ToolResponse.invalidParameter("project", buildProblem);
            }

            boolean testng = "testng".equalsIgnoreCase(frameworkRaw);
            RunnerClasspath.Assembled assembled = RunnerClasspath.assemble(javaProject, testng);
            if (assembled.isRefused()) {
                return ToolResponse.invalidParameter("project", assembled.refusalReason);
            }
            spec.classpath = assembled.classpath;
            // Launch descriptor (Stage 3): caller-supplied runtime-only
            // classpath additions — the plain-Java shape's escape hatch.
            if (arguments.has("extraClasspath") && arguments.get("extraClasspath").isArray()) {
                arguments.get("extraClasspath").forEach(n ->
                    spec.classpath.add(Path.of(n.asText())));
            }
            org.eclipse.core.runtime.IPath loc = javaProject.getProject().getLocation();
            if (loc != null) {
                spec.workingDirectory = loc.toFile().toPath();
            }

            String frameworkLabel = frameworkRaw.equals("auto")
                ? runnerKindToShortName(runner) : frameworkRaw;

            // Sprint 23 D3: optional coverage collection on this run.
            boolean withCoverage = arguments.has("coverage")
                && arguments.get("coverage").asBoolean(false);
            String evidenceKind = getStringParam(arguments, "evidenceKind", "unit");
            String artifactId = null;
            if (withCoverage) {
                artifactId = coverage.mountCollector(spec);
            }
            final String covArtifact = artifactId;
            final LoadedProject covProject = loaded;
            final String covFramework = frameworkLabel;

            if ("start".equals(action)) {
                org.jawata.mcp.execution.TestSessionRegistry.Session session = sessions.create();
                session.frameworkLabel = frameworkLabel;
                spec.eventConsumer = session::onEvent;
                ForkedTestRunner.RunningSession running;
                try {
                    running = new ForkedTestRunner().start(spec);
                    session.attach(running);
                } catch (ForkedTestRunner.SessionLimitException limit) {
                    sessions.remove(session.id);
                    return ToolResponse.invalidParameter("concurrency", limit.getMessage());
                }
                if (withCoverage) {
                    // Finalize the artifact when the async run completes.
                    Thread finalizer = new Thread(() -> {
                        try {
                            ForkedTestRunner.Result done = running.await();
                            coverage.finalizeArtifact(covArtifact, covProject, spec, done,
                                covFramework, evidenceKind);
                        } catch (Exception e) {
                            log.warn("coverage artifact {} not finalized: {}",
                                covArtifact, e.getMessage());
                        }
                    }, "jawata-coverage-finalizer");
                    finalizer.setDaemon(true);
                    finalizer.start();
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "run_tests");
                data.put("action", "start");
                data.put("sessionId", session.id);
                data.put("state", session.state());
                data.put("framework", frameworkLabel);
                if (withCoverage) data.put("coverageArtifactId", artifactId);
                data.put("hint", "Poll run_tests(action=status, sessionId=…) for per-class "
                    + "progress; abort with action=cancel.");
                return ToolResponse.success(data, ResponseMeta.builder().build());
            }

            ForkedTestRunner.Result r;
            try {
                r = new ForkedTestRunner().run(spec);
            } catch (ForkedTestRunner.SessionLimitException limit) {
                return ToolResponse.invalidParameter("concurrency", limit.getMessage());
            }
            if (withCoverage) {
                coverage.finalizeArtifact(artifactId, loaded, spec, r, frameworkLabel, evidenceKind);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "run_tests");
            data.put("framework", frameworkLabel);
            data.put("projectsTested", 1);
            if (withCoverage) {
                data.put("coverageArtifactId", artifactId);
                data.put("coverageHint", "Query run_tests(action=coverage_report|"
                    + "coverage_uncovered, artifactId=…) for the evidence.");
            }
            appendResult(data, r);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(r.total)
                .returnedCount(r.failures.size())
                .build());
        } catch (Exception e) {
            log.warn("run_tests threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /** Sprint 23 D3 — the coverage-evidence query surface. */
    private ToolResponse handleCoverageAction(String action, JsonNode arguments) {
        try {
            org.jawata.mcp.coverage.CoverageStore store = coverage.store();
            if ("coverage_artifacts".equals(action)) {
                java.util.List<Map<String, Object>> items = new ArrayList<>();
                for (String id : store.list()) {
                    store.readManifest(id).ifPresent(m -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("artifactId", id);
                        row.put("createdAt", m.createdAt);
                        row.put("evidenceKind", m.evidenceKind);
                        row.put("completionStatus", m.completionStatus);
                        row.put("gitRevision", m.gitRevision);
                        row.put("dirtyFingerprint", m.dirtyFingerprint);
                        row.put("selection", selectionSummary(m));
                        items.add(row);
                    });
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "run_tests");
                data.put("action", action);
                data.put("artifacts", items);
                return ToolResponse.success(data, ResponseMeta.builder()
                    .totalCount(items.size()).returnedCount(items.size()).build());
            }
            if ("coverage_delete".equals(action)) {
                String id = getStringParam(arguments, "artifactId");
                if (id == null || id.isBlank()) {
                    return ToolResponse.invalidParameter("artifactId",
                        "artifactId is required for coverage_delete.");
                }
                boolean removed = store.delete(id);
                coverage.evict(id);
                if (!removed) {
                    return ToolResponse.invalidParameter("artifactId",
                        "Unknown coverage artifact '" + id + "'.");
                }
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "run_tests");
                data.put("action", action);
                data.put("artifactId", id);
                data.put("deleted", true);
                return ToolResponse.success(data, ResponseMeta.builder().build());
            }

            // report / uncovered need a resolved artifact.
            String id = getStringParam(arguments, "artifactId");
            if (id == null || id.isBlank()) {
                id = store.latest().orElse(null);
                if (id == null) {
                    return ToolResponse.invalidParameter("artifactId",
                        "No coverage artifacts exist yet — run with coverage=true first.");
                }
            }
            org.jawata.mcp.coverage.CoverageModel model = coverage.model(id);
            if (model == null) {
                return ToolResponse.invalidParameter("artifactId",
                    "Unknown coverage artifact '" + id + "'.");
            }

            if ("coverage_report".equals(action)) {
                return coverageReport(id, model);
            }
            String target = getStringParam(arguments, "target");
            if (target == null || target.isBlank()) {
                return ToolResponse.invalidParameter("target",
                    "target is required for coverage_uncovered: 'com.example.Foo' or "
                        + "'com.example.Foo#method'.");
            }
            return coverageUncovered(id, model, target);
        } catch (Exception e) {
            log.warn("coverage action {} failed: {}", action, e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse coverageReport(String id, org.jawata.mcp.coverage.CoverageModel model) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", "coverage_report");
        data.put("artifactId", id);
        org.jawata.mcp.coverage.CoverageManifest m = model.manifest;
        Map<String, Object> provenance = new LinkedHashMap<>();
        provenance.put("createdAt", m.createdAt);
        provenance.put("gitRevision", m.gitRevision);
        provenance.put("dirtyFingerprint", m.dirtyFingerprint);
        provenance.put("evidenceKind", m.evidenceKind);
        provenance.put("environment", m.environment);
        provenance.put("jdkVersion", m.jdkVersion);
        provenance.put("jacocoVersion", m.jacocoVersion);
        provenance.put("framework", m.framework);
        provenance.put("selection", selectionSummary(m));
        provenance.put("completionStatus", m.completionStatus);
        provenance.put("classRoots", m.classRoots);
        data.put("provenance", provenance);
        data.put("measurementBoundary", m.measurementBoundary);

        if (!m.runFinalized) {
            data.put("state", "unknown-run-failed");
            data.put("note", "The producing run never finalized its evidence ("
                + m.completionStatus + ") — no coverage claims can be made from this artifact.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }
        if (model.instrumentationFailure) {
            data.put("state", "instrumentation-failure");
            data.put("note", "The run finished but produced NO execution data — the agent "
                + "did not collect; coverage claims would be invented.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }

        int linesCovered = 0, linesMissed = 0, branchesCovered = 0, branchesMissed = 0;
        Map<String, Integer> states = new LinkedHashMap<>();
        java.util.List<String> staleClasses = new ArrayList<>();
        for (org.jawata.mcp.coverage.CoverageModel.ClassCov c : model.classes) {
            states.merge(c.state.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'),
                1, Integer::sum);
            if (c.state == org.jawata.mcp.coverage.CoverageModel.State.STALE_BYTES) {
                staleClasses.add(c.fqn);
                continue;
            }
            linesCovered += c.linesCovered;
            linesMissed += c.linesMissed;
            branchesCovered += c.branchesCovered;
            branchesMissed += c.branchesMissed;
        }
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("classes", model.classes.size());
        totals.put("linesCovered", linesCovered);
        totals.put("linesMissed", linesMissed);
        totals.put("linePercent", percent(linesCovered, linesMissed));
        totals.put("branchesCovered", branchesCovered);
        totals.put("branchesMissed", branchesMissed);
        totals.put("branchPercent", percent(branchesCovered, branchesMissed));
        data.put("totals", totals);
        data.put("stateHistogram", states);
        if (!staleClasses.isEmpty()) {
            data.put("staleClasses", staleClasses);
            data.put("staleNote", "REFUSED for these classes: their CURRENT bytes differ "
                + "from the measured bytes (class-id mismatch) — re-run with coverage "
                + "to get truthful data.");
        }
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(model.classes.size()).returnedCount(model.classes.size()).build());
    }

    private ToolResponse coverageUncovered(String id,
            org.jawata.mcp.coverage.CoverageModel model, String target) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", "coverage_uncovered");
        data.put("artifactId", id);
        data.put("target", target);
        data.put("measurementBoundary", model.manifest.measurementBoundary);

        if (!model.manifest.runFinalized) {
            data.put("state", "unknown-run-failed");
            data.put("note", "The producing run never finalized its evidence — no per-symbol "
                + "claims can be made.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }
        if (model.instrumentationFailure) {
            data.put("state", "instrumentation-failure");
            data.put("note", "No execution data was collected for this artifact.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }

        String fqn = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
        String methodName = target.contains("#") ? target.substring(target.indexOf('#') + 1) : null;

        java.util.List<org.jawata.mcp.coverage.CoverageModel.ClassCov> facts = model.allOf(fqn);
        if (facts.isEmpty()) {
            data.put("state", "not-instrumented");
            data.put("note", "No class '" + fqn + "' under the artifact's class roots — it was "
                + "not part of the measured build output.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }
        java.util.List<Map<String, Object>> perBundle = new ArrayList<>();
        for (org.jawata.mcp.coverage.CoverageModel.ClassCov c : facts) {
            Map<String, Object> fact = new LinkedHashMap<>();
            fact.put("classRoot", c.classRoot);
            if (c.state == org.jawata.mcp.coverage.CoverageModel.State.STALE_BYTES) {
                fact.put("state", "stale-bytes");
                fact.put("note", "REFUSED: current bytes differ from the measured bytes.");
                perBundle.add(fact);
                continue;
            }
            if (methodName == null) {
                fact.put("state", c.state.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
                java.util.List<Map<String, Object>> methods = new ArrayList<>();
                for (org.jawata.mcp.coverage.CoverageModel.MethodCov mm : c.methods) {
                    methods.add(methodDetail(mm));
                }
                fact.put("methods", methods);
            } else {
                org.jawata.mcp.coverage.CoverageModel.MethodCov mm = c.methods.stream()
                    .filter(x -> x.name.equals(methodName)).findFirst().orElse(null);
                if (mm == null) {
                    fact.put("state",
                        c.state == org.jawata.mcp.coverage.CoverageModel.State.NOT_INSTRUMENTED
                            ? "not-instrumented" : "generated-or-excluded");
                    fact.put("note", c.state
                            == org.jawata.mcp.coverage.CoverageModel.State.NOT_INSTRUMENTED
                        ? "The class never loaded in the measured run."
                        : "No such method in the ANALYZED model — either it does not exist, "
                            + "or JaCoCo's synthetic filters excluded it (bridges, generated "
                            + "members): no line data is honest data.");
                } else {
                    fact.putAll(methodDetail(mm));
                }
            }
            perBundle.add(fact);
        }
        data.put("facts", perBundle);
        if (facts.size() > 1) {
            data.put("bundleIdentityNote", "The FQN exists under " + facts.size()
                + " class roots — each fact is keyed by its root; they are NOT merged.");
        }

        // Links into the compiler-aware tools, bound to this exact symbol.
        Map<String, Object> links = new LinkedHashMap<>();
        String symbol = methodName == null ? fqn : fqn + "#" + methodName;
        links.put("callers", Map.of(
            "tool", "get_call_hierarchy",
            "args", Map.of("direction", "incoming", "symbol", symbol)));
        links.put("implementations", Map.of(
            "tool", "find_references",
            "args", Map.of("kind", "implementations", "query", fqn)));
        links.put("existingTests", model.manifest.selectClasses.isEmpty()
            ? selectionSummary(model.manifest) : model.manifest.selectClasses);
        data.put("links", links);
        data.put("nextAction", "Smallest next step: write a test that calls " + symbol
            + " directly (see 'links.callers' for where it is exercised from), then re-run "
            + "with coverage=true and re-query this target.");
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    private static Map<String, Object> methodDetail(
            org.jawata.mcp.coverage.CoverageModel.MethodCov m) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("method", m.name);
        out.put("state", m.state.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
        out.put("lines", m.firstLine + "-" + m.lastLine);
        out.put("linesCovered", m.linesCovered);
        out.put("linesMissed", m.linesMissed);
        out.put("branchesCovered", m.branchesCovered);
        out.put("branchesMissed", m.branchesMissed);
        if (!m.uncoveredLines.isEmpty()) out.put("uncoveredLines", m.uncoveredLines);
        if (!m.partlyCoveredLines.isEmpty()) out.put("partlyCoveredLines", m.partlyCoveredLines);
        return out;
    }

    private static String percent(int covered, int missed) {
        int total = covered + missed;
        return total == 0 ? "n/a" : String.format(java.util.Locale.ROOT,
            "%.1f%%", 100.0 * covered / total);
    }

    private static String selectionSummary(org.jawata.mcp.coverage.CoverageManifest m) {
        java.util.List<String> parts = new ArrayList<>();
        parts.addAll(m.selectClasses);
        parts.addAll(m.selectMethods);
        m.selectPackages.forEach(p -> parts.add(p + ".*"));
        return String.join(", ", parts);
    }

    /** status/cancel on a previously started async session. */
    private ToolResponse handleSessionAction(String action, JsonNode arguments) {
        String sid = getStringParam(arguments, "sessionId");
        if (sid == null || sid.isBlank()) {
            return ToolResponse.invalidParameter("sessionId",
                "sessionId is required for action='" + action + "'.");
        }
        org.jawata.mcp.execution.TestSessionRegistry.Session session = sessions.get(sid);
        if (session == null) {
            return ToolResponse.invalidParameter("sessionId",
                "Unknown or expired session '" + sid + "'.");
        }
        if ("cancel".equals(action)) {
            session.cancel();
            // Give the reap a moment so the response can carry the final
            // (honestly partial) result instead of a still-RUNNING state.
            long deadline = System.currentTimeMillis() + 5000;
            while ("RUNNING".equals(session.state()) && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", action);
        data.put("sessionId", sid);
        data.put("state", session.state());
        data.put("framework", session.frameworkLabel);
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("plannedTests", session.plannedTests);
        progress.put("classesStarted", session.classesStarted.get());
        progress.put("classesFinished", session.classesFinished.get());
        progress.put("testsFinished", session.testsFinished.get());
        data.put("progress", progress);
        List<Map<String, Object>> events = session.recentEvents();
        data.put("recentEvents",
            events.size() > 25 ? events.subList(events.size() - 25, events.size()) : events);
        ForkedTestRunner.Result r = session.resultNow();
        if (r != null) {
            appendResult(data, r);
        }
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    /** Map a runner result onto the response shape (shared by run/status/cancel). */
    private static void appendResult(Map<String, Object> data, ForkedTestRunner.Result r) {
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("total", r.total);
        summary.put("passed", r.passed);
        summary.put("failed", r.failed);
        summary.put("skipped", r.skipped);
        if (r.aborted > 0) summary.put("aborted", r.aborted);
        summary.put("timeMs", r.timeMs);
        if (r.timedOut) summary.put("timedOut", true);
        if (r.cancelled) summary.put("cancelled", true);
        summary.put("evidenceFinalized", r.evidenceFinalized);
        if (!r.evidenceFinalized) {
            String note;
            if (r.cancelled) {
                note = "The run was CANCELLED and the runner reaped — counts are the "
                    + "partial events seen before the kill; evidence was NOT finalized.";
            } else if (r.timedOut) {
                note = "The run TIMED OUT and was reaped — counts below are the partial "
                    + "events seen before the kill; evidence was NOT finalized.";
            } else {
                note = "The runner JVM ended abnormally (exit " + r.exitCode + ") — counts "
                    + "below are partial; evidence was NOT finalized.";
            }
            summary.put("evidenceNote", note);
        }
        data.put("summary", summary);

        List<Map<String, Object>> failures = new ArrayList<>();
        for (ForkedTestRunner.CaseResult c : r.failures) {
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("testClass", c.testClass);
            f.put("testMethod", c.testMethod);
            f.put("status", c.status);
            if (c.message != null) f.put("message", c.message);
            if (c.stackTrace != null) f.put("stackTrace", c.stackTrace);
            f.put("durationMs", c.durationMs);
            failures.add(f);
        }
        data.put("failures", failures);
        data.put("stdoutTail", r.stdoutTail == null ? "" : r.stdoutTail);
        data.put("stderrTail", r.stderrTail == null ? "" : r.stderrTail);
    }

    private ToolResponse configureMethodScope(ForkedTestRunner.Spec spec,
                                              JsonNode scope, IJdtService service)
            throws Exception {
        if (scope.has("typeName") && scope.has("methodName")) {
            spec.selectMethods.add(scope.get("typeName").asText()
                + "#" + scope.get("methodName").asText());
            return null;
        }
        if (!scope.has("filePath") || !scope.has("line") || !scope.has("column")) {
            return ToolResponse.invalidParameter("scope",
                "kind='method' requires either {typeName, methodName} or {filePath, line, column}.");
        }
        Path filePath = service.getPathUtils().resolve(scope.get("filePath").asText());
        int line = scope.get("line").asInt();
        int column = scope.get("column").asInt();
        IJavaElement element = service.getElementAtPosition(filePath, line, column);
        IMethod method = null;
        // Walk up if the position landed on a body element rather than the
        // declaration itself.
        for (IJavaElement walk = element; walk != null; walk = walk.getParent()) {
            if (walk instanceof IMethod m) {
                method = m;
                break;
            }
        }
        if (method == null) {
            return ToolResponse.symbolNotFound(
                "No method at " + filePath + ":" + line + ":" + column);
        }
        IType declaring = method.getDeclaringType();
        if (declaring == null) {
            return ToolResponse.invalidParameter("scope",
                "Method has no declaring type (synthetic?).");
        }
        spec.selectMethods.add(declaring.getFullyQualifiedName()
            + "#" + method.getElementName());
        return null;
    }

    private ToolResponse configureClassScope(ForkedTestRunner.Spec spec,
                                             JsonNode scope, IJdtService service)
            throws Exception {
        if (scope.has("typeName") && !scope.get("typeName").asText().isBlank()) {
            spec.selectClasses.add(scope.get("typeName").asText());
            return null;
        }
        if (!scope.has("filePath") || !scope.has("line") || !scope.has("column")) {
            return ToolResponse.invalidParameter("scope",
                "kind='class' requires either typeName or {filePath, line, column}.");
        }
        Path filePath = service.getPathUtils().resolve(scope.get("filePath").asText());
        int line = scope.get("line").asInt();
        int column = scope.get("column").asInt();
        IType type = service.getTypeAtPosition(filePath, line, column);
        if (type == null) {
            return ToolResponse.symbolNotFound(
                "No type at " + filePath + ":" + line + ":" + column);
        }
        spec.selectClasses.add(type.getFullyQualifiedName());
        return null;
    }

    private static String runnerKindToShortName(JUnitLaunchHelper.TestRunnerKind k) {
        return switch (k) {
            case JUNIT3 -> "junit3";
            case JUNIT4 -> "junit4";
            case JUNIT5 -> "junit5";
        };
    }

}
