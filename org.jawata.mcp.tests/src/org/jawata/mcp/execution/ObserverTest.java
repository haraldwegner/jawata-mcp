package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S11 (D13, D50a): the observer. The rule tier flags what a worker did against the instructions it was given; the judging
 * tier is asked about flags only; a halted flag becomes a correction that carries the instruction's number; a flag nobody judged is
 * reported as unjudged and never as clear.
 */
class ObserverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static JsonNode args(String json) {
        try {
            return JSON.readTree(json);
        } catch (Exception e) {
            throw new IllegalArgumentException(e);
        }
    }

    private static Optional<Boolean> changes(String tool) {
        return Optional.of("rename_symbol".equals(tool));
    }

    private static final List<Instructions.Instruction> ISSUED = List.of(
        new Instructions.Instruction(1, "rename_symbol", args("{\"symbol\":\"a.B#c\",\"newName\":\"d\"}")),
        new Instructions.Instruction(2, "compile_workspace", args("{}")));

    @Test
    @DisplayName("a worker that ran every instruction as written raises no flag and the judge is never asked (the positive control)")
    void aWorkerThatRanEveryInstructionAsWrittenIsNotFlagged() {
        List<ToolCall> made = List.of(
            new ToolCall("rename_symbol", args("{\"symbol\":\"a.B#c\",\"newName\":\"d\"}")),
            new ToolCall("compile_workspace", args("{}")));
        List<String> asked = new ArrayList<>();

        Observer.Observation seen = Observer.observe(ISSUED, made, ObserverTest::changes, (flag, instruction, window) -> {
            asked.add(flag.why());
            return Optional.of(new Observer.Verdict(true, "should not be asked"));
        });

        assertTrue(seen.flagged().isEmpty(), seen.flagged().toString());
        assertTrue(seen.corrections().isEmpty());
        assertTrue(asked.isEmpty(), "the judge was asked about a step that ran as written: " + asked);
    }

    @Test
    @DisplayName("a flagged step reaches the judge, and the correction it gives carries the number of the instruction it came from")
    void aFlaggedStepIsJudgedAndItsCorrectionCarriesTheInstructionNumber() {
        List<ToolCall> made = List.of(
            new ToolCall("rename_symbol", args("{\"symbol\":\"a.B#c\",\"newName\":\"other\"}")),
            new ToolCall("compile_workspace", args("{}")));
        List<Observer.Flag> asked = new ArrayList<>();

        Observer.Observation seen = Observer.observe(ISSUED, made, ObserverTest::changes, (flag, instruction, window) -> {
            asked.add(flag);
            return Optional.of(new Observer.Verdict(true, "you renamed to the wrong name"));
        });

        assertEquals(1, asked.size(), asked.toString());
        assertEquals(1, asked.get(0).instruction());
        assertEquals(List.of(new Observer.Correction(1, "you renamed to the wrong name")), seen.corrections());
        assertTrue(seen.unjudged().isEmpty());
    }

    @Test
    @DisplayName("a judge that says clear gives no correction, and the flag is not reported as unjudged")
    void aClearVerdictGivesNoCorrection() {
        List<ToolCall> made = List.of(new ToolCall("compile_workspace", args("{}")));

        Observer.Observation seen = Observer.observe(ISSUED, made, ObserverTest::changes,
            (flag, instruction, window) -> Optional.of(new Observer.Verdict(false, "")));

        assertFalse(seen.flagged().isEmpty(), "instruction 1 never ran, which is a flag");
        assertTrue(seen.corrections().isEmpty());
        assertTrue(seen.unjudged().isEmpty(), "a judged flag was reported as unjudged");
    }

    @Test
    @DisplayName("with no judge, or a judge that cannot answer, every flag is reported as unjudged and none as clear")
    void aFlagNobodyJudgedIsReportedAsUnjudged() {
        List<ToolCall> made = List.of(new ToolCall("compile_workspace", args("{}")));

        Observer.Observation noJudge = Observer.observe(ISSUED, made, ObserverTest::changes, null);
        Observer.Observation silent = Observer.observe(ISSUED, made, ObserverTest::changes, (flag, instruction, window) -> Optional.empty());

        assertEquals(noJudge.flagged(), noJudge.unjudged());
        assertEquals(silent.flagged(), silent.unjudged());
        assertTrue(noJudge.corrections().isEmpty() && silent.corrections().isEmpty());
    }

    @Test
    @DisplayName("a code-changing call that no instruction asked for is flagged against instruction 0")
    void anUnaskedCodeChangeIsFlaggedAgainstNoInstruction() {
        List<ToolCall> made = List.of(
            new ToolCall("compile_workspace", args("{}")),
            new ToolCall("rename_symbol", args("{\"symbol\":\"x.Y#z\",\"newName\":\"w\"}")));

        List<Observer.Flag> flags = Observer.flags(Instructions.score(
            List.of(new Instructions.Instruction(2, "compile_workspace", args("{}"))), made, ObserverTest::changes));

        assertEquals(1, flags.size(), flags.toString());
        assertEquals(0, flags.get(0).instruction());
        assertTrue(flags.get(0).why().contains("rename_symbol"), flags.get(0).why());
    }

    @Test
    @DisplayName("the observer seat's answer is read as a verdict: clear, a halt with its correction, and an answer with no verdict line is no verdict")
    void theSeatsAnswerIsReadAsAVerdict() {
        assertEquals(Optional.of(new Observer.Verdict(false, "")), Observer.verdictOf("VERDICT: CLEAR"));
        assertEquals(Optional.of(new Observer.Verdict(true, "you went around it at call #4")),
            Observer.verdictOf("VERDICT: HALT 4: you went around it at call #4"));
        assertEquals(Optional.of(new Observer.Verdict(true, "no number given")), Observer.verdictOf("some words\nVERDICT: HALT no number given\n"));
        assertEquals(Optional.empty(), Observer.verdictOf("I think it is probably fine"));
        assertEquals(Optional.empty(), Observer.verdictOf(null));
    }
}
