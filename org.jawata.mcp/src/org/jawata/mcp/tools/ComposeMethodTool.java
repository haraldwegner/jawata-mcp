package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RecipeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 19 (Kerievsky) — <b>Compose Method</b> (toward pattern). A long method is
 * reshaped into a short sequence of intention-revealing calls by extracting each of
 * its disjoint statement sections into a named sub-method. The extraction of one
 * section shifts the offsets of the others, so this is a <em>dependent</em> recipe:
 * it reuses {@link ExtractMethodTool} per section, driven by {@link RecipeEngine}.
 *
 * <p>Sections are processed <b>bottom-up</b> (highest start line first) — since
 * {@code ExtractMethodTool} inserts the new method <em>after</em> the containing
 * method and replaces the section with a call, extracting a lower section never
 * shifts a higher one, so the caller-supplied coordinates stay valid across steps.
 * Applies atomically (rolls back on failure) with one {@code undoChangeId};
 * {@code auto_apply:false} staging is not supported for multi-step recipes.</p>
 *
 * <p>A delegate of {@link RefactorToPatternTool} (kind {@code compose_method});
 * not registered as a standalone tool. Find candidates via
 * {@code find_quality_issue(kind=long_method)}.</p>
 */
public class ComposeMethodTool extends AbstractTool {

    private final RefactoringChangeCache cache;
    private final ExtractMethodTool extract;
    private final ObjectMapper mapper = new ObjectMapper();

    public ComposeMethodTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.cache = cache;
        this.extract = new ExtractMethodTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "compose_method";
    }

    @Override
    public String getDescription() {
        return "Compose Method — extract each disjoint section of a long method into a named sub-method, "
            + "leaving a short sequence of intention-revealing calls. Delegate of refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> section = new LinkedHashMap<>();
        section.put("type", "object");
        section.put("description",
            "One statement section to extract: {startLine, startColumn, endLine, endColumn, methodName} "
                + "(ZERO-BASED, section must be complete statements inside the method body).");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file of the long method."),
            "sections", Map.of("type", "array", "items", section,
                "description", ">= 2 disjoint sections to extract, each with its own methodName.")));
        schema.put("required", List.of("filePath", "sections"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        if (!getBooleanParam(arguments, "auto_apply", true)) {
            return ToolResponse.invalidParameter("auto_apply",
                "compose_method applies atomically as a multi-step recipe; staging (auto_apply=false) is not "
                    + "supported. It applies and returns an undoChangeId.");
        }
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }
        JsonNode sectionsNode = arguments.get("sections");
        if (sectionsNode == null || !sectionsNode.isArray() || sectionsNode.size() < 2) {
            return ToolResponse.invalidParameter("sections",
                "Provide >= 2 disjoint sections, each {startLine, startColumn, endLine, endColumn, methodName}.");
        }

        List<Section> sections = new ArrayList<>();
        for (JsonNode s : sectionsNode) {
            if (!s.hasNonNull("startLine") || !s.hasNonNull("startColumn") || !s.hasNonNull("endLine")
                || !s.hasNonNull("endColumn") || !s.hasNonNull("methodName")) {
                return ToolResponse.invalidParameter("sections",
                    "Each section needs startLine, startColumn, endLine, endColumn, methodName.");
            }
            sections.add(new Section(s.get("startLine").asInt(), s.get("startColumn").asInt(),
                s.get("endLine").asInt(), s.get("endColumn").asInt(), s.get("methodName").asText()));
        }
        // Bottom-up: extract the lowest section first so higher sections' coordinates stay valid.
        sections.sort(Comparator.comparingInt((Section x) -> x.startLine)
            .thenComparingInt(x -> x.startColumn).reversed());

        List<RecipeEngine.Step> steps = new ArrayList<>();
        for (Section s : sections) {
            steps.add(() -> {
                ObjectNode a = mapper.createObjectNode();
                a.put("filePath", filePath);
                a.put("startLine", s.startLine);
                a.put("startColumn", s.startColumn);
                a.put("endLine", s.endLine);
                a.put("endColumn", s.endColumn);
                a.put("methodName", s.methodName);
                AbstractApplyingRefactoringTool.Preparation prep = extract.prepareChange(service, a);
                if (prep.error != null) {
                    throw new IllegalStateException("extract '" + s.methodName + "': "
                        + String.valueOf(prep.error.getError()));
                }
                return prep.change;
            });
        }

        RecipeEngine.Result result = RecipeEngine.run("compose method", steps, service);
        if (!result.ok()) {
            return ToolResponse.error("REFACTORING_FAILED",
                "compose_method failed: " + result.error(),
                "No changes were applied (the recipe rolled back). Check the section ranges are complete "
                    + "statements inside the method body.");
        }

        String undoChangeId = cache.put(RefactoringChangeCache.Kind.UNDO, result.compositeUndo(),
            "undo: compose method", "", result.modifiedFilePaths());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", getName());
        data.put("applied", true);
        data.put("filesModified", result.modifiedFilePaths());
        data.put("undoChangeId", undoChangeId);
        data.put("sectionsExtracted", sections.size());
        data.put("summary", "compose method: extracted " + sections.size() + " sections into named sub-methods");
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(result.modifiedFilePaths().size())
            .returnedCount(result.modifiedFilePaths().size())
            .suggestedNextTools(List.of(
                "compile_workspace to verify the refactoring",
                "undo_refactoring with the undoChangeId if verification fails"))
            .build());
    }

    private record Section(int startLine, int startColumn, int endLine, int endColumn, String methodName) {
    }
}
