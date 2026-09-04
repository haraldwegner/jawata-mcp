package org.jawata.mcp.tools;

import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * WHAT IS LEFT OF M3B'S TEMPORARY TEST — one door, one surviving second list.
 *
 * <p>M3b introduced this class to assert, per converted door, that the published {@code kind}
 * enum equalled {@code delegates().keySet()}. That was a real check while the enum was a
 * hand-written constant beside the routing table, and M3b's own step note says it is deleted
 * at M6 "once the assembly makes it a tautology".</p>
 *
 * <p><b>M6 made five of the six a tautology and left one alone, so the class shrank instead of
 * going.</b> Each of those five reads its published enum from {@code publishedKinds()}, which
 * {@link KindedTool} defines AS the key set — so comparing the two compares a value with
 * itself. Deleting those assertions is the whole point.</p>
 *
 * <p><b>The test is DEFINITIONAL identity, not a green result</b>, and the distinction is load
 * bearing: a later audit read it the loose way and called a live regression lock vacuous on
 * the strength of this paragraph. What makes those five assertions worthless is that the two
 * sides are ONE expression a definitional step apart, in an interface that tells implementors
 * never to override the method joining them — no edit separates them.</p>
 *
 * <p><b>And that was only true of three doors until C6a made it true of six.</b> A third audit
 * measured the claim: {@code extract} and {@code move} read a private {@code kinds()} over
 * their {@code delegates} FIELD, and {@code apply_cleanup} a private {@code KINDS} constant
 * over its {@code RULES} map — three second derivations of the same key set, each over the
 * structure {@code delegates()} wraps rather than over that method. All three derived, so the deleted assertions could not in fact have
 * failed — but the REASON given for deleting them held only where the joining method was the
 * interface's. The three private readers were deleted rather than the paragraph weakened,
 * which is the same move this whole stage is: the door asks, instead of keeping its own copy
 * of the answer.</p>
 *
 * <p>An assertion that
 * merely happens to be green today, over a property one plausible edit would break, is a
 * regression lock; it is kept, labelled as one, and proved by making that edit. See the
 * per-door assertion in {@code FrontDoorDescriptionTest#noDoorWritesItsOwnUsageLine}, which
 * was mutation-proved for exactly this reason.</p>
 *
 * <p><b>It was six, briefly, and only because a claim was false.</b> {@code generate} was
 * reported converted in the same change and was not: its schema still read a hand-written
 * constant, so the deleted comparison had been LIVE for that door — two independent structures,
 * a literal list against the delegates' own {@code kindName()}s. A C6a audit found it. The
 * cause was mechanical rather than a misreading: a mutation harness reverted that file with
 * {@code git checkout --} while the change was still uncommitted, and the commit message was
 * written from what had been done rather than from what survived. Both halves are restored,
 * and this paragraph stays because the deletion was only honest once they were.</p>
 *
 * <p>{@code refactor_to_pattern} is the exception, and the reason is measured rather than
 * stylistic. Its {@code KINDS} constant survives because {@code patternKinds()} is
 * {@code static} and is read from SIX places in FIVE classes — {@code CureLookup} and
 * {@code CureTier} in production, then this class twice, {@code CureTierTest} and
 * {@code EveryShippedFixIsReachableTest} — and a static method cannot read an instance's
 * delegates. (An earlier version of this sentence said "four callers", from memory rather
 * than from {@code find_references}; the same wrong number is in commit 10082733.) So the
 * constant is
 * still a SECOND home for the kind list, read by the code that decides which cure steps exist.
 * Nothing else compares it to the routing table. That is what this class now does, and it is
 * why deleting the class outright would have dropped a live guard rather than a dead one.</p>
 *
 * <p>It goes when {@code patternKinds()} does — when its callers read
 * {@code OperationRegistry} instead, which is where the operation namespace already lives.</p>
 */
class TheRoutingTableIsTheKindListTest {

    @Test
    @DisplayName("refactor_to_pattern's static kind list still equals the table it dispatches on")
    void theStaticKindListAgreesWithTheRoutingTable() {
        RefactorToPatternTool door =
            new RefactorToPatternTool(() -> null, new RefactoringChangeCache());

        assertEquals(List.copyOf(door.delegates().keySet()), RefactorToPatternTool.patternKinds(),
            "patternKinds() is what CureLookup and CureTier read to decide whether a cure step"
                + " names a real operation. It is a hand-written constant and the routing table"
                + " is the truth; a kind added to one and not the other makes the cure table"
                + " validate against a list of operations that is not the one the door runs");
        // PROOF OF LIFE: two empty lists are equal.
        assertEquals(11, RefactorToPatternTool.patternKinds().size(),
            "eleven pattern kinds ship; a shrinking list must be a deliberate edit here");
    }

    @Test
    @DisplayName("and each delegate agrees with the key it is routed under")
    void everyDelegateAgreesWithItsRoutingKey() {
        // The key and kindName() are two spellings of one fact — unavoidably so, because a
        // field-holding door has no key and a map-holding one has no need of the method.
        // Asserting they match is what keeps the duplication from being able to drift, and it
        // is NOT a tautology: the map is built by asking each delegate its name on some doors
        // and by literal keys on others.
        RefactoringChangeCache cache = new RefactoringChangeCache();
        java.util.function.Supplier<org.jawata.core.IJdtService> none = () -> null;
        List<KindedTool> doors = List.of(
            new ExtractTool(none, cache),
            new InlineTool(none, cache),
            new MoveTool(none, cache),
            new RefactorToPatternTool(none, cache),
            new org.jawata.mcp.tools.codegen.GenerateTool(none, cache),
            new ApplyCleanupTool(none, cache));

        int compared = 0;
        List<String> problems = new java.util.ArrayList<>();
        for (KindedTool door : doors) {
            for (java.util.Map.Entry<String, KindDelegate> routed : door.delegates().entrySet()) {
                compared++;
                if (!routed.getKey().equals(routed.getValue().kindName())) {
                    problems.add(door.getName() + ": routed as '" + routed.getKey()
                        + "' but calls itself '" + routed.getValue().kindName() + "'");
                }
            }
        }
        assertEquals(List.of(), problems, String.join("\n  ", problems));
        // PROOF OF LIFE: six doors routing nothing would satisfy the loop.
        assertEquals(50, compared,
            "the six converted doors route fifty kinds between them — extract 11, inline 5,"
                + " move 6, refactor_to_pattern 11, generate 7, apply_cleanup 10");
    }
}
