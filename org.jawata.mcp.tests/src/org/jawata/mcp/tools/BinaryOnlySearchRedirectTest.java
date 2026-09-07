package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.models.WorkspaceIdentity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#25 — <b>a page of nothing but classpath binaries is the wrong-workspace shape, exactly as
 * an empty page is.</b>
 *
 * <p>A machine runs one jawata server per workspace, so a question asked of the wrong one must
 * say WHERE it looked. That redirect fires on an empty result (D11) and on
 * {@code SYMBOL_NOT_FOUND}. It did not fire here: a bare-name query falls back to a substring
 * retry, which matches JDK and dependency types by the dozen, so an agent hunting a class that
 * lives in ANOTHER workspace got a page of plausible-looking hits and no redirect at all.</p>
 *
 * <p>Measured in the v3.9.0 dogfood: {@code search_symbols(query="Order", kind="Class")} against
 * a workspace containing no such class returned <b>32 results</b> — {@code ByteOrder},
 * {@code SortOrder} and their kin — while the truly-empty query on the same run correctly
 * produced the redirect. <b>The successful-looking answer was the misleading one.</b></p>
 *
 * <p>THE STEERING LEADS WITH THE FACT, not with the guess. "Every match is a classpath binary"
 * is true whether the caller wanted a JDK type or has come to the wrong server, so it informs
 * the first and redirects the second without having to tell them apart — which nothing here
 * can do, since intent is not on the wire.</p>
 */
class BinaryOnlySearchRedirectTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /** Wording only {@code elsewhereHint()} emits, so a sibling steering line cannot satisfy it. */
    private static final String REDIRECT = "served by that tree's own jawata server";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private SearchSymbolsTool tool;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        tool = new SearchSymbolsTool(() -> service);
        // The redirect exists only once the server knows what it is. Installed per test and
        // taken back down in @AfterEach: this is a STATIC holder, and one left set poisons
        // every later test in the same JVM.
        WorkspaceIdentity.install("probe-workspace", List.of(Path.of("simple-maven")));
    }

    @AfterEach
    void tearDown() {
        // reset() is package-private to models; installing nothing makes installed() false,
        // which is the same end state for every reader of this holder.
        WorkspaceIdentity.install(null, List.of());
    }

    @Test
    @DisplayName("mcp#25: a page of only classpath binaries carries the wrong-workspace redirect")
    void aBinaryOnlyPageStillSaysWhereItLooked() {
        ToolResponse response = search("ArrayList");

        assertTrue(response.isSuccess(), "got: " + response.getError());
        List<Map<String, Object>> results = resultsOf(response);
        String steering = String.valueOf(response.getMeta().getSteering());

        assertAll(
            // PROOF OF LIFE, and it is the load-bearing half. An empty page ALSO carries the
            // redirect, by the D11 path — so without these two clauses this test would pass on
            // a search that found nothing, proving the opposite of what it claims.
            () -> assertFalse(results.isEmpty(),
                "the query must actually match, or the empty-result path answers instead"),
            () -> assertTrue(results.stream().allMatch(r -> Boolean.TRUE.equals(r.get("binary"))),
                "every row must be a classpath binary for this to be the case under test: "
                    + results.stream().map(r -> r.get("name") + "/" + r.get("binary")).toList()),
            // THE DEFECT: this page looked like a successful search and said nothing.
            () -> assertTrue(steering.contains(REDIRECT),
                "a page with no workspace source must still say where it looked: " + steering));
    }

    @Test
    @DisplayName("THE CONTROL — a hit in the workspace's own source carries no redirect")
    void aWorkspaceHitIsNotRedirected() {
        // Without this, a fix that appended the redirect to EVERY search would pass the case
        // above while making the redirect meaningless, and the two would be indistinguishable.
        ToolResponse response = search("HelloWorld");

        assertTrue(response.isSuccess(), "got: " + response.getError());
        List<Map<String, Object>> results = resultsOf(response);
        String steering = String.valueOf(response.getMeta().getSteering());

        assertAll(
            () -> assertFalse(results.isEmpty(), "precondition: the fixture's own class is found"),
            () -> assertTrue(results.stream().anyMatch(r -> !Boolean.TRUE.equals(r.get("binary"))),
                "precondition: at least one row is workspace source"),
            () -> assertFalse(steering.contains(REDIRECT),
                "a workspace hit must not be told to look elsewhere: " + steering));
    }

    private ToolResponse search(String query) {
        ObjectNode args = OM.createObjectNode();
        args.put("query", query);
        args.put("kind", "Class");
        return tool.execute(args);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> resultsOf(ToolResponse response) {
        Map<String, Object> data = (Map<String, Object>) response.getData();
        return (List<Map<String, Object>>) data.get("results");
    }
}
