package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.coverage.CoverageManifest;
import org.jawata.mcp.coverage.CoverageModel;
import org.jawata.mcp.coverage.CoverageService;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.domain.Findings;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 23 (D3, GATE-1 addition) — <b>coverage_lack</b>: lack of test
 * evidence as a SMELL, evidence-backed only. Reads the FRESHEST VALID
 * coverage artifact; every finding names a symbol whose executable lines
 * have ZERO coverage in that evidence. Stale classes are excluded (their
 * evidence would lie) and an absent/unusable artifact yields an HONEST
 * no-fresh-evidence answer with zero findings — this detector never invents.
 */
public final class CoverageLackDetector implements Detector {

    @Override
    public String kind() {
        return "coverage_lack";
    }

    @Override
    public String description() {
        return "Symbols with NO coverage evidence in the freshest valid coverage artifact "
            + "(run run_tests with coverage=true to produce one). Evidence-backed only: "
            + "absent or stale evidence yields an honest empty answer, never invented findings.";
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        try {
            CoverageService coverage = new CoverageService();
            String artifactId = null;
            CoverageManifest manifest = null;
            for (String id : coverage.store().list()) {
                CoverageManifest m = coverage.store().readManifest(id).orElse(null);
                if (m != null && m.runFinalized) {
                    artifactId = id;
                    manifest = m;
                    break;
                }
            }
            if (artifactId == null) {
                return noEvidence("No finalized coverage artifact exists — run tests with "
                    + "coverage=true first; coverage_lack never invents findings.");
            }
            CoverageModel model = coverage.model(artifactId);
            if (model == null || model.instrumentationFailure) {
                return noEvidence("The freshest artifact (" + artifactId + ") carries no usable "
                    + "execution data — no fresh evidence, no findings.");
            }

            String filePathFilter = arguments != null && arguments.hasNonNull("filePath")
                ? arguments.get("filePath").asText() : null;

            List<Finding> findings = new ArrayList<>();
            List<String> staleExcluded = new ArrayList<>();
            for (CoverageModel.ClassCov clazz : model.classes) {
                if (clazz.state == CoverageModel.State.STALE_BYTES) {
                    staleExcluded.add(clazz.fqn);
                    continue;
                }
                if (filePathFilter != null && (clazz.sourceFile == null
                        || !filePathFilter.endsWith(clazz.sourceFile))) {
                    continue;
                }
                if (clazz.state == CoverageModel.State.NOT_INSTRUMENTED) {
                    findings.add(new Finding("coverage_lack",
                        clazz.sourceFile, -1, -1, "warning",
                        "Class '" + clazz.fqn + "' never loaded in the measured run — "
                            + "NO test exercises it (evidence: " + artifactId + ").",
                        clazz.fqn));
                    continue;
                }
                for (CoverageModel.MethodCov m : clazz.methods) {
                    if (m.state == CoverageModel.State.MISSED) {
                        findings.add(new Finding("coverage_lack",
                            clazz.sourceFile, m.firstLine, -1, "warning",
                            "Method '" + clazz.fqn + "#" + m.name + "' has ZERO covered lines "
                                + "in the freshest evidence (" + artifactId
                                + ") — untested code is a smell.",
                            clazz.fqn + "#" + m.name));
                    }
                }
            }
            ToolResponse response = Findings.toResponse(findings);
            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) response.getData();
            Map<String, Object> evidence = new LinkedHashMap<>();
            evidence.put("artifactId", artifactId);
            evidence.put("createdAt", manifest.createdAt);
            evidence.put("evidenceKind", manifest.evidenceKind);
            data.put("evidence", evidence);
            Map<String, Object> policy = coverage.store().readPolicy();
            if (!policy.isEmpty()) {
                data.put("threshold", Map.of(
                    "lineThresholdPercent", policy.get("lineThresholdPercent"),
                    "policyVersion", policy.get("version"),
                    "waivers", policy.getOrDefault("waivers", List.of())));
            }
            if (!staleExcluded.isEmpty()) {
                data.put("staleExcluded", staleExcluded);
                data.put("staleNote", "These classes changed since the evidence was measured — "
                    + "excluded rather than guessed; re-run with coverage=true.");
            }
            return response;
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private static ToolResponse noEvidence(String note) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", 0);
        data.put("findings", List.of());
        data.put("evidence", "none");
        data.put("note", note);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(0).returnedCount(0).build());
    }
}
