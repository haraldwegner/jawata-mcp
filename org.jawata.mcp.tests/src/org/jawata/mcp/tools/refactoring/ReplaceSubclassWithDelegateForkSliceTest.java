package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HierarchyTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 56 ON CODE WE DID NOT AUTHOR — and it PERFORMS, which most of this stage's slices do not.
 *
 * <p>Upstream's {@code RingInstance} is Fowler's shape without being written for it: it extends
 * {@code AbstractInstance} purely to override four handler methods, and its only field is a
 * {@code static final} string. Its variation IS its overrides, which is precisely the case where
 * spending the single inheritance slot costs the most.</p>
 *
 * <p><b>Its sibling {@code BullyInstance} is the argument for this refactoring, in upstream's own
 * tree.</b> Two subclasses vary the same axis; anything wanting to vary a SECOND axis on top has
 * nowhere to go. That is the cost delegation removes, and it is visible here rather than
 * asserted.</p>
 */
class ReplaceSubclassWithDelegateForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PKG = "com/iluwatar/leaderelection/ring";

    /** Upstream's own line — the override this row moves. */
    private static final String UPSTREAM_OVERRIDE = "handleHeartbeatInvokeMessage";

    @Test
    @DisplayName("row 56 PERFORMS on upstream's RingInstance, whose variation IS its overrides")
    void performsOnUpstreamsHandlerSubclass() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-leader-election", PKG,
            "RingInstance.java", UPSTREAM_OVERRIDE);
        String before = slice.read("RingInstance.java");

        Assertions.assertAll(
            () -> assertTrue(before.contains("extends AbstractInstance"),
                "PROOF OF LIFE, the superclass:\n" + before),
            () -> assertTrue(before.contains("@Override"),
                "PROOF OF LIFE, the overrides: without one this would refuse as"
                    + " NOTHING_OVERRIDDEN and prove the other row's case instead"));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("direction", "replace_subclass_with_delegate");
        args.put("typeName", "com.iluwatar.leaderelection.ring.RingInstance");

        ToolResponse r = new HierarchyTool(slice::service, slice.cache()).execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        String behaviour = slice.read("RingInstanceBehaviour.java");
        Assertions.assertAll(
            () -> assertTrue(behaviour.contains("class RingInstanceBehaviour"),
                "the behaviour class is generated beside upstream's own file:\n" + behaviour),
            () -> assertTrue(behaviour.contains("handleHeartbeatInvokeMessage"),
                "carrying upstream's OWN handler, copied verbatim:\n" + behaviour),
            () -> assertFalse(behaviour.contains("@Override"),
                "and without the annotation, since the delegate overrides nothing — a detail"
                    + " nothing downstream would catch, because a created file is parsed and"
                    + " not resolved:\n" + behaviour),
            () -> assertEquals(before, slice.read("RingInstance.java"),
                "while upstream's own file is UNTOUCHED: this row adds, it does not migrate"));
    }

    private static void assertEquals(String expected, String actual, String message) {
        Assertions.assertEquals(expected, actual, message);
    }
}
