package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TWO FOWLER SMELLS WHOSE FINDER ALREADY SHIPPED.
 *
 * <p>Fowler's <b>Loops</b> and <b>Data Class</b> were both listed as undetected, and
 * neither needed a new analysis. {@code find_modernization} has scanned for
 * accumulation loops that could become a pipeline, and for classes that are only
 * fields and accessors, since Sprint 15 — behind a tool a reader has to already know
 * to run, keyed by a MODERNIZATION name rather than by the smell's name. A sweep
 * looking for smells found neither.</p>
 *
 * <p>So these adapt rather than re-implement. Writing a second Data Class detector
 * beside {@code class_to_record} would have produced two answers to one question, which
 * is the state Sprint 28d spent a stage removing from the cure tables.</p>
 *
 * <h2>The rows are RESHAPED, and that is not cosmetic</h2>
 *
 * <p>{@code find_modernization} answers with a {@code candidates} list.
 * {@code find_quality_issue} merges six list shapes and {@code candidates} is not one
 * of them, so a straight pass-through would have made {@code summary=true}, paging and
 * the baseline silently inert for exactly these two kinds — the defect its own
 * RESULT_LIST_KEYS note records, one size smaller. Adding a seventh key would fix this
 * pair and leave the next one to repeat it, so each candidate becomes a normal finding
 * instead: {@code kind}, {@code filePath}, {@code line}, {@code message}, {@code symbol}.</p>
 *
 * <p>Lives in this package for the same reason {@link QualityDetectors} does — to reach
 * {@link AbstractTool#executeWithService}.</p>
 */
public final class ModernizationSmells {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The sweep's own default page size — the number a full page is measured against. */
    private static final int DEFAULT_MAX_RESULTS = 200;

    private ModernizationSmells() {
    }

    /**
     * Fowler — <b>Loops</b>. An accumulation loop hides what it is accumulating behind
     * how it walks. Cure: Replace Loop with Pipeline.
     */
    public static Detector loops() {
        return adapt("loops", "loop_to_stream",
            "Loops — an enhanced-for that accumulates into a collection or a total, where "
                + "the walking hides what is being computed. Points to Replace Loop with "
                + "Pipeline (map/filter/collect). The same scan as "
                + "find_modernization(kind=loop_to_stream), reported as the smell it is.",
            "Consider Replace Loop with Pipeline.");
    }

    /**
     * Fowler — <b>Data Class</b>. A class that is fields and accessors and nothing else
     * is being operated on by everyone else; either give it behaviour or make it a
     * record and stop pretending it has any.
     */
    public static Detector dataClass() {
        return adapt("data_class", "class_to_record",
            "Data Class — a class that is only fields, accessors and the generated "
                + "equals/hashCode/toString, with no behaviour of its own. Either move the "
                + "behaviour that operates on it INTO it, or declare what it already is and "
                + "make it a record. The same scan as "
                + "find_modernization(kind=class_to_record), reported as the smell it is.",
            "Either give this class the behaviour that operates on it, or make it a record.");
    }

    /**
     * One modernization sweep, presented as a smell kind.
     *
     * @param kind              the smell name a reader asks for
     * @param modernizationKind the sweep that answers it
     * @param description       what the kind reports, for the tool's own kind list
     * @param cure              the sentence appended to every finding
     */
    private static Detector adapt(String kind, String modernizationKind, String description,
                                  String cure) {
        return new Detector() {
            @Override
            public String kind() {
                return kind;
            }

            @Override
            public String description() {
                return description;
            }

            @Override
            public ToolResponse detect(IJdtService service, JsonNode arguments) {
                ToolResponse raw = new FindModernizationTool(() -> service)
                    .executeWithService(service, withModernizationKind(arguments));
                if (!raw.isSuccess() || !(raw.getData() instanceof Map<?, ?> data)) {
                    return raw;
                }
                return ToolResponse.success(asFindings(service, data, arguments),
                    ResponseMeta.builder()
                    .totalCount(countOf(data))
                    .returnedCount(countOf(data))
                    .build());
            }

            /**
             * Replace the caller's `kind` — which names the SMELL — with the sweep's own.
             * A copy, never the caller's node: the same arguments reach other detectors
             * in a family sweep, and rewriting them in place would hand the next one a
             * kind it never asked for.
             */
            private JsonNode withModernizationKind(JsonNode arguments) {
                // Only an ObjectNode can carry the optional parameters (projectKey,
                // maxResults); anything else carries nothing worth copying, and the
                // sweep needs only the kind. Written this way rather than walking the
                // node's fields because that walk is deprecated and this branch would
                // be the only caller of it.
                ObjectNode copy = arguments instanceof ObjectNode existing
                    ? existing.deepCopy() : MAPPER.createObjectNode();
                copy.put("kind", modernizationKind);
                return copy;
            }

            private int countOf(Map<?, ?> data) {
                return data.get("candidates") instanceof List<?> list ? list.size() : 0;
            }

            @SuppressWarnings("unchecked")
            private Map<String, Object> asFindings(IJdtService service, Map<?, ?> data,
                                                   JsonNode arguments) {
                List<Map<String, Object>> findings = new ArrayList<>();
                if (data.get("candidates") instanceof List<?> candidates) {
                    for (Object o : candidates) {
                        if (o instanceof Map<?, ?> c) {
                            findings.add(asFinding(service, (Map<String, Object>) c));
                        }
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("operation", "find_quality_issue");
                out.put("kind", kind);
                out.put("count", findings.size());
                // A CAP IS NOT A COUNT, and this one bites at 200 by default. Measured on
                // this repository: `loops` answered 200 — exactly the sweep's own limit —
                // and reporting that as the count would state a number the scan never
                // established. When the page is full, the page size is a floor and the
                // response says so rather than letting a reader read a total.
                int limit = arguments != null && arguments.has("maxResults")
                    ? arguments.get("maxResults").asInt(DEFAULT_MAX_RESULTS)
                    : DEFAULT_MAX_RESULTS;
                if (findings.size() >= limit) {
                    out.put("truncated", true);
                    out.put("countIsAtLeast", findings.size());
                    out.put("note", "This is a full page of " + limit + " and not a total:"
                        + " the underlying sweep stops there. Raise maxResults, or scope"
                        + " the scan, before reading `count` as how many there are.");
                }
                out.put("findings", findings);
                return out;
            }

            private Map<String, Object> asFinding(IJdtService service,
                                                  Map<String, Object> candidate) {
                String path = String.valueOf(candidate.get("filePath"));
                Map<String, Object> finding = new LinkedHashMap<>();
                finding.put("kind", kind);
                finding.put("filePath", path);
                finding.put("line", candidate.get("line"));
                finding.put("severity", "info");
                Object suggestion = candidate.get("suggestion");
                finding.put("message",
                    (suggestion == null ? "" : suggestion + " ") + cure);
                finding.put("symbol", qualifiedTypeOf(service, path));
                // The sketch of what it would become — the half a reader judges the
                // suggestion by, and dropping it would make the finding weaker than the
                // sweep it came from.
                finding.put("snippet", candidate.get("snippet"));
                return finding;
            }

            /**
             * The top-level type's name, from the file name.
             *
             * <p>A candidate carries a file and a line and no symbol, and there is no
             * symbol to be had without re-analysing what the sweep already walked. Java
             * requires a public top-level type to match its file name, so this is
             * derived rather than guessed — and it is the coarser answer, which is why
             * the line stays on the finding beside it.</p>
             */
            /**
             * The type name QUALIFIED BY ITS PACKAGE, read off the compilation unit.
             *
             * <p>The javadoc below used to end "there is no symbol to be had without
             * re-analysing what the sweep already walked", and that was true of the
             * TYPE. It is not true of the PACKAGE: the unit knows its own, so the
             * qualified name costs one model lookup and no re-analysis. Without it the
             * symbol was `PipelineTargets` — a name shared by every package that has
             * one, which no door can resolve, so the cure this finding names could not
             * be pointed at the thing it found.</p>
             */
            private String qualifiedTypeOf(IJdtService service, String path) {
                String simple = typeNameOf(path);
                if (simple == null || service == null) {
                    return simple;
                }
                try {
                    org.eclipse.jdt.core.ICompilationUnit unit =
                        service.getCompilationUnit(java.nio.file.Path.of(path));
                    if (unit == null || unit.getParent() == null) {
                        return simple;
                    }
                    String pkg = unit.getParent().getElementName();
                    return pkg == null || pkg.isBlank() ? simple : pkg + "." + simple;
                } catch (RuntimeException e) {
                    // A path the model cannot resolve degrades to the simple name, which
                    // renders CONSIDER downstream and says the address was not usable —
                    // rather than throwing inside a sweep over hundreds of files.
                    return simple;
                }
            }

            private String typeNameOf(String path) {
                if (path == null || path.isBlank()) {
                    return null;
                }
                String file = path.replace('\\', '/');
                file = file.substring(file.lastIndexOf('/') + 1);
                return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
            }
        };
    }
}
