package org.jawata.mcp.tools.navigation;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.GetSuperMethodTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class GetSuperMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();
    private GetSuperMethodTool tool;
    private ObjectMapper objectMapper;
    private String animalPath;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new GetSuperMethodTool(() -> service);
        objectMapper = new ObjectMapper();
        Path projectPath = helper.getFixturePath("simple-maven");
        animalPath = projectPath.resolve("src/main/java/com/example/Animal.java").toString();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    @Test @DisplayName("finds super method with complete response")
    void findsSuperMethodComprehensively() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animalPath);
        args.put("line", 22);  // Dog.speak() which overrides Animal.speak()
        args.put("column", 16);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);

        // Verify method info
        assertNotNull(data.get("method"));
        @SuppressWarnings("unchecked")
        Map<String, Object> methodInfo = (Map<String, Object>) data.get("method");
        assertNotNull(methodInfo.get("name"));
        assertNotNull(methodInfo.get("signature"));

        // REL44-22: asserted, not guarded — this test is about FINDING the super method, and an
        // `if` let a lookup that found none pass the one check the test is named for.
        @SuppressWarnings("unchecked")
        Map<String, Object> overrides = (Map<String, Object>) data.get("overrides");
        assertNotNull(overrides, "the overriding method's super method must be found: " + data);
        assertNotNull(overrides.get("declaringType"));
    }

    @Test @DisplayName("returns null overrides for non-overriding method")
    void handlesNonOverridingMethod() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animalPath);
        args.put("line", 7);  // Animal.speak() - base method, not overriding anything
        args.put("column", 16);

        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess());
        Map<String, Object> data = getData(r);
        // REL44-22: asserted, not guarded — the `if` let a base method reported as overriding
        // something pass, which is the one wrong answer this test exists to catch.
        assertNull(data.get("overrides"), "a base method overrides nothing: " + data);
        assertNotNull(data.get("message"), "and says so: " + data);
    }

    @Test @DisplayName("requires filePath, line, column parameters")
    void requiresParameters() {
        ObjectNode noFile = objectMapper.createObjectNode();
        noFile.put("line", 22);
        noFile.put("column", 16);
        assertFalse(tool.execute(noFile).isSuccess());

        ObjectNode noLine = objectMapper.createObjectNode();
        noLine.put("filePath", animalPath);
        noLine.put("column", 16);
        assertFalse(tool.execute(noLine).isSuccess());
    }

    @Test @DisplayName("handles non-method position gracefully")
    void handlesNonMethodPosition() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", animalPath);
        args.put("line", 5);  // Class declaration
        args.put("column", 13);

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
    }
}
