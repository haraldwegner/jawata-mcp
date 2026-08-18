package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.DetectorCatalog;
import org.jawata.mcp.domain.JawataService;
import org.jawata.mcp.domain.IJawataService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.smell.DependencyDetectors;
import org.jawata.mcp.tools.smell.FowlerDetectors;
import org.jawata.mcp.tools.smell.KerievskyDetectors;
import org.jawata.mcp.tools.smell.SolidDetectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntConsumer;
import java.util.function.Supplier;

/**
 * Parametric Smell front door. Its {@code kind} enum and dispatch are a
 * <em>projection</em> of a {@link DetectorCatalog} (Sprint 16b/D): the loaded
 * tool surface is constant while the capability behind it grows by registration.
 * Sprint 17 (Fowler) / 20 (SOLID) add a {@code Detector} (a kind) to the catalog
 * and it appears here automatically — no edit to this class, no new tool.
 *
 * <p>Originally (Sprint 11 Phase D) this hard-coded a switch over eight narrow
 * analyzers; those analyzers are now the built-in detectors registered by
 * {@link QualityDetectors#builtins}.</p>
 */
public class FindQualityIssueTool extends AbstractTool {

    /** Sprint 22a 2.6.1 (#1) — default cap on a family sweep's findings; the full total stays in `count`. */
    private static final int DEFAULT_FINDINGS_LIMIT = 100;

    private final DetectorCatalog catalog;

    /**
     * Build the default catalog: the eight built-in quality detectors plus the
     * Sprint 17 Fowler smell detectors. The live registration
     * ({@code JawataApplication}) uses this ctor, so registering a Fowler kind in
     * {@link FowlerDetectors} surfaces it here automatically — no edit to this
     * class, no new tool.
     */
    public FindQualityIssueTool(Supplier<IJdtService> serviceSupplier) {
        this(new JawataService(serviceSupplier,
            DependencyDetectors.registerInto(
                KerievskyDetectors.registerInto(
                    SolidDetectors.registerInto(
                        FowlerDetectors.registerInto(QualityDetectors.builtins(serviceSupplier)))))));
    }

    /** The seam: project from the supplied service's detector catalog. */
    public FindQualityIssueTool(IJawataService jawata) {
        super(jawata::jdt);
        this.catalog = jawata.detectors();
    }

    @Override
    public String getName() {
        return "find_quality_issue";
    }

    @Override
    public String getDescription() {
        return """
            Run a code-quality analysis or search across the project.

            USAGE: find_quality_issue(kind="<kind>", ...)

            Kinds (most accept an optional filePath; otherwise scan all files):
            - naming         — Java naming-convention violations (PascalCase classes,
                               camelCase methods/fields, UPPER_SNAKE_CASE constants).
            - bugs           — common bug patterns (null deref, ==, mutation in lambda…).
            - unused         — unused private methods and fields.
            - large_classes  — classes exceeding maxMethods/maxFields/maxLines thresholds.
            - circular_deps  — cyclic package or class dependencies.
            - reflection     — Class.forName / Method.invoke / Field.get usage sites.
            - throws         — methods declaring 'throws <query>' (query = exception FQN).
            - catches        — 'catch (<query> ...)' blocks (query = exception FQN).

            Fowler smell kinds (Sprint 17) are also registered — long_method,
            god_class, long_parameter_list, data_clumps, feature_envy,
            message_chains, inappropriate_intimacy, middle_man, primitive_obsession,
            switch_statements, refused_bequest, temporary_field, lazy_class,
            speculative_generality, parallel_inheritance, incomplete_delegation,
            divergent_change, shotgun_surgery; most accept an optional `threshold`.
            See the kind enum for the full list.

            The available kinds are the registered detectors (see the kind enum);
            more analyses may be added without introducing new tools.

            ASYNC FAMILY SWEEPS (v3.0.0): a family sweep can take minutes and
            outlive a client timeout. action=start returns a sweepId at once;
            action=status shows progress (kindsDone/kindsTotal) while running
            and returns the FULL result once finished — retrievable repeatedly,
            so a timed-out client loses nothing. action=cancel stops between
            kinds and status then returns the honest partial (partial: true).

            Examples:
            - find_quality_issue(kind="naming", filePath="path/to/File.java")
            - find_quality_issue(kind="large_classes", maxLines=500)
            - find_quality_issue(kind="throws", query="java.io.IOException")

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", catalog.kinds()); // projected from the catalog
        kind.put("description",
            "Which single analysis to run. See the tool description for what each kind reports. "
                + "Provide `kind` OR `family` (family runs the whole set).");
        properties.put("kind", kind);

        Map<String, Object> family = new LinkedHashMap<>();
        family.put("type", "string");
        family.put("enum", List.of("quality", "fowler", "solid", "kerievsky"));
        family.put("description",
            "Optional family lens (Sprint 20). With no `kind`, runs every detector in the family and "
                + "merges the findings (e.g. family=\"solid\" returns the SOLID set as a unit: dip, isp, "
                + "srp_cohesion, lsp + the tagged incomplete_delegation/refused_bequest/divergent_change/"
                + "shotgun_surgery). With a `kind`, validates the kind belongs to the family.");
        properties.put("family", family);

        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description",
            "Optional. Limit naming/bugs/unused/reflection scans to a single file. Omit for whole-project.");
        properties.put("filePath", filePath);

        Map<String, Object> query = new LinkedHashMap<>();
        query.put("type", "string");
        query.put("description",
            "Required for kind=throws or kind=catches: fully-qualified exception type name (e.g. 'java.io.IOException').");
        properties.put("query", query);

        Map<String, Object> maxMethods = new LinkedHashMap<>();
        maxMethods.put("type", "integer");
        maxMethods.put("description", "kind=large_classes only — class is 'large' above this method count (default 20).");
        properties.put("maxMethods", maxMethods);

        Map<String, Object> maxFields = new LinkedHashMap<>();
        maxFields.put("type", "integer");
        maxFields.put("description", "kind=large_classes only — class is 'large' above this field count (default 10).");
        properties.put("maxFields", maxFields);

        Map<String, Object> maxLines = new LinkedHashMap<>();
        maxLines.put("type", "integer");
        maxLines.put("description", "kind=large_classes only — class is 'large' above this line count (default 300).");
        properties.put("maxLines", maxLines);

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "kind=throws or kind=catches — caps result count (default 100).");
        properties.put("maxResults", maxResults);

        Map<String, Object> threshold = new LinkedHashMap<>();
        threshold.put("type", "integer");
        threshold.put("description",
            "Optional sensitivity threshold for the Fowler smell kinds (Sprint 17), e.g. "
                + "long_method LOC cutoff. Each smell kind documents its threshold meaning; "
                + "omit to use the kind's default.");
        properties.put("threshold", threshold);

        Map<String, Object> includeTests = new LinkedHashMap<>();
        includeTests.put("type", "boolean");
        includeTests.put("description",
            "Fowler smell kinds only — include test sources in the scan. Test-ness comes from "
                + "the source root's test attribute in the loaded model, not from a path or name "
                + "convention, so a flat-layout test bundle is recognised too. "
                + "Default false: test code legitimately has long methods and subject-under-test "
                + "access, so it is excluded to keep findings actionable.");
        properties.put("includeTests", includeTests);

        properties.put("from", Map.of("type", "string",
            "description", "forbidden_edge: the source package prefix (the layer that must not depend outward)."));
        properties.put("forbidden", Map.of("type", "string",
            "description", "forbidden_edge: the package prefix the `from` layer must not depend on."));
        properties.put("baseline", Map.of("type", "string",
            "enum", List.of("save", "diff"),
            "description", "family sweeps only: 'save' snapshots the current findings; 'diff' "
                + "returns {new, fixed, unchanged} vs the saved snapshot (trend over time)."));
        properties.put("summary", Map.of("type", "boolean",
            "description", "Return counts-by-kind + conflicts only (NO findings array) — the "
                + "consumable shape for a broad sweep. Works for a family sweep AND for a single "
                + "`kind`. `count` carries the true finding total and `conflictCount` the true "
                + "conflict total; the conflicts list itself is capped by `limit` and sets "
                + "`conflictsTruncated` when it is."));
        properties.put("excludePaths", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "Optional path substrings; findings (and conflicts) whose filePath contains "
                + "any entry are dropped BEFORE counts/summary/baseline/pagination — e.g. a vendored "
                + "module like a copied upstream source file (v2.8.1)."));
        properties.put("offset", Map.of("type", "integer",
            "description", "Skip the first N findings (pagination; default 0)."));
        properties.put("limit", Map.of("type", "integer",
            "description", "Cap the findings returned, and the conflicts returned under `summary` "
                + "(default " + DEFAULT_FINDINGS_LIMIT + "); the full totals are always in `count` "
                + "and `conflictCount`. A family sweep caps by default; a single `kind` returns "
                + "everything unless you pass this. Prefer `summary` for a broad sweep."));

        schema.put("properties", properties);
        // kind OR family — validated in executeWithService (a static `required` can't express "one of").
        Map<String, Object> action = new LinkedHashMap<>();
        action.put("type", "string");
        action.put("enum", List.of("run", "start", "status", "cancel"));
        action.put("description",
            "run (default) = synchronous. start = run a FAMILY sweep asynchronously (returns a "
                + "sweepId immediately — a sweep can outlive a client timeout; the result stays "
                + "retrievable). status = progress while running, the FULL result once finished "
                + "(repeatable). cancel = honest partial (partial: true).");
        properties.put("action", action);

        Map<String, Object> sweepId = new LinkedHashMap<>();
        sweepId.put("type", "string");
        sweepId.put("description", "Sweep handle from action=start; required for status/cancel.");
        properties.put("sweepId", sweepId);

        schema.put("required", List.of());

        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Sprint 25 Stage 14a (C0-F2 cure): async family sweeps. A family
        // sweep can outlive a client's timeout; before this, the server kept
        // computing and the RESPONSE was silently lost ("an empty result on
        // failure is a lie"). start/status/cancel make the result
        // retrievable and cancellation honest.
        String action = getStringParam(arguments, "action");
        if (action != null && !action.isBlank() && !"run".equals(action)) {
            return handleSweepAction(service, action, arguments);
        }
        String kind = getStringParam(arguments, "kind");
        String family = getStringParam(arguments, "family");
        boolean hasKind = kind != null && !kind.isBlank();
        boolean hasFamily = family != null && !family.isBlank();

        // family without kind: run every detector in the family and merge findings.
        if (!hasKind && hasFamily) {
            // jawata-mcp#10 — REFUSED, in milliseconds, naming the path that works.
            //
            // Measured on a warm resident: `family="fowler"` on a 1040-file
            // Eclipse plug-in timed out twice at the 30 s default, with ONE of
            // eighteen detector kinds taking 10.3 s. The tool advertised a
            // synchronous family sweep it cannot honour at realistic project
            // sizes, and the failure taught the caller nothing — a bare timeout
            // says neither that an async path exists nor that the sweep is
            // still computing a result nobody will ever read.
            //
            // Refusing beats auto-starting: `action=start` returns a sweepId
            // instead of findings, and silently changing WHAT COMES BACK is how
            // a caller ends up parsing a handle as a result. The caller asks
            // for the async path in one word and knows what it will get.
            //
            // It is a smaller project's convenience that is lost here, and
            // that is the trade: a capability that works on 655 files and
            // fails on 1040 is not a capability, it is a size limit nobody
            // documented.
            return ToolResponse.error("SWEEP_REQUIRES_ASYNC",
                "A whole-family sweep runs " + catalog.kinds(family).size()
                    + " detectors and can outlive your client's timeout, so it is not "
                    + "available synchronously. Re-issue this call with action=\"start\" — "
                    + "it returns a sweepId immediately — then poll action=\"status\" with "
                    + "that id for progress and, once finished, the full result. The result "
                    + "stays retrievable, so a timed-out poll loses nothing.",
                "For a synchronous answer, ask for a single `kind` instead of a `family`; "
                    + "those complete well inside the timeout. This family's kinds: "
                    + catalog.kinds(family));
        }
        if (!hasKind) {
            return ToolResponse.invalidParameter("kind",
                "Provide `kind` (one of " + catalog.kinds() + ") or `family` (quality/fowler/solid/kerievsky).");
        }
        if (hasFamily && !catalog.kinds(family).contains(kind)) {
            return ToolResponse.invalidParameter("kind",
                "kind '" + kind + "' is not in family '" + family + "'. That family: " + catalog.kinds(family));
        }
        return catalog.get(kind)
            .map(detector -> filterExcludedPaths(detector.detect(service, arguments), arguments))
            // Sprint 28 (v3.6.4): a single kind honours summary/limit/offset too. It used to
            // return the detector's response untouched, so `summary:true` here was accepted
            // and dropped without a word. capByDefault=false keeps the default shape: paging
            // only happens when the caller asks for it.
            .map(r -> r.isSuccess() ? boundResponse(r, arguments, false) : r)
            .orElseGet(() -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + catalog.kinds()));
    }

    // ==================================================================
    // Sprint 25 Stage 14a — async family sweeps (start / status / cancel)
    // ==================================================================

    /** One background family sweep. Results stay retrievable until evicted. */
    private static final class SweepSession {
        final String id;
        final String family;
        final int kindsTotal;
        final AtomicInteger kindsDone = new AtomicInteger();
        final AtomicBoolean cancelRequested = new AtomicBoolean();
        volatile boolean finished;
        volatile boolean cancelled;
        volatile ToolResponse result;
        final long startedAtMillis = System.currentTimeMillis();

        SweepSession(String id, String family, int kindsTotal) {
            this.id = id;
            this.family = family;
            this.kindsTotal = kindsTotal;
        }
    }

    /** Sessions by id; bounded by {@link #MAX_SWEEP_SESSIONS} (oldest finished evicted). */
    private static final Map<String, SweepSession> SWEEP_SESSIONS = new ConcurrentHashMap<>();
    private static final AtomicLong SWEEP_COUNTER = new AtomicLong();
    private static final int MAX_SWEEP_SESSIONS = 8;

    private ToolResponse handleSweepAction(IJdtService service, String action, JsonNode arguments) {
        switch (action) {
            case "start": {
                String family = getStringParam(arguments, "family");
                if (family == null || family.isBlank()) {
                    return ToolResponse.invalidParameter("family",
                        "action=start runs a FAMILY sweep asynchronously — provide `family`.");
                }
                List<String> kinds = catalog.kinds(family);
                if (kinds.isEmpty()) {
                    return ToolResponse.invalidParameter("family",
                        "Unknown family '" + family + "'. One of: quality, fowler, solid, kerievsky.");
                }
                evictOldestFinishedSweeps();
                String id = "sweep-" + System.currentTimeMillis() + "-" + SWEEP_COUNTER.incrementAndGet();
                SweepSession session = new SweepSession(id, family, kinds.size());
                SWEEP_SESSIONS.put(id, session);
                Thread worker = new Thread(() -> {
                    ToolResponse r;
                    try {
                        r = runFamily(service, family, arguments,
                            session.kindsDone::set, session.cancelRequested);
                        String baseline = getStringParam(arguments, "baseline");
                        if (r.isSuccess() && baseline != null && !baseline.isBlank()
                                && !session.cancelRequested.get()) {
                            r = applyBaseline(service, family, baseline, r);
                        }
                        // jawata-mcp#6 (Sprint 27a Stage 8): store UNSHAPED.
                        // Shaping (summary/limit/offset) happens at STATUS time
                        // with the RETRIEVING call's own arguments — shaping
                        // here froze the start call's view into every later
                        // retrieval, so status ignored summary:true and burst
                        // the client limit while advising the opposite.
                    } catch (Throwable e) {
                        // v3.0.1 (audit F3): Throwable, not RuntimeException — an
                        // Error must not leave the session forever "running".
                        r = ToolResponse.internalError(
                            "async sweep '" + id + "' failed: " + e.getMessage());
                    }
                    // v3.0.1 (audit F1): cancelled = the loop actually stopped
                    // early. A cancel that landed during the LAST detector left a
                    // COMPLETE result — labeling it partial was contradictory.
                    session.cancelled = session.cancelRequested.get()
                        && session.kindsDone.get() < session.kindsTotal;
                    session.result = r;
                    session.finished = true;
                }, "jawata-sweep-" + id);
                worker.setDaemon(true);
                worker.start();
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "find_quality_issue");
                data.put("action", "start");
                data.put("sweepId", id);
                data.put("family", family);
                data.put("kindsTotal", kinds.size());
                data.put("hint", "Poll find_quality_issue(action=status, sweepId=…); the finished "
                    + "result stays retrievable — a client timeout loses nothing.");
                return ToolResponse.success(data,
                    ResponseMeta.builder().totalCount(1).returnedCount(1).build());
            }
            case "status": {
                SweepSession session = sweepFor(arguments);
                if (session == null) {
                    return unknownSweep(arguments);
                }
                if (!session.finished) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("operation", "find_quality_issue");
                    data.put("action", "status");
                    data.put("sweepId", session.id);
                    data.put("state", "running");
                    data.put("family", session.family);
                    data.put("kindsDone", session.kindsDone.get());
                    data.put("kindsTotal", session.kindsTotal);
                    data.put("elapsedMillis", System.currentTimeMillis() - session.startedAtMillis);
                    return ToolResponse.success(data,
                        ResponseMeta.builder().totalCount(1).returnedCount(1).build());
                }
                // Finished (or cancelled): shape the stored result with THIS
                // call's summary/limit/offset (jawata-mcp#6 — status honors
                // them exactly as run does), then wrap the session envelope;
                // repeatable retrieval is the point of the feature.
                ToolResponse r = session.result;
                if (r != null && r.isSuccess()) {
                    r = boundResponse(r, arguments, true);
                }
                if (r != null && r.isSuccess() && r.getData() instanceof Map<?, ?> raw) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("sweepId", session.id);
                    data.put("state", session.cancelled ? "cancelled" : "finished");
                    if (session.cancelled) {
                        data.put("kindsDone", session.kindsDone.get());
                        data.put("kindsTotal", session.kindsTotal);
                        data.put("partial", true);
                    }
                    raw.forEach((k, v) -> data.put(String.valueOf(k), v));
                    return ToolResponse.success(data,
                        ResponseMeta.builder().totalCount(1).returnedCount(1).build());
                }
                return r != null ? r
                    : ToolResponse.internalError("sweep '" + session.id + "' finished without a result");
            }
            case "cancel": {
                SweepSession session = sweepFor(arguments);
                if (session == null) {
                    return unknownSweep(arguments);
                }
                session.cancelRequested.set(true);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "find_quality_issue");
                data.put("action", "cancel");
                data.put("sweepId", session.id);
                data.put("state", session.finished
                    ? (session.cancelled ? "cancelled" : "finished")
                    : "cancel_requested");
                data.put("hint", "action=status returns the honest partial (partial: true) once the "
                    + "worker observes the cancellation between kinds.");
                return ToolResponse.success(data,
                    ResponseMeta.builder().totalCount(1).returnedCount(1).build());
            }
            default:
                return ToolResponse.invalidParameter("action",
                    "Unknown action '" + action + "'. One of: run (default), start, status, cancel.");
        }
    }

    private SweepSession sweepFor(JsonNode arguments) {
        String id = getStringParam(arguments, "sweepId");
        return id == null || id.isBlank() ? null : SWEEP_SESSIONS.get(id);
    }

    private ToolResponse unknownSweep(JsonNode arguments) {
        String id = getStringParam(arguments, "sweepId");
        return ToolResponse.invalidParameter("sweepId",
            (id == null || id.isBlank())
                ? "Provide the sweepId from action=start."
                : "Unknown sweepId '" + id + "' — expired (bounded registry of "
                    + MAX_SWEEP_SESSIONS + ") or never started.");
    }

    private static void evictOldestFinishedSweeps() {
        while (SWEEP_SESSIONS.size() >= MAX_SWEEP_SESSIONS) {
            SWEEP_SESSIONS.values().stream()
                .filter(s -> s.finished)
                .min((a, b) -> Long.compare(a.startedAtMillis, b.startedAtMillis))
                .map(s -> SWEEP_SESSIONS.remove(s.id))
                .orElseGet(() -> {
                    // Nothing finished to evict: refuse silently-unbounded growth
                    // by evicting the OLDEST running one's record (its worker
                    // finishes into the void — recorded in the hint).
                    return SWEEP_SESSIONS.values().stream()
                        .min((a, b) -> Long.compare(a.startedAtMillis, b.startedAtMillis))
                        .map(s -> SWEEP_SESSIONS.remove(s.id))
                        .orElse(null);
                });
            if (SWEEP_SESSIONS.isEmpty()) {
                break;
            }
        }
    }

    /** v2.8.1 (dogfood 2026-07-11): parse the optional excludePaths array. */
    private static List<String> excludePathsOf(JsonNode arguments) {
        List<String> excludes = new ArrayList<>();
        if (arguments != null && arguments.get("excludePaths") != null
                && arguments.get("excludePaths").isArray()) {
            arguments.get("excludePaths").forEach(n -> {
                String s = n.asText("");
                if (!s.isBlank()) excludes.add(s);
            });
        }
        return excludes;
    }

    /**
     * v2.8.1 (dogfood 2026-07-11): drop findings whose filePath contains any
     * excludePaths entry. The trigger: a fowler sweep on jawata-mcp itself was
     * flooded by the vendored 12k-line ProblemReporter copy in
     * org.jawata.jdtpatch — correct findings, useless signal. Applied to
     * single-kind responses here; the family path filters the merged list
     * before conflicts/counts so every derived number stays consistent.
     */
    @SuppressWarnings("unchecked")
    private static ToolResponse filterExcludedPaths(ToolResponse r, JsonNode arguments) {
        List<String> excludes = excludePathsOf(arguments);
        if (excludes.isEmpty() || r == null || !r.isSuccess()
                || !(r.getData() instanceof Map<?, ?> raw)
                || !(raw.get("findings") instanceof List<?> fs)) {
            return r;
        }
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) raw);
        List<Object> kept = keepUnexcluded((List<Object>) fs, excludes);
        data.put("findings", kept);
        if (data.get("count") instanceof Number) {
            data.put("count", kept.size());
        }
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(kept.size()).returnedCount(kept.size()).build());
    }

    private static List<Object> keepUnexcluded(List<Object> findings, List<String> excludes) {
        List<Object> kept = new ArrayList<>(findings.size());
        for (Object o : findings) {
            if (o instanceof Map<?, ?> f && f.get("filePath") instanceof String fp
                    && excludes.stream().anyMatch(fp::contains)) {
                continue;
            }
            kept.add(o);
        }
        return kept;
    }

    /**
     * Sprint 25 Stage 14a: the async worker's variant — {@code progress}
     * receives the completed-kind count after each detector; a set
     * {@code cancelRequested} stops BETWEEN kinds (honest partial, never a
     * torn detector run).
     */
    @SuppressWarnings("unchecked")
    private ToolResponse runFamily(IJdtService service, String family, JsonNode arguments,
                                   IntConsumer progress, AtomicBoolean cancelRequested) {
        List<String> kinds = catalog.kinds(family);
        if (kinds.isEmpty()) {
            return ToolResponse.invalidParameter("family",
                "Unknown family '" + family + "'. One of: quality, fowler, solid, kerievsky.");
        }
        // Longest root first, so a project nested inside another wins its own
        // files. Built ONCE per sweep rather than per finding: a sweep over a
        // 29-project workspace produces thousands of rows.
        // MEASURED, not assumed: a finding's `filePath` is WORKSPACE-RELATIVE
        // ("org.jawata.core/src/org/jawata/core/ProjectKeys.java"), while
        // `projectRoot` is absolute. Matching one against the other is what the
        // first version of this did, and it attributed nothing at all — 0 of 92
        // rows — while looking entirely reasonable. The key that actually
        // matches is the project directory's own NAME, which is that relative
        // path's first segment.
        List<Map.Entry<String, String>> owners = service.allProjects().stream()
            .filter(p -> p.projectRoot() != null && p.projectRoot().getFileName() != null
                && p.projectKey() != null)
            .map(p -> Map.entry(p.projectRoot().getFileName().toString(), p.projectKey()))
            // Longest name first: "core" must not claim "core-tests".
            .sorted((a, b) -> Integer.compare(b.getKey().length(), a.getKey().length()))
            .toList();
        // With exactly one project loaded, every finding is that project's by
        // construction — the paths may not carry a project segment at all, and
        // saying nothing would be less true than saying the only thing there is
        // to say.
        String soleProject = owners.size() == 1 ? owners.get(0).getValue() : null;
        List<Object> merged = new ArrayList<>();
        int done = 0;
        for (String k : kinds) {
            if (cancelRequested != null && cancelRequested.get()) {
                break;
            }
            ToolResponse r = catalog.get(k).map(d -> d.detect(service, arguments)).orElse(null);
            // Sprint 28 C4 (audit finding 7) — this read data.get("findings")
            // ONLY. Six of the analyzers return their list under a different
            // key (unusedItems, violations, issues, cycles, largeClasses), so
            // they contributed NOTHING to any family sweep and said nothing
            // about it: large_classes reported 101 violations when named by
            // kind and 0 through family="quality". A sweep that structurally
            // cannot hear from a third of its detectors while answering "489
            // findings across 2 kinds" is the same lie as an empty result from
            // a failed scan. resultListKey() is the resolution the summary path
            // already used; the merge now uses it too.
            if (r != null && r.isSuccess() && r.getData() instanceof Map<?, ?> raw) {
                @SuppressWarnings("unchecked")
                Map<String, Object> data = (Map<String, Object>) raw;
                if (data.get(resultListKey(data)) instanceof List<?> fs) {
                    // Entries from the non-"findings" shapes carry no `kind` —
                    // merging them raw makes the sweep non-empty but leaves
                    // byKind counting them under null and the reader unable to
                    // say WHICH detector spoke. Stamp the kind we just ran.
                    for (Object entry : fs) {
                        merged.add(withProject(withKind(entry, k), owners, soleProject));
                    }
                }
            }
            done++;
            if (progress != null) {
                progress.accept(done);
            }
        }
        // v2.8.1: excludePaths filters BEFORE conflicts/counts, so prevalence,
        // conflict list, summary, baseline and pagination all see one set.
        merged = keepUnexcluded(merged, excludePathsOf(arguments));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("family", family);
        out.put("kinds", kinds);
        out.put("count", merged.size());
        out.put("findings", merged);
        out.put("conflicts", conflicts(merged));
        return ToolResponse.success(out, ResponseMeta.builder()
            .totalCount(merged.size()).returnedCount(merged.size()).build());
    }

    /**
     * Sprint 22a R.1 — the multi-catalog conflict seam. Where &ge;2 distinct detectors
     * flag ONE location ({@code filePath|line}), surface the arbitration INPUTS — the
     * detector kinds, their families, and a neutral prevalence signal — and decide
     * NOTHING. Which cure wins is design intent (the caller's), not a code property jawata
     * can read; jawata only makes the collision legible.
     */
    private List<Map<String, Object>> conflicts(List<Object> merged) {
        Map<String, java.util.LinkedHashSet<String>> kindsAt = new LinkedHashMap<>();
        Map<String, Object[]> locOf = new LinkedHashMap<>();
        Map<String, Integer> prevalence = new LinkedHashMap<>();
        for (Object o : merged) {
            if (!(o instanceof Map<?, ?> f)) {
                continue;
            }
            Object kind = f.get("kind");
            Object file = f.get("filePath");
            Object line = f.get("line");
            if (kind == null || file == null || line == null) {
                continue;
            }
            prevalence.merge(String.valueOf(kind), 1, Integer::sum);
            String loc = file + "|" + line;
            kindsAt.computeIfAbsent(loc, k -> new java.util.LinkedHashSet<>()).add(String.valueOf(kind));
            locOf.putIfAbsent(loc, new Object[]{file, line});
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map.Entry<String, java.util.LinkedHashSet<String>> e : kindsAt.entrySet()) {
            if (e.getValue().size() < 2) {
                continue; // a conflict needs >=2 distinct detectors at one location
            }
            List<String> detectors = new ArrayList<>(e.getValue());
            java.util.LinkedHashSet<String> families = new java.util.LinkedHashSet<>();
            Map<String, Integer> byPrevalence = new LinkedHashMap<>();
            for (String k : detectors) {
                families.addAll(catalog.familiesOf(k));
                byPrevalence.put(k, prevalence.getOrDefault(k, 0));
            }
            Map<String, Object> conflict = new LinkedHashMap<>();
            conflict.put("filePath", locOf.get(e.getKey())[0]);
            conflict.put("line", locOf.get(e.getKey())[1]);
            conflict.put("detectors", detectors);
            conflict.put("families", new ArrayList<>(families));
            Map<String, Object> signal = new LinkedHashMap<>();
            signal.put("prevalence", byPrevalence);
            signal.put("note", "project-wide prevalence of each conflicting kind — a weak INPUT, "
                + "not a decision; jawata arbitrates nothing");
            conflict.put("conventionSignal", signal);
            conflict.put("decision", null);
            out.add(conflict);
        }
        return out;
    }

    /**
     * Sprint 22a 2.6.1 (#1) — bound a response for MCP consumers. A sweep on a large repo
     * produces thousands of findings (222k+ chars) — unconsumable inline.
     * {@code summary=true} returns counts-by-kind + the conflicts (what a broad sweep wants);
     * otherwise the findings are paged. Applied AFTER the baseline branch, so trend diffing
     * still sees the full finding set.
     *
     * <p>Sprint 28 (v3.6.4), from the Cursor dogfood of v3.6.3. Two defects, both of them a
     * response that does not do what the caller asked:</p>
     * <ul>
     *   <li>This ran for FAMILY sweeps only, so {@code summary=true} on a single {@code kind}
     *       was accepted and then silently dropped — the caller asked for counts and got the
     *       full findings array with nothing saying the flag had been ignored. The parameter
     *       is documented as family-only, which makes the call out of contract; a silently
     *       discarded parameter is still the wrong answer to it. It is honoured now, for any
     *       shape. {@code capByDefault} keeps the DEFAULT unchanged for a single kind: it
     *       pages only when the caller passes {@code limit}/{@code offset}, so no existing
     *       caller silently starts losing findings.</li>
     *   <li>The summary itself was not consumable. It correctly dropped {@code findings} and
     *       then returned the {@code conflicts} list in full — 295 entries, ~111k chars,
     *       over an MCP client's result limit. The one shape built to make a broad sweep
     *       readable was the shape that could not be read. Conflicts are now paged by the
     *       same {@code limit}, with {@code conflictCount} carrying the true total.</li>
     * </ul>
     */
    /**
     * The result-list keys a quality detector may return, in probe order.
     *
     * <p>Sprint 28, from the v3.6.4 dogfood: {@link #boundResponse} read {@code findings} and
     * nothing else, so {@code summary=true} and paging were silently inert for every detector
     * that names its list differently — {@code unusedItems} ({@code unused}),
     * {@code violations} ({@code naming}, {@code large_classes}, nullness),
     * {@code issues} ({@code bugs}) and {@code cycles} ({@code circular_deps}). The caller
     * asked for counts and received the full array with nothing saying the flag was ignored.
     * The v3.6.4 fix covered ONE of the six shapes, which is the same defect a size smaller.</p>
     *
     * <p>Order matters only for a response carrying two lists at once, which no detector does
     * today; {@code findings} stays first because it is the common shape.</p>
     *
     * <p>These are read from each tool's own {@code data.put} call, NOT from a text search:
     * the first attempt at this list was assembled by searching for the literal
     * {@code "violations"} and wrongly mapped {@code large_classes} to it — that hit is a
     * NESTED field inside a {@code largeClasses} entry, and the top-level key is
     * {@code largeClasses}. The test that guards this derives the key from the response
     * instead of trusting the list, which is what caught the mistake.</p>
     */
    private static final List<String> RESULT_LIST_KEYS =
        List.of("findings", "unusedItems", "violations", "issues", "cycles", "largeClasses");

    /**
     * Ensure a merged entry names the detector it came from.
     *
     * <p>Sprint 28 C4 (audit finding 7). Findings built from {@code Finding}
     * already carry {@code kind}; the narrow analyzers' entries
     * ({@code largeClasses}, {@code violations}, {@code cycles}, …) do not, and
     * an unlabelled entry in a family sweep is only marginally better than a
     * dropped one — the reader still cannot tell which detector spoke, and
     * {@code byKind} buckets it under null.</p>
     */
    /**
     * Name the project a finding came from.
     *
     * <p>A whole-workspace sweep merges findings from every loaded project into
     * one list and, before this, said nothing about which project each row
     * belonged to — while {@code compile_workspace} has emitted
     * {@code sourceProject} on every diagnostic all along. On the 29-project
     * workspace this plan was measured against, that is a flat list of
     * thousands of rows with no way to say "these are clicktrader's", so the
     * reader has to reconstruct ownership from path prefixes by hand — the
     * work the loaded model already did.</p>
     *
     * <p>Resolved by longest matching project root, so a project nested inside
     * another claims its own files. A row whose path matches nothing keeps
     * {@code null} rather than being assigned to the nearest guess: an
     * unattributed finding is honest, a misattributed one sends someone to the
     * wrong repository.</p>
     */
    @SuppressWarnings("unchecked")
    private static Object withProject(Object entry, List<Map.Entry<String, String>> owners,
            String soleProject) {
        if (owners.isEmpty() || !(entry instanceof Map<?, ?> m)) {
            return entry;
        }
        Map<String, Object> row = (Map<String, Object>) m;
        if (row.get("sourceProject") instanceof String s && !s.isBlank()) {
            return row;
        }
        if (!(row.get("filePath") instanceof String path) || path.isBlank()) {
            return row;
        }
        String key = null;
        for (Map.Entry<String, String> owner : owners) {
            String dir = owner.getKey();
            if (path.equals(dir) || path.startsWith(dir + "/") || path.startsWith(dir + "\\")) {
                key = owner.getValue();
                break;
            }
        }
        if (key == null) {
            key = soleProject;
        }
        if (key == null) {
            return row;
        }
        Map<String, Object> stamped = new LinkedHashMap<>(row);
        stamped.put("sourceProject", key);
        return stamped;
    }

    @SuppressWarnings("unchecked")
    private static Object withKind(Object entry, String kind) {
        if (!(entry instanceof Map<?, ?> m)) {
            return entry;
        }
        Map<String, Object> row = (Map<String, Object>) m;
        if (row.get("kind") instanceof String s && !s.isBlank()) {
            return row;
        }
        // containsKey, NOT just an instanceof check: a row carrying `kind` with
        // a null or blank value would otherwise be stamped and then have the
        // null written straight back over it by putAll (C4 audit round 2, N2).
        // No detector emits that today; the guard should not read as total when
        // it is not.
        row = new LinkedHashMap<>(row);
        row.remove("kind");
        Map<String, Object> stamped = new LinkedHashMap<>();
        stamped.put("kind", kind);
        stamped.putAll(row);
        return stamped;
    }

    private static String resultListKey(Map<String, Object> data) {
        for (String key : RESULT_LIST_KEYS) {
            if (data.get(key) instanceof List<?>) {
                return key;
            }
        }
        return "findings";
    }

    @SuppressWarnings("unchecked")
    private ToolResponse boundResponse(ToolResponse full, JsonNode args, boolean capByDefault) {
        if (!(full.getData() instanceof Map<?, ?> raw)) {
            return full;
        }
        Map<String, Object> data = new LinkedHashMap<>((Map<String, Object>) raw);
        String listKey = resultListKey(data);
        List<Object> findings = data.get(listKey) instanceof List<?> l
            ? new ArrayList<>((List<Object>) l) : new ArrayList<>();
        int total = findings.size();

        int offset = Math.max(0, args.path("offset").asInt(0));
        boolean callerGaveLimit = args.hasNonNull("limit") && args.path("limit").asInt(0) > 0;
        int limit = callerGaveLimit ? args.path("limit").asInt(DEFAULT_FINDINGS_LIMIT)
            : DEFAULT_FINDINGS_LIMIT;

        if (args.path("summary").asBoolean(false)) {
            Map<String, Integer> byKind = new LinkedHashMap<>();
            for (Object o : findings) {
                if (o instanceof Map<?, ?> f && f.get("kind") != null) {
                    byKind.merge(String.valueOf(f.get("kind")), 1, Integer::sum);
                }
            }
            List<Object> conflicts = data.get("conflicts") instanceof List<?> c
                ? new ArrayList<>((List<Object>) c) : new ArrayList<>();
            int conflictTotal = conflicts.size();
            List<Object> conflictPage = conflicts.subList(0, Math.min(limit, conflictTotal));
            data.remove(listKey);
            data.put("summary", true);
            data.put("resultKey", listKey);
            // Sprint 28: always carry the total. byKind is empty when the entries have no
            // `kind` field — true of several non-findings shapes — and a summary whose only
            // number is an empty map answers nothing at all.
            data.put("count", total);
            data.put("byKind", byKind);
            data.put("conflictCount", conflictTotal);
            data.put("conflicts", new ArrayList<>(conflictPage));
            if (conflictTotal > conflictPage.size()) {
                data.put("conflictsTruncated", true);
                data.put("hint", "showing " + conflictPage.size() + " of " + conflictTotal
                    + " conflicts — conflictCount is the true total; raise `limit` for more");
            }
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(total).returnedCount(0).build());
        }

        // A single kind returned every finding before this existed; keep that unless the
        // caller opted into paging. Only a family sweep caps by default.
        if (!capByDefault && !callerGaveLimit && offset == 0) {
            return full;
        }
        if (limit <= 0) {
            limit = DEFAULT_FINDINGS_LIMIT;
        }
        int from = Math.min(offset, total);
        int to = Math.min(from + limit, total);
        List<Object> page = new ArrayList<>(findings.subList(from, to));
        boolean truncated = to < total || from > 0;
        data.put("count", total);
        data.put("offset", offset);
        data.put("limit", limit);
        data.put("returnedCount", page.size());
        data.put("truncated", truncated);
        data.put(listKey, page);
        if (truncated) {
            data.put("hint", "showing " + listKey + " " + from + "–" + to + " of " + total
                + " — use summary:true for counts+conflicts only, or offset/limit to page");
        }
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(total).returnedCount(page.size()).build());
    }

    /**
     * Sprint 22a P2-c — baseline / trend diffing. Each finding is keyed by
     * {@code kind|filePath|line}. {@code baseline=save} writes the current keys
     * to {@code <root>/.jawata/quality-baseline-<family>.json}; {@code baseline=diff}
     * returns {@code {new, fixed, unchanged}} vs the saved snapshot.
     */
    @SuppressWarnings("unchecked")
    private ToolResponse applyBaseline(IJdtService service, String family, String action,
                                       ToolResponse familyResponse) {
        Map<String, Object> data = (Map<String, Object>) familyResponse.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        java.util.LinkedHashSet<String> current = new java.util.LinkedHashSet<>();
        for (Map<String, Object> f : findings) {
            current.add(f.get("kind") + "|" + f.get("filePath") + "|" + f.get("line"));
        }
        java.nio.file.Path snapshot = service.getProjectRoot()
            .resolve(".jawata").resolve("quality-baseline-" + family + ".json");

        if ("save".equals(action)) {
            try {
                java.nio.file.Files.createDirectories(snapshot.getParent());
                java.nio.file.Files.write(snapshot,
                    (String.join("\n", current) + "\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception e) {
                return ToolResponse.internalError(e);
            }
            Map<String, Object> out = new LinkedHashMap<>(data);
            out.put("baseline", "saved");
            out.put("baselineSize", current.size());
            return ToolResponse.success(out);
        }

        // diff against the saved snapshot (empty when absent → everything is new).
        java.util.LinkedHashSet<String> previous = new java.util.LinkedHashSet<>();
        try {
            if (java.nio.file.Files.exists(snapshot)) {
                for (String line : java.nio.file.Files.readAllLines(snapshot,
                        java.nio.charset.StandardCharsets.UTF_8)) {
                    if (!line.isBlank()) {
                        previous.add(line.strip());
                    }
                }
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
        List<String> added = new ArrayList<>();
        int unchanged = 0;
        for (String k : current) {
            if (previous.contains(k)) {
                unchanged++;
            } else {
                added.add(k);
            }
        }
        List<String> fixed = new ArrayList<>();
        for (String k : previous) {
            if (!current.contains(k)) {
                fixed.add(k);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("family", family);
        out.put("baseline", "diff");
        out.put("newCount", added.size());
        out.put("fixedCount", fixed.size());
        out.put("unchangedCount", unchanged);
        out.put("new", added);
        out.put("fixed", fixed);
        return ToolResponse.success(out);
    }
}
