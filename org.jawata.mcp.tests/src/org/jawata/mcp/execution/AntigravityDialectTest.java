package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The Antigravity dialect, read from events captured from a real {@code agy} 1.5.15 run on 2026-11-23: a one-word question in an empty folder,
 * {@code agy --output-format stream-json -p='Reply with the single word OK.'}.
 */
class AntigravityDialectTest {

    private static final String INIT = "{\"event\":\"init\",\"conversation_id\":\"68432d0a\",\"init\":{\"cwd\":\"/tmp/x\","
        + "\"tools\":[\"ask_permission\",\"run_command\",\"write_to_file\"],\"permission_mode\":\"request-review\"}}";
    private static final String DELTA = "{\"event\":\"step_update\",\"step_update\":{\"conversation_id\":\"68432d0a\",\"step_index\":1,"
        + "\"state\":\"ACTIVE\",\"step_type\":\"agent_response\",\"text_delta\":\"OK\"}}";
    private static final String RESULT = "{\"event\":\"result\",\"result\":{\"conversation_id\":\"68432d0a\",\"status\":\"SUCCESS\","
        + "\"response\":\"OK\\n\",\"duration_seconds\":3.29,\"num_turns\":1,\"usage\":{\"input_tokens\":9611,\"output_tokens\":26,"
        + "\"thinking_tokens\":24,\"cache_read_tokens\":0,\"total_tokens\":9637}}}";

    @Test
    @DisplayName("28h z54: the prompt is attached to its flag, so a brief with newlines or flags in it is one argument, and a model goes before it")
    void thePromptIsAttachedToItsFlag() {
        assertEquals(List.of("agy", "--output-format", "stream-json", "-p=line one\n--model not-a-flag"),
            new AntigravityDialect("agy", null).command("line one\n--model not-a-flag", List.of()));
        assertEquals(List.of("agy", "--output-format", "stream-json", "--model", "gemini-4-neon", "-p=hi"),
            new AntigravityDialect(null, "gemini-4-neon").command("hi", List.of()));
    }

    @Test
    @DisplayName("28h z54: the answer is the result's response and carries the tokens, and no dollar cost is invented")
    void theResultIsTheAnswerAndCarriesTheTokens() {
        BrainLine line = new AntigravityDialect("agy", null).read(RESULT);

        assertTrue(line.terminal(), "the result ends the run");
        assertEquals(Optional.of("OK\n"), line.text());
        assertTrue(line.costUsd().isEmpty(), "agy reports no dollar cost, so a cost ceiling can never fire on it");
        assertEquals(9611L + 26L, line.tokens().orElseThrow().spent());
        assertEquals(24L, line.tokens().orElseThrow().reasoning());
    }

    @Test
    @DisplayName("28h z54: the init event reports the tools agy offers, and the step deltas are not added to the answer a second time")
    void theInitReportsTheToolsAndTheDeltasAreNotTheAnswer() {
        AntigravityDialect dialect = new AntigravityDialect("agy", null);

        assertEquals(Optional.of(List.of("ask_permission", "run_command", "write_to_file")), dialect.read(INIT).toolsOffered());
        BrainLine delta = dialect.read(DELTA);
        assertTrue(delta.text().isEmpty(), "the whole answer arrives in the result; adding the pieces would say it twice");
        assertFalse(delta.terminal());
    }

    @Test
    @DisplayName("28h z54: a line that is not JSON is counted as unreadable, not as an error and not as silence")
    void anUnreadableLineIsCountedNotErrored() {
        BrainLine line = new AntigravityDialect("agy", null).read("Fetching available models...");

        assertFalse(line.parsed());
    }

    @Test
    @DisplayName("28h z54: the dialect says plainly that agy's writes are not contained, with the measured runs, and that a command-line hire cannot attach the resident")
    void theWriteContainmentAndTheToolAttachmentAreStatedAsMeasured() {
        AntigravityDialect dialect = new AntigravityDialect("agy", null);

        assertFalse(dialect.writeContainment().isContained());
        assertTrue(dialect.writeContainment().report().contains("5 of 5"), dialect.writeContainment().report());
        assertTrue(dialect.toolAttachment().toString().contains("ACP"), dialect.toolAttachment().toString());
    }
}
