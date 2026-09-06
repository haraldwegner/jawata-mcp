package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.HierarchyTool;
import org.jawata.mcp.tools.inheritance.PullUpConstructorBodyTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 29 ON CODE WE DID NOT AUTHOR — and it REFUSES, which is evidence rather than a
 * demonstration. Stage 6 recorded that distinction and this row inherits it.
 *
 * <h2>The census, and why there is no performing candidate anywhere in the corpus</h2>
 *
 * <p>Over the fork's <b>1354 main sources</b>, every class that assigns a field it does not
 * itself declare was enumerated — <b>SEVEN</b>. Not one is a case this row would perform, and
 * each falls into a refusal it already names:</p>
 *
 * <ul>
 *   <li><b>FOUR already delegate upward with arguments</b> — {@code RingInstance},
 *       {@code MenuAction}, {@code ContentAction}, {@code Skeleton}. Upstream has already done
 *       this refactoring, which is the state a curated corpus is in: it demonstrates finished
 *       designs, and a subclass doing the superclass's job is this refactoring's BEFORE.</li>
 *   <li><b>ONE extends a JDK class</b> — {@code Worker extends Thread}. There is no source to
 *       add a constructor to, which is {@code SUPERCLASS_NOT_IN_SOURCE}.</li>
 *   <li><b>ONE is a false positive of the census method itself</b>, and it is worth recording
 *       rather than quietly dropping: {@code TreeNode<T extends Comparable<T>>} matched a scan
 *       for "class ... extends" and extends NOTHING — the {@code extends} is a type-parameter
 *       BOUND. A measurement is only as good as what it actually matched, and this one had to
 *       be read to be believed.</li>
 * </ul>
 *
 * <p>So the absence is EXPLAINED rather than merely reported, and it is exhaustive rather than
 * a sample.</p>
 *
 * <h2>What is pinned, and why THIS one</h2>
 *
 * <p>Upstream's {@code RingInstance} passes all three of its parameters to
 * {@code AbstractInstance}, which assigns them. That is the shape this row produces, arrived at
 * by somebody else — so the refusal is not an accident of a fixture, and if upstream ever stops
 * delegating, this test goes red and someone re-reads the note above.</p>
 *
 * <p><b>WHICH refusal fires is asserted by reason CODE, not by a substring.</b> Two of this
 * row's refusals could be argued for the same class, and a message shared by both would prove
 * only that something declined — the lesson this sprint paid for twice.</p>
 */
class PullUpConstructorBodyForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PKG = "com/iluwatar/leaderelection/ring";

    /** Upstream's own line — the delegation that makes this a refusal. */
    private static final String UPSTREAM_SUPER_CALL = "super(messageManager, localId, leaderId);";

    @Test
    @DisplayName("row 29 REFUSES upstream's RingInstance, which already delegates upward — the "
        + "state this refactoring produces, reached by somebody else")
    void refusesUpstreamsAlreadyDelegatingConstructor() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-leader-election", PKG,
            "RingInstance.java", UPSTREAM_SUPER_CALL);
        String before = slice.read("RingInstance.java");

        Assertions.assertAll(
            () -> assertTrue(before.contains("extends AbstractInstance"),
                "PROOF OF LIFE, the superclass: with none this would refuse as NO_SUPERCLASS"
                    + " and prove something else entirely:\n" + before),
            () -> assertTrue(slice.read("../AbstractInstance.java")
                    .contains("this.messageManager = messageManager;"),
                "PROOF OF LIFE, the superclass ASSIGNING its own state — which is where the"
                    + " subclass's constructor body already went"));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("direction", "pull_up_constructor_body");
        args.put("symbol", "com.iluwatar.leaderelection.ring.RingInstance#RingInstance");

        ToolResponse r = new HierarchyTool(slice::service, slice.cache()).execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "upstream already delegates upward with arguments"),
            () -> assertEquals(PullUpConstructorBodyTool.Refusal.ALREADY_CALLS_SUPER,
                r.getError().getReason(),
                "and it must be THIS refusal — a reason code rather than a substring, because"
                    + " more than one branch could be argued for a class like this: "
                    + r.getError()),
            () -> assertEquals(before, slice.read("RingInstance.java"),
                "upstream's file is untouched, which a refusal must leave true"));
    }
}
