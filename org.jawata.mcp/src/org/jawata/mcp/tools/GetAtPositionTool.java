package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A — parametric front door collapsing the nine read-only
 * position-lookup tools into one always-loaded tool. Every delegate takes the
 * identical {@code {filePath, line, column}} input, so this is a clean uniform
 * collapse: the {@code kind} selects which view of the element-at-position to
 * return.
 *
 * <p>The narrow tool classes stay in the package as the concrete
 * implementations this tool delegates to (via their package-private
 * {@link AbstractTool#executeWithService}); they are no longer registered as
 * user-facing MCP tools, so agents see only {@code get_at_position}.</p>
 *
 * <p>Replaces: {@code get_type_at_position}, {@code get_method_at_position},
 * {@code get_field_at_position}, {@code get_hover_info}, {@code get_javadoc},
 * {@code get_signature_help}, {@code get_enclosing_element},
 * {@code get_super_method}, {@code get_symbol_info}.</p>
 */
public class GetAtPositionTool extends AbstractTool {

    private static final List<String> KINDS = List.of(
        "type", "method", "field", "hover", "javadoc",
        "signature", "enclosing", "super", "symbol");

    private final GetTypeAtPositionTool type;
    private final GetMethodAtPositionTool method;
    private final GetFieldAtPositionTool field;
    private final GetHoverInfoTool hover;
    private final GetJavadocTool javadoc;
    private final GetSignatureHelpTool signature;
    private final GetEnclosingElementTool enclosing;
    private final GetSuperMethodTool superMethod;
    private final GetSymbolInfoTool symbol;

    public GetAtPositionTool(Supplier<IJdtService> serviceSupplier) {
        super(serviceSupplier);
        this.type = new GetTypeAtPositionTool(serviceSupplier);
        this.method = new GetMethodAtPositionTool(serviceSupplier);
        this.field = new GetFieldAtPositionTool(serviceSupplier);
        this.hover = new GetHoverInfoTool(serviceSupplier);
        this.javadoc = new GetJavadocTool(serviceSupplier);
        this.signature = new GetSignatureHelpTool(serviceSupplier);
        this.enclosing = new GetEnclosingElementTool(serviceSupplier);
        this.superMethod = new GetSuperMethodTool(serviceSupplier);
        this.symbol = new GetSymbolInfoTool(serviceSupplier);
    }

    @Override
    public String getName() {
        return "get_at_position";
    }

    @Override
    public String getDescription() {
        return """
            Inspect the Java element at a source position. One tool, many views.

            USAGE: get_at_position(kind="<kind>", filePath=..., line=..., column=...)

            Kinds (all take filePath + ZERO-BASED line/column):
            - type      — the type at the position: kind, modifiers, superclass, interfaces, member counts.
            - method    — the method: signature, parameters, return type, modifiers, exceptions.
            - field     — the field: type, modifiers, constant value if any.
            - hover     — IDE-style hover summary for the symbol at the position.
            - javadoc   — the resolved Javadoc for the symbol at the position.
            - signature — signature help (overloads + parameters) at a call site.
            - enclosing — the element that encloses the position (method/type/initializer).
            - super     — the overridden/super method of the method at the position.
            - symbol    — generic symbol info (name, kind, declaring type, location).

            IMPORTANT: Uses ZERO-BASED coordinates.

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
        kind.put("enum", KINDS);
        kind.put("description",
            "Which view of the element at the position to return. See the tool description.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of(
            "type", "string",
            "description", "Path to source file"));
        properties.put("line", Map.of(
            "type", "integer",
            "description", "Zero-based line number"));
        properties.put("column", Map.of(
            "type", "integer",
            "description", "Zero-based column number"));
        properties.put("maxResults", Map.of(
            "type", "integer",
            "description", "Optional. Some kinds (e.g. signature) cap their result list with this."));

        schema.put("properties", properties);
        schema.put("required", List.of("kind", "filePath", "line", "column"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + KINDS);
        }
        return switch (kind) {
            case "type"      -> type.executeWithService(service, arguments);
            case "method"    -> method.executeWithService(service, arguments);
            case "field"     -> field.executeWithService(service, arguments);
            case "hover"     -> hover.executeWithService(service, arguments);
            case "javadoc"   -> javadoc.executeWithService(service, arguments);
            case "signature" -> signature.executeWithService(service, arguments);
            case "enclosing" -> enclosing.executeWithService(service, arguments);
            case "super"     -> superMethod.executeWithService(service, arguments);
            case "symbol"    -> symbol.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS);
        };
    }
}
