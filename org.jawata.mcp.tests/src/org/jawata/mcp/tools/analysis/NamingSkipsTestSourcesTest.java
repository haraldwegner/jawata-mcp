package org.jawata.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindNamingViolationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE SECOND DEFECT THE 4.0.3 DOGFOOD FOUND.
 *
 * <p>Every code-smell check takes {@code includeTests}, defaulting to excluding test
 * sources, because test code legitimately looks different. The naming check predates
 * that switch and never received it, so it was the one scan still reporting deliberate
 * test method names as violations: 1,533 findings on this repository, and the sampled
 * page was entirely intentional names like {@code a_failed_write_is_counted_loudly}.
 * The one real finding in the file examined was buried under fifteen hundred.</p>
 *
 * <p>This asserts the switch behaves as the smell checks' does — the same default and
 * the same classifier — rather than asserting a count, which would pin this repository's
 * contents rather than the tool's contract.</p>
 */
class NamingSkipsTestSourcesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindNamingViolationsTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindNamingViolationsTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> scan(boolean includeTests) {
        ObjectNode args = mapper.createObjectNode();
        if (includeTests) {
            args.put("includeTests", true);
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the naming scan must run");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return (List<Map<String, Object>>) data.get("violations");
    }

    private static boolean anyFromTestSource(List<Map<String, Object>> violations) {
        return violations.stream().anyMatch(v -> {
            // The record's key is `file`. Reading `filePath` yielded the string "null"
            // for every row, so the exclusion assertion passed while looking at nothing
            // — which is why the proof-of-life assertion below is not optional.
            String path = String.valueOf(v.get("file")).replace('\\', '/');
            return path.contains("/test/") || path.contains("test/java/");
        });
    }

    @Test
    @DisplayName("test sources are excluded by default, and included on opt-in")
    void testSourcesAreExcludedByDefault() {
        List<Map<String, Object>> byDefault = scan(false);
        List<Map<String, Object>> optedIn = scan(true);

        assertFalse(anyFromTestSource(byDefault),
            "no violation may come from a test source unless asked for; got: " + byDefault);
        assertTrue(optedIn.size() >= byDefault.size(),
            "opting in can only widen the scan: " + optedIn.size() + " vs " + byDefault.size());
        assertTrue(anyFromTestSource(optedIn),
            "PROOF OF LIFE: the fixture must HAVE a test-source violation, or the"
                + " assertion above passes for the wrong reason — it would hold just as"
                + " well if the scan found nothing at all. Opted in: " + optedIn);
    }

    @Test
    @DisplayName("the switch is published, so a caller can find it")
    void theSwitchIsPublished() {
        @SuppressWarnings("unchecked")
        Map<String, Object> props =
            (Map<String, Object>) tool.getInputSchema().get("properties");
        assertTrue(props.containsKey("includeTests"),
            "a switch nobody can see is a switch nobody uses: " + props.keySet());
        assertTrue(tool.getDescription().contains("includeTests"),
            "and the description is the only documentation a client reaches");
    }
}
