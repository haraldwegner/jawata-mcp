package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractInterfaceTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 25 (spec D1a item 6) — PARITY BATTERY for the {@code extract_interface}
 * migration onto JDT's {@code ExtractInterfaceProcessor}. Golden archived from
 * the OLD string-building implementation before the migration; see
 * {@code parity/extract-interface/DIVERGENCES.md}.
 */
class ExtractInterfaceParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER = List.of("className", "interfaceName");

    @Test
    @DisplayName("parity: extract IExtractTarget from InterfaceExtractTarget (default: all public methods)")
    void parityExtractInterface() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/InterfaceExtractTarget.java");

        RefactoringChangeCache cache = new RefactoringChangeCache();
        ExtractInterfaceTool tool = new ExtractInterfaceTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", 8);
        args.put("column", 13);
        args.put("interfaceName", "IExtractTarget");
        args.put("auto_apply", false); // stage: compare the planned change

        ToolResponse response = tool.execute(args);
        ParitySupport.assertParity("extract-interface", "iextracttarget-default", response,
            projectPath, helper.getTempDirectory(), HEADER);
    }
}
