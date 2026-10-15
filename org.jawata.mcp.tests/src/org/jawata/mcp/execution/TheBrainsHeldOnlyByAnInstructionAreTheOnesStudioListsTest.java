package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.TreeSet;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S16-39: the engine's side of a contract with jawata-studio.
 *
 * <p>jawata-studio's Seats view writes {@code accepts_uncontained_writes: true} into a seat file
 * when the user picks a brain whose writes are held only by an instruction, and it keeps its own
 * list of those brains ({@code brains::HELD_BY_AN_INSTRUCTION} in jawata-studio's
 * {@code src-tauri/src/brains.rs}). The engine decides which brains those are, through each
 * dialect's {@link BrainDialect#writeContainment()}. Nothing derives one from the other across the
 * two repositories, so BOTH sides pin the same fact: this test holds the engine's answer, and a
 * test in {@code brains.rs} holds studio's list. If the engine's answer for a brain changes, this
 * goes red and says which file to change.</p>
 */
class TheBrainsHeldOnlyByAnInstructionAreTheOnesStudioListsTest {

    /** The brains studio can offer a seat, by the name a seat file gives them. */
    private static final List<String> STUDIO_HIRABLE = List.of("claude-code", "codex", "cursor", "copilot");

    private static final SelfEndpoint SELF = new SelfEndpoint("http://127.0.0.1:1/mcp", "t");

    @Test
    @DisplayName("exactly the brains studio lists as held only by an instruction are the ones that are not contained")
    void theEnginesAnswerMatchesStudiosList() {
        TreeSet<String> notContained = new TreeSet<>();
        for (String brain : STUDIO_HIRABLE) {
            SeatHire seat = SeatHire.read("---\nname: probe\nbrain: " + brain + "\n---\n");
            // As a resident serving HTTP hires: claude is given the endpoint, which is what makes
            // its answer a mechanism.
            if (!seat.dialect(SELF).writeContainment().isContained()) {
                notContained.add(brain);
            }
        }

        assertEquals(new TreeSet<>(List.of("cursor")), notContained,
            "jawata-studio keeps this list as brains::HELD_BY_AN_INSTRUCTION in"
                + " src-tauri/src/brains.rs (and a test there pins it to the same value). If this"
                + " answer changed on purpose, change that list, its test, and what the Seats view"
                + " says beside the choice");
    }
}
