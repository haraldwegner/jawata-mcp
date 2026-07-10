package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 19 (Kerievsky) — <b>Replace Pattern with Idiom</b> (away from pattern). A
 * pattern wrapped around a language feature that has since landed is replaced by the
 * native idiom. v1.4 ships the canonical case — an anonymous class implementing a
 * functional interface becomes a <b>lambda</b> — by delegating to the proven
 * {@link ConvertAnonymousToLambdaTool}. The {@code idiom} parameter reserves room for
 * further idioms (e.g. a hand-rolled Visitor over a closed hierarchy → a
 * pattern-matching {@code switch}) without changing the tool surface.
 *
 * <p>A delegate of {@link RefactorToPatternTool} (kind {@code replace_pattern_with_idiom}).
 * Find candidates via {@code find_quality_issue(kind=speculative_generality)} or
 * {@code find_modernization(kind=anon_to_lambda)}.</p>
 */
public class ReplacePatternWithIdiomTool extends AbstractTool {

    private static final List<String> IDIOMS = List.of("anonymous_to_lambda");

    private final ConvertAnonymousToLambdaTool anonymousToLambda;

    public ReplacePatternWithIdiomTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.anonymousToLambda = new ConvertAnonymousToLambdaTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "replace_pattern_with_idiom";
    }

    @Override
    public String getDescription() {
        return "Replace Pattern with Idiom (away) — replace a pattern with the language feature that "
            + "superseded it. v1.4: anonymous class implementing a functional interface -> lambda. "
            + "Delegate of refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> idiom = new LinkedHashMap<>();
        idiom.put("type", "string");
        idiom.put("enum", IDIOMS);
        idiom.put("description", "Which idiom to apply (default anonymous_to_lambda).");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file."),
            "line", Map.of("type", "integer", "description", "Zero-based line of the 'new' keyword of the anonymous class."),
            "column", Map.of("type", "integer", "description", "Zero-based column of the 'new' keyword."),
            "idiom", idiom));
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String idiom = getStringParam(arguments, "idiom");
        if (idiom == null || idiom.isBlank()) {
            idiom = "anonymous_to_lambda";
        }
        return switch (idiom) {
            case "anonymous_to_lambda" -> anonymousToLambda.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("idiom",
                "Unsupported idiom '" + idiom + "'. Available: " + IDIOMS);
        };
    }
}
