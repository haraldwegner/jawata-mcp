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

    /**
     * Every action {@link #executeWithService} accepts, in one place.
     *
     * <p>The declared schema said <em>four</em> while the dispatcher accepted
     * <em>eighteen</em>, and the refusal message named ten of the fourteen
     * coverage suffixes — omitting {@code coverage_tests_covering},
     * {@code coverage_of_test}, {@code coverage_impacted_tests} and
     * {@code coverage_mutation}. So the schema a caller reads, and the error
     * text that would otherwise teach it, both hid capabilities that had
     * shipped: impacted-test selection was built and verified in Sprint 23 and
     * sat unused for five weeks, and nothing a caller could read said it
     * existed.</p>
     *
     * <p>Three hand-maintained copies of one list is what produced that, so
     * there is now one list and no copies. {@code RunTestsToolActionsTest}
     * drives every entry through the real dispatcher and refuses anything the
     * switch routes but this list omits — the divergence cannot silently
     * return.</p>
     */
    static final List<String> ACTIONS = List.of(
        "run", "start", "status", "cancel",
        "coverage_report", "coverage_uncovered", "coverage_artifacts",
        "coverage_delete", "coverage_delta", "coverage_baseline_set",
        "coverage_baseline_compare", "coverage_threshold_set",
        "coverage_stability", "coverage_tests_covering",
        "coverage_of_test", "coverage_impacted_tests",
        "coverage_import", "coverage_mutation");

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

    /**
     * A GATE: this tool's answer is evidence a claim was verified (S9a M1).
     *
     * <p>Declared here rather than held as a name in {@code learn/}, which is where it
     * lived in three byte-identical sets at once — and where this tool's name ALSO sat
     * inline, in a fourth place no set could cover. See {@link Tool#isGate()}.</p>
     */
    @Override
    public boolean isGate() {
        return true;
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
              run_tests(action="coverage_delta", diff="worktree|staged|range")
                → changed-lines coverage (rename-aware; unstable lines never
                count covered); baselines via coverage_baseline_set/compare
                (regression judged per symbol/line, blind to a rising global
                percentage); coverage_threshold_set stores a VERSIONED policy
                whose value/version/waivers surface in responses;
                coverage_stability registers unstable lines from two
                same-selection runs; coverage_import ingests a CI exec file
                (class-id gated).

            Who-tests-what (attribution=true runs record per-test segments):
              run_tests(action="coverage_tests_covering", target="Foo#m")
                → the tests that EXERCISED the symbol, ranked focused-first
                (unit before integration), kinds labeled; answers
                "unavailable" without attribution evidence — never guesses.
              run_tests(action="coverage_of_test", test="FooTest#m")
                → every symbol that test exercised.
              run_tests(action="coverage_impacted_tests" [, symbols] [, diff]
                [, forScope])
                → the smallest evidence-backed test set for a change, plus
                selectionVerdict — read mayNarrow, which is a COMPLETE answer
                for the forScope you named ('whole-suite' by default). Do not
                shorten a run on impactedTests while mayNarrow is false: the
                reason beside it says what the evidence could not speak for.

            SELECTION BY COMPILER REACH, and it is the DEFAULT, not a flag:
              a scope={kind:"package"} run executes only the tests this same
              evidence says your changes reach, and reports what it narrowed to
              under `selection` on the run, start AND status responses. Where
              the evidence cannot carry the claim it runs the WHOLE scope and
              says why — it never shortens silently, and it never shortens a
              run that is itself recording coverage or attribution. Pass
              fullRun=true to pin the whole scope, which is what a checkpoint
              or a release gate wants.

            Mutation (OPT-IN, always targeted, runtime-bounded):
              run_tests(action="coverage_mutation", targetClasses=["com.example.Foo"]
                [, targetTests] [, timeoutSeconds])
                → killed/survived/timed-out/no-coverage; each SURVIVOR maps to
                its symbol + line + a candidate missing-assertion location.
                targetTests defaults to the attribution-derived exercising set.

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
            "enum", ACTIONS,
            "description", "run (default) = synchronous. start = async, returns a sessionId "
                + "immediately. status/cancel operate on a sessionId (scope not needed). "
                + "The coverage_* actions query and manage coverage evidence — including "
                + "coverage_impacted_tests, which selects the tests a diff actually touches, "
                + "and coverage_mutation, which checks whether those tests would notice a "
                + "change."));
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
        properties.put("fullRun", Map.of("type", "boolean",
            "description", "PIN the whole scope. A kind='package' run otherwise narrows "
                + "ITSELF to the tests the coverage evidence says your changes reach — by "
                + "default, on every client and OS, with no flag to remember. Pass this "
                + "true where the whole scope is the point: a checkpoint, a release gate. "
                + "Where the evidence cannot prove the narrowing is safe the response SAYS "
                + "SO and everything runs anyway, so a quiet short run is not a state this "
                + "can reach."));
        properties.put("evidenceKind", Map.of("type", "string",
            "enum", List.of("unit", "integration", "system", "replay", "manual"),
            "description", "Evidence classification recorded in the coverage artifact "
                + "(default unit)."));
        properties.put("artifactId", Map.of("type", "string",
            "description", "Coverage artifact for the coverage_* actions (default: latest)."));
        properties.put("target", Map.of("type", "string",
            "description", "coverage_uncovered / coverage_tests_covering target: "
                + "'com.example.Foo' or 'com.example.Foo#method'."));
        properties.put("attribution", Map.of("type", "boolean",
            "description", "Collect PER-TEST coverage segments during this run (implies "
                + "coverage=true); enables the who-tests-what queries."));
        properties.put("test", Map.of("type", "string",
            "description", "coverage_of_test: 'com.example.FooTest#method'."));
        properties.put("symbols", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "coverage_impacted_tests: explicit symbol list (else derived "
                + "from the git diff)."));
        properties.put("forScope", Map.of("type", "string",
            "description", "coverage_impacted_tests: the scope you mean to NARROW, so the "
                + "verdict can be a complete answer rather than a half of one — "
                + "'whole-suite' (the default, and the strictest) or 'package:com.foo'. "
                + "Evidence recorded from a run narrower than this cannot say which tests "
                + "cover it, only which cover what that run happened to execute, so "
                + "selectionVerdict.mayNarrow comes back false and says which."));
        properties.put("diff", Map.of("type", "string",
            "enum", List.of("worktree", "staged", "range"),
            "description", "coverage_delta / coverage_impacted_tests: which git diff "
                + "(default worktree)."));
        properties.put("range", Map.of("type", "string",
            "description", "Commit range for diff=range, e.g. 'HEAD~1..HEAD'."));
        properties.put("name", Map.of("type", "string",
            "description", "Baseline name for coverage_baseline_set/compare."));
        properties.put("otherArtifactId", Map.of("type", "string",
            "description", "coverage_stability: the second same-selection artifact."));
        properties.put("lineThresholdPercent", Map.of("type", "number",
            "description", "coverage_threshold_set: the line-coverage threshold; every "
                + "change bumps the stored policy VERSION."));
        properties.put("waivers", Map.of("type", "array",
            "items", Map.of("type", "object"),
            "description", "coverage_threshold_set: [{pattern, reason}] — always visible "
                + "in threshold-bearing responses."));
        properties.put("execFile", Map.of("type", "string",
            "description", "coverage_import: path to an external jacoco.exec (CI run); "
                + "acceptance is gated by the class-id check."));
        properties.put("targetClasses", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "coverage_mutation: the production classes to mutate "
                + "(typically the CHANGED classes — mutation is always targeted)."));
        properties.put("targetTests", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "coverage_mutation: the exercising test classes; omitted = "
                + "derived from attribution evidence."));
        schema.put("properties", properties);
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        if (arguments == null) {
            return ToolResponse.invalidParameter("scope", "Required object 'scope' is missing.");
        }
        String action = getStringParam(arguments, "action", "run");
        // THE DECLARED LIST IS THE GATE, so the two cannot drift apart: an
        // action the switch below routes but ACTIONS omits is unreachable by
        // construction, rather than merely caught by a test someone has to
        // remember to update. The reverse direction — declared here and not
        // routed below — is what the `default` arm now means, and it says so.
        if (!ACTIONS.contains(action)) {
            return ToolResponse.invalidParameter("action",
                "Must be one of: " + String.join(", ", ACTIONS) + "; got '" + action + "'.");
        }
        switch (action) {
            case "status", "cancel" -> {
                return handleSessionAction(action, arguments);
            }
            case "coverage_report", "coverage_uncovered", "coverage_artifacts",
                 "coverage_delete", "coverage_delta", "coverage_baseline_set",
                 "coverage_baseline_compare", "coverage_threshold_set",
                 "coverage_stability", "coverage_tests_covering",
                 "coverage_of_test", "coverage_impacted_tests" -> {
                return handleCoverageAction(action, arguments);
            }
            case "coverage_import" -> {
                return handleCoverageImport(service, arguments);
            }
            case "coverage_mutation" -> {
                return handleCoverageMutation(service, arguments);
            }
            case "run", "start" -> { /* fall through to launch */ }
            default -> {
                // NOT a caller error — the guard above already refused anything
                // undeclared. Reaching here means jawata declares an action it
                // does not route, and saying "you passed something invalid"
                // would send the caller hunting for a mistake it did not make.
                log.error("run_tests declares action '{}' and routes it nowhere", action);
                return ToolResponse.error("ACTION_NOT_ROUTED",
                    "'" + action + "' is a declared run_tests action that this build does not "
                        + "route. This is a jawata defect, not a problem with your call.",
                    "Report it; nothing you change about the call will help.");
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

            // D21 — selection by compiler reach, as the DEFAULT of the broad
            // scope. A method or class scope is already the caller's own
            // narrowing and is left alone; a package scope is the one that
            // runs everything, so it is the one worth deriving.
            //
            // It is not a flag, and that is the deliverable rather than a
            // convenience. A selection offered as an opt-in costs the agent
            // nothing to skip — the wall clock lands on the human — so it is
            // chosen by nobody and never fires. fullRun pins the whole scope
            // for the two cases that genuinely want it.
            boolean fullRun = arguments.has("fullRun")
                && arguments.get("fullRun").asBoolean(false);
            // A run that is RECORDING evidence must not be shaped by evidence.
            // Narrowing it would record a manifest whose own selection is
            // narrow, which the scope rule then reads to narrow the next run
            // further — a loop that converges on one test class and calls it a
            // suite. The run that produces the evidence sees the scope it
            // claims to describe.
            boolean recording = (arguments.has("attribution")
                    && arguments.get("attribution").asBoolean(false))
                || (arguments.has("coverage") && arguments.get("coverage").asBoolean(false));
            Map<String, Object> selection = null;
            if ("package".equals(kind) && !fullRun && !recording) {
                selection = narrowToImpacted(spec, arguments,
                    scope.get("packageName").asText());
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

            // Sprint 23 D3/D4: optional coverage collection on this run;
            // attribution=true additionally records per-test segments.
            boolean withAttribution = arguments.has("attribution")
                && arguments.get("attribution").asBoolean(false);
            boolean withCoverage = withAttribution || (arguments.has("coverage")
                && arguments.get("coverage").asBoolean(false));
            String evidenceKind = getStringParam(arguments, "evidenceKind", "unit");
            String artifactId = null;
            if (withCoverage) {
                artifactId = coverage.mountCollector(spec, withAttribution);
            }
            final String covArtifact = artifactId;
            final LoadedProject covProject = loaded;
            final String covFramework = frameworkLabel;

            if ("start".equals(action)) {
                org.jawata.mcp.execution.TestSessionRegistry.Session session = sessions.create();
                session.frameworkLabel = frameworkLabel;
                session.selection = selection;
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
                if (selection != null) data.put("selection", selection);
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
            if (selection != null) data.put("selection", selection);
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
            if ("coverage_tests_covering".equals(action)) {
                return coverageTestsCovering(arguments);
            }
            if ("coverage_impacted_tests".equals(action)) {
                return coverageImpactedTests(arguments);
            }
            if ("coverage_threshold_set".equals(action)) {
                if (!arguments.has("lineThresholdPercent")) {
                    return ToolResponse.invalidParameter("lineThresholdPercent",
                        "lineThresholdPercent is required for coverage_threshold_set.");
                }
                double threshold = arguments.get("lineThresholdPercent").asDouble();
                java.util.List<Map<String, String>> waivers = new ArrayList<>();
                if (arguments.has("waivers") && arguments.get("waivers").isArray()) {
                    arguments.get("waivers").forEach(w -> waivers.add(Map.of(
                        "pattern", w.path("pattern").asText(""),
                        "reason", w.path("reason").asText(""))));
                }
                Map<String, Object> policy = store.setPolicy(threshold, waivers);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "run_tests");
                data.put("action", action);
                data.put("policy", policy);
                return ToolResponse.success(data, ResponseMeta.builder().build());
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
            if ("coverage_delta".equals(action)) {
                return coverageDelta(id, model, arguments);
            }
            if ("coverage_baseline_set".equals(action)) {
                String name = getStringParam(arguments, "name");
                if (name == null || name.isBlank()) {
                    return ToolResponse.invalidParameter("name", "baseline name is required.");
                }
                store.setBaseline(name, id);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "run_tests");
                data.put("action", action);
                data.put("baseline", name);
                data.put("artifactId", id);
                return ToolResponse.success(data, ResponseMeta.builder().build());
            }
            if ("coverage_baseline_compare".equals(action)) {
                return coverageBaselineCompare(id, model, arguments);
            }
            if ("coverage_stability".equals(action)) {
                return coverageStability(id, model, arguments);
            }
            if ("coverage_of_test".equals(action)) {
                return coverageOfTest(id, model, arguments);
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
        int lineTotal = linesCovered + linesMissed;
        attachPolicy(data, lineTotal == 0 ? null : 100.0 * linesCovered / lineTotal);
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

    /** Stage 8 — intersect the coverage model with a git diff (rename-aware). */
    private ToolResponse coverageDelta(String id, org.jawata.mcp.coverage.CoverageModel model,
            JsonNode arguments) throws Exception {
        if (!model.manifest.runFinalized || model.instrumentationFailure) {
            return ToolResponse.invalidParameter("artifactId",
                "Artifact '" + id + "' carries no usable evidence ("
                    + model.manifest.completionStatus + ") — no delta can be computed.");
        }
        String diffKind = getStringParam(arguments, "diff", "worktree");
        String range = getStringParam(arguments, "range");
        org.jawata.mcp.coverage.GitDiff diff = org.jawata.mcp.coverage.GitDiff.read(
            Path.of(model.manifest.projectRoot), diffKind, range);
        Map<String, java.util.List<Integer>> unstable = coverage.store().readUnstable();

        java.util.List<Map<String, Object>> files = new ArrayList<>();
        int changedExecutable = 0, coveredChanged = 0;
        java.util.List<String> uncoveredAll = new ArrayList<>();
        java.util.List<String> unstableAll = new ArrayList<>();
        java.util.List<String> advisoryLines = new ArrayList<>();
        Path repoRoot = Path.of(model.manifest.projectRoot);
        for (Map.Entry<String, java.util.Set<Integer>> e : diff.changedLinesByFile.entrySet()) {
            String path = e.getKey();
            if (!path.endsWith(".java") || e.getValue().isEmpty()) continue;
            boolean mechanical = org.jawata.mcp.coverage.MechanicalChangeJournal
                .isMechanicallyTouched(repoRoot.resolve(path).toString());
            org.jawata.mcp.coverage.CoverageModel.ClassCov clazz = classForPath(model, path);
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("file", path);
            if (mechanical) row.put("exemptMechanicalTransform", true);
            if (clazz == null) {
                row.put("note", "no analyzed class maps to this file (new/removed/non-main)");
                files.add(row);
                continue;
            }
            row.put("class", clazz.fqn);
            if (clazz.state == org.jawata.mcp.coverage.CoverageModel.State.STALE_BYTES) {
                row.put("state", "stale-bytes");
                row.put("note", "REFUSED: the artifact predates the current bytes of this class.");
                files.add(row);
                continue;
            }
            java.util.List<Integer> unstableLines = unstable.getOrDefault(clazz.fqn, List.of());
            java.util.List<Integer> covered = new ArrayList<>();
            java.util.List<Integer> uncoveredL = new ArrayList<>();
            java.util.List<Integer> unstableHit = new ArrayList<>();
            for (org.jawata.mcp.coverage.CoverageModel.MethodCov m : clazz.methods) {
                for (Integer line : e.getValue()) {
                    if (line < m.firstLine || line > m.lastLine) continue;
                    if (unstableLines.contains(line)) {
                        // Proven non-deterministic: NEVER counts as covered.
                        unstableHit.add(line);
                    } else if (m.uncoveredLines.contains(line)
                            || m.partlyCoveredLines.contains(line)) {
                        uncoveredL.add(line);
                    } else if (m.coveredLines.contains(line)) {
                        covered.add(line);
                    }
                    // else: non-executable changed line — not counted
                }
            }
            changedExecutable += covered.size() + uncoveredL.size() + unstableHit.size();
            coveredChanged += covered.size();
            if (!uncoveredL.isEmpty()) {
                row.put("uncoveredChangedLines", uncoveredL);
                uncoveredL.forEach(l -> uncoveredAll.add(clazz.fqn + ":" + l));
                if (!mechanical) {
                    uncoveredL.forEach(l -> advisoryLines.add(clazz.fqn + ":" + l));
                }
            }
            if (!unstableHit.isEmpty()) {
                row.put("unstableChangedLines", unstableHit);
                row.put("unstableNote", "proven non-deterministic across runs — NOT counted covered");
                unstableHit.forEach(l -> unstableAll.add(clazz.fqn + ":" + l));
            }
            row.put("coveredChangedLines", covered.size());
            files.add(row);
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", "coverage_delta");
        data.put("artifactId", id);
        data.put("diff", diffKind + (range == null ? "" : " " + range));
        data.put("files", files);
        Map<String, Object> totals = new LinkedHashMap<>();
        totals.put("changedExecutableLines", changedExecutable);
        totals.put("coveredChangedLines", coveredChanged);
        totals.put("uncoveredChangedLines", uncoveredAll.size());
        totals.put("unstableChangedLines", unstableAll.size());
        totals.put("changedLinePercent", percent(coveredChanged,
            changedExecutable - coveredChanged));
        data.put("totals", totals);
        attachPolicy(data, changedExecutable == 0 ? null
            : 100.0 * coveredChanged / changedExecutable);
        // Sprint 23 (D6) — the done-time advisory: BEHAVIORAL changed lines
        // without coverage get named; mechanically-transformed files stay
        // silent (their uncovered lines still appear in the row data).
        ResponseMeta.Builder meta = ResponseMeta.builder()
            .totalCount(files.size()).returnedCount(files.size());
        if (!advisoryLines.isEmpty()) {
            meta.steering("DONE-TIME COVERAGE ADVISORY: these CHANGED lines have NO test "
                + "coverage: " + advisoryLines + " — new behavior needs a test; write or "
                + "extend one (coverage_tests_covering shows what exercises the class), "
                + "re-run with coverage=true, then re-check this delta.");
        }
        return ToolResponse.success(data, meta.build());
    }

    /** Stage 8 — regression vs a named baseline, blind to a rising global %. */
    private ToolResponse coverageBaselineCompare(String id,
            org.jawata.mcp.coverage.CoverageModel current, JsonNode arguments) throws Exception {
        String name = getStringParam(arguments, "name");
        if (name == null || name.isBlank()) {
            return ToolResponse.invalidParameter("name", "baseline name is required.");
        }
        String baseId = coverage.store().baseline(name).orElse(null);
        if (baseId == null) {
            return ToolResponse.invalidParameter("name", "Unknown baseline '" + name + "'.");
        }
        org.jawata.mcp.coverage.CoverageModel base = coverage.model(baseId);
        if (base == null) {
            return ToolResponse.invalidParameter("name",
                "Baseline '" + name + "' points at a deleted artifact (" + baseId + ").");
        }
        java.util.List<Map<String, Object>> newlyMissed = new ArrayList<>();
        java.util.List<String> newlyUncoveredSymbols = new ArrayList<>();
        for (org.jawata.mcp.coverage.CoverageModel.ClassCov baseClass : base.classes) {
            org.jawata.mcp.coverage.CoverageModel.ClassCov nowClass = current.classOf(baseClass.fqn);
            if (nowClass == null) continue;
            for (org.jawata.mcp.coverage.CoverageModel.MethodCov bm : baseClass.methods) {
                org.jawata.mcp.coverage.CoverageModel.MethodCov nm = nowClass.methods.stream()
                    .filter(x -> x.name.equals(bm.name) && x.desc.equals(bm.desc))
                    .findFirst().orElse(null);
                if (nm == null) continue;
                java.util.List<Integer> lost = new ArrayList<>();
                for (Integer line : bm.coveredLines) {
                    if (nm.uncoveredLines.contains(line)) lost.add(line);
                }
                if (!lost.isEmpty()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("symbol", baseClass.fqn + "#" + bm.name);
                    row.put("newlyMissedLines", lost);
                    newlyMissed.add(row);
                }
                boolean wasCovered = bm.linesCovered > 0;
                boolean nowMissed = nm.linesCovered == 0 && nm.linesMissed > 0;
                if (wasCovered && nowMissed) {
                    newlyUncoveredSymbols.add(baseClass.fqn + "#" + bm.name);
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", "coverage_baseline_compare");
        data.put("baseline", name);
        data.put("baselineArtifactId", baseId);
        data.put("artifactId", id);
        data.put("globalLinePercentBefore", globalLinePercent(base));
        data.put("globalLinePercentAfter", globalLinePercent(current));
        data.put("newlyMissed", newlyMissed);
        data.put("newlyUncoveredSymbols", newlyUncoveredSymbols);
        boolean regression = !newlyMissed.isEmpty() || !newlyUncoveredSymbols.isEmpty();
        data.put("regression", regression);
        if (regression) {
            data.put("note", "Regression is judged per SYMBOL/LINE — a rising global "
                + "percentage does not excuse newly-uncovered code.");
        }
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    /** Stage 8 — two same-selection artifacts → unstable lines, registered. */
    private ToolResponse coverageStability(String id,
            org.jawata.mcp.coverage.CoverageModel a, JsonNode arguments) throws Exception {
        String otherId = getStringParam(arguments, "otherArtifactId");
        if (otherId == null || otherId.isBlank()) {
            return ToolResponse.invalidParameter("otherArtifactId",
                "otherArtifactId is required for coverage_stability.");
        }
        org.jawata.mcp.coverage.CoverageModel b = coverage.model(otherId);
        if (b == null) {
            return ToolResponse.invalidParameter("otherArtifactId",
                "Unknown coverage artifact '" + otherId + "'.");
        }
        if (!selectionSummary(a.manifest).equals(selectionSummary(b.manifest))) {
            return ToolResponse.invalidParameter("otherArtifactId",
                "Stability needs TWO runs of the SAME selection; got '"
                    + selectionSummary(a.manifest) + "' vs '" + selectionSummary(b.manifest) + "'.");
        }
        Map<String, java.util.List<Integer>> unstable = new LinkedHashMap<>();
        for (org.jawata.mcp.coverage.CoverageModel.ClassCov ca : a.classes) {
            org.jawata.mcp.coverage.CoverageModel.ClassCov cb = b.classOf(ca.fqn);
            if (cb == null) continue;
            java.util.List<Integer> lines = new ArrayList<>();
            for (org.jawata.mcp.coverage.CoverageModel.MethodCov ma : ca.methods) {
                org.jawata.mcp.coverage.CoverageModel.MethodCov mb = cb.methods.stream()
                    .filter(x -> x.name.equals(ma.name) && x.desc.equals(ma.desc))
                    .findFirst().orElse(null);
                if (mb == null) continue;
                for (Integer line : ma.coveredLines) {
                    if (mb.uncoveredLines.contains(line) && !lines.contains(line)) lines.add(line);
                }
                for (Integer line : mb.coveredLines) {
                    if (ma.uncoveredLines.contains(line) && !lines.contains(line)) lines.add(line);
                }
            }
            if (!lines.isEmpty()) unstable.put(ca.fqn, lines);
        }
        if (!unstable.isEmpty()) {
            coverage.store().addUnstable(unstable);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", "coverage_stability");
        data.put("artifactId", id);
        data.put("otherArtifactId", otherId);
        data.put("unstable", unstable);
        data.put("gateNote", unstable.isEmpty()
            ? "No unstable lines between the two runs."
            : "These lines are REGISTERED as unstable — they never count as covered "
                + "in gate/delta answers until proven stable again.");
        return ToolResponse.success(data, ResponseMeta.builder().build());
    }

    

    /** Stage 8 — import an external exec file; class-id gate applies at analysis. */
    private ToolResponse handleCoverageImport(IJdtService service, JsonNode arguments) {
        try {
            String execFile = getStringParam(arguments, "execFile");
            if (execFile == null || execFile.isBlank()) {
                return ToolResponse.invalidParameter("execFile",
                    "execFile is required for coverage_import.");
            }
            String projectKey = getStringParam(arguments, "projectKey");
            LoadedProject project;
            if (projectKey != null && !projectKey.isBlank()) {
                Optional<LoadedProject> scoped = service.getProject(projectKey);
                if (scoped.isEmpty()) {
                    return ToolResponse.invalidParameter("projectKey",
                        "Unknown projectKey '" + projectKey + "'.");
                }
                project = scoped.get();
            } else {
                project = service.allProjects().stream().findFirst().orElse(null);
                if (project == null) return ToolResponse.projectNotLoaded();
            }
            String evidenceKind = getStringParam(arguments, "evidenceKind", "imported");
            String id = coverage.importArtifact(project,
                Path.of(execFile), evidenceKind);
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "run_tests");
            data.put("action", "coverage_import");
            data.put("artifactId", id);
            data.put("note", "Imported. The class-id check gates every query: classes whose "
                + "CURRENT bytes differ from the imported measurement surface as "
                + "stale-bytes REFUSALS.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        } catch (Exception e) {
            log.warn("coverage_import failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /** Stage 9 — evidence-backed who-tests-what, focused-first. */
    private ToolResponse coverageTestsCovering(JsonNode arguments) throws Exception {
        String target = getStringParam(arguments, "target");
        if (target == null || target.isBlank()) {
            return ToolResponse.invalidParameter("target",
                "target is required: 'com.example.Foo' or 'com.example.Foo#method'.");
        }
        String fqn = target.contains("#") ? target.substring(0, target.indexOf('#')) : target;
        String methodName = target.contains("#") ? target.substring(target.indexOf('#') + 1) : null;

        String onlyArtifact = getStringParam(arguments, "artifactId");
        java.util.List<String> artifacts = onlyArtifact != null && !onlyArtifact.isBlank()
            ? List.of(onlyArtifact) : coverage.store().list();

        boolean anyAttribution = false;
        // test → {test, evidenceKind, artifactId}; first (most focused) hit wins.
        Map<String, Map<String, Object>> hits = new LinkedHashMap<>();
        for (String id : artifacts) {
            org.jawata.mcp.coverage.CoverageManifest m =
                coverage.store().readManifest(id).orElse(null);
            if (m == null || !m.attribution || !m.runFinalized) continue;
            anyAttribution = true;
            Path classFile = org.jawata.mcp.coverage.CoverageAttribution.classFile(m, fqn);
            if (classFile == null) continue;
            Map<String, Path> segments =
                org.jawata.mcp.coverage.CoverageAttribution.segments(coverage.store(), id);
            for (Map.Entry<String, Path> seg : segments.entrySet()) {
                if (!org.jawata.mcp.coverage.CoverageAttribution.covers(
                        seg.getValue(), classFile, methodName)) {
                    continue;
                }
                Map<String, Object> existing = hits.get(seg.getKey());
                int rank = org.jawata.mcp.coverage.CoverageAttribution.kindRank(m.evidenceKind);
                if (existing == null || rank < org.jawata.mcp.coverage.CoverageAttribution
                        .kindRank((String) existing.get("evidenceKind"))) {
                    Map<String, Object> hit = new LinkedHashMap<>();
                    hit.put("test", seg.getKey());
                    hit.put("evidenceKind", m.evidenceKind);
                    hit.put("artifactId", id);
                    hits.put(seg.getKey(), hit);
                }
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", "coverage_tests_covering");
        data.put("target", target);
        if (!anyAttribution) {
            data.put("attributionAvailable", false);
            data.put("note", "No attribution artifacts exist (runs with attribution=true) — "
                + "who-tests-what CANNOT be answered from plain coverage runs, and this "
                + "surface never guesses.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }
        data.put("attributionAvailable", true);
        java.util.List<Map<String, Object>> ranked = new ArrayList<>(hits.values());
        ranked.sort(java.util.Comparator
            .comparingInt((Map<String, Object> h) -> org.jawata.mcp.coverage.CoverageAttribution
                .kindRank((String) h.get("evidenceKind")))
            .thenComparing(h -> String.valueOf(h.get("test"))));
        data.put("coveredBy", ranked);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(ranked.size()).returnedCount(ranked.size()).build());
    }

    /** Stage 9 — everything one test exercised (from its segment). */
    private ToolResponse coverageOfTest(String id, org.jawata.mcp.coverage.CoverageModel model,
            JsonNode arguments) {
        String test = getStringParam(arguments, "test");
        if (test == null || test.isBlank()) {
            return ToolResponse.invalidParameter("test",
                "test is required: 'com.example.FooTest#method'.");
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", "coverage_of_test");
        data.put("artifactId", id);
        data.put("test", test);
        if (!model.manifest.attribution) {
            data.put("attributionAvailable", false);
            data.put("note", "Artifact '" + id + "' has no per-test segments — run with "
                + "attribution=true.");
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }
        Map<String, Path> segments =
            org.jawata.mcp.coverage.CoverageAttribution.segments(coverage.store(), id);
        Path segment = segments.get(test);
        if (segment == null) {
            data.put("attributionAvailable", true);
            data.put("note", "No segment for test '" + test + "' in this artifact; known tests: "
                + segments.keySet());
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }
        java.util.List<String> covers = org.jawata.mcp.coverage.CoverageAttribution
            .coveredSymbols(segment, model.manifest, 200);
        data.put("attributionAvailable", true);
        data.put("covers", covers);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(covers.size()).returnedCount(covers.size()).build());
    }

    /**
     * Sprint 28g S10-9 — the SAME impacted-test selection, offered to a read-only door.
     *
     * <p>{@code run_tests} is refused to a hired Claude in plan mode because the tool is not
     * read-only as a whole, while its {@code coverage_impacted_tests} action only reads evidence.
     * D41's first clause says the reach is computed "by the same compiler proof D21 computes", and a
     * seat that cannot call this action had to answer from the call graph instead. {@code inspect}
     * (kind=impacted_tests) delegates here, so the proof is one implementation reached from two
     * doors, not two implementations that can disagree.</p>
     */
    public ToolResponse impactedTests(JsonNode arguments) {
        try {
            return coverageImpactedTests(arguments);
        } catch (Exception e) {
            log.warn("impacted_tests threw unexpectedly: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /** Stage 9 — smallest evidence-backed test set for a change. */
    private ToolResponse coverageImpactedTests(JsonNode arguments) throws Exception {
        ImpactedSelection sel = selectImpacted(arguments);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "run_tests");
        data.put("action", "coverage_impacted_tests");
        if (!sel.attributionAvailable()) {
            data.put("attributionAvailable", false);
            data.put("note", sel.note());
            return ToolResponse.success(data, ResponseMeta.builder().build());
        }
        if (sel.derivedFromDiff()) {
            // D2: the list is never bare — what was examined to produce it, and
            // what could not be spoken for, travel with it.
            data.put("filesChanged", sel.filesChanged());
            data.put("filesWithoutEvidence", sel.filesWithoutEvidence());
            data.put("staleClasses", sel.staleClasses());
        }
        data.put("symbols", sel.symbols());
        data.put("attributionAvailable", true);
        // THE VERDICT, so a caller narrowing a run reads ONE field instead of
        // re-deriving the staleness rules from the facts above it. Four rules
        // read five ways is four chances to disagree, and the disagreement is
        // silent on the side that runs fewer tests.
        // THE VERDICT IS COMPLETE, because the caller says which scope it means
        // to narrow. An earlier version answered `mayNarrow` over the rules
        // that need no scope and published the scope as a separate fact for
        // each caller to judge — which put ONE predicate (does the evidence
        // cover the scope?) in two places that had already drifted apart, and
        // left a field named for a whole judgement holding part of one. A third
        // consumer reading `mayNarrow` and trusting its name would have
        // narrowed unsafely, and unsafely here means FEWER tests.
        String forScope = getStringParam(arguments, "forScope", WHOLE_SUITE);
        String blocked = sel.cannotNarrowBecause() != null ? sel.cannotNarrowBecause()
            : evidenceTooNarrowFor(sel.evidenceScope(), forScope);
        Map<String, Object> verdict = new LinkedHashMap<>();
        verdict.put("forScope", forScope);
        verdict.put("mayNarrow", blocked == null);
        if (blocked != null) verdict.put("reason", blocked);
        // Still reported, because a caller deciding what to do about a refusal
        // wants to see what the evidence actually covered — but nothing has to
        // re-derive the judgement from it any more.
        verdict.put("evidenceScope", sel.evidenceScope());
        data.put("selectionVerdict", verdict);
        data.put("impactedTests", sel.rankedTests());
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(sel.rankedTests().size())
            .returnedCount(sel.rankedTests().size()).build());
    }

    /**
     * The impacted-test derivation, in ONE place: which tests the evidence says
     * a change reaches, and — just as load-bearing — whether that answer may be
     * used to NARROW a run at all.
     *
     * <p>Two callers read it. The {@code coverage_impacted_tests} action
     * REPORTS it; the run path NARROWS by it. A second implementation would be
     * a second answer, and the two would disagree the first time either one
     * changed — which is the whole reason this is a method and not a copy.</p>
     */
    private ImpactedSelection selectImpacted(JsonNode arguments) throws Exception {
        // Symbols: explicit list, or derived from a git diff via the newest
        // attribution artifact's line model.
        java.util.List<String> symbols = new ArrayList<>();
        if (arguments != null && arguments.has("symbols") && arguments.get("symbols").isArray()) {
            arguments.get("symbols").forEach(n -> symbols.add(n.asText()));
        }
        boolean derivedFromDiff = symbols.isEmpty();
        int filesChanged = 0;
        java.util.List<String> withoutEvidence = new ArrayList<>();
        java.util.List<String> staleClasses = new ArrayList<>();
        String attributionArtifact = null;
        org.jawata.mcp.coverage.CoverageManifest manifest = null;
        for (String id : coverage.store().list()) {
            org.jawata.mcp.coverage.CoverageManifest m =
                coverage.store().readManifest(id).orElse(null);
            if (m != null && m.attribution && m.runFinalized) {
                attributionArtifact = id;
                manifest = m;
                break;
            }
        }
        if (attributionArtifact == null) {
            String none = "there is no attribution evidence yet, so nothing can say which "
                + "tests reach the change — run once with attribution=true to record some";
            return new ImpactedSelection(false,
                "No attribution artifacts exist — impacted-test selection needs "
                    + "at least one run with attribution=true.",
                false, 0, symbols, withoutEvidence, staleClasses, List.of(), List.of(), none);
        }
        org.jawata.mcp.coverage.CoverageModel model = coverage.model(attributionArtifact);
        if (derivedFromDiff) {
            String diffKind = getStringParam(arguments, "diff", "worktree");
            String range = getStringParam(arguments, "range");
            org.jawata.mcp.coverage.GitDiff diff = org.jawata.mcp.coverage.GitDiff.read(
                Path.of(model.manifest.projectRoot), diffKind, range);
            filesChanged = diff.changedLinesByFile.size();
            for (Map.Entry<String, java.util.Set<Integer>> e : diff.changedLinesByFile.entrySet()) {
                org.jawata.mcp.coverage.CoverageModel.ClassCov clazz =
                    classForPath(model, e.getKey());
                if (clazz == null) {
                    // mcp#40: NOT the same fact as "this file has no impacted
                    // test". The evidence has never seen this file, so nothing
                    // here can speak for it, and a caller narrowing a run must
                    // be told rather than handed a shorter list.
                    withoutEvidence.add(e.getKey());
                    continue;
                }
                int derivedBefore = symbols.size();
                for (org.jawata.mcp.coverage.CoverageModel.MethodCov m : clazz.methods) {
                    for (Integer line : e.getValue()) {
                        if (line >= m.firstLine && line <= m.lastLine) {
                            String sym = clazz.fqn + "#" + m.name;
                            if (!symbols.contains(sym)) symbols.add(sym);
                            break;
                        }
                    }
                }
                if (clazz.state
                        == org.jawata.mcp.coverage.CoverageModel.State.STALE_BYTES) {
                    staleClasses.add(clazz.fqn);
                }
                if (symbols.size() == derivedBefore) {
                    // mcp#40: no method range claimed a changed line, and that
                    // is never evidence that nothing was impacted.
                    //
                    // MEASURED — the ordinary case is a class REBUILT since the
                    // evidence was recorded, which is the state of any file you
                    // have just edited: JaCoCo matches the class NAME, refuses
                    // its id, and CoverageModel.fillDetail is skipped for
                    // STALE_BYTES, so the class arrives here carrying NO methods
                    // at all. The loop above then runs over an empty list. A
                    // change BETWEEN methods — a field, an initializer — lands
                    // here too.
                    //
                    // The type is what is known, so the type is what is said.
                    // Attribution segments are matched by class name, so a
                    // type-level symbol still selects exactly the tests that
                    // cover it; over-selection costs time, under-selection
                    // costs correctness, and only one of those is acceptable
                    // in a test gate.
                    if (!symbols.contains(clazz.fqn)) symbols.add(clazz.fqn);
                }
            }
        }
        // Union of tests covering each symbol; count how many symbols each covers.
        Map<String, Map<String, Object>> tests = new LinkedHashMap<>();
        for (String symbol : symbols) {
            String fqn = symbol.contains("#") ? symbol.substring(0, symbol.indexOf('#')) : symbol;
            String method = symbol.contains("#") ? symbol.substring(symbol.indexOf('#') + 1) : null;
            for (String id : coverage.store().list()) {
                org.jawata.mcp.coverage.CoverageManifest m =
                    coverage.store().readManifest(id).orElse(null);
                if (m == null || !m.attribution || !m.runFinalized) continue;
                Path classFile = org.jawata.mcp.coverage.CoverageAttribution.classFile(m, fqn);
                if (classFile == null) continue;
                for (Map.Entry<String, Path> seg : org.jawata.mcp.coverage.CoverageAttribution
                        .segments(coverage.store(), id).entrySet()) {
                    if (!org.jawata.mcp.coverage.CoverageAttribution.covers(
                            seg.getValue(), classFile, method)) {
                        continue;
                    }
                    Map<String, Object> row = tests.computeIfAbsent(seg.getKey(), k -> {
                        Map<String, Object> t = new LinkedHashMap<>();
                        t.put("test", k);
                        t.put("evidenceKind", m.evidenceKind);
                        t.put("coversSymbols", new ArrayList<String>());
                        t.put("evidenceScope", new ArrayList<String>());
                        return t;
                    });
                    @SuppressWarnings("unchecked")
                    java.util.List<String> covers = (java.util.List<String>) row.get("coversSymbols");
                    if (!covers.contains(symbol)) covers.add(symbol);
                    // REL44-20: rows are read from EVERY finalized attribution run — deliberately,
                    // so a unit run and an integration run both answer (C9) — while the verdict's
                    // evidenceScope describes the newest run alone, the one it judges. So each row
                    // names the scope of the run(s) it was read from, and a row from an older run
                    // is never presented as if the verdict's scope covered it.
                    @SuppressWarnings("unchecked")
                    java.util.List<String> from = (java.util.List<String>) row.get("evidenceScope");
                    for (java.util.List<String> selected : java.util.Arrays.asList(
                            m.selectClasses, m.selectMethods, m.selectPackages)) {
                        if (selected == null) continue;
                        for (String s : selected) {
                            if (!from.contains(s)) from.add(s);
                        }
                    }
                }
            }
        }
        java.util.List<Map<String, Object>> ranked = new ArrayList<>(tests.values());
        ranked.sort(java.util.Comparator
            .comparingInt((Map<String, Object> t) -> org.jawata.mcp.coverage.CoverageAttribution
                .kindRank((String) t.get("evidenceKind")))
            .thenComparing(t -> -((java.util.List<?>) t.get("coversSymbols")).size())
            .thenComparing(t -> String.valueOf(t.get("test"))));
        java.util.List<String> evidenceScope = new ArrayList<>();
        if (manifest.selectClasses != null) evidenceScope.addAll(manifest.selectClasses);
        if (manifest.selectMethods != null) evidenceScope.addAll(manifest.selectMethods);
        if (manifest.selectPackages != null) evidenceScope.addAll(manifest.selectPackages);
        return new ImpactedSelection(true, null, derivedFromDiff, filesChanged,
            symbols, withoutEvidence, staleClasses, ranked, evidenceScope,
            cannotNarrow(manifest, symbols, withoutEvidence, ranked, derivedFromDiff,
                filesChanged));
    }

    /**
     * THE STALENESS RULES — attribution evidence may narrow a run only while it
     * still describes THIS tree. Any one failing leaves the run whole and says
     * which, because a selection nobody can see is a silent short run.
     *
     * <p>These four were written in {@code build/impacted-tests.sh}, which read
     * the derivation and then judged it from the outside. They are HERE now
     * because the judgement needs the artifact's own manifest — its completion
     * status and the revision it was recorded at — which the shell could only
     * ask for over a second round-trip and, in the case of partial evidence,
     * never got: the script tested {@code data.partial} and
     * {@code data.evidenceComplete}, and this response has never carried either
     * field, so that rule has been reading null and passing since it was
     * written. Moving the verdict is what turns it back on.</p>
     */
    private String cannotNarrow(org.jawata.mcp.coverage.CoverageManifest m,
            java.util.List<String> symbols, java.util.List<String> withoutEvidence,
            java.util.List<Map<String, Object>> ranked, boolean derivedFromDiff,
            int filesChanged) {
        // RULE 4 (near half) — a changed file no class in the evidence claims.
        if (!withoutEvidence.isEmpty()) {
            return "the evidence has never seen " + withoutEvidence.size()
                + " changed file(s), so it cannot speak for them: "
                + String.join(", ", withoutEvidence);
        }
        if (ranked.isEmpty()) {
            return filesChanged == 0 && derivedFromDiff
                ? "the diff is empty, so there is nothing to select against"
                : "no test in the evidence covers the change, which is a gap in the evidence "
                    + "rather than a proof that nothing is affected";
        }
        if (m.gitRevision == null || m.gitRevision.isBlank()) {
            return "the evidence records no git revision, so nothing can say which tree it "
                + "describes";
        }
        Path repo = Path.of(m.projectRoot);
        // RULE 3 — evidence from a rewritten or divergent history describes
        // code this tree does not have, and that is not a smaller mismatch
        // than a stale one. It is a different tree.
        if (!git(repo, "merge-base", "--is-ancestor", m.gitRevision, "HEAD")) {
            return "the attribution evidence was recorded at "
                + m.gitRevision.substring(0, Math.min(12, m.gitRevision.length()))
                + ", which is not an ancestor of HEAD — it describes a different tree";
        }
        // RULE 4 (far half) — everything that changed SINCE that revision,
        // committed or not, must be accounted for. Rule 4 subsumes "the
        // evidence is older than my change": stale evidence and a dropped
        // derivation are one failure seen from the selector's side, a file
        // whose tests were never considered.
        java.util.Set<String> derived = new java.util.LinkedHashSet<>();
        for (String s : symbols) {
            String fqn = s.contains("#") ? s.substring(0, s.indexOf('#')) : s;
            derived.add(fqn.substring(fqn.lastIndexOf('.') + 1));
        }
        java.util.List<String> unaccounted = new ArrayList<>();
        try {
            org.jawata.mcp.coverage.GitDiff since =
                org.jawata.mcp.coverage.GitDiff.read(repo, "range", m.gitRevision);
            for (String file : since.changedLinesByFile.keySet()) {
                if (!file.endsWith(".java")) continue;
                String simple = file.substring(file.lastIndexOf('/') + 1,
                    file.length() - ".java".length());
                if (!derived.contains(simple)) unaccounted.add(file);
            }
        } catch (Exception e) {
            return "the changes since " + m.gitRevision.substring(0,
                Math.min(12, m.gitRevision.length())) + " could not be read, so nothing can "
                + "say whether the evidence covers them: " + e;
        }
        if (!unaccounted.isEmpty()) {
            return "the derivation accounts for none of " + unaccounted.size()
                + " file(s) changed since the evidence was recorded (e.g. "
                + unaccounted.get(0) + "); narrowing on this would skip the tests covering them";
        }
        return null;
    }

    /**
     * RULE 2, applied where the scope IS known: evidence recorded from a run
     * narrower than the one being narrowed cannot carry the claim.
     *
     * <p>A package run may be narrowed by whole-suite evidence, or by evidence
     * from a run of that same package. It may NOT be narrowed by evidence from
     * one test class: a production class exercised both by the class that run
     * saw and by one it never started comes back naming only the first, and
     * the second is then dropped from a run that used to include it. That is
     * under-selection, and it is the failure this whole mechanism is one
     * mistake away from at all times.</p>
     *
     * <p>THE ONLY implementation of that predicate, and every caller reaches it
     * by NAMING its own scope. It briefly had a twin: the action published
     * `evidenceCoversWholeSuite`, which is this same question specialised for
     * the suite runner, and the two had already diverged — the boolean admitted
     * nothing but empty evidence while this method also admitted evidence from
     * the very package being run. One rule in two places is two rules as soon
     * as either is edited.</p>
     *
     * <p>The whole suite is the strictest caller and gets the strictest answer:
     * only evidence that selected nothing in particular ever saw everything.</p>
     *
     * @param evidenceScope what the recording run selected; empty = whole suite
     * @param forScope {@link #WHOLE_SUITE}, or {@code package:<name>}
     * @return the reason it may not narrow, or null
     */
    private static String evidenceTooNarrowFor(java.util.List<String> evidenceScope,
            String forScope) {
        if (evidenceScope.isEmpty()) return null;            // whole-suite evidence
        if (WHOLE_SUITE.equals(forScope)) {
            return "the evidence was recorded from a run that selected " + evidenceScope
                + ", so it cannot speak for the whole suite";
        }
        String packageName = forScope.startsWith(PACKAGE_SCOPE)
            ? forScope.substring(PACKAGE_SCOPE.length()) : forScope;
        if (evidenceScope.contains(packageName)) return null; // this very package
        return "the evidence was recorded from a run that selected " + evidenceScope
            + ", which is narrower than the package being run — it cannot say which tests "
            + "cover " + packageName + ", only which cover what it happened to execute";
    }

    /** The strictest scope a caller can name: everything. */
    private static final String WHOLE_SUITE = "whole-suite";

    /** The only narrower scope this understands today. */
    private static final String PACKAGE_SCOPE = "package:";

    /**
     * One git predicate, answered by exit status — and spawned through the
     * host seam rather than a {@code ProcessBuilder} of its own.
     *
     * <p>The first version built the process here, and the full suite refused
     * it: {@code HostBoundaryRulesTest} keeps a list of production files that
     * construct a ProcessBuilder directly, and its message is the reason —
     * <em>"Eleven such sites once each decided privately how an executable is
     * spelled and whether a timeout means anything."</em> A git that is
     * {@code git.exe} on one platform, and a hang with no bound, are exactly
     * the two things this call must not decide for itself.</p>
     *
     * <p>Cannot-ask is not the same as yes: a launch failure and a timeout are
     * both false, and the caller reads false as "do not narrow" — the side that
     * costs time rather than correctness.</p>
     */
    private static boolean git(Path repo, String... args) {
        java.util.List<String> argv = new ArrayList<>();
        argv.add("git");
        argv.addAll(List.of(args));
        try {
            return org.jawata.core.host.HostProcesses.system()
                .run(org.jawata.core.host.HostCommand.of(argv).in(repo))
                .succeeded();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * What the coverage evidence says a change reaches, and whether that is a
     * proof a run may be narrowed by.
     *
     * <p>The two are kept apart deliberately. A caller READING the selection
     * wants every derived fact, including the ones that make it unusable; a
     * caller NARROWING by it needs one yes or no and a sentence saying why.
     * Folding them would make "I found nothing" and "I could not look"
     * indistinguishable, and those two must never be the same answer.</p>
     */
    private record ImpactedSelection(
            boolean attributionAvailable,
            String note,
            boolean derivedFromDiff,
            int filesChanged,
            java.util.List<String> symbols,
            java.util.List<String> filesWithoutEvidence,
            java.util.List<String> staleClasses,
            java.util.List<Map<String, Object>> rankedTests,
            /**
             * WHAT THE RECORDING RUN ITSELF SELECTED — the empty list meaning
             * it selected nothing in particular and therefore saw the whole
             * suite.
             *
             * <p>This is RULE 2, and it is stated as a FACT rather than folded
             * into {@link #cannotNarrowBecause} because the rule needs a scope
             * and the verdict does not have one. Evidence recorded from one
             * test class cannot say which tests cover a whole package: a class
             * exercised by the run it saw AND by one it did not comes back
             * naming only the first, which is under-selection wearing a clean
             * answer's face. So each caller compares this against the scope IT
             * is about to narrow — the suite runner needs whole-suite
             * evidence, a package run needs evidence that covered that
             * package — and neither re-derives the other's answer.</p>
             *
             * <p>It does NOT come from {@code completionStatus}. That field
             * records how the run ENDED (FINALIZED / CANCELLED / TIMED_OUT /
             * ABNORMAL) and is already implied by the {@code runFinalized}
             * filter above, so reading it here would have been a rule that
             * cannot fail — which is what the first version of this was, and
             * what the test caught on its first run.</p>
             */
            java.util.List<String> evidenceScope,
            /**
             * Why a run may NOT be narrowed to this selection, or null when it
             * may. Every value this can hold means "then run EVERYTHING",
             * never "then run nothing": an absent proof is not a proof of
             * absence, and a selection that silently shrank to zero on missing
             * evidence is the one failure a test gate cannot tolerate.
             */
            String cannotNarrowBecause) {

        /**
         * The distinct test CLASSES this selection names, for a runner spec.
         *
         * <p>Class-level, not method-level, and that is the conservative
         * choice rather than a limitation of the evidence: the segments carry
         * {@code Class#method}, so a method selection is available and would be
         * sharper. It is not taken, because a sibling method the evidence
         * happens not to cover would then be dropped from a run that used to
         * include it. Over-selection costs time and under-selection costs
         * correctness, which is the same trade the symbol derivation makes one
         * level up and resolves the same way.</p>
         */
        java.util.List<String> testClasses() {
            java.util.List<String> out = new ArrayList<>();
            for (Map<String, Object> t : rankedTests) {
                String id = String.valueOf(t.get("test"));
                String fqn = id.contains("#") ? id.substring(0, id.indexOf('#')) : id;
                if (!out.contains(fqn)) out.add(fqn);
            }
            return out;
        }

    }

    /**
     * D21 — narrow a broad run to the tests the evidence says the change
     * reaches, or leave it whole and say why it could not be narrowed.
     *
     * <p>The selectors are a UNION in the runner, so narrowing means CLEARING
     * the package selector rather than adding to it. Returns the note that
     * travels on the response either way: a narrowing nobody can see is a
     * silent short run, which is the thing this must never be.</p>
     */
    private Map<String, Object> narrowToImpacted(ForkedTestRunner.Spec spec, JsonNode arguments,
            String packageName) {
        Map<String, Object> note = new LinkedHashMap<>();
        ImpactedSelection sel;
        try {
            sel = selectImpacted(arguments);
        } catch (Exception e) {
            // The proof could not be TAKEN at all — no git, an unreadable
            // artifact. That is not a reason to run less than was asked for.
            log.warn("impacted-test selection failed; running the whole scope: {}",
                e.getMessage());
            note.put("narrowed", false);
            note.put("ran", "the whole scope");
            note.put("reason", "the impacted-test derivation failed: " + e);
            return note;
        }
        String blocked = sel.cannotNarrowBecause();
        if (blocked == null) {
            blocked = evidenceTooNarrowFor(sel.evidenceScope(), PACKAGE_SCOPE + packageName);
        }
        if (blocked != null) {
            note.put("narrowed", false);
            note.put("ran", "the whole scope");
            note.put("reason", blocked);
            return note;
        }
        java.util.List<String> classes = sel.testClasses();
        // A BOUND, not a reachable branch, and it is written down as such
        // rather than claimed as covered: `cannotNarrowBecause` already refuses
        // an empty ranking, so nothing today reaches here with no classes. It
        // stays because the failure it prevents is the worst one available —
        // clearing the package selector and adding nothing leaves the runner
        // with no selectors at all, which exits 2 with "argfile has no
        // selectors" and, on a path that swallowed that, would be a run that
        // executed NOTHING and reported success. Mutation M4 produced exactly
        // that state, which is how this came to be written.
        if (classes.isEmpty()) {
            note.put("narrowed", false);
            note.put("ran", "the whole scope");
            note.put("reason", "the selection named no test class, and a run with no "
                + "selectors executes nothing at all");
            return note;
        }
        spec.selectPackages.clear();
        spec.selectClasses.addAll(classes);
        note.put("narrowed", true);
        note.put("selectedClasses", classes);
        note.put("symbols", sel.symbols());
        note.put("filesChanged", sel.filesChanged());
        if (!sel.staleClasses().isEmpty()) note.put("staleClasses", sel.staleClasses());
        note.put("pin", "Pass fullRun=true to run the whole scope instead.");
        return note;
    }

    /** Stage 10 — targeted, opt-in, bounded mutation testing via PIT. */
    private ToolResponse handleCoverageMutation(IJdtService service, JsonNode arguments) {
        try {
            java.util.List<String> targetClasses = new ArrayList<>();
            if (arguments.has("targetClasses") && arguments.get("targetClasses").isArray()) {
                arguments.get("targetClasses").forEach(n -> targetClasses.add(n.asText()));
            } else {
                String target = getStringParam(arguments, "target");
                if (target != null && !target.isBlank()) {
                    targetClasses.add(target.contains("#")
                        ? target.substring(0, target.indexOf('#')) : target);
                }
            }
            if (targetClasses.isEmpty()) {
                return ToolResponse.invalidParameter("targetClasses",
                    "coverage_mutation is TARGETED: pass targetClasses (or target) — "
                        + "typically the changed class(es).");
            }
            java.util.List<String> targetTests = new ArrayList<>();
            if (arguments.has("targetTests") && arguments.get("targetTests").isArray()) {
                arguments.get("targetTests").forEach(n -> targetTests.add(n.asText()));
            } else {
                // Derive from attribution evidence: the test CLASSES that
                // exercised the target classes.
                for (String clazz : targetClasses) {
                    for (String id : coverage.store().list()) {
                        org.jawata.mcp.coverage.CoverageManifest m =
                            coverage.store().readManifest(id).orElse(null);
                        if (m == null || !m.attribution || !m.runFinalized) continue;
                        Path classFile =
                            org.jawata.mcp.coverage.CoverageAttribution.classFile(m, clazz);
                        if (classFile == null) continue;
                        for (Map.Entry<String, Path> seg
                                : org.jawata.mcp.coverage.CoverageAttribution
                                    .segments(coverage.store(), id).entrySet()) {
                            if (org.jawata.mcp.coverage.CoverageAttribution.covers(
                                    seg.getValue(), classFile, null)) {
                                String testClass = seg.getKey().contains("#")
                                    ? seg.getKey().substring(0, seg.getKey().indexOf('#'))
                                    : seg.getKey();
                                if (!targetTests.contains(testClass)) targetTests.add(testClass);
                            }
                        }
                    }
                }
                if (targetTests.isEmpty()) {
                    return ToolResponse.invalidParameter("targetTests",
                        "No attributed tests found for " + targetClasses + " — pass "
                            + "targetTests explicitly or run with attribution=true first.");
                }
            }

            LoadedProject project = service.allProjects().stream().findFirst().orElse(null);
            if (project == null) return ToolResponse.projectNotLoaded();
            String buildProblem = RunnerClasspath.buildAndCheck(
                project.javaProject(), new NullProgressMonitor());
            if (buildProblem != null) {
                return ToolResponse.invalidParameter("project", buildProblem);
            }
            java.util.List<String> sourceRoots = new ArrayList<>();
            org.eclipse.core.resources.IWorkspaceRoot wsRoot =
                org.eclipse.core.resources.ResourcesPlugin.getWorkspace().getRoot();
            for (org.eclipse.jdt.core.IClasspathEntry entry
                    : project.javaProject().getResolvedClasspath(true)) {
                if (entry.getEntryKind() == org.eclipse.jdt.core.IClasspathEntry.CPE_SOURCE) {
                    org.eclipse.core.resources.IResource res = wsRoot.findMember(entry.getPath());
                    if (res != null && res.getLocation() != null) {
                        sourceRoots.add(res.getLocation().toOSString());
                    }
                }
            }

            int timeout = Math.min(600, Math.max(30,
                getIntParam(arguments, "timeoutSeconds", 300)));
            org.jawata.mcp.coverage.MutationService.Result r =
                new org.jawata.mcp.coverage.MutationService().run(
                    project.javaProject(), targetClasses, targetTests, sourceRoots, timeout);

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "run_tests");
            data.put("action", "coverage_mutation");
            data.put("targetClasses", targetClasses);
            data.put("targetTests", targetTests);
            data.put("timeMs", r.timeMs);
            if (!r.ran) {
                data.put("state", "no-result");
                data.put("note", r.note);
                data.put("stderrTail", r.stderrTail);
                return ToolResponse.success(data, ResponseMeta.builder().build());
            }
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("killed", r.killed);
            summary.put("survived", r.survived);
            summary.put("timedOutMutations", r.timedOutMutations);
            summary.put("noCoverage", r.noCoverage);
            data.put("summary", summary);
            // The one count a hire gate reads: surviving mutations, i.e. changes no test noticed.
            // Absent on the no-result path above ON PURPOSE — a run that produced nothing must read
            // as "did not answer", never as zero survivors.
            data.put("errorCount", r.survived);
            data.put("survivors", r.survivors);
            if (r.survived > 0) {
                data.put("note", "SURVIVING mutations = code changes no test noticed — each "
                    + "survivor carries a candidate missing-assertion location.");
            }
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(r.killed + r.survived + r.timedOutMutations + r.noCoverage)
                .returnedCount(r.survivors.size()).build());
        } catch (Exception e) {
            log.warn("coverage_mutation failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private void attachPolicy(Map<String, Object> data, Double achievedPercent) {
        Map<String, Object> policy = coverage.store().readPolicy();
        if (policy.isEmpty()) return;
        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("lineThresholdPercent", policy.get("lineThresholdPercent"));
        threshold.put("policyVersion", policy.get("version"));
        threshold.put("waivers", policy.getOrDefault("waivers", List.of()));
        if (achievedPercent != null && policy.get("lineThresholdPercent") instanceof Number n) {
            threshold.put("verdict", achievedPercent >= n.doubleValue() ? "PASS" : "BELOW_THRESHOLD");
            threshold.put("achievedPercent", String.format(java.util.Locale.ROOT,
                "%.1f%%", achievedPercent));
        }
        data.put("threshold", threshold);
    }

    private static String globalLinePercent(org.jawata.mcp.coverage.CoverageModel model) {
        int covered = 0, missed = 0;
        for (org.jawata.mcp.coverage.CoverageModel.ClassCov c : model.classes) {
            if (c.state == org.jawata.mcp.coverage.CoverageModel.State.STALE_BYTES) continue;
            covered += c.linesCovered;
            missed += c.linesMissed;
        }
        return percent(covered, missed);
    }

    /** Map a repo-relative changed path to the analyzed class whose source it is. */
    private static org.jawata.mcp.coverage.CoverageModel.ClassCov classForPath(
            org.jawata.mcp.coverage.CoverageModel model, String path) {
        for (org.jawata.mcp.coverage.CoverageModel.ClassCov c : model.classes) {
            if (c.sourceFile == null) continue;
            int lastDot = c.fqn.lastIndexOf('.');
            String pkgPath = lastDot < 0 ? "" : c.fqn.substring(0, lastDot).replace('.', '/') + "/";
            if (path.endsWith(pkgPath + c.sourceFile)) {
                return c;
            }
        }
        return null;
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
        // status/cancel is where an async caller READS the totals, so it is
        // where a narrowing has to be visible. Putting it only on the start
        // response would have left the numbers and the fact that explains them
        // on two different messages.
        if (session.selection != null) data.put("selection", session.selection);
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
        // Sprint 28g S3, D7d — A RUN THAT DISCOVERED NOTHING IS NOT A PASSING RUN, and until
        // this note existed the two were the same answer. Measured through the built artifact:
        // `run_tests scope=class` on a class find_tests enumerates with FIVE JUnit5 methods
        // answered success with total=0 failed=0 evidenceFinalized=true in 173ms — and the
        // SAME call shape on a class that does not exist at all answered identically. So three
        // states collapsed into one and the one chosen was the green: the class has no tests,
        // the selection never resolved, and the run worked.
        //
        // That is why the sprint being reported on made ZERO run_tests calls and attributed it
        // to a stale note about something else: a tool that cannot say it found nothing is
        // indistinguishable from a tool with nothing to find.
        //
        // The note states the fact and NAMES the possibilities without claiming which — it has
        // the counts but not the selection, and `appendResult` is shared by run/status/cancel.
        // Telling the three apart needs the selection threaded in, which is a signature change
        // on a method with three callers and is recorded as the follow-up rather than guessed
        // at here.
        if (r.total == 0 && r.evidenceFinalized && !r.cancelled && !r.timedOut) {
            summary.put("discoveryNote",
                "NO TESTS WERE DISCOVERED, and this is NOT the same answer as a passing run. "
                    + "A selection naming a class that is absent from the RUNTIME classpath, or "
                    + "whose compiled output is missing or stale, discovers nothing and reports "
                    + "exactly these zeros. Check that the selection resolves and that the "
                    + "project's test output exists before reading this as green.");
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
