package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.InlineVariableTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 25 (spec D1a item 2) — PARITY BATTERY for the {@code inline_variable}
 * migration onto JDT's {@code InlineTempRefactoring}. Goldens archived from the
 * OLD hand-rolled inliner before the migration; see
 * {@code parity/inline-variable/DIVERGENCES.md}.
 */
class InlineVariableParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER = List.of("variableName", "usageCount");

    @Test
    @DisplayName("parity: inline local `trimmed` (two usages) into its initializer")
    void parityInlineTrimmed() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java");

        RefactoringChangeCache cache = new RefactoringChangeCache();
        InlineVariableTool tool = new InlineVariableTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", 26);   // String trimmed = input.trim();
        args.put("column", 15); // on `trimmed`
        args.put("auto_apply", false); // stage: compare the planned change

        ToolResponse response = tool.execute(args);
        ParitySupport.assertParity("inline-variable", "trimmed-two-usages", response,
            projectPath, helper.getTempDirectory(), HEADER);
    }
}
