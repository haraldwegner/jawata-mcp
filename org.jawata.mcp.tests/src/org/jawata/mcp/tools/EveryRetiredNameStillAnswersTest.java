package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A RETIRED TOOL NAME IS A BROKEN CONTRACT UNLESS IT ANSWERS.
 *
 * <p>Sprint 28d-rescue stage 1 removed six published names: four tools folded into a
 * front door that already did their job, and two renamed because absorbing new
 * operations made their names narrower than their contents.</p>
 *
 * <p>Callers outside this workspace cannot be enumerated. Nothing can search another
 * team's scripts, another editor's saved prompts, or a configuration file written six
 * months ago, so the rename map is not a convenience — it is the ONLY guarantee any of
 * those callers has. A retired name that answers "Tool not found" and nothing else
 * turns a rename into an outage the caller has to diagnose.</p>
 *
 * <p>And for a FOLD the front door alone is not enough. Told only that
 * {@code replace_duplicates} is now {@code extract}, a caller has six kinds to guess
 * between. Each fold's pointer names the kind, which is the whole cost the fold was
 * meant to save them.</p>
 */
class EveryRetiredNameStillAnswersTest {

    /**
     * The six, and what each must point at. Written out rather than read from the map
     * under test: a test that reads its subject's own table asserts only that the table
     * equals itself, and would pass just as well if every value were wrong.
     */
    private static Map<String, String> theSix() {
        Map<String, String> m = new LinkedHashMap<>();
        // The four folds.
        m.put("move_method", "move kind=method");
        m.put("convert_anonymous_to_lambda",
            "refactor_to_pattern kind=replace_pattern_with_idiom");
        m.put("replace_duplicates", "extract kind=replace_inline_code");
        m.put("optimize_imports_workspace", "organize_imports with scope=workspace");
        // The two renames.
        m.put("encapsulate_field", "data");
        m.put("move_in_hierarchy", "hierarchy");
        return m;
    }

    @Test
    @DisplayName("each of the six retired names answers with the pointer a caller can act on")
    void eachRetiredNameAnswersWithItsPointer() {
        ToolRegistry registry = new ToolRegistry();
        ObjectMapper mapper = new ObjectMapper();

        theSix().forEach((retired, pointer) -> {
            ToolRegistry.ToolNotFoundException thrown =
                assertThrows(ToolRegistry.ToolNotFoundException.class,
                    () -> registry.callTool(retired, mapper.createObjectNode()),
                    retired + " is retired and must not resolve to a tool");
            String message = thrown.getMessage();
            assertTrue(message.contains(retired),
                "the refusal must name what was called: " + message);
            assertTrue(message.contains(pointer),
                retired + " must point at '" + pointer + "'. A caller outside this"
                    + " workspace cannot be found and told; this message is the only"
                    + " notice they get. Got: " + message + ")");
        });
    }

    @Test
    @DisplayName("a fold's pointer names the KIND, not just the front door")
    void aFoldsPointerNamesTheKind() {
        ToolRegistry registry = new ToolRegistry();
        ObjectMapper mapper = new ObjectMapper();

        for (String folded : new String[] {"move_method", "replace_duplicates",
                "convert_anonymous_to_lambda"}) {
            ToolRegistry.ToolNotFoundException thrown =
                assertThrows(ToolRegistry.ToolNotFoundException.class,
                    () -> registry.callTool(folded, mapper.createObjectNode()));
            assertTrue(thrown.getMessage().contains("kind="),
                folded + " folded into ONE KIND of a front door with several. Pointing at"
                    + " the front door alone leaves the caller to guess which, which is"
                    + " the work the fold was supposed to remove: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("EVERY pointer resolves, not just the six this test writes out")
    void everyPointerInTheTableResolvesToSomethingLive() {
        ToolRegistry registry = new ToolRegistry();
        ObjectMapper mapper = new ObjectMapper();

        // The six above are the CLAIM — that each retired name points somewhere useful.
        // This is the INVARIANT, and it covers the rows nobody is currently thinking
        // about. `pull_up` has pointed at `move_in_hierarchy` since Sprint 19; stage 1
        // renamed that tool, and a single-hop lookup left a caller of `pull_up` pointed
        // at a second name that is also not found. A hand-written list of six could not
        // see it, because `pull_up` is not one of the six.
        for (String retired : ToolRegistry.retiredNames()) {
            ToolRegistry.ToolNotFoundException thrown =
                assertThrows(ToolRegistry.ToolNotFoundException.class,
                    () -> registry.callTool(retired, mapper.createObjectNode()),
                    retired + " is in the rename map, so it must not resolve to a tool");
            String pointer = ToolRegistry.pointerFor(retired);
            assertNotNull(pointer, retired + " must have a pointer");
            String head = pointer.contains(" ") ? pointer.substring(0, pointer.indexOf(' '))
                                                : pointer;
            assertNull(ToolRegistry.pointerFor(head),
                retired + " points at '" + pointer + "', whose front door '" + head
                    + "' is ITSELF a retired name. A caller following this pointer arrives"
                    + " at a second not-found. Resolution must land on a live tool.");
            assertTrue(thrown.getMessage().contains(head),
                "and the refusal must carry the resolved pointer: " + thrown.getMessage());
        }
    }

    @Test
    @DisplayName("a name that was never a tool gets no invented pointer")
    void anUnknownNameGetsNoPointer() {
        ToolRegistry registry = new ToolRegistry();
        ObjectMapper mapper = new ObjectMapper();
        ToolRegistry.ToolNotFoundException thrown =
            assertThrows(ToolRegistry.ToolNotFoundException.class,
                () -> registry.callTool("refactor_the_whole_thing", mapper.createObjectNode()));
        assertFalse(thrown.getMessage().contains("Did you mean"),
            "the map answers for names that WERE published; guessing for anything else"
                + " would send a caller somewhere on no evidence: " + thrown.getMessage());
    }
}
