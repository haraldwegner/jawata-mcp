package org.jawata.mcp.tools.smell;

import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE ROSTER — the one place that knows which detector kinds exist.
 *
 * <p><b>Why it exists.</b> The kind-count assertion used to live inside one
 * detector's own test, so adding a second principle detector turned the FIRST
 * one's test red and the fix was to edit a literal in a file that had nothing to
 * do with the change. Two more detectors would have meant two more such edits.
 * That is shotgun surgery — the exact smell this sprint builds cures for —
 * appearing in our own tests, and the cure is the ordinary one: give the fact a
 * single home.</p>
 *
 * <p><b>What each side now owns.</b> A detector's own test asserts that ITS kind
 * is registered and that it detects what it claims. This test owns the roster:
 * the baseline the sprint started from, the kinds it adds, and the arithmetic
 * between them. Adding a detector edits {@link #ADDED_BY_28D} and nothing
 * else.</p>
 *
 * <p><b>Why a count survives at all, rather than only a set.</b> A set
 * assertion cannot see a kind that appeared without anyone intending it — a
 * merge, a copied registration, a rename that leaves both names live. The count
 * is what makes an unplanned arrival fail; the set is what makes the failure
 * legible. Neither alone does both jobs.</p>
 */
class PrincipleDetectorKindsTest {

    /**
     * The kinds registered before Sprint 28d began, measured at C0 by reading
     * the enum the tool returns — NOT by counting detector files, and not by
     * reasoning about which kinds "really count". The C0 baseline first said 30
     * because six kinds needing input parameters were subtracted; they are
     * registered kinds and the subtraction was reasoning applied to a list
     * instead of reading its length.
     */
    static final int BASELINE_BEFORE_28D = 36;

    /**
     * The principle detectors this sprint adds, in the order they ship.
     * <b>Adding a detector edits THIS LIST and nothing else.</b>
     */
    static final List<String> ADDED_BY_28D = List.of("cqs", "coupling");

    private List<String> registeredKinds() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> null);
        @SuppressWarnings("unchecked")
        Map<String, Object> properties =
            (Map<String, Object>) tool.getInputSchema().get("properties");
        @SuppressWarnings("unchecked")
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        @SuppressWarnings("unchecked")
        List<String> kinds = (List<String>) kind.get("enum");
        return kinds;
    }

    @Test
    @DisplayName("every kind this sprint adds is registered, and nothing else arrived unnoticed")
    void theRosterIsExactlyTheBaselinePlusWhatWeAdded() {
        List<String> kinds = registeredKinds();

        for (String added : ADDED_BY_28D) {
            assertTrue(kinds.contains(added),
                () -> "kind '" + added + "' is on this sprint's roster but is NOT registered"
                    + " — a detector built and not reachable through find_quality_issue is"
                    + " not shipped, however green its own test: " + kinds);
        }

        assertEquals(BASELINE_BEFORE_28D + ADDED_BY_28D.size(), kinds.size(),
            () -> "the registered kinds are " + kinds.size() + ", the roster expects "
                + (BASELINE_BEFORE_28D + ADDED_BY_28D.size()) + " (" + BASELINE_BEFORE_28D
                + " before this sprint + " + ADDED_BY_28D + "). A HIGHER number means a kind"
                + " arrived that nobody put on the roster — a copied registration, a rename"
                + " that left both names live — which a set assertion alone cannot see."
                + " Registered: " + kinds);
    }

    @Test
    @DisplayName("no kind is registered twice")
    void noKindIsRegisteredTwice() {
        List<String> kinds = registeredKinds();
        assertEquals(kinds.size(), kinds.stream().distinct().count(),
            () -> "a duplicate kind would make the count arithmetic above pass for the"
                + " wrong reason — one kind lost, one kind doubled: " + kinds);
    }
}
