package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A (v1.1.1) — parametric reference/navigation front door, collapsing
 * find_references / find_implementations / find_method_references by {@code kind}.
 * Read-only; delegates self-validate their position/symbol inputs.
 */
public class FindRefsTool extends AbstractTool {

    private static final List<String> KINDS = List.of("references", "implementations", "method_references");

    private final FindReferencesTool references;
    private final FindImplementationsTool implementations;
    private final FindMethodReferencesTool methodReferences;

    public FindRefsTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.references = new FindReferencesTool(serviceSupplier);
        this.implementations = new FindImplementationsTool(serviceSupplier);
        this.methodReferences = new FindMethodReferencesTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "find_references";
    }

    @Override
    public String getDescription() {
        return """
            Find references to a symbol.

            USAGE: find_references(kind="<kind>", ...)
            - references: all references.
            - implementations: implementations/subtypes of a type or method.
            - method_references: `Foo::bar` expressions only (calls: get_call_hierarchy direction=incoming).
            Address by filePath + ZERO-BASED line/column, or symbol="pkg.Type", "pkg.Type#member",
            "pkg.Type#method(int)". fields=[...] trims rows.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", KINDS);
        kind.put("description", "Which search.");
        properties.put("kind", kind);
        properties.put("filePath", Map.of("type", "string", "description", "Source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));
        properties.put("symbol", Map.of("type", "string", "description",
            "FQN instead of a position."));
        properties.put("scope", Map.of("type", "string", "enum", List.of("workspace", "project"),
            "description", "With symbol; project needs projectKey."));
        properties.put("query", Map.of("type", "string", "description",
            "Alias for symbol."));
        properties.put("maxResults", Map.of("type", "integer", "description", "Result cap."));
        properties.put("fields",
            org.jawata.mcp.tools.shared.FieldsProjection.schemaProperty("result"));
        schema.put("properties", properties);
        schema.put("required", List.of("kind"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        // v2.8.1 (dogfood 2026-07-11): the pre-2.8.1 description advertised
        // `query`, but no delegate consumed it — honor it as a symbol alias.
        // Explicit `symbol` wins on conflict.
        String symbol = getStringParam(arguments, "symbol");
        String query = getStringParam(arguments, "query");
        if ((symbol == null || symbol.isBlank()) && query != null && !query.isBlank()
                && arguments instanceof com.fasterxml.jackson.databind.node.ObjectNode obj) {
            obj.put("symbol", query);
        }
        // A file no loaded project holds is said so once for every kind, the symbol form excepted: the kinds below would each say the position does
        // not resolve (the post-release audit found implementations and method_references answering that way).
        // after the kind is known to be one of ours: an unknown kind is a parameter error first, whatever file it carries
        ToolResponse unresolved = KINDS.contains(kind) ? org.jawata.mcp.tools.shared.UnresolvedFile.ofTheFileNamed(service, arguments) : null;
        if (unresolved != null) {
            return unresolved;
        }
        return switch (kind) {
            case "references"        -> references.executeWithService(service, arguments);
            case "implementations"   -> implementations.executeWithService(service, arguments);
            case "method_references" -> methodReferences.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }
}
