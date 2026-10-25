package org.jawata.mcp.engine;

import java.util.List;

import org.jawata.mcp.tools.Tool;

/**
 * The tools a language adds to the engine's neutral set (Sprint 28k, E19; contract §4.5 as built, revision 2.6). A language provider
 * that has tools of its own also implements this; the engine asks each bound provider that does, in the order they arrived, and
 * publishes what comes back beside the tools that name no language.
 *
 * <p>This is a slot of its own, not a member of {@code LanguageProvider}: a tool is the engine's type, and the SPI package imports
 * nothing of the engine (R10). Moving {@code Tool} and its response type into the SPI would rewrite every tool in the product.</p>
 */
public interface ToolSource {

    /**
     * The tools this language adds. Called each time the set of languages changes, so a call may build new tool objects; the engine
     * keeps none of the previous answer.
     *
     * @param needs what the engine hands every tool beyond the language model
     * @return the tools, unregistered, in no promised order (the producer fixes the published order)
     */
    List<Tool> tools(ToolNeeds needs);
}
