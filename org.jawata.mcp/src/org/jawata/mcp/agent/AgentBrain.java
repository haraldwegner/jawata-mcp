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

    /**
     * Answers {@code prompt} and also passes the brain's thinking and its plan to {@code thinking} as they happen, each as the ACP
     * {@code agent_thought_chunk} or {@code plan} update the brain streamed (contract R9 and R10). A brain that streams neither, or
     * that cannot see them, ignores the sink; the sink may be null.
     */
    default void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls, Consumer<JsonNode> thinking) {
        answer(prompt, chunks, toolCalls);
    }

    /**
     * The session id this brain's own calls to the engine's tools carry, when it has one: the hires it issues are opened under it, so it is
     * also the session whose pending steps the turn boundary must ask about. None for a brain that makes no calls the engine can attribute.
     */
    default java.util.Optional<String> toolSessionKey() {
        return java.util.Optional.empty();
    }

    /** The name of the brain this runs on, the way the registry names it; none for an answerer with no named brain (D53 (i)). */
    default java.util.Optional<String> currentBrain() {
        return java.util.Optional.empty();
    }

    /** Moves to the brain the registry names, from the next prompt; false when this answerer cannot, or the registry has no such program. */
    default boolean useBrain(String registry, String name) {
        return false;
    }

    /** Pictures that go with the next prompt, as ACP image blocks (D53 (e)); an answerer that takes none ignores them. */
    default void attachImages(java.util.List<JsonNode> images) {
    }

    /** The folder the person opened, which the conversation works in (D53): an answerer that starts a brain starts it there. */
    default void workIn(String folder) {
    }

    /** A conversation has opened and a first prompt is coming: an answerer that needs a slow start begins it now (D53 (g)). */
    default void prepare() {
    }

    /** The person stopped the turn: an answerer that is reading a brain's answer tells that brain, and its turn ends with the brain's reply (D53 (f)). */
    default void cancel() {
    }

    /** The conversation is over: whatever this answerer started for it, a process among it, goes now. */
    default void close() {
    }
}
