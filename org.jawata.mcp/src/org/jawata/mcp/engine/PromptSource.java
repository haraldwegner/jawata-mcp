package org.jawata.mcp.engine;

import java.util.List;
import java.util.Optional;

/**
 * Where the engine's MCP {@code prompts/list} and {@code prompts/get} get their prompts from (Sprint 28h, S14, D21).
 *
 * <p>The protocol handler asks this port and knows nothing of where a prompt is kept, in the way it asks {@link ToolCalls} for tools and
 * knows nothing of how one runs. The one implementation serves the seat files, so a seat added to the folder appears in every host that
 * lists prompts, with no change to the host. Both methods are asked on every call and answer from what is there at that moment, so a
 * seat switched off or added since the last call is seen at once.</p>
 */
public interface PromptSource {

    /**
     * One prompt as {@code prompts/list} shows it.
     *
     * @param name        what the host calls it by: the seat's command when it declares one, else the seat's name
     * @param title       what a picker shows: the seat's name, and its command when it has one
     * @param description what the prompt is for, in the seat's own words
     */
    record Prompt(String name, String title, String description) {
    }

    /** The prompts that can be offered now. An empty list is a real answer: no seat is there to offer. */
    List<Prompt> list();

    /**
     * The text of one prompt, as {@code prompts/get} returns it.
     *
     * @param name a name {@link #list()} gave
     * @return the text, or empty when no prompt has that name now
     */
    Optional<String> text(String name);
}
