package org.jawata.mcp.agent;

import java.util.function.Consumer;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Whatever answers the person's prompt (Sprint 28h, D1). Behind this seam sits the hierarchy and, below it, the hired brains; the
 * agent face knows only that a prompt goes in and the answer comes out in pieces, so a test can play a recorded answer into the
 * same face a real brain feeds.
 */
@FunctionalInterface
public interface AgentBrain {

    /**
     * Answers {@code prompt}, passing each piece of the answer to {@code chunks} as it is produced. Returns when the answer is complete.
     */
    void answer(String prompt, Consumer<String> chunks);

    /**
     * Answers {@code prompt} and also passes every tool call the brain makes to {@code toolCalls} as it happens, each as the ACP
     * {@code tool_call} or {@code tool_call_update} the brain streamed. A brain that makes no tool calls the face can see ignores the sink.
     */
    default void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
        answer(prompt, chunks);
    }
}
