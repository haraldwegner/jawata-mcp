package org.jawata.mcp.tools.search;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindReflectionUsageTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class FindReflectionUsageToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindReflectionUsageTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindReflectionUsageTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) { return (Map<String, Object>) r.getData(); }

    // ========== Detection Tests ==========

    @Test
    @DisplayName("should find reflection usage in project")
    void findsReflectionUsage() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        int totalCalls = (int) data.get("totalCalls");
        assertTrue(totalCalls > 0, "Should find reflection calls in DiAndReflectionPatterns.java");
        assertNull(data.get("notSearched"), "CONTROL: a healthy project has a use that is never searched for: " + data.get("notSearched"));
        // The fixture's own calls, each in its file. Searched by a signature that never matched, the project reported only hits inside jars.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) data.get("reflectionCalls");
        java.util.Set<String> inTheFixture = calls.stream()
            .filter(call -> String.valueOf(call.get("filePath")).endsWith("DiAndReflectionPatterns.java"))
            .map(call -> String.valueOf(call.get("reflectionMethod")))
            .collect(java.util.stream.Collectors.toCollection(java.util.TreeSet::new));
        assertEquals(new java.util.TreeSet<>(List.of("Class.forName", "Class.getDeclaredConstructor", "Class.getDeclaredField", "Class.getMethod",
            "Constructor.newInstance", "Field.get", "Method.invoke")), inTheFixture);
    }

    @Test
    @DisplayName("28h-C27-95 and -94: filePath limits the scan to that file, and a list cut at maxResults says which uses were cut")
    void filePathAndTheCutAreHonoured() {
        ObjectNode elsewhere = objectMapper.createObjectNode().put("filePath", "src/main/java/com/example/NoSuchFile.java");
        ObjectNode inTheFile = objectMapper.createObjectNode().put("filePath", "src/main/java/com/example/DiAndReflectionPatterns.java");
        // Each use in the sample project occurs once, so only a cap of zero cuts anything.
        ObjectNode cut = objectMapper.createObjectNode().put("maxResults", 0);

        assertFalse(tool.execute(elsewhere).isSuccess(), "a path that names no source file was answered as a scan that found nothing");
        assertTrue((int) getData(tool.execute(inTheFile)).get("totalCalls") > 0, "CONTROL: the file's own calls were filtered away");
        Map<String, Object> capped = getData(tool.execute(cut));
        assertNotNull(capped.get("cutAtMaxResults"), "a list cut at maxResults did not say so: " + capped.get("summary"));
        assertNull(getData(tool.execute(objectMapper.createObjectNode())).get("cutAtMaxResults"), "CONTROL: a whole list said it was cut");
    }

    @Test
    @DisplayName("should include reflection method label in results")
    void includesReflectionMethodLabel() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> calls = (List<Map<String, Object>>) getData(response).get("reflectionCalls");
        // REL44-22: asserted, not guarded — the fixture uses reflection, and an `if` let a scan
        // that found none pass the label check this test is named for.
        assertFalse(calls.isEmpty(), "the fixture's reflection calls must be found; not searched: " + getData(response).get("notSearched"));
        Map<String, Object> firstCall = calls.get(0);
        assertNotNull(firstCall.get("reflectionMethod"), "Should include reflection method label");
        // filePath and line may be absent if match is in a binary JAR
    }

    @Test
    @DisplayName("a use that could not be searched for is named with why, so nothing found is not read from a search that never ran")
    void aScanThatCouldNotLookSaysSo() throws Exception {
        org.jawata.core.IJdtService real = helper.loadProject("simple-maven");
        org.jawata.core.IJdtService failing = (org.jawata.core.IJdtService) java.lang.reflect.Proxy.newProxyInstance(
            org.jawata.core.IJdtService.class.getClassLoader(), new Class<?>[] { org.jawata.core.IJdtService.class }, (proxy, method, callArgs) -> {
                if ("findType".equals(method.getName()) && "java.lang.Class".equals(callArgs[0])) {
                    throw new IllegalStateException("the model did not answer");
                }
                if ("findType".equals(method.getName()) && "java.lang.reflect.Method".equals(callArgs[0])) {
                    return null;
                }
                try {
                    return method.invoke(real, callArgs);
                } catch (java.lang.reflect.InvocationTargetException thrown) {
                    throw thrown.getCause();
                }
            });

        ToolResponse response = new FindReflectionUsageTool(() -> failing).execute(objectMapper.createObjectNode());

        assertTrue(response.isSuccess());
        String notSearched = String.valueOf(getData(response).get("notSearched"));
        assertTrue(notSearched.contains("Class.forName") && notSearched.contains("the model did not answer"), notSearched);
        assertTrue(notSearched.contains("Method.invoke") && notSearched.contains("not found"), notSearched);
    }

    // ========== Summary Tests ==========

    @Test
    @DisplayName("should group results by reflection method type in summary")
    void groupsResultsInSummary() {
        ObjectNode args = objectMapper.createObjectNode();

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
        Map<String, Object> data = getData(response);
        assertNotNull(data.get("summary"), "Should include summary");
        assertNotNull(data.get("reflectionCalls"), "Should include reflectionCalls list");
    }

    @Test
    @DisplayName("should respect maxResults parameter")
    void respectsMaxResults() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("maxResults", 1);

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess());
    }
}
