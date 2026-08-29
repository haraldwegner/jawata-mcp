package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.InlineMethodTool;
import org.jawata.mcp.tools.RefactorToPatternTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d S9.0 — THE SPIKE: is a round trip constructible at all?
 *
 * <h2>What the measurement said before a line of this was written</h2>
 *
 * <p>D3 wants a canonical implementation taken AWAY from its pattern and then
 * back TOWARD it, landing on the human's own bytes. That needs both directions
 * for one pattern. Counted off the front door's own description: <b>2 AWAY
 * operations and 8 TOWARD</b>, and <b>no pattern has both</b>. {@code
 * inline_singleton} has no toward-singleton; {@code replace_pattern_with_idiom}
 * has no lambda-to-anonymous. So no pairing exists inside
 * {@code refactor_to_pattern} at all.</p>
 *
 * <p>One pairing exists OUTSIDE it, and this spike is here to find out whether
 * it is real: {@code inline(kind=method)} should invert
 * {@code replace_constructor_with_factory}, because the factory that operation
 * generates is exactly a static method wrapping a constructor — the shape
 * inlining collapses.</p>
 *
 * <h2>Why the direction is reversed, and what that costs</h2>
 *
 * <p>D3 says AWAY first, from a canonical implementation. Doing it that way
 * needs a human-authored SELF-RETURNING STATIC FACTORY as the starting point,
 * because that is the only factory shape our TOWARD direction produces.</p>
 *
 * <p><b>CORRECTED at C9 after the auditor re-ran the survey.</b> This javadoc
 * said "six sites, in four classes, every one carries Lombok". Both halves were
 * wrong. The measurement is now reproducible —
 * {@code build/survey-self-returning-factories.py}, which records what broke the
 * first two attempts — and it reports <b>nine sites in six classes</b>, of which
 * <b>one is dependency-free</b>: {@code monad/Validator.of()}.</p>
 *
 * <p><b>The conclusion survives, on a different and stronger reason.</b>
 * {@code Validator}'s constructor is PRIVATE, and this trip is only defined
 * where the old path stays open — the AWAY leg folds the factory back into its
 * callers, which cannot compile against a constructor they may not reach. So of
 * the nine, <b>zero</b> can serve as an AWAY-first original: eight carry a
 * dependency the fixtures exclude, and the ninth cannot be inlined into. C9 asks
 * for three.</p>
 *
 * <p>Lombok was never the real blocker; constructor accessibility is. The first
 * claim happened to reach the right conclusion by a route that does not hold.</p>
 *
 * <p>So this runs TOWARD then AWAY instead, starting from human code that has
 * no factory — a public constructor and its call sites, which the fork has in
 * abundance. It tests the same property, composition equals identity, anchored
 * on human bytes at both ends.</p>
 *
 * <p><b>What it does NOT test, stated rather than left to be discovered:</b>
 * that our TOWARD direction reproduces the factory a HUMAN would have written.
 * The literal reading tests that; this does not, and the literal reading is the
 * one with no clean corpus. A human writes an instance factory on a separate
 * type; this operation writes a static method on the type itself.</p>
 */
class RoundTripSpikeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool pattern;
    private InlineMethodTool inline;
    private ObjectMapper mapper;
    private Path shipmentFile;
    private Path dispatcherFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("factory-target");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        pattern = new RefactorToPatternTool(() -> service, cache);
        inline = new InlineMethodTool(() -> service, cache);
        mapper = new ObjectMapper();
        Path pkg = helper.getTempDirectory()
            .resolve("factory-target/src/main/java/com/example/factory");
        shipmentFile = pkg.resolve("Shipment.java");
        dispatcherFile = pkg.resolve("Dispatcher.java");
    }

    private static int lineOf(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("source no longer contains: " + needle);
    }

    /**
     * THE SPIKE. Green means Stage 9 has a mechanism; red means D3 needs an
     * inverse operation built, which is a stage of its own and a decision.
     */
    @Test
    @DisplayName("S9.0: TOWARD then AWAY returns to the human's own bytes")
    void theRoundTripClosesOnTheOriginal() throws Exception {
        String shipmentBefore = Files.readString(shipmentFile);
        String dispatcherBefore = Files.readString(dispatcherFile);
        assertTrue(dispatcherBefore.contains("new Shipment("),
            "PROOF OF LIFE: the original must construct directly, or the trip starts"
                + " where it is supposed to end");

        // ---- TOWARD: the constructor becomes a static factory ---------------
        // protectConstructor=false is REQUIRED and it is not a convenience: the
        // default makes the constructor private, and inlining the factory would
        // then rewrite call sites into a constructor they cannot reach. The
        // round trip is only defined where the old path stays open.
        ObjectNode toward = mapper.createObjectNode();
        toward.put("kind", "replace_constructor_with_factory");
        toward.put("filePath", shipmentFile.toString());
        int ctorLine = lineOf(shipmentBefore, "public Shipment(String destination");
        toward.put("line", ctorLine);
        toward.put("column", shipmentBefore.split("\n", -1)[ctorLine].indexOf("Shipment("));
        toward.put("factoryMethodName", "of");
        toward.put("protectConstructor", false);

        ToolResponse t = pattern.execute(toward);
        assertTrue(t.isSuccess(), () -> "TOWARD refused: " + t.getError());

        String shipmentMid = Files.readString(shipmentFile);
        String dispatcherMid = Files.readString(dispatcherFile);
        assertTrue(shipmentMid.contains("of("), () -> "the factory must exist:\n" + shipmentMid);
        assertFalse(dispatcherMid.contains("new Shipment("),
            () -> "the call sites must have moved to the factory, or the AWAY leg has"
                + " nothing to undo:\n" + dispatcherMid);

        // ---- AWAY: inline the factory, and the call sites should come back ---
        int factoryLine = lineOf(shipmentMid, " of(");
        ObjectNode away = mapper.createObjectNode();
        away.put("filePath", shipmentFile.toString());
        away.put("line", factoryLine);
        away.put("column", shipmentMid.split("\n", -1)[factoryLine].indexOf("of("));

        ToolResponse a = inline.execute(away);
        assertTrue(a.isSuccess(),
            () -> "AWAY refused — inline(kind=method) does not invert the factory, so no"
                + " round trip is constructible from the operations that exist and D3"
                + " needs an inverse operation built: " + a.getError());

        // ---- THE FIXED POINT ------------------------------------------------
        // Byte-identical, not equivalent. A trip that returns something that
        // merely compiles the same has not found a fixed point — it has found
        // two shapes a compiler cannot tell apart, which is a weaker claim and
        // not the one D3 makes.
        assertEquals(dispatcherBefore, Files.readString(dispatcherFile),
            "THE CALL SITES must return byte-identically — this is the half the round"
                + " trip is actually about, because it is the half that moved twice");
        assertEquals(shipmentBefore, Files.readString(shipmentFile),
            "THE DECLARING FILE must return byte-identically too. A leftover factory,"
                + " a changed modifier or a reflowed line means the two directions do"
                + " not compose to the identity");
    }
}
