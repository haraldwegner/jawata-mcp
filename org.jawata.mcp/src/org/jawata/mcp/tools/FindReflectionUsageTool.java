package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Find places where types or methods are referenced dynamically via Java reflection API.
 * These usages are invisible to static reference searches like find_references.
 */
public class FindReflectionUsageTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(FindReflectionUsageTool.class);

    // Type -> method name. Every method of that name is searched, so no spelling of a parameter list can fail to match: spelled out with
    // slashes, eleven of these twelve never matched a method of a compiled type and were silently not searched.
    private static final String[][] REFLECTION_METHODS = {
        {"java.lang.Class", "forName"},
        {"java.lang.Class", "getMethod"},
        {"java.lang.Class", "getDeclaredMethod"},
        {"java.lang.Class", "getField"},
        {"java.lang.Class", "getDeclaredField"},
        {"java.lang.Class", "getConstructor"},
        {"java.lang.Class", "getDeclaredConstructor"},
        {"java.lang.Class", "newInstance"},
        {"java.lang.reflect.Method", "invoke"},
        {"java.lang.reflect.Field", "get"},
        {"java.lang.reflect.Field", "set"},
        {"java.lang.reflect.Constructor", "newInstance"},
    };

    public FindReflectionUsageTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_reflection_usage";
    }

    @Override
    public String getDescription() {
        return """
            Find places where Java reflection API is used.

            USAGE: find_reflection_usage()
            OUTPUT: All reflection calls grouped by method type

            Detects calls to:
            - Class.forName(), Class.newInstance()
            - Class.getMethod/getDeclaredMethod/getField/getDeclaredField
            - Class.getConstructor/getDeclaredConstructor
            - Method.invoke(), Field.get/set(), Constructor.newInstance()

            These usages are invisible to static reference searches
            and can break when types or methods are renamed.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();

        Map<String, Object> maxResults = new LinkedHashMap<>();
        maxResults.put("type", "integer");
        maxResults.put("description", "Maximum results per reflection method (default 100)");
        properties.put("maxResults", maxResults);

        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Optional: only the uses in this source file");
        properties.put("filePath", filePath);

        schema.put("properties", properties);
        schema.put("required", List.of());

        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        int maxResults = getIntParam(arguments, "maxResults", 100);
        String filePath = getStringParam(arguments, "filePath");
        boolean oneFile = filePath != null && !filePath.isBlank();

        try {
            // The one file asked for; a path that names no source file of a loaded project has no uses.
            org.eclipse.jdt.core.ICompilationUnit unit = oneFile ? service.getCompilationUnit(service.getPathUtils().resolve(filePath)) : null;
            org.eclipse.core.resources.IResource wanted = unit == null ? null : unit.getResource();
            if (oneFile && wanted == null) {
                return ToolResponse.invalidParameter("filePath", "'" + filePath + "' is no source file of a loaded project, so nothing was searched");
            }
            // One more than is shown, so a list that was cut can be told from one that is whole; with one file asked for, everything, since the cap
            // applies to that file's uses and not to the project's.
            int searched = oneFile ? Integer.MAX_VALUE : (int) Math.min(Integer.MAX_VALUE, maxResults + 1L);
            List<String> cut = new ArrayList<>();
            List<Map<String, Object>> allCalls = new ArrayList<>();
            Map<String, Integer> summary = new LinkedHashMap<>();
            // The uses this run could not search for, each with why: without them a search that never ran answers like one that found nothing.
            List<String> notSearched = new ArrayList<>();

            for (String[] entry : REFLECTION_METHODS) {
                String typeName = entry[0];
                String methodName = entry[1];
                String label = typeName.substring(typeName.lastIndexOf('.') + 1) + "." + methodName;

                try {
                    IType type = service.findType(typeName);
                    if (type == null) {
                        notSearched.add(label + ": " + typeName + " was not found in any loaded project");
                        continue;
                    }

                    List<SearchMatch> matches = new ArrayList<>();
                    boolean declared = false;
                    for (IMethod method : type.getMethods()) {
                        if (method.getElementName().equals(methodName)) {
                            declared = true;
                            matches.addAll(service.getSearchService().findSourceReferences(method, searched));
                        }
                    }
                    if (!declared) {
                        notSearched.add(label + ": " + typeName + " has no such method here");
                        continue;
                    }
                    if (oneFile) {
                        matches.removeIf(match -> !wanted.equals(match.getResource()));
                    }
                    if (matches.size() > maxResults) {
                        cut.add(label);
                    }
                    List<Map<String, Object>> formatted = formatMatches(matches.subList(0, Math.min(maxResults, matches.size())), service);

                    for (Map<String, Object> match : formatted) {
                        match.put("reflectionMethod", label);
                        allCalls.add(match);
                    }

                    if (!formatted.isEmpty()) {
                        summary.put(label, formatted.size());
                    }
                } catch (Exception e) {
                    log.warn("Could not scan for {}.{}", typeName, methodName, e);
                    notSearched.add(label + ": " + e);
                }
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("totalCalls", allCalls.size());
            data.put("summary", summary);
            data.put("reflectionCalls", allCalls);
            if (!notSearched.isEmpty()) {
                data.put("notSearched", notSearched);
            }
            if (!cut.isEmpty()) {
                data.put("cutAtMaxResults", cut);
            }

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(allCalls.size())
                .returnedCount(allCalls.size())
                .suggestedNextTools(List.of(
                    "analyze(kind=change_impact) to assess risk of renaming reflected types",
                    "get_at_position(kind=symbol) at a reflection call site for context"
                ))
                .build());

        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
