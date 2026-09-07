package org.jawata.mcp.tools;

import org.jawata.core.IJdtService;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.util.List;
import java.util.function.Supplier;

/**
 * THE REFACTORING FRONT DOORS, IN ONE PLACE — the population, not a description of it.
 *
 * <p>Sprint 28d-rescue, S8b step 8. Four different files held a hand-written list of these
 * doors: the application's registrations, the cure table's mirror, the honesty test's map and
 * the routing guard's set. Every one of them was a copy of the same fact, and no two agreed.</p>
 *
 * <p><b>A QUARTET OF COUNTS STOOD HERE AND IS GONE, which a C8b audit is owed for.</b> It read
 * "10 · 8 · 6 · 8 against a true population of NINE" and the last figure was the only
 * reproducible one. Re-derived at C8 from the files themselves: the honesty test's map held
 * TEN, the routing guard's set SIX, and the value 8 described none of the four. The sentence
 * was a hand-written count of hand-written lists — the very shape this class exists to remove,
 * in the paragraph explaining why. What survives is the part that needs no arithmetic and is
 * checkable at any commit: they disagreed, and the disagreement is below.</p>
 *
 * <h2>The errors were COMPLEMENTARY, which is what made them one defect rather than four
 * omissions</h2>
 *
 * <p>The operation allowlist omitted {@code change_method_signature}, so its eleven kinds
 * could not be routed at all — and the routing guard then EXAMINED that door and PASSED,
 * because the gap the first list created is exactly what made the second list's silence
 * correct. Inversely the routing guard omitted {@code data}, whose kinds are routable and
 * three of which are routed. Each list's blind spot sat where the other could see, and
 * neither could see the other. One missing object, two vantage points.</p>
 *
 * <p>So the list is not maintained here — it is CONSTRUCTED here, once, and everything that
 * needs to know the door set asks. A door added to this method is registered by the
 * application, mirrored by the cure table, covered by the honesty test and examined by the
 * routing guard in the same edit, because none of them holds a name any more.</p>
 *
 * <h2>Why the order is the order it is</h2>
 *
 * <p>{@code ToolRegistry} keeps its tools in a {@link java.util.LinkedHashMap}, so the
 * sequence below is the sequence a client sees in {@code tools/list}. It reproduces the
 * relative order the nine scattered registrations had, so consolidating them moves the doors
 * as a block rather than shuffling them among themselves.</p>
 *
 * <p><b>What is NOT here:</b> {@code refactoring}, the lifecycle door. Its verbs are actions
 * over a change the doors above produced rather than transformations of code, and it
 * deliberately implements {@link FrontDoor} and not {@link KindedTool} so its verbs never
 * enter the operation namespace. Including it here would put them there through the back.</p>
 */
public final class RefactoringDoors {

    private RefactoringDoors() {
    }

    /**
     * Every refactoring front door, freshly constructed against the given service and cache.
     *
     * <p>New instances each call, deliberately: the application wires one set into its
     * registry and a test wires another into a registry of its own, and a shared instance
     * would let one of them observe the other's state.</p>
     */
    public static List<AbstractTool> all(Supplier<IJdtService> service,
                                         RefactoringChangeCache cache) {
        return List.of(
            new ExtractTool(service, cache),
            new InlineTool(service, cache),
            new MoveTool(service, cache),
            new HierarchyTool(service, cache),
            new org.jawata.mcp.tools.codegen.GenerateTool(service, cache),
            new RefactorToPatternTool(service, cache),
            new ChangeMethodSignatureTool(service, cache),
            new DataTool(service, cache),
            new ApplyCleanupTool(service, cache));
    }

    /**
     * The standalone refactoring tools — the ones that PERFORM a refactoring without
     * dispatching on a discriminator, so they are not front doors and {@link #all} does not
     * carry them.
     *
     * <p><b>Why this is here rather than in a class of its own, stated because the class name
     * says "Doors" and these are not doors.</b> What this class actually owns is <i>the
     * refactoring tools the application registers</i>, and the door set is the large half of
     * that. Splitting the small half into a second file would put two halves of one
     * registration in two places, which is the shape the paragraphs above exist to remove.</p>
     *
     * <p><b>What it cost while these two were registered inline — measured at C9.</b>
     * {@code PerformedRefactoringCountTest} counts Fowler's 62 by joining each catalogue row
     * against what the product publishes, and it took the door half from {@link #all} and the
     * {@code rename_symbol} half from a tool IT CONSTRUCTED. So rows 39 and 40 counted toward
     * 62 on the strength of a tool the test instantiated rather than one the application
     * registers: deleting the registration would have left the test green while
     * {@code rename_symbol} shipped to nobody. Sixty rows were counted from the shipped list
     * and two were not, in the test written to close a deviation about exactly that. An
     * architect watch found it; nothing else could have, because the vacuous half passes.</p>
     *
     * <p>New instances each call, for the reason {@link #all} gives.</p>
     */
    public static List<AbstractTool> standalone(Supplier<IJdtService> service,
                                                RefactoringChangeCache cache) {
        return List.of(
            new RenameSymbolTool(service, cache),
            new OrganizeImportsTool(service, cache));
    }
}
