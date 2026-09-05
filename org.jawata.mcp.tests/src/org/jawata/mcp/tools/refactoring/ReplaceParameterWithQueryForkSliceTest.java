package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.api.ReplaceParameterWithQueryTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 53 ON CODE WE DID NOT AUTHOR — the corpus's ONLY instance of the trigger, and it is the
 * case Fowler warns about.
 *
 * <h2>The census, and it is a census rather than a sample</h2>
 *
 * <p>Over the fork's <b>1354 main sources</b> there are <b>2683 multi-argument call sites</b>, and
 * <b>41</b> of them pass {@code x.something()} for one parameter and the bare {@code x} for
 * another — this row's trigger. Thirty-nine are {@code map.put(thing.getId(), thing)} or
 * {@code new SomeException(e.getMessage(), e)}: a JDK or JDK-shaped method with no source to
 * change. The two that remain are both calls to {@code Feind.fightForTheSword}, one of them the
 * method's own recursive call, and they agree on the query and on which argument is its receiver.
 * So the corpus offers exactly one candidate for this row, and this is it.</p>
 *
 * <h2>What it pins, and why the refusal is a finding rather than a limitation</h2>
 *
 * <p>Upstream's {@code holder} parameter is always {@code sword.getLocker()} at both call sites,
 * so unanimity holds and every condition this row had at the time it was written passed. It is
 * read FIVE times, four of them inside a {@code while} loop whose body attacks the holder and can
 * release the sword — so substituting the query turns one evaluation into one per read, and the
 * reads can answer differently. That change COMPILES, which is why no gate below this row could
 * catch it, and it is Fowler's own precondition: not when the query depends on state the function
 * modifies.</p>
 *
 * <p>The row did not have that precondition until this slice was read. Every fixture written for
 * it reads its parameter once, in a straight line, in a method that mutates nothing — which is the
 * shape a fixture takes when its author is the same person who wrote the rule.</p>
 */
class ReplaceParameterWithQueryForkSliceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PKG = "com/iluwatar/lockableobject/domain";

    /** Upstream's own line — the outer call, whose derivation the recursive one repeats. */
    private static final String UPSTREAM_CALL = "fightForTheSword(creature, target.getLocker()";

    @Test
    @DisplayName("row 53 REFUSES upstream's fightForTheSword, because its holder is read five "
        + "times inside a loop that can change what the query answers")
    void refusesUpstreamsRepeatedlyReadParameter() throws Exception {
        ForkSliceSupport.Slice slice = ForkSliceSupport.load(helper, "fork-lockable-object", PKG,
            "Feind.java", UPSTREAM_CALL);
        String before = slice.read("Feind.java");

        Assertions.assertAll(
            () -> assertTrue(before.contains("fightForTheSword(reacher, sword.getLocker()"),
                "PROOF OF LIFE, the second call site: without a SECOND caller deriving it the"
                    + " same way there is no unanimity and the refusal below would be that"
                    + " instead:\n" + before),
            () -> assertTrue(before.contains("while (this.target.isLocked()"),
                "PROOF OF LIFE, the loop: it is what makes the repeated evaluation observable"
                    + " rather than merely wasteful:\n" + before));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("kind", "replace_parameter_with_query");
        args.put("symbol", "com.iluwatar.lockableobject.domain.Feind#fightForTheSword");
        args.put("parameter", "holder");

        ToolResponse r = new ChangeMethodSignatureTool(slice::service, slice.cache()).execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(),
                "the callers agree, so without the re-evaluation precondition this SUCCEEDS and"
                    + " ships a method that re-reads the locker on every pass of the fight"),
            () -> assertEquals(ReplaceParameterWithQueryTool.Refusal.PARAMETER_READ_REPEATEDLY,
                r.getError().getReason(),
                "and it must refuse for THAT reason rather than for unanimity, which holds here,"
                    + " or for anything the unresolved lombok imports cause: " + r.getError()),
            () -> assertEquals(before, slice.read("Feind.java"),
                "upstream's file is untouched"));
    }
}
