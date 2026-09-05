package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.SeparateQueryFromModifierTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 61 ON CODE WE DID NOT AUTHOR — a MEASURED ABSENCE, and the one near-miss pinned.
 *
 * <h2>The census, and it is a census rather than a sample</h2>
 *
 * <p>All twelve vendored slices were scanned for the shape this row performs: a method that
 * WRITES a field and RETURNS that same field. Over <b>108 value-returning methods</b> there is
 * exactly ONE apparent hit, and reading it shows it is not one —
 * {@code DatabaseService.createDataSource} declares {@code var dataSource = new JdbcDataSource()}
 * inside itself, a LOCAL that shadows the static field of the same name, and returns the local.
 * The true count is <b>zero</b>.</p>
 *
 * <p>That is unsurprising in a teaching corpus and it is said plainly rather than dressed up: a
 * pattern demo returns what it computed, and a method that mutates state and hands the state
 * back is an artefact of code that has been maintained. The row has NO success demonstration on
 * foreign code.</p>
 *
 * <h2>What IS pinned, and why this near-miss is worth a test</h2>
 *
 * <p>The near-miss is the best evidence the corpus offers, because it is exactly the case a
 * NAME-based rule gets wrong. A rule matching the returned identifier's TEXT against the field
 * list would accept upstream's method and generate a query answering the static field, while the
 * method returns a freshly built local — a change that compiles and is wrong. This row resolves
 * BINDINGS, so it refuses, and the refusal is asserted here on somebody else's shadowing rather
 * than on a fixture written to shadow.</p>
 */
class SeparateQueryFromModifierForkSliceTest {

    private static final String PKG = "com/iluwatar/slob/dbservice";

    /** Upstream's own line — the local that shadows the field of the same name. */
    private static final String UPSTREAM_SHADOW = "var dataSource = new JdbcDataSource();";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("upstream's shadowing local is NOT the field it names, so the row refuses "
        + "where a name-based rule would rewrite")
    void refusesUpstreamsShadowedLocal() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-serialized-lob", PKG,
            "DatabaseService.java", UPSTREAM_SHADOW);
        String before = slice.read("DatabaseService.java");
        assertTrue(before.contains("private static final DataSource dataSource ="),
            "PROOF OF LIFE: the class must still declare a FIELD of that name too — the"
                + " shadowing is the whole point, and without both there is nothing to confuse");

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "separate_query_from_modifier");
        args.put("symbol", "com.iluwatar.slob.dbservice.DatabaseService#createDataSource");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache())
            .execute(args);

        assertFalse(r.isSuccess(),
            "the method returns a LOCAL that happens to share a field's name, so there is no"
                + " command-and-query here to separate");
        assertEquals(SeparateQueryFromModifierTool.Refusal.RETURN_NOT_A_WRITTEN_FIELD,
            r.getError().getReason(),
            "and it must be the returned-shape PRECONDITION, reached by resolving the binding"
                + " rather than by matching the name: " + r.getError());
        assertEquals(before, slice.read("DatabaseService.java"),
            "upstream's file must be byte-for-byte untouched");
    }
}
