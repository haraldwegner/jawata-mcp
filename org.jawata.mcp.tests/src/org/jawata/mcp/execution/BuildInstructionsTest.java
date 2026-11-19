package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage 27 (z5): the second kind of instruction. A structural change names a tool and its arguments; new behaviour names what is built and
 * each behaviour the tests must prove, with no code. The issuer writes the block, the engine reads it from the issuer's answer and from a
 * worker's brief.
 */
class BuildInstructionsTest {

    private static final String ANSWER = """
        Here is the work.

        INSTRUCTION 1
        TOOL: rename_symbol
        ARGUMENTS: {"symbol":"a.B#c","newName":"d"}

        INSTRUCTION 2
        BUILD: org.jawata.mcp.execution.Observer collects the flagged steps of a run and
          judges them in one hire at the end of the run
        TESTS: 15 flagged steps start one observer hire whose correction has 15 points
        TESTS: a run with no flagged step starts no observer hire
        - a flag with no instruction is listed under instruction 0
        """;

    @Test
    @DisplayName("a build block is read as what is built and one sentence per behaviour its tests must prove, and is not a tool instruction")
    void aBuildBlockIsRead() {
        List<Instructions.Build> builds = Instructions.builds(ANSWER);

        assertAll(
            () -> assertEquals(1, builds.size(), builds.toString()),
            () -> assertEquals(2, builds.get(0).number()),
            () -> assertEquals("org.jawata.mcp.execution.Observer collects the flagged steps of a run and judges them in one hire at the end of the run",
                builds.get(0).build()),
            () -> assertEquals(List.of(
                "15 flagged steps start one observer hire whose correction has 15 points",
                "a run with no flagged step starts no observer hire",
                "a flag with no instruction is listed under instruction 0"), builds.get(0).tests()),
            () -> assertEquals(1, Instructions.parse(ANSWER).size(), "CONTROL: the tool block was lost, or the build block became one"),
            () -> assertEquals("rename_symbol", Instructions.parse(ANSWER).get(0).tool()));
    }

    @Test
    @DisplayName("a build block is not a fault, one with no TESTS sentence is, and one that also names a tool is")
    void aBuildBlockIsCheckedForWhatItMustCarry() {
        String noTests = "INSTRUCTION 1\nBUILD: Foo does a thing\n";
        String both = "INSTRUCTION 1\nTOOL: rename_symbol\nARGUMENTS: {\"symbol\":\"a.B#c\",\"newName\":\"d\"}\nBUILD: Foo does a thing\nTESTS: it does it\n";

        List<String> clean = Instructions.faults(ANSWER);
        List<String> missingTests = Instructions.faults(noTests);
        List<String> mixed = Instructions.faults(both);

        assertAll(
            () -> assertTrue(clean.isEmpty(), "a whole build block was called a fault: " + clean),
            () -> assertEquals(1, missingTests.size(), missingTests.toString()),
            () -> assertTrue(missingTests.get(0).contains("TESTS"), missingTests.get(0)),
            () -> assertEquals(1, mixed.size(), mixed.toString()),
            () -> assertTrue(mixed.get(0).contains("both"), mixed.get(0)));
    }

    @Test
    @DisplayName("an answer with no build block has none, and a block with an empty BUILD line is not a build")
    void noBuildBlockMeansNoBuild() {
        assertAll(
            () -> assertTrue(Instructions.builds("INSTRUCTION 1\nTOOL: x\nARGUMENTS: {}\n").isEmpty()),
            () -> assertTrue(Instructions.builds(null).isEmpty()),
            () -> assertTrue(Instructions.builds("INSTRUCTION 1\nBUILD:\nTESTS: it does it\n").isEmpty(), "an empty BUILD line names nothing built"));
    }
}
