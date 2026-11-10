package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.Set;

import org.jawata.mcp.agent.HireEntry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Stage 24, D55: the first names hires carry, so two hires of one seat can be told apart. */
class HireNamesTest {

    @Test
    @DisplayName("no name repeats inside one conversation, however many hires it makes")
    void aConversationNeverGetsTheSameNameTwice() {
        HireNames names = new HireNames();
        Set<String> seen = new HashSet<>();
        List<String> repeated = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            String name = names.assign("principal-one");
            if (!seen.add(name)) {
                repeated.add(name);
            }
        }
        assertTrue(repeated.isEmpty(), "names given twice in one conversation: " + repeated);
    }

    @Test
    @DisplayName("the same conversation, replayed on a fresh engine, is given the same names in the same order")
    void aReplayOfTheSameKeyShowsTheSameNames() {
        List<String> first = new ArrayList<>();
        List<String> replay = new ArrayList<>();
        HireNames once = new HireNames();
        HireNames again = new HireNames();
        for (int i = 0; i < 5; i++) {
            first.add(once.assign("principal-replayed"));
            replay.add(again.assign("principal-replayed"));
        }
        assertEquals(first, replay, "a replay shows other names than the recording");
    }

    @Test
    @DisplayName("two hires of one seat in one conversation read differently")
    void twoHiresOfOneSeatInOneConversationAreToldApart() {
        HireNames names = new HireNames();
        String first = names.assign("principal-two");
        String second = names.assign("principal-two");
        assertAll(
            () -> assertNotEquals(first, second, "two project managers of one conversation read the same"),
            () -> assertTrue(!first.isBlank() && !second.isBlank(), "a name is blank"));
    }

    @Test
    @DisplayName("the name leads the row text and rides the card the host reads")
    void theNameIsInTheRowTextAndOnTheCard() {
        HireEntry entry = new HireEntry("call-1", "pm", Optional.of("claude-code"), Optional.of("sonnet"), HireEntry.RUNNING, 0, 0,
            OptionalDouble.empty(), "plan it", Optional.empty(), Optional.empty(), Optional.empty(), 0, 0, java.util.OptionalLong.empty(),
            Optional.empty(), Optional.empty(), Optional.of("Claudia"));
        assertAll(
            () -> assertEquals("Claudia · pm · claude-code · sonnet — running", entry.title()),
            () -> assertEquals("Claudia", entry.toMeta().path("name").asText(), "the card does not carry the name"));
    }

    @Test
    @DisplayName("found live with Cursor: hires of different leads of one chat are named from one sequence, so no two rows of the chat read the same")
    void hiresUnderDifferentLeadsOfOneChatNeverShareAName() {
        HireRegistry registry = HireRegistry.shared();
        List<String> given = new ArrayList<>();
        for (int lead = 0; lead < 12; lead++) {
            String leadPlan = "names-lead-plan-" + lead;
            registry.speaksFor(leadPlan, "names-one-chat");
            try (HireRegistry.Registration child = registry.begin("chief-engineer", "names brief " + lead, leadPlan)) {
                given.add(child.name());
            }
        }
        // The names are the chat's own sequence, in order: drawn per issuing lead they would be twelve first names of twelve other sequences.
        HireNames oneChat = new HireNames();
        List<String> expected = new ArrayList<>();
        for (int lead = 0; lead < 12; lead++) {
            expected.add(oneChat.assign("names-one-chat"));
        }
        assertEquals(expected, given, "the hires of one chat were not named from the chat's one sequence");
    }

    @Test
    @DisplayName("an entry made without a name reads as before and its card has no name field")
    void anEntryWithoutANameIsUnchanged() {
        HireEntry entry = new HireEntry("call-2", "pm", Optional.empty(), Optional.empty(), HireEntry.RUNNING, 0, 0,
            OptionalDouble.empty(), "plan it", Optional.empty());
        assertAll(
            () -> assertEquals("pm — running", entry.title()),
            () -> assertTrue(!entry.toMeta().has("name"), "a card without a name carries a placeholder"));
    }
}
