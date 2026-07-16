package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.OrganizeImportsTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 25 (spec D1a item 3) — PARITY BATTERY for the {@code organize_imports}
 * migration onto JDT's {@code OrganizeImportsOperation} (public API). Goldens
 * archived from the OLD remove+sort-only implementation before the migration;
 * see {@code parity/organize-imports/DIVERGENCES.md}.
 */
class OrganizeImportsParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER = List.of("totalImports", "unusedImports");

    @Test
    @DisplayName("parity: organize RefactoringTarget.java (unused imports removed, rest sorted)")
    void parityOrganizeRefactoringTarget() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/RefactoringTarget.java");

        RefactoringChangeCache cache = new RefactoringChangeCache();
        OrganizeImportsTool tool = new OrganizeImportsTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("auto_apply", false); // stage: compare the planned change

        ToolResponse response = tool.execute(args);
        ParitySupport.assertParity("organize-imports", "refactoring-target", response,
            projectPath, helper.getTempDirectory(), HEADER);
    }
}
