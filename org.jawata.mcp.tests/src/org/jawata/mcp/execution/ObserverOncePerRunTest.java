package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage 27 (z1): the observer judges once per run. A run with fifteen flagged steps starts one judge call, not fifteen, and the one answer
 * carries a point for each flagged instruction, so the lead can act on each by its number.
 */
class ObserverOncePerRunTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Optional<Boolean> changes(String bare) {
        return Optional.of(true);
    }

    private static List<Instructions.Instruction> instructions(int count) {
        List<Instructions.Instruction> issued = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            issued.add(new Instructions.Instruction(i, "rename_symbol", MAPPER.createObjectNode().put("symbol", "a.B#c" + i).put("newName", "d")));
        }
        return issued;
    }

    private static Observer.BatchJudge haltingEveryPoint(AtomicInteger asked, List<Integer> flagsSeen) {
        return (flags, issued, window) -> {
            asked.incrementAndGet();
            flagsSeen.add(flags.size());
            return Optional.of(java.util.stream.IntStream.rangeClosed(1, flags.size())
                .mapToObj(n -> new Observer.Point(n, true, "point " + flags.get(n - 1).instruction())).toList());
        };
    }

    @Test
    @DisplayName("fifteen flagged instructions start exactly one judge call, and its correction carries fifteen points, each by its instruction number")
    void fifteenFlagsAreJudgedInOneCall() {
        AtomicInteger asked = new AtomicInteger();
        List<Integer> flagsSeen = new ArrayList<>();

        Observer.Observation seen = Observer.observe(instructions(15), List.of(), ObserverOncePerRunTest::changes, List.of(),
            haltingEveryPoint(asked, flagsSeen), false);

        assertAll(
            () -> assertEquals(1, asked.get(), "the judge was asked once per flag, or not at all"),
            () -> assertEquals(List.of(15), flagsSeen, "the one call was not shown every flag"),
            () -> assertEquals(15, seen.flagged().size()),
            () -> assertEquals(15, seen.corrections().size(), "the correction does not carry a point for each flag"),
            () -> assertEquals(new Observer.Correction(7, "point 7"), seen.corrections().get(6)),
            () -> assertTrue(seen.unjudged().isEmpty(), seen.unjudged().toString()));
    }

    @Test
    @DisplayName("a run with no flag starts no judge call and has no correction")
    void noFlagStartsNoJudgeCall() {
        AtomicInteger asked = new AtomicInteger();
        // two instructions for two different tools: the same tool called twice for two instructions counts as rework, which is another rule
        List<Instructions.Instruction> issued = List.of(
            new Instructions.Instruction(1, "rename_symbol", MAPPER.createObjectNode().put("symbol", "a.B#c").put("newName", "d")),
            new Instructions.Instruction(2, "extract", MAPPER.createObjectNode().put("kind", "method")));
        List<ToolCall> made = issued.stream().map(i -> new ToolCall(i.tool(), i.arguments())).toList();

        Observer.Observation seen = Observer.observe(issued, made, ObserverOncePerRunTest::changes, List.of(), haltingEveryPoint(asked, new ArrayList<>()), false);

        assertAll(
            () -> assertEquals(0, asked.get(), "a judge was asked about a run nobody flagged"),
            () -> assertTrue(seen.flagged().isEmpty(), seen.flagged().toString()),
            () -> assertTrue(seen.corrections().isEmpty(), seen.corrections().toString()));
    }

    @Test
    @DisplayName("a flag another rule raised joins the flags of the rule tier in the one call")
    void aFlagFromAnotherRuleJoinsTheOneCall() {
        AtomicInteger asked = new AtomicInteger();
        List<Integer> flagsSeen = new ArrayList<>();
        List<Instructions.Instruction> issued = instructions(1);
        List<ToolCall> made = List.of(new ToolCall("rename_symbol", issued.get(0).arguments()));

        Observer.Observation seen = Observer.observe(issued, made, ObserverOncePerRunTest::changes,
            List.of(new Observer.Flag(0, "production code was written before a test was seen failing")), haltingEveryPoint(asked, flagsSeen), false);

        assertAll(
            () -> assertEquals(1, asked.get()),
            () -> assertEquals(List.of(1), flagsSeen),
            () -> assertEquals(List.of(new Observer.Correction(0, "point 0")), seen.corrections()));
    }

    @Test
    @DisplayName("a worker briefed with no tool instruction is not flagged for its own editor writes, and keeps the flags other rules raise")
    void aRunWithNoToolInstructionIsNotFlaggedForItsOwnWrites() {
        AtomicInteger asked = new AtomicInteger();
        List<ToolCall> made = List.of(new ToolCall("Edit", MAPPER.createObjectNode().put("file_path", "/x/Foo.java")));

        Observer.Observation quiet = Observer.observe(List.of(), made, ObserverOncePerRunTest::changes, List.of(), haltingEveryPoint(asked, new ArrayList<>()), false);
        Observer.Observation flagged = Observer.observe(List.of(), made, ObserverOncePerRunTest::changes,
            List.of(new Observer.Flag(1, "production code was written before a test was seen failing")), haltingEveryPoint(asked, new ArrayList<>()), false);

        assertAll(
            () -> assertTrue(quiet.flagged().isEmpty(), "an editor write was flagged as one no instruction asked for: " + quiet.flagged()),
            () -> assertEquals(1, asked.get(), "the judge was asked about the quiet run, or not about the flagged one"),
            () -> assertEquals(1, flagged.flagged().size()),
            () -> assertEquals(List.of(new Observer.Correction(1, "point 1")), flagged.corrections()));
    }

    @Test
    @DisplayName("a flag the judge's answer has no point for is listed as unjudged, and so is every flag when there is no judge or it cannot answer")
    void aFlagNobodyJudgedIsListedAsUnjudged() {
        Observer.BatchJudge three = (flags, issued, window) -> Optional.of(List.of(
            new Observer.Point(1, true, "one"), new Observer.Point(2, false, ""), new Observer.Point(3, true, "three")));

        Observer.Observation partial = Observer.observe(instructions(5), List.of(), ObserverOncePerRunTest::changes, List.of(), three, false);
        Observer.Observation noJudge = Observer.observe(instructions(5), List.of(), ObserverOncePerRunTest::changes, List.of(), null, false);
        Observer.Observation silent = Observer.observe(instructions(5), List.of(), ObserverOncePerRunTest::changes, List.of(),
            (flags, issued, window) -> Optional.empty(), false);

        assertAll(
            () -> assertEquals(List.of(new Observer.Correction(1, "one"), new Observer.Correction(3, "three")), partial.corrections()),
            () -> assertEquals(2, partial.unjudged().size(), partial.unjudged().toString()),
            () -> assertEquals(5, noJudge.unjudged().size()),
            () -> assertTrue(noJudge.corrections().isEmpty()),
            () -> assertEquals(5, silent.unjudged().size()),
            () -> assertTrue(silent.corrections().isEmpty()));
    }

    @Test
    @DisplayName("C27 audit: two flags about no instruction are told apart by their point numbers, so a verdict on one leaves the other unjudged")
    void twoFlagsAboutNoInstructionAreToldApart() {
        List<Observer.Flag> both = List.of(new Observer.Flag(0, "a code-changing call that no instruction asked for: Edit on A"),
            new Observer.Flag(0, "production code was written before a test was seen failing"));

        Observer.Observation seen = Observer.observe(List.of(), List.of(), ObserverOncePerRunTest::changes, both,
            (flags, issued, window) -> Optional.of(List.of(new Observer.Point(1, true, "undo the edit of A"))), false);

        assertAll(
            () -> assertEquals(List.of(new Observer.Correction(0, "undo the edit of A")), seen.corrections()),
            () -> assertEquals(List.of(both.get(1)), seen.unjudged(), "a verdict on the first point marked the second judged: " + seen.unjudged()));
    }

    @Test
    @DisplayName("C27 audit: a verdict that names no point judges no flag, so every flag is listed as unjudged and nothing is called clear by default")
    void aVerdictWithNoNumberJudgesNothing() {
        List<Observer.Flag> both = List.of(new Observer.Flag(0, "one"), new Observer.Flag(0, "two"));

        Observer.Observation seen = Observer.observe(List.of(), List.of(), ObserverOncePerRunTest::changes, both,
            (flags, issued, window) -> Optional.of(Observer.pointsOf("VERDICT: CLEAR")), false);

        assertAll(
            () -> assertTrue(seen.corrections().isEmpty(), seen.corrections().toString()),
            () -> assertEquals(2, seen.unjudged().size(), "a verdict with no number was taken to judge a flag: " + seen.unjudged()));
    }

    @Test
    @DisplayName("the judge is shown each flag by its instruction number, and its answer is read one point per VERDICT line")
    void theBriefAndTheAnswerCarryEveryPoint() {
        List<Observer.Flag> flags = List.of(new Observer.Flag(3, "rename_symbol was called, but not with the arguments written"),
            new Observer.Flag(0, "production code was written before a test was seen failing"));

        String brief = Observer.briefFor(flags, instructions(3), List.of(new ToolCall("Edit", MAPPER.createObjectNode().put("file_path", "/x"))));
        List<Observer.Point> points = Observer.pointsOf("my notes\nVERDICT: HALT 3: you renamed the wrong symbol at call #1\nVERDICT: CLEAR 5\nVERDICT: HALT 0: no test came first\n");

        assertAll(
            () -> assertTrue(brief.contains("INSTRUCTION 3"), brief),
            () -> assertTrue(brief.contains("rename_symbol was called, but not with the arguments written"), brief),
            () -> assertTrue(brief.contains("production code was written before a test was seen failing"), brief),
            () -> assertTrue(brief.contains("#1 Edit"), brief),
            () -> assertEquals(List.of(new Observer.Point(3, true, "you renamed the wrong symbol at call #1"),
                new Observer.Point(5, false, ""), new Observer.Point(0, true, "no test came first")), points),
            () -> assertTrue(Observer.pointsOf("I think it is fine").isEmpty()),
            () -> assertTrue(Observer.pointsOf(null).isEmpty()));
    }
}
