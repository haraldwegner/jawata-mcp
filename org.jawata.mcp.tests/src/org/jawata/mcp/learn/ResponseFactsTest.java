package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a tool's response reported, read in one place (Sprint 28g S16).
 *
 * <p>The cases that matter are the ABSENT ones. Two shipped classes carried this extraction
 * privately and both answered ZERO for a response they could not read — correct for telemetry, and
 * for a GATE the defect that lets a step nothing checked advance a plan. Those two keep their zero
 * by mapping it at their own call site, and the distinction only exists if something pins it.</p>
 */
class ResponseFactsTest {

    @Test
    @DisplayName("a response carrying no count in any known shape reports ABSENT, never zero")
    void anUnreadableResponseReportsNothingRatherThanZero() {
        // The clause this class exists for. Every one of these is a response a gate could really
        // receive, and reading any of them as "zero errors" is reading it as a PASS.
        assertAll(
            () -> assertTrue(ResponseFacts.errorCount(
                ToolResponse.success(Map.of("operation", "something_else"))).isEmpty(),
                "a map with neither key"),
            () -> assertTrue(ResponseFacts.errorCount(
                ToolResponse.success(List.of("not", "a", "map"))).isEmpty(),
                "data that is not a map at all"),
            () -> assertTrue(ResponseFacts.errorCount(
                ToolResponse.error("SOMETHING", "it refused", null)).isEmpty(),
                "a refusal, which carries no data"),
            () -> assertTrue(ResponseFacts.errorCount(null).isEmpty(),
                "and no response at all, rather than throwing"),
            // A count that is PRESENT and zero is a different answer, and the two must not collapse:
            // this one really did run and really found nothing.
            () -> assertEquals(0L, ResponseFacts.errorCount(
                ToolResponse.success(Map.of("errorCount", 0))).orElseThrow(),
                "a reported zero is present, and is not the same as silence"));
    }

    @Test
    @DisplayName("the two measured keys are read in the order the shipped copies read them")
    void itReadsTheMeasuredKeysInTheMeasuredOrder() {
        // errorCount is what the compile, diagnostics, refresh and syntax tools publish; failed is
        // what the test runner publishes. The ORDER is asserted because a response carrying both
        // must resolve one way and not by accident.
        assertAll(
            () -> assertEquals(4L, ResponseFacts.errorCount(
                ToolResponse.success(Map.of("errorCount", 4))).orElseThrow()),
            () -> assertEquals(7L, ResponseFacts.errorCount(
                ToolResponse.success(Map.of("failed", 7))).orElseThrow(),
                "the test runner's key, when it is the only one"),
            () -> assertEquals(4L, ResponseFacts.errorCount(
                ToolResponse.success(Map.of("errorCount", 4, "failed", 9))).orElseThrow(),
                "errorCount wins, which is the order both shipped copies had"));
    }

    @Test
    @DisplayName("files modified is a count, and its absence means this was not a modifying tool")
    void filesModifiedCountsWhatTheToolSaidItTouched() {
        assertAll(
            () -> assertEquals(2, ResponseFacts.filesModified(
                ToolResponse.success(Map.of("filesModified", List.of("A.java", "B.java"))))),
            () -> assertEquals(0, ResponseFacts.filesModified(
                ToolResponse.success(Map.of("filesModified", List.of())))),
            // Zero rather than absent, deliberately and unlike errorCount: every tool that
            // modifies files publishes the list, so no list means this was not one of them.
            () -> assertEquals(0, ResponseFacts.filesModified(
                ToolResponse.success(Map.of("operation", "search_symbols")))),
            () -> assertEquals(0, ResponseFacts.filesModified(null)));
    }
}
