package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import org.jawata.mcp.engine.PromptSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28h, S14 (D21): the seat files of a folder are what the prompt list offers, read again on every call.
 */
class SeatPromptsTest {

    @TempDir
    Path folder;

    private void seat(String file, String front, String body) throws IOException {
        Files.writeString(folder.resolve(file), "---\n" + front + "\n---\n\n" + body + "\n");
    }

    private SeatPrompts prompts() {
        return new SeatPrompts(() -> Optional.of(folder));
    }

    private static List<String> names(SeatPrompts prompts) {
        return prompts.list().stream().map(PromptSource.Prompt::name).toList();
    }

    @Test
    @DisplayName("a seat is offered under its command, titled with its name, described by its purpose, and its text is its stance")
    void aSeatIsOfferedUnderItsCommand() throws IOException {
        seat("architect.md", "name: architect\ncommand: refactor\npurpose: Reviews the design.", "You are the architect seat.");

        List<PromptSource.Prompt> listed = prompts().list();

        assertEquals(1, listed.size());
        assertEquals("refactor", listed.get(0).name());
        assertEquals("architect (/refactor)", listed.get(0).title());
        assertEquals("Reviews the design.", listed.get(0).description());
        assertEquals(Optional.of("You are the architect seat."), prompts().text("refactor"));
    }

    @Test
    @DisplayName("a seat with no command is offered under its own name")
    void aSeatWithNoCommandIsOfferedUnderItsName() throws IOException {
        seat("worker.md", "name: worker", "You are the worker.");

        PromptSource.Prompt only = prompts().list().get(0);

        assertEquals("worker", only.name());
        assertEquals("worker", only.title());
        assertEquals("", only.description());
    }

    @Test
    @DisplayName("a seat switched off is not offered, and a seat written afterwards is, on the very next call")
    void theFolderIsReadOnEveryCall() throws IOException {
        seat("a.md", "name: a\nenabled: false", "You are a.");
        seat("b.md", "name: b", "You are b.");
        SeatPrompts prompts = prompts();
        assertEquals(List.of("b"), names(prompts));

        seat("c.md", "name: c", "You are c.");
        seat("a.md", "name: a\nenabled: true", "You are a.");

        assertEquals(List.of("a", "b", "c"), names(prompts));
    }

    @Test
    @DisplayName("a name no seat has gives no text")
    void anUnknownNameGivesNoText() throws IOException {
        seat("b.md", "name: b", "You are b.");

        assertTrue(prompts().text("nobody").isEmpty());
    }

    @Test
    @DisplayName("a switch misspelled in a seat file keeps that seat out and leaves the others offered")
    void aMisspelledSwitchKeepsOnlyThatSeatOut() throws IOException {
        seat("a.md", "name: a\nenabled: yes", "You are a.");
        seat("b.md", "name: b", "You are b.");

        assertEquals(List.of("b"), names(prompts()));
    }

    @Test
    @DisplayName("a seat file with nothing below its frontmatter has no text to offer, so it is not offered")
    void aSeatWithNoStanceIsNotOffered() throws IOException {
        seat("a.md", "name: a", "");

        assertTrue(prompts().list().isEmpty());
    }

    @Test
    @DisplayName("two seats claiming one name: the first file keeps it and the second is not offered")
    void aNameTwoSeatsClaimGoesToTheFirst() throws IOException {
        seat("a.md", "name: a\ncommand: go", "You are a.");
        seat("b.md", "name: b\ncommand: go", "You are b.");

        assertEquals(List.of("go"), names(prompts()));
        assertEquals(Optional.of("You are a."), prompts().text("go"));
    }

    @Test
    @DisplayName("no folder set offers nothing, and a folder that does not exist offers nothing")
    void noFolderOffersNothing() {
        assertTrue(new SeatPrompts(Optional::empty).list().isEmpty());
        assertTrue(new SeatPrompts(() -> Optional.of(folder.resolve("absent"))).list().isEmpty());
    }

    @Test
    @DisplayName("files that are not seat files (a settings file, a backup) are not offered")
    void onlyMarkdownIsOffered() throws IOException {
        Files.writeString(folder.resolve("seat-settings.json"), "{}");
        Files.writeString(folder.resolve("a.md.pre-reset"), "---\nname: a\n---\n\nYou are a.\n");

        assertTrue(prompts().list().isEmpty());
    }
}
