package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S14 (D8) — the lead's instructions are issued as blocks, a run shows each one, and a
 * block that is not a whole instruction is named instead of vanishing.
 *
 * <p>{@link #PM_BLOCK} is the block exactly as {@code pm.md} words it. This test parses it once its
 * placeholders are filled in; it does not read the seat file. The studio test
 * {@code the_leads_instruction_block_in_the_seat_files_is_the_literal_the_engine_test_parses}
 * ({@code src-tauri/src/conductor.rs}) holds the same three lines equal to what the seat files say, so a
 * change to the wording goes red there, and the literal here is then changed with it.</p>
 */
class InstructionBlocksTest {

    /**
     * The instruction block as {@code pm.md} words it, three lines. THE OTHER SIDE is the studio test named above,
     * which holds this text equal to the seat file; keep the two literals identical.
     */
    private static final String PM_BLOCK =
        "INSTRUCTION <n>\nTOOL: <engine tool name>\nARGUMENTS: <one JSON object, every argument the tool needs>";

    @Test
    void theBlockAsTheSeatFilesWordItParsesToNumberToolAndArgumentsOnceItsPlaceholdersAreFilled() {
        String filled = PM_BLOCK.replace("<n>", "7")
            .replace("<engine tool name>", "rename_symbol")
            .replace("<one JSON object, every argument the tool needs>", "{\"symbol\":\"a.B#c\",\"newName\":\"d\"}");

        List<Instructions.Instruction> parsed = Instructions.parse(filled);

        assertEquals(1, parsed.size(), filled);
        assertEquals(7, parsed.get(0).number());
        assertEquals("rename_symbol", parsed.get(0).tool());
        assertEquals("d", parsed.get(0).arguments().path("newName").asText());
        assertTrue(Instructions.faults(filled).isEmpty(), "the filled block is a whole instruction");
        assertTrue(Instructions.parse(PM_BLOCK).isEmpty(),
            "THE CONTROL: the template itself, with its placeholders unfilled, issues no instruction");
    }

    /** A lead's answer carrying two whole instructions, in the wording of the seat files. */
    private static final String WHOLE = """
        The slice is the rename.

        INSTRUCTION 1
        TOOL: rename_symbol
        ARGUMENTS: {"symbol":"org.jawata.mcp.execution.Instructions#parse","newName":"read"}

        INSTRUCTION 2
        TOOL: compile_workspace
        ARGUMENTS: {}
        """;

    @Test
    void aLeadsInstructionBlockParsesToNumberToolAndArguments() {
        List<Instructions.Instruction> parsed = Instructions.parse(WHOLE);

        assertEquals(2, parsed.size(), "both blocks are instructions");
        assertEquals(1, parsed.get(0).number());
        assertEquals("rename_symbol", parsed.get(0).tool());
        assertNotNull(parsed.get(0).arguments(), "the arguments are one JSON object");
        assertEquals("read", parsed.get(0).arguments().path("newName").asText());
        assertEquals(2, parsed.get(1).number());
        assertEquals("compile_workspace", parsed.get(1).tool());
        assertTrue(Instructions.faults(WHOLE).isEmpty(), "two whole blocks leave nothing to name: " + Instructions.faults(WHOLE));
    }

    @Test
    void aBlockWithNoToolLineIsNamedAndNotCountedAsIssued() {
        String answer = WHOLE + """

            INSTRUCTION 3
            ARGUMENTS: {"symbol":"a.B#c"}
            """;

        assertEquals(2, Instructions.parse(answer).size(), "a block that names no tool is not an instruction");
        List<String> faults = Instructions.faults(answer);
        assertEquals(1, faults.size(), faults.toString());
        assertTrue(faults.get(0).startsWith("instruction 3:") && faults.get(0).contains("TOOL"),
            "the fault says which block and what it lacks: " + faults);
    }

    @Test
    void aBlockWithNoArgumentsOrArgumentsThatAreNoObjectIsNamedAndKeptWithNone() {
        String noArguments = "INSTRUCTION 1\nTOOL: format\n";
        String prose = "INSTRUCTION 2\nTOOL: format\nARGUMENTS: the usual ones\n";

        List<Instructions.Instruction> kept = Instructions.parse(noArguments + "\n" + prose);
        assertEquals(2, kept.size(), "a block with a tool is kept, as before");
        assertEquals(null, kept.get(0).arguments());
        assertEquals(null, kept.get(1).arguments());
        List<String> faults = Instructions.faults(noArguments + "\n" + prose);
        assertEquals(2, faults.size(), faults.toString());
        assertTrue(faults.get(0).startsWith("instruction 1:") && faults.get(0).contains("ARGUMENTS"), faults.toString());
        assertTrue(faults.get(1).startsWith("instruction 2:") && faults.get(1).contains("ARGUMENTS"), faults.toString());
    }

    @Test
    void anAnswerWithNoBlockHasNoInstructionsAndNoFaults() {
        assertTrue(Instructions.parse("I could not decide; here is why.").isEmpty());
        assertTrue(Instructions.faults("I could not decide; here is why.").isEmpty());
        assertTrue(Instructions.faults(null).isEmpty());
    }
}
