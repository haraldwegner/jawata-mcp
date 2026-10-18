package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostOS;
import org.jawata.core.host.HostProcesses;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * THE TWO MEASURES THE SPRINT SPEC ADDED FOR THE ROLES (2026-10-11), read from the hire record and not
 * from any seat's report of itself:
 *
 * <ul>
 *   <li>the PM's tokens per instruction that ran as written, which needs the tokens each brain reports
 *       in its own final event (and an honest absence where it reports none), and the arguments each
 *       call passed, and a parse of the instructions the PM issued;</li>
 *   <li>the worker's share of code changes made through a tool, which is tested beside the classifier
 *       it extends in {@code RunEffectsTest}.</li>
 * </ul>
 */
class TokensAndInstructionsTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ---- tokens, per dialect, read from the line each vendor's final event is ------------------

    @Test
    @DisplayName("claude: the final event's usage is read, its cached part is ADDED to the input, and reasoning is not invented")
    void claudeTokens() {
        BrainLine line = new ClaudeCodeDialect().read(
            "{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.04,"
                + "\"usage\":{\"input_tokens\":10,\"cache_read_input_tokens\":1000,"
                + "\"cache_creation_input_tokens\":50,\"output_tokens\":200}}");
        TokenUsage tokens = line.tokens().orElseThrow();
        assertAll(
            () -> assertEquals(1060, tokens.input(), "claude's input_tokens EXCLUDES the cached part, so it is added"),
            () -> assertEquals(1050, tokens.cached()),
            () -> assertEquals(200, tokens.output()),
            () -> assertEquals(1260, tokens.spent()));
    }

    @Test
    @DisplayName("a final event with no usage block yields NO tokens, which is not zero tokens")
    void aMissingUsageBlockIsAbsentNotZero() {
        assertTrue(new ClaudeCodeDialect().read("{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.04}")
            .tokens().isEmpty());
        assertTrue(new ClaudeCodeDialect().read("{\"type\":\"result\",\"result\":\"done\",\"usage\":{}}")
            .tokens().isEmpty(), "a usage block with neither an input nor an output count is not a report");
    }

    @Test
    @DisplayName("codex: turn.completed's input_tokens already INCLUDES the cached part, so nothing is added; reasoning is kept")
    void codexTokens() {
        BrainLine line = new CodexDialect().read(
            "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":5000,\"cached_input_tokens\":4000,"
                + "\"output_tokens\":300,\"reasoning_output_tokens\":120}}");
        TokenUsage tokens = line.tokens().orElseThrow();
        assertAll(
            () -> assertEquals(5000, tokens.input()),
            () -> assertEquals(4000, tokens.cached()),
            () -> assertEquals(300, tokens.output()),
            () -> assertEquals(120, tokens.reasoning()),
            () -> assertEquals(5300, tokens.spent()));
    }

    @Test
    @DisplayName("cursor: its terminal usage is read, and the basis line says what is not established")
    void cursorTokens() {
        BrainLine line = new CursorDialect("cursor-agent", true, null).read(
            "{\"type\":\"result\",\"result\":\"done\",\"usage\":{\"inputTokens\":100,\"outputTokens\":40,"
                + "\"cacheReadTokens\":900,\"cacheWriteTokens\":0}}");
        TokenUsage tokens = line.tokens().orElseThrow();
        assertAll(
            () -> assertEquals(1000, tokens.input()),
            () -> assertEquals(40, tokens.output()),
            () -> assertEquals(1040, tokens.spent()),
            () -> assertTrue(tokens.basis().contains("not established"), tokens.basis()));
    }

    @Test
    @DisplayName("copilot reports premium requests and no tokens, so it yields none")
    void copilotReportsNoTokens() {
        assertTrue(new CopilotDialect().read(
            "{\"type\":\"result\",\"usage\":{\"premiumRequests\":0.33}}").tokens().isEmpty());
    }

    // ---- the arguments each call passed ------------------------------------------------------

    @Test
    @DisplayName("a claude assistant turn carries each call's arguments beside its name, in the same order")
    void toolCallsCarryTheirArguments() {
        BrainLine line = new ClaudeCodeDialect().read(
            "{\"type\":\"assistant\",\"message\":{\"content\":["
                + "{\"type\":\"tool_use\",\"name\":\"mcp__jawata-x__rename_symbol\",\"input\":{\"symbol\":\"a.B#c\",\"newName\":\"d\"}},"
                + "{\"type\":\"tool_use\",\"name\":\"compile_workspace\"}]}}");
        assertAll(
            () -> assertEquals(List.of("mcp__jawata-x__rename_symbol", "compile_workspace"), line.toolNames()),
            () -> assertEquals(2, line.toolCalls().size()),
            () -> assertEquals("d", line.toolCalls().get(0).arguments().path("newName").asText()),
            () -> assertTrue(line.toolCalls().get(1).arguments().isObject() && line.toolCalls().get(1).arguments().isEmpty(),
                "a call with no input has an empty object, never a missing node"));
    }

    // ---- the whole run: a script speaking claude's dialect, through the real driver ----------------

    @Test
    @DisplayName("the driver carries a run's tokens and its calls' arguments into the outcome")
    void theOutcomeCarriesTokensAndCalls() throws Exception {
        assumeTrue(!HostOS.current().isWindows(), "the brain fixture is a POSIX shell script");
        Path dir = Files.createTempDirectory("jawata-tokens-");
        try {
            Path file = dir.resolve("brain.sh");
            Files.writeString(file, "#!/bin/sh\n"
                + "echo '{\"type\":\"assistant\",\"message\":{\"content\":[{\"type\":\"tool_use\",\"name\":\"extract\",\"input\":{\"kind\":\"method\"}}]}}'\n"
                + "echo '{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.01,\"usage\":{\"input_tokens\":7,\"output_tokens\":3}}'\n");
            Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
            HostCommand command = HostCommand.of(file.toString());
            HiredBrain.Outcome outcome = HiredBrain.hire(HostProcesses.system(),
                new ClaudeCodeDialect(command.argv().get(0), 12, null, false),
                new HireSite(command.workingDirectory(), command.environment(), command.cleanEnvironment()),
                "brief", List.of(), HiredBrain.Ceilings.of(Duration.ofSeconds(20)));
            assertAll(
                () -> assertEquals(10, outcome.tokens().orElseThrow().spent()),
                () -> assertEquals(1, outcome.toolCalls().size()),
                () -> assertEquals("method", outcome.toolCalls().get(0).arguments().path("kind").asText()));
        } finally {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
            }
        }
    }

    // ---- the instructions an issuer writes, and whether they ran as written --------------------

    private static final String ANSWER = """
        Here is what I decided, from reading Foo.java.

        INSTRUCTION 1
        TOOL: rename_symbol
        ARGUMENTS: {"symbol":"a.B#c","newName":"d"}

        INSTRUCTION 2
        TOOL: change_method_signature
        ARGUMENTS: {"symbol":"a.B#e","visibility":"package"} and then nothing else

        INSTRUCTION 3
        TOOL: extract
        ARGUMENTS: not json at all
        """;

    @Test
    @DisplayName("INSTRUCTION blocks are parsed: tool and arguments, trailing prose tolerated, bad arguments marked rather than dropped")
    void instructionsAreParsed() {
        List<Instructions.Instruction> parsed = Instructions.parse(ANSWER);
        assertAll(
            () -> assertEquals(3, parsed.size()),
            () -> assertEquals("rename_symbol", parsed.get(0).tool()),
            () -> assertEquals("d", parsed.get(0).arguments().path("newName").asText()),
            () -> assertEquals("package", parsed.get(1).arguments().path("visibility").asText(),
                "the first object is read even when the line goes on in prose"),
            () -> assertEquals("extract", parsed.get(2).tool()),
            () -> assertNull(parsed.get(2).arguments(), "arguments that are not one JSON object leave the instruction with no execution"));
        assertTrue(Instructions.parse("no instruction here").isEmpty());
        assertTrue(Instructions.parse(null).isEmpty());
    }

    private static final Function<String, Optional<Boolean>> ENGINE = name -> switch (name) {
        case "rename_symbol", "change_method_signature", "extract" -> Optional.of(true);
        case "compile_workspace", "search_symbols" -> Optional.of(false);
        default -> Optional.empty();
    };

    private static ToolCall call(String name, String json) throws Exception {
        return new ToolCall(name, MAPPER.readTree(json));
    }

    @Test
    @DisplayName("an instruction ran as written when exactly one call carried its tool and its arguments, and reads and the compile check are not rework")
    void ranAsWritten() throws Exception {
        List<Instructions.Instruction> issued = List.of(
            new Instructions.Instruction(1, "rename_symbol", MAPPER.readTree("{\"symbol\":\"a.B#c\",\"newName\":\"d\"}")));
        Instructions.Score score = Instructions.score(issued, List.of(
            call("mcp__jawata-x__search_symbols", "{\"query\":\"B\"}"),
            call("mcp__jawata-x__rename_symbol", "{\"newName\":\"d\",\"symbol\":\"a.B#c\"}"),
            call("compile_workspace", "{}")), ENGINE);
        assertAll(
            () -> assertEquals(1, score.issued()),
            () -> assertEquals(1, score.ranAsWritten(), "key order does not matter, the prefix does not matter"),
            () -> assertTrue(score.notAsWritten().isEmpty()),
            () -> assertTrue(score.extraCodeChangingCalls().isEmpty()));
    }

    @Test
    @DisplayName("an instruction did NOT run as written when the arguments differ, the tool was never called, or it was called twice")
    void didNotRunAsWritten() throws Exception {
        List<Instructions.Instruction> issued = List.of(
            new Instructions.Instruction(1, "rename_symbol", MAPPER.readTree("{\"symbol\":\"a.B#c\",\"newName\":\"d\"}")),
            new Instructions.Instruction(2, "change_method_signature", MAPPER.readTree("{\"symbol\":\"a.B#e\"}")),
            new Instructions.Instruction(3, "extract", MAPPER.readTree("{\"kind\":\"method\"}")));
        Instructions.Score score = Instructions.score(issued, List.of(
            call("rename_symbol", "{\"symbol\":\"a.B#c\",\"newName\":\"OTHER\"}"),   // 1: wrong arguments
            call("extract", "{\"kind\":\"method\"}"),                                  // 3: right ...
            call("extract", "{\"kind\":\"method\"}")), ENGINE);                        // ... and again: rework
        assertAll(
            () -> assertEquals(0, score.ranAsWritten()),
            () -> assertEquals(3, score.notAsWritten().size()),
            () -> assertTrue(score.notAsWritten().get(0).contains("not with the arguments written")),
            () -> assertTrue(score.notAsWritten().get(1).contains("never called")),
            () -> assertTrue(score.notAsWritten().get(2).contains("rework")));
    }

    @Test
    @DisplayName("a code change nobody asked for is reported beside the score: a tool the instructions never named, and the CLI's own editor")
    void extraCodeChangesAreNamed() throws Exception {
        List<Instructions.Instruction> issued = List.of(
            new Instructions.Instruction(1, "rename_symbol", MAPPER.readTree("{\"symbol\":\"a.B#c\",\"newName\":\"d\"}")));
        Instructions.Score score = Instructions.score(issued, List.of(
            call("rename_symbol", "{\"symbol\":\"a.B#c\",\"newName\":\"d\"}"),
            call("extract", "{\"kind\":\"method\"}"),
            call("Edit", "{\"file_path\":\"/x\"}"),
            call("Read", "{}")), ENGINE);
        assertAll(
            () -> assertEquals(1, score.ranAsWritten()),
            () -> assertEquals(List.of("extract", "Edit"), score.extraCodeChangingCalls()));
    }

    @Test
    @DisplayName("precedentOverride, the meta-argument every call may carry, is not part of what was written")
    void theMetaArgumentIsIgnored() throws Exception {
        List<Instructions.Instruction> issued = List.of(
            new Instructions.Instruction(1, "rename_symbol", MAPPER.readTree("{\"symbol\":\"a.B#c\",\"newName\":\"d\"}")));
        Instructions.Score score = Instructions.score(issued, List.of(
            call("rename_symbol", "{\"symbol\":\"a.B#c\",\"newName\":\"d\",\"precedentOverride\":\"differs\"}")), ENGINE);
        assertEquals(1, score.ranAsWritten());
    }

    @Test
    @DisplayName("the journal row's detail carries the tokens and the instructions a later score reads back")
    void rowsRoundTrip() throws Exception {
        List<Map<String, Object>> rows = Instructions.rows(Instructions.parse(ANSWER));
        JsonNode back = MAPPER.readTree(MAPPER.writeValueAsString(rows));
        assertAll(
            () -> assertEquals(3, back.size()),
            () -> assertEquals("rename_symbol", back.get(0).path("tool").asText()),
            () -> assertEquals("d", back.get(0).path("arguments").path("newName").asText()),
            () -> assertTrue(back.get(2).path("arguments").isNull()),
            () -> assertFalse(back.get(0).path("number").isMissingNode()));
    }
}
