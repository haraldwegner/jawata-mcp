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

    /**
     * The name of the model this brain is on (D54 (a)), as the brain itself reports it for its session: what is running, and not what a registry
     * says the brain would start with. None when the brain has not said, which a row shows as the brain's name alone.
     */
    default java.util.Optional<String> currentModel() {
        return java.util.Optional.empty();
    }

    /** The value the brain's own model option takes for the model it is on; the one a choice of that model is sent back as. None when it has not said. */
    default java.util.Optional<String> currentModelValue() {
        return java.util.Optional.empty();
    }

    /** The models the brain offers for the session it has open: none before it has one, and none for a brain that offers none. */
    default java.util.List<org.jawata.mcp.execution.AcpBrainSession.Model> models() {
        return java.util.List.of();
    }

    /** Moves to the model named (a value the brain offered, or its name), from the next prompt; false when the brain offers no such model or this answerer cannot. */
    default boolean useModel(String model) {
        return false;
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

    /**
     * {@link #prepare()}, and {@code whenReady} is run once the brain's session is open (or has failed to open), so the face can tell the host what
     * only then becomes known: the models the brain offers and the one it is on (D54 (a)). An answerer with no slow start runs it at once.
     */
    default void prepare(Runnable whenReady) {
        prepare();
        whenReady.run();
    }

    /** The person stopped the turn: an answerer that is reading a brain's answer tells that brain, and its turn ends with the brain's reply (D53 (f)). */
    default void cancel() {
    }

    /** The conversation is over: whatever this answerer started for it, a process among it, goes now. */
    default void close() {
    }
}
