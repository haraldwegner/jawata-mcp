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
 * going.</b> Every converted door's schema now reads {@code publishedKinds()}, which
 * {@link KindedTool} defines AS the key set — so comparing the two compares a value with
 * itself. Deleting those assertions is the whole point: an assertion that cannot fail reads as
 * coverage while covering nothing.</p>
 *
 * <p>{@code refactor_to_pattern} is the exception, and the reason is measured rather than
 * stylistic. Its {@code KINDS} constant survives because {@code patternKinds()} is
 * {@code static} and has four callers — {@code CureLookup} and {@code CureTier} in production,
 * two in tests — and a static method cannot read an instance's delegates. So the constant is
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
