package org.jawata.mcp.engine;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import org.jawata.mcp.ShippedTools;
import org.jawata.mcp.ShippedTools.Built;
import org.jawata.mcp.tools.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Publishes the engine's tools as languages arrive and leave (Sprint 28k, E19): the tools that name no language straight away, and
 * each bound language's tools when it binds. The engine reports "loading" from the moment it is built until the language its host
 * serves has bound, and only then runs the check that every tool has fed its operations into the registry.
 *
 * <p>A language's tools are built once, when it first appears, and kept until it leaves, so a second language arriving does not
 * rebuild the first one's tools. Every change publishes the whole set again in the order the product publishes it.</p>
 */
public final class LanguagePublisher {

    private static final Logger log = LoggerFactory.getLogger(LanguagePublisher.class);

    private final EngineWhiteboard board;
    private final Built neutral;
    private final Consumer<Built> publish;
    private final EngineState state;
    private final Runnable whenAwaitedLanguageBound;
    private final String awaitedLanguage;

    /** What each bound language contributed. */
    private final Map<ToolSource, List<Tool>> sourceTools = new LinkedHashMap<>();

    private boolean awaiting;

    /**
     * Starts publishing: the neutral tools go out now, and every later arrival or departure on {@code board} publishes again.
     *
     * @param board                    where languages arrive
     * @param neutral                  the tools that name no language, built once
     * @param publish                  receives the whole published set each time it changes
     * @param awaitedLanguage          the language the host serves, which the engine waits for; empty when it waits for none
     * @param state                    told whether a language is still awaited
     * @param whenAwaitedLanguageBound runs once, after the awaited language has published its tools and before the engine stops
     *                                 reporting "loading"
     */
    public LanguagePublisher(EngineWhiteboard board, Built neutral, Consumer<Built> publish, Optional<String> awaitedLanguage,
            EngineState state, Runnable whenAwaitedLanguageBound) {
        this.board = board;
        this.neutral = neutral;
        this.publish = publish;
        this.state = state;
        this.whenAwaitedLanguageBound = whenAwaitedLanguageBound;
        this.awaitedLanguage = awaitedLanguage.orElse(null);
        this.awaiting = awaitedLanguage.isPresent();
        state.awaitingLanguage(awaiting);
        board.whenProvidersChange(this::languagesChanged);
        languagesChanged();
    }

    private synchronized void languagesChanged() {
        List<ToolSource> bound = board.providers().stream().filter(ToolSource.class::isInstance)
            .map(ToolSource.class::cast).toList();
        sourceTools.keySet().retainAll(bound);
        for (ToolSource source : bound) {
            sourceTools.computeIfAbsent(source, s -> s.tools(neutral.needs()));
        }
        List<Tool> all = new ArrayList<>(neutral.tools());
        sourceTools.values().forEach(all::addAll);
        Built published = ShippedTools.published(neutral, all);
        publish.accept(published);
        log.info("Published {} tools over {} language(s)", published.tools().size(), board.providers().size());

        if (awaitedLanguage == null) {
            return;
        }
        boolean awaitedBound = board.providers().stream().anyMatch(provider -> provider.language().equals(awaitedLanguage));
        if (awaiting && awaitedBound) {
            awaiting = false;
            whenAwaitedLanguageBound.run();
            state.awaitingLanguage(false);
        } else if (!awaiting && !awaitedBound) {
            // The language the host serves has left: the engine has no tools of its own for it, so it is loading again.
            awaiting = true;
            state.awaitingLanguage(true);
        }
    }
}
