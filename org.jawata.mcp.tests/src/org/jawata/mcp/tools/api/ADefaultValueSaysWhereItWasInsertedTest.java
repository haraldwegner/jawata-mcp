package org.jawata.mcp.tools.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S3 / D7c — a {@code defaultValue} is TEXT, and the caller is told where it landed.
 *
 * <p>It cannot be made to resolve everywhere, and that is the finding rather than a gap: the
 * two call sites in the fixture hold the same value under different names, so ANY single text
 * default is wrong at one of them. A third kind is worse and no compile gate can see it — the
 * report's own case of a {@code 25_000} default inserted at sites built with {@code 10_000}
 * and {@code 100_000}, which compiles and is simply not what those sites meant.</p>
 *
 * <p>So the deliverable is not resolution; it is that the response NAMES the sites a default
 * was inserted at, because those are exactly the ones a caller must read.</p>
 */
class ADefaultValueSaysWhereItWasInsertedTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ChangeMethodSignatureTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new ChangeMethodSignatureTool(() -> service, new RefactoringChangeCache());
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("a text default that cannot resolve everywhere is reported, with its sites")
    void theDefaultNamesTheSitesItWasInsertedAt() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "change_signature");
        args.put("symbol", "com.example.DefaultValueTargets#DefaultValueTargets");
        ArrayNode params = args.putArray("newParameters");
        ObjectNode names = params.addObject();
        names.put("name", "names");
        names.put("type", "String[]");
        ObjectNode budget = params.addObject();
        budget.put("name", "budget");
        budget.put("type", "int");
        // The defect, stated as an input: `roster` exists at ONE of the two call sites.
        ObjectNode added = params.addObject();
        added.put("name", "label");
        added.put("type", "String");
        added.put("defaultValue", "roster[0]");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "staging failed: " + r.getError());
        Map<String, Object> data = dataOf(r);
        String rendered = String.valueOf(data);

        Object inserted = data.get("defaultsInserted");
        assertNotNull(inserted,
            () -> "a text default was spliced into call sites and the response said nothing "
                + "about where — those sites are the only ones a caller must read, and the two "
                + "here cannot both resolve `roster[0]`. Response: " + rendered);

        // BOTH sites, not one. An earlier version of this assertion accepted the mere word
        // "defaultValue" appearing anywhere in the response, which the schema echo alone would
        // have satisfied — the vacuous shape this stage keeps producing.
        List<?> sites = (List<?>) inserted;
        assertEquals(2, sites.size(),
            () -> "the default was spliced into BOTH call sites, so both must be named: " + sites);
        for (Object site : sites) {
            Map<?, ?> row = (Map<?, ?>) site;
            assertNotNull(row.get("line"), () -> "a site without a line cannot be read: " + row);
            assertTrue(String.valueOf(row.get("defaults")).contains("roster[0]"),
                () -> "the site must name the default it received: " + row);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }
}
