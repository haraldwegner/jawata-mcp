package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Stage 27 (o), his words 2026-11-18: Agent Control shows whether the agent used jawata. Each agent's calls split into jawata calls,
 * other calls and shell calls on Java, and a shell call on Java is listed with the reason it declared, if any.
 */
class ToolUseTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Set<String> ENGINE = Set.of("search_symbols", "extract", "find_references");

    private static ToolCall call(String name, String argumentsJson) {
        try {
            return new ToolCall(name, JSON.readTree(argumentsJson));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static ToolUse of(int toolSteps, ToolCall... calls) {
        return ToolUse.of(List.of(calls), toolSteps, ENGINE::contains);
    }

    @Test
    @DisplayName("a run's calls split into jawata, other and shell on Java; a shell call that touches no Java is an other call")
    void theCallsSplitThreeWays() {
        ToolUse use = of(6,
            call("mcp__jawata-javata-dev__search_symbols", "{}"),
            call("extract", "{}"),
            call("Read", "{\"file_path\":\"/x/A.java\"}"),
            call("Bash", "{\"command\":\"ls -la\"}"),
            call("Bash", "{\"command\":\"grep -rn foo src/A.java\"}"),
            call("Grep", "{\"pattern\":\"foo\"}"));
        assertAll(
            () -> assertTrue(use.observed()),
            () -> assertEquals(2, use.jawata(), "THE CLAIM: the two jawata calls, one prefixed and one bare"),
            () -> assertEquals(1, use.shellOnJava().size(), "CONTROL: `ls -la` names no Java and is not counted here"),
            () -> assertEquals(3, use.other(), "Read, `ls -la` and Grep"),
            () -> assertEquals(6, use.jawata() + use.other() + use.shellOnJava().size()),
            () -> assertEquals("grep -rn foo src/A.java", use.shellOnJava().get(0).command()));
    }

    @Test
    @DisplayName("a shell call on Java carries the reason it declared, and none when it declared none")
    void aDeclaredReasonIsKeptWithTheCall() {
        ToolUse use = of(2,
            call("Bash", "{\"command\":\"grep x A.java # jawata-fallback: the symbol tools do not search log strings\"}"),
            call("Bash", "{\"command\":\"sed -i s/a/b/ B.java\"}"));
        assertAll(
            () -> assertEquals(Optional.of("the symbol tools do not search log strings"), use.shellOnJava().get(0).declared()),
            () -> assertEquals(Optional.empty(), use.shellOnJava().get(1).declared(), "CONTROL: no declaration, none invented"),
            () -> assertEquals(2, use.shellOnJava().size()));
    }

    @Test
    @DisplayName("a jawata-author declaration is read as well as a jawata-fallback one")
    void anAuthorDeclarationCounts() {
        ToolUse use = of(1, call("Bash", "{\"command\":\"python3 edit.py A.java # jawata-author: rename_symbol refused the case\"}"));
        assertEquals(Optional.of("rename_symbol refused the case"), use.shellOnJava().get(0).declared());
    }

    @Test
    @DisplayName("a tool of a foreign MCP server with a jawata tool's name is an other call, never a jawata one")
    void aForeignServersToolIsNotJawata() {
        ToolUse use = of(2, call("mcp__other-server__search_symbols", "{}"), call("search_symbols", "{}"));
        assertAll(
            () -> assertEquals(1, use.jawata(), "only the bare name the engine knows"),
            () -> assertEquals(1, use.other()));
    }

    @Test
    @DisplayName("a shell whose command arrives as a list, as codex sends it, is read too")
    void aCommandGivenAsAListIsRead() {
        ToolUse use = of(1, call("shell", "{\"command\":[\"bash\",\"-lc\",\"cat A.java\"]}"));
        assertEquals(1, use.shellOnJava().size());
    }

    @Test
    @DisplayName("a run whose calls were counted but not named is NOT OBSERVED, and its figures are absent rather than zero")
    void aRunWithNoNamedCallsIsNotObserved() {
        ToolUse use = ToolUse.of(List.of(), 3, ENGINE::contains);
        JsonNode map = JSON.valueToTree(use.asMap());
        assertAll(
            () -> assertFalse(use.observed()),
            () -> assertEquals(false, map.path("observed").asBoolean(true)),
            () -> assertFalse(map.has("jawata"), "a zero here would read as a run that never used jawata"),
            () -> assertFalse(map.has("shellOnJava")));
    }

    @Test
    @DisplayName("a run that made no call at all is observed, and all of it is zero")
    void aRunWithNoCallsIsObservedZero() {
        ToolUse use = ToolUse.of(List.of(), 0, ENGINE::contains);
        assertAll(
            () -> assertTrue(use.observed()),
            () -> assertEquals(0, use.jawata()),
            () -> assertEquals(0, use.other()),
            () -> assertTrue(use.shellOnJava().isEmpty()));
    }

    @Test
    @DisplayName("the jawata calls the engine kept as failed or refused ride in the map with the tool and the error code, and an unobserved run still lists them")
    void theFailedCallsRideInTheMap() {
        List<CallOutcomes.Failed> failed = List.of(new CallOutcomes.Failed("rename_symbol", "REFACTORING_FAILED"), new CallOutcomes.Failed("files", "ACCESS_DENIED", "Access denied: /x"));
        JsonNode seen = JSON.valueToTree(ToolUse.of(List.of(call("search_symbols", "{}")), 1, ENGINE::contains, failed).asMap());
        JsonNode blind = JSON.valueToTree(ToolUse.of(List.of(), 3, ENGINE::contains, failed).asMap());
        JsonNode clean = JSON.valueToTree(ToolUse.of(List.of(call("search_symbols", "{}")), 1, ENGINE::contains, List.of()).asMap());

        assertAll(
            () -> assertEquals(2, seen.path("failed").size(), "THE CLAIM: the failed calls were not on the card"),
            () -> assertEquals("rename_symbol", seen.path("failed").get(0).path("tool").asText()),
            () -> assertEquals("ACCESS_DENIED", seen.path("failed").get(1).path("code").asText()),
            () -> assertEquals("Access denied: /x", seen.path("failed").get(1).path("said").asText(), "the engine's message did not reach the card"),
            () -> assertTrue(seen.path("failed").get(0).path("said").isMissingNode(), "CONTROL: a failure with no message was given an empty one"),
            () -> assertEquals(2, blind.path("failed").size(), "an unobserved run lost the failures the engine itself saw"),
            () -> assertEquals(0, clean.path("failed").size(), "CONTROL: a run with no failures was given some"));
    }

    @Test
    @DisplayName("the map the card carries holds the three counts and each shell call with its reason")
    void theMapCarriesTheFigures() {
        ToolUse use = of(2, call("search_symbols", "{}"), call("Bash", "{\"command\":\"cat A.java # jawata-fallback: no tool reads a log line\"}"));
        JsonNode map = JSON.valueToTree(use.asMap());
        assertAll(
            () -> assertEquals(1, map.path("jawata").asInt()),
            () -> assertEquals(0, map.path("other").asInt()),
            () -> assertEquals(1, map.path("shellOnJava").size()),
            () -> assertEquals("no tool reads a log line", map.path("shellOnJava").get(0).path("declared").asText()));
    }
}
