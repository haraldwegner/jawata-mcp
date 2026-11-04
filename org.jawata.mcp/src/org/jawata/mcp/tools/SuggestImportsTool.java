package org.jawata.mcp.tools;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.jdt.core.IType;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.ImportCandidates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Find import candidates for an unresolved type name.
 *
 * Searches across project sources, JDK, and libraries to find types
 * matching the simple name, then ranks them by relevance.
 */
public class SuggestImportsTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(SuggestImportsTool.class);

    public SuggestImportsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
    }

    @Override
    public String getName() {
        return "suggest_imports";
    }

    @Override
    public String getDescription() {
        return """
            Find import candidates for unresolved type.

            USAGE: suggest_imports(typeName="List")
            OUTPUT: List of matching types with fully qualified names and relevance

            Searches project sources, JDK, and libraries for types matching
            the simple name. Results are sorted by relevance (java.util types
            ranked higher than java.awt, etc.).

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "typeName", Map.of(
                "type", "string",
                "description", "Simple type name to find imports for (e.g., 'List', 'Map')"
            ),
            "maxResults", Map.of(
                "type", "integer",
                "description", "Maximum candidates to return (default 20)"
            )
        ));
        schema.put("required", List.of("typeName"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String typeName = getStringParam(arguments, "typeName");
        int maxResults = getIntParam(arguments, "maxResults", 20);

        if (typeName == null || typeName.isBlank()) {
            return ToolResponse.invalidParameter("typeName", "Required");
        }

        try {
            List<Map<String, Object>> result = new ArrayList<>();
            for (ImportCandidates.Candidate found : ImportCandidates.find(service, typeName, maxResults, ImportCandidates.inProject())) {
                IType type = found.type();
                Map<String, Object> candidate = new LinkedHashMap<>();
                candidate.put("fullyQualifiedName", found.fullyQualifiedName());
                candidate.put("packageName", found.packageName());
                candidate.put("simpleName", type.getElementName());
                candidate.put("relevance", found.relevance());
                candidate.put("isInterface", type.isInterface());
                candidate.put("isClass", type.isClass());
                candidate.put("isEnum", type.isEnum());
                candidate.put("fixId", "add_import:" + found.fullyQualifiedName());
                result.add(candidate);
            }

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("typeName", typeName);
            data.put("totalCandidates", result.size());
            data.put("candidates", result);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(result.size())
                .returnedCount(result.size())
                .suggestedNextTools(List.of(
                    "quick_fix(action=apply) with fixId to add the import",
                    "quick_fix(action=list) to see all available fixes"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error suggesting imports for '{}': {}", typeName, e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }
}
