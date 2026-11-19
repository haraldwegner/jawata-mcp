package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage 27 (z2): the main seat and a lead edit no code, only documents. The engine cannot take an editor away from a brain that runs in its
 * normal mode, so it reads the brain's own stream and flags, afterwards, a write to a file that is not a document.
 */
class OwnWritesTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static ToolCall write(String tool, String path) {
        return new ToolCall(tool, MAPPER.createObjectNode().put("file_path", path));
    }

    @Test
    @DisplayName("a write of the brain's own editor to a file that is not a document is flagged by file, and a document, a read and a jawata call are not")
    void onlyNonDocumentWritesAreFlagged() {
        List<ToolCall> made = List.of(
            write("Edit", "/w/org/x/Foo.java"),
            write("Write", "/w/docs/plan.md"),
            write("Read", "/w/org/x/Foo.java"),
            new ToolCall("mcp__jawata-x__rename_symbol", MAPPER.createObjectNode().put("symbol", "a.B#c")),
            write("Write", "/w/crate/src/lib.rs"));

        List<Observer.Flag> flags = OwnWrites.flags(made);

        assertAll(
            () -> assertEquals(2, flags.size(), flags.toString()),
            () -> assertEquals(0, flags.get(0).instruction(), "an own write is flagged against no instruction"),
            () -> assertTrue(flags.get(0).why().contains("Edit") && flags.get(0).why().contains("/w/org/x/Foo.java"), flags.get(0).why()),
            () -> assertTrue(flags.get(1).why().contains("Write") && flags.get(1).why().contains("/w/crate/src/lib.rs"), flags.get(1).why()),
            () -> assertTrue(flags.get(0).why().contains("only .md"), "the flag does not say what a lead may write: " + flags.get(0).why()));
    }

    @Test
    @DisplayName("a run that wrote only documents, or nothing, has no flag")
    void documentsAndNothingAreNotFlagged() {
        assertAll(
            () -> assertTrue(OwnWrites.flags(List.of(write("Edit", "/w/notes.md"), write("Write", "/w/plan.md"))).isEmpty()),
            () -> assertTrue(OwnWrites.flags(List.of()).isEmpty()));
    }

    @Test
    @DisplayName("the correction names each file and carries the rule in the words the seats carry, and is empty when nothing was flagged")
    void theCorrectionNamesTheFilesAndTheRule() {
        String correction = OwnWrites.correction(OwnWrites.flags(List.of(write("Edit", "/w/org/x/Foo.java"), write("Write", "/w/a.rs"))));

        assertAll(
            () -> assertTrue(correction.contains("/w/org/x/Foo.java") && correction.contains("/w/a.rs"), correction),
            () -> assertTrue(correction.contains("Do not edit code, but hire"), correction),
            () -> assertTrue(correction.contains("only .md file edits are allowed"), correction),
            () -> assertEquals("", OwnWrites.correction(List.of())));
    }
}
