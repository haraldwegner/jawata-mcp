package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.OperationRegistry;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, S8b step 6 — <b>the address the product renders must be one the door
 * actually accepts.</b>
 *
 * <p>A cure names an operation; {@link OperationRegistry#invocationOf} turns that into the
 * call a reader is told to make. Rendering it as {@code "<tool> kind=<kind>"} is right for
 * eight doors and WRONG for {@code hierarchy}, which has always selected on {@code
 * direction} — so every address this product printed for that door was an instruction the
 * product itself refuses. It predates Stage 7 (it was already true of {@code up} and {@code
 * down}) and Stage 7 multiplied it from two operations to seven.</p>
 *
 * <h2>Why this drives the door instead of comparing two strings</h2>
 *
 * <p>A test that asserted {@code invocationOf} contains {@code "direction="} would pass the
 * moment the renderer says so, whether or not the door agrees. The claim is about
 * ACCEPTANCE, so the door is asked: the rendered discriminator is put on an otherwise empty
 * argument node and the door is invoked with it.</p>
 *
 * <h2>The vacuity trap this had to be built around, and it is measured rather than feared</h2>
 *
 * <p>{@link AbstractTool#execute} answers {@code projectNotLoaded()} at its own gate when a
 * tool that requires a project is handed none — BEFORE {@code executeWithService} runs. A
 * gate built on {@code execute} with a null service would therefore be refused for the
 * project every time, never reach the discriminator, and PASS AT HEAD while measuring
 * nothing. So the door is entered one level down, where the discriminator is read. That path
 * is null-safe as far as this test needs: {@code FqnTarget.materializePosition} returns
 * empty before touching the service when no {@code symbol}/{@code typeName} is given, which
 * is the case here.</p>
 *
 * <p><b>What a THROWN exception means, stated so the catch is not read as a workaround.</b>
 * Past the discriminator the door dispatches to a delegate, and a delegate handed a null
 * service may well throw. That is not this test's subject — reaching the delegate AT ALL is
 * proof the door accepted the rendered discriminator, which is exactly the claim. So a
 * throwable counts as routed, and the door is judged ONLY on whether it refused with its own
 * dispatch guard's wording.</p>
 *
 * <h2>The needle is wording only the dispatch guard emits</h2>
 *
 * <p>Every one of the nine doors refuses a missing or unknown discriminator with {@code
 * "<discriminator> is required; one of "} or {@code "Unknown <discriminator> '"}, and no
 * delegate emits either, because a delegate never reads the door's discriminator. The needle
 * is built from THE DOOR'S OWN {@link FrontDoor#discriminator()} rather than from the
 * rendered text — which is the whole point at HEAD, where {@code hierarchy} is handed {@code
 * kind=} and answers about {@code direction}. A needle taken from the rendered string would
 * look for the wrong word and report success.</p>
 *
 * <p>The door list is hand-written here and is the same nine {@code
 * TheCureTableRefusesAnAmbiguousStepTest} mirrors. Step 8 extracts {@code refactoringDoors()}
 * out of the application and re-points both at it; until then this comment is the marker.</p>
 */
class EveryRenderedInvocationIsAcceptedByItsDoorTest {

    private static final ObjectMapper OM = new ObjectMapper();

    private static List<AbstractTool> doors() {
        Supplier<IJdtService> none = () -> null;
        RefactoringChangeCache cache = new RefactoringChangeCache();
        return List.of(
            new RefactorToPatternTool(none, cache),
            new ExtractTool(none, cache),
            new MoveTool(none, cache),
            new InlineTool(none, cache),
            new HierarchyTool(none, cache),
            new DataTool(none, cache),
            new org.jawata.mcp.tools.codegen.GenerateTool(none, cache),
            new ChangeMethodSignatureTool(none, cache),
            new ApplyCleanupTool(none, cache));
    }

    @Test
    @DisplayName("every rendered invocation names a discriminator its own door accepts")
    void everyRenderedInvocationIsAcceptedByItsDoor() {
        // THE DOORS ARE PUBLISHED THE WAY THE APPLICATION PUBLISHES THEM. Hand-registering
        // them here would let this test hand the registry the discriminator and then assert
        // the renderer echoed it back — true of any value, including a wrong one. Going
        // through OperationSurface.publish is what puts the door's own declaration in the
        // chain, so the gate covers tool -> publish -> registry -> render rather than the
        // last hop alone. The singleton is borrowed and given back.
        OperationRegistry registry = OperationRegistry.theRegistry();
        OperationRegistry.Snapshot borrowed = registry.snapshot();
        List<String> refused = new ArrayList<>();
        int examined = 0;
        try {
            registry.clear();
            for (AbstractTool door : doors()) {
                OperationSurface.publish(door);
            }

            for (AbstractTool door : doors()) {
                List<String> kinds = door.publishedKinds();
                // PROOF OF LIFE, per door: a door that silently stopped publishing would
                // otherwise shrink this loop and leave the assertion below green.
                assertFalse(kinds.isEmpty(),
                    () -> "PROOF OF LIFE: " + door.getName() + " publishes no kinds, so this"
                        + " gate examines nothing for it");
                String discriminator = ((FrontDoor) door).discriminator();
                for (String kind : kinds) {
                    examined++;
                    String rendered = registry.invocationOf(
                        OperationRegistry.qualify(door.getName(), kind));
                    String message = refusalFor(door, rendered);
                    if (message != null
                        && (message.contains(discriminator + " is required; one of ")
                            || message.contains("Unknown " + discriminator + " '"))) {
                        refused.add(rendered + "  ->  " + message);
                    }
                }
            }
        } finally {
            registry.restore(borrowed);
        }

        int seen = examined;
        assertTrue(refused.isEmpty(),
            () -> "THE PRODUCT RENDERS AN INSTRUCTION IT REFUSES. Each line is an address a"
                + " cure would print, followed by what the door said when handed it."
                + " Examined " + seen + " operations across " + doors().size() + " doors;"
                + " " + refused.size() + " are unrunnable as rendered:\n  "
                + String.join("\n  ", refused));
    }

    /**
     * Drive the door with the rendered discriminator and return its refusal message, or
     * null when it did not refuse in a way this test can read.
     *
     * <p>A throwable is null — see the class note: past the discriminator the delegate runs
     * with a null service, and getting that far is the acceptance being asserted.</p>
     */
    private static String refusalFor(AbstractTool door, String rendered) {
        int space = rendered.indexOf(' ');
        int equals = rendered.indexOf('=', space + 1);
        if (space < 0 || equals < 0) {
            return null;
        }
        ObjectNode args = OM.createObjectNode();
        args.put(rendered.substring(space + 1, equals), rendered.substring(equals + 1));
        try {
            ToolResponse response = door.executeWithService(null, args);
            if (response == null || response.isSuccess() || response.getError() == null) {
                return null;
            }
            return String.valueOf(response.getError().getMessage());
        } catch (Throwable routedPastTheDiscriminator) {
            return null;
        }
    }
}
