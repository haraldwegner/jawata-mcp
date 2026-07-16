package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ExtractSuperclassTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 25 (spec D1a item 7) — PARITY BATTERY for the CONSERVATIVE mode of
 * {@code extract_superclass} ({@code mode=identical}: byte-identical +
 * self-contained methods only). Golden archived from the pre-migration
 * implementation; the mode parameter routes to the preserved conservative path
 * after the JDT default lands (the pre-migration tool ignores it, so the
 * invocation is identical on both sides). See
 * {@code parity/extract-superclass/DIVERGENCES.md}.
 */
class ExtractSuperclassParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final List<String> HEADER =
        List.of("newSuperclass", "subclasses", "pulledUpMembers");

    @Test
    @DisplayName("parity (mode=identical): Greeter from GreetFormal + GreetCasual")
    void parityExtractSuperclassConservative() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path projectPath = helper.getTempDirectory().resolve("simple-maven");
        Path file = projectPath.resolve("src/main/java/com/example/GreetFormal.java");

        RefactoringChangeCache cache = new RefactoringChangeCache();
        ExtractSuperclassTool tool = new ExtractSuperclassTool(() -> service, cache);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", 3);
        args.put("column", 13);
        args.put("superclassName", "Greeter");
        args.putArray("siblings").add("GreetCasual");
        args.put("mode", "identical");
        args.put("auto_apply", false); // stage: compare the planned change

        ToolResponse response = tool.execute(args);
        ParitySupport.assertParity("extract-superclass", "greeter-identical", response,
            projectPath, helper.getTempDirectory(), HEADER);
    }
}
