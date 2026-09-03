package org.jawata.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.AnalyzeNamingTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE OTHER HALF OF THE 4.0.3 NAMING DEFECT, which shipped its fix without a control.
 *
 * <p>The naming CHECK could not skip test sources, and that half was repaired with a
 * fixture, a proof-of-life assertion and a measured revert. The naming INFERENCE carried
 * the identical defect and was repaired in the same commit with no test at all.</p>
 *
 * <p>What it cost, measured on this product's own repository before the fix: five
 * conventions were inferred — type, field, constant, package, test — and {@code method}
 * was ABSENT, with nothing in the answer saying it had been dropped. Roughly fifteen
 * hundred deliberate snake_case test method names pushed camelCase conformance under the
 * confidence floor, so the category was declined. Field held at 684 of 685 and constant
 * at 510 of 512, which is why the tool looked healthy: the one category test code
 * actually differs in is the one it lost.</p>
 *
 * <p>The fixture's test sources carry a snake_case method and a {@code SampleTest} type,
 * which is what the exclusion has to survive.</p>
 *
 * <p><b>What is NOT asserted here, and why.</b> The exclusion is per CATEGORY, not a skip
 * of the file — packages are still collected from test sources, deliberately, because a
 * Java test lives in its subject's package on purpose. That property has no observable
 * on this fixture: it declares one package and one test class, both under the sample size
 * any convention needs, so `package` and `test` are absent here whatever the rule is. An
 * assertion about them would pin the fixture's size rather than the behaviour, which is
 * how a test comes to pass for the wrong reason.</p>
 */
class ConventionsAreLearnedFromProductionCodeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private AnalyzeNamingTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new AnalyzeNamingTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> conventions() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "infer");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the inference must run; got: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return (List<Map<String, Object>>) data.get("conventions");
    }

    private Set<String> categories() {
        return conventions().stream().map(c -> String.valueOf(c.get("category")))
            .collect(Collectors.toSet());
    }

    private Map<String, Object> convention(String category) {
        return conventions().stream()
            .filter(c -> category.equals(String.valueOf(c.get("category"))))
            .findFirst().orElse(null);
    }

    @Test
    @DisplayName("the method convention survives a corpus whose test code names methods differently")
    void theMethodConventionIsNotSunkByTestNames() {
        Set<String> categories = categories();
        assertTrue(categories.contains("method"),
            "this is the category the defect removed, silently: with test sources in the"
                + " sample, deliberate snake_case test names push camelCase under the"
                + " confidence floor and the whole category is declined with no note."
                + " Inferred: " + categories);
    }

    @Test
    @DisplayName("no production convention is learned from a test source")
    void noProductionCategoryLearnsFromTestSources() {
        // Package is NOT in this list, and that is the tool's stated exception rather
        // than an oversight — see the class note. The four here are the categories a
        // test source says nothing useful about.
        for (String category : List.of("type", "method", "field", "constant")) {
            Map<String, Object> convention = convention(category);
            if (convention == null) {
                continue;   // a category with too small a sample is a separate question
            }
            String exceptions = String.valueOf(convention.get("exceptions"));
            assertFalse(exceptions.contains("Bad_Test_Method_Name"),
                "the '" + category + "' convention learned from a test source. The rule"
                    + " is that a convention is learned from the code it governs, and a"
                    + " deliberately misnamed test method is not evidence about how"
                    + " production names anything: " + convention);
        }
    }

}
