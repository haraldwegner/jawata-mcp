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
 * the routing guard's set. Every one of them was a copy of the same fact, and by C8 the
 * memberships were <b>10 · 8 · 6 · 8</b> against a true population of NINE.</p>
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
}
