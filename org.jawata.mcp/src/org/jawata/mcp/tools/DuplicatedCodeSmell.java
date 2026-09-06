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
 * FOWLER'S <b>DUPLICATED CODE</b>, WHOSE FINDER ALREADY SHIPPED.
 *
 * <p>{@code find_duplicate_code} has grouped structurally-identical methods by normalized
 * token sequence since Sprint 14b. It answers with GROUPS — a shape a smell sweep does
 * not read, keyed by a name a reader has to already know. So the smell was listed as
 * covered and no sweep for smells returned it.</p>
 *
 * <p>This ADAPTS that finder rather than re-implementing it, for the reason
 * {@link ModernizationSmells} gives for its own two: a second clone detector beside the
 * first would be two answers to one question, which is the state Sprint 28d spent a stage
 * removing from the cure tables. The finder is unchanged.</p>
 *
 * <h2>One finding per INSTANCE, not per group</h2>
 *
 * <p>A group is an aggregate over the workspace and has no single place. Every cure this
 * smell routes to is pointed at a method, so the finding is the unit a cure can be run
 * from: one per clone instance, each naming the group it belongs to and how many others
 * are in it. Reporting one finding per group would have named a place for one instance
 * and left the rest unaddressable.</p>
 *
 * <h2>The group id rides in the MESSAGE, and that is deliberate</h2>
 *
 * <p>{@code extract kind=replace_inline_code} takes a {@code cloneGroupId}, which is not a
 * place in a file and so is not something {@code CodeAddress} can carry. The cure declares
 * it in its own {@code needs[]} — the agent supplies it — and the finding therefore has to
 * SAY it. It is in the message rather than in a new field on {@code Finding}, because a
 * field would change the finding wire shape for every kind to serve one, and the agent
 * reads the message either way.</p>
 */
public final class DuplicatedCodeSmell {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * How many groups to ask the finder for.
     *
     * <p>The finder pages at 20 GROUPS by default, which is a sensible ceiling for a
     * human reading clone groups and a poor one for a sweep that must not silently
     * report a page as a total. A smell sweep asks for everything and lets its own
     * {@code maxResults} do the capping, so the truncation a reader sees is the sweep's
     * and is labelled as such.</p>
     */
    private static final int GROUPS_ASKED_FOR = 100_000;

    private DuplicatedCodeSmell() {
    }

    /**
     * Fowler — <b>Duplicated Code</b>. The same structure written more than once; every
     * later change has to find all the copies.
     */
    public static Detector detector() {
        return new Detector() {
            @Override
            public String kind() {
                return "duplicated_code";
            }

            @Override
            public String description() {
                return "Duplicated Code — methods with the same STRUCTURE under different"
                    + " identifier names, which a text search cannot see. The same scan as"
                    + " find_duplicate_code, reported as the smell it is: one finding per"
                    + " clone, each naming its group so a cure can be pointed at the group"
                    + " as well as at the method.";
            }

            @Override
            public ToolResponse detect(IJdtService service, JsonNode arguments) {
                ToolResponse raw = new FindDuplicateCodeTool(() -> service)
                    .executeWithService(service, forTheFinder(arguments));
                if (!raw.isSuccess() || !(raw.getData() instanceof Map<?, ?> data)) {
                    return raw;
                }
                Map<String, Object> out = asFindings(service, data, arguments);
                int count = (int) out.get("count");
                return ToolResponse.success(out, ResponseMeta.builder()
                    .totalCount(count)
                    .returnedCount(count)
                    .build());
            }

            /**
             * The finder's arguments, built from the sweep's.
             *
             * <p>A COPY, never the caller's node: the same arguments reach every other
             * detector in a family sweep, and rewriting them in place would hand the next
             * one parameters it never asked for — the trap {@link ModernizationSmells}
             * records for the same reason. {@code projectKey} is the one parameter both
             * sides spell the same and it is carried through; {@code kind} is dropped,
             * because to the finder it would name nothing.</p>
             */
            private JsonNode forTheFinder(JsonNode arguments) {
                ObjectNode copy = MAPPER.createObjectNode();
                if (arguments != null && arguments.hasNonNull("projectKey")) {
                    copy.put("projectKey", arguments.get("projectKey").asText());
                }
                copy.put("limit", GROUPS_ASKED_FOR);
                return copy;
            }

            @SuppressWarnings("unchecked")
            private Map<String, Object> asFindings(IJdtService service, Map<?, ?> data,
                                                   JsonNode arguments) {
                List<Map<String, Object>> findings = new ArrayList<>();
                if (data.get("groups") instanceof List<?> groups) {
                    for (Object g : groups) {
                        if (g instanceof Map<?, ?> group) {
                            addGroup(service, (Map<String, Object>) group, findings);
                        }
                    }
                }
                Map<String, Object> out = new LinkedHashMap<>();
                out.put("operation", "find_quality_issue");
                out.put("kind", "duplicated_code");
                out.put("count", findings.size());
                // What the scan LOOKED AT travels with the count, because the finder
                // refuses to let a zero read as a clean bill of health and this must not
                // undo that by dropping the half that says so. The key names are the
                // finder's own (ScanReport.describe), and the conditional ones are absent
                // when nothing went wrong — which is why each is copied only if present.
                for (String key : List.of("projectsScanned", "methodsExamined",
                        "methodsUnreadable", "projectsSkipped", "projectsSkippedReason",
                        "scanIncomplete", "failures")) {
                    if (data.get(key) != null) {
                        out.put(key, data.get(key));
                    }
                }
                out.put("findings", findings);
                return out;
            }

            @SuppressWarnings("unchecked")
            private void addGroup(IJdtService service, Map<String, Object> group,
                                  List<Map<String, Object>> findings) {
                Object id = group.get("groupId");
                if (!(group.get("instances") instanceof List<?> instances)) {
                    return;
                }
                for (Object o : instances) {
                    if (o instanceof Map<?, ?> instance) {
                        findings.add(asFinding(service, String.valueOf(id),
                            instances.size(), (Map<String, Object>) instance));
                    }
                }
            }

            private Map<String, Object> asFinding(IJdtService service, String groupId,
                                                  int copies, Map<String, Object> instance) {
                String path = String.valueOf(instance.get("filePath"));
                String method = String.valueOf(instance.get("methodName"));
                Map<String, Object> finding = new LinkedHashMap<>();
                finding.put("kind", "duplicated_code");
                finding.put("filePath", path);
                finding.put("line", instance.get("line"));
                finding.put("severity", "warning");
                finding.put("message", "Method '" + method + "' has the same structure as "
                    + (copies - 1) + " other method(s) — clone group " + groupId
                    + " (tokens: " + instance.get("tokenCount") + "). Consider Extract"
                    + " Function, or Parameterize Function where the copies differ only by"
                    + " a literal. The group id is what names the whole set: pass"
                    + " cloneGroupId=" + groupId + ".");
                finding.put("symbol", symbolOf(service, path, method));
                finding.put("cloneGroupId", groupId);
                return finding;
            }

            /**
             * The clone's method, addressed as {@code pkg.Type#method}.
             *
             * <p>An instance carries a file, a line and a method NAME, and the fingerprint
             * does not keep the declaring type. Java requires a public top-level type to
             * match its file name, so the owner is DERIVED from the path and the package
             * read off the compilation unit — the same derivation
             * {@link ModernizationSmells} makes, and the same degradation: a clone inside a
             * NESTED type gets its outer type's name, which is coarser than the truth. The
             * line stays on the finding beside it for exactly that reason, and a path the
             * model cannot resolve falls back to a bare name, which renders CONSIDER
             * downstream and says the address was not usable rather than throwing.</p>
             */
            private String symbolOf(IJdtService service, String path, String method) {
                String simple = typeNameOf(path);
                if (simple == null) {
                    return null;
                }
                String owner = simple;
                if (service != null) {
                    try {
                        org.eclipse.jdt.core.ICompilationUnit unit =
                            service.getCompilationUnit(java.nio.file.Path.of(path));
                        if (unit != null && unit.getParent() != null) {
                            String pkg = unit.getParent().getElementName();
                            if (pkg != null && !pkg.isBlank()) {
                                owner = pkg + "." + simple;
                            }
                        }
                    } catch (RuntimeException e) {
                        owner = simple;
                    }
                }
                return method == null || method.isBlank() ? owner : owner + "#" + method;
            }

            private String typeNameOf(String path) {
                if (path == null || path.isBlank() || "null".equals(path)) {
                    return null;
                }
                String file = path.replace('\\', '/');
                file = file.substring(file.lastIndexOf('/') + 1);
                return file.endsWith(".java") ? file.substring(0, file.length() - 5) : file;
            }
        };
    }
}
