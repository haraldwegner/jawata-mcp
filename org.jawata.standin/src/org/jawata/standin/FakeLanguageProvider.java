package org.jawata.standin;

import java.util.List;
import java.util.Optional;

import org.jawata.mcp.engine.ToolNeeds;
import org.jawata.mcp.engine.ToolSource;
import org.jawata.mcp.tools.Tool;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;
import org.jawata.spi.lang.LanguageProvider;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;

/**
 * The stand-in language provider (Sprint 28k, Stage 8; contract §18 ring 1, the slot for a second language): a language that imports no
 * Java tooling at all. The engine assembles with it alone, the tools that name no language work over it, and dropped into a built
 * distribution as a bundle it appears beside {@code java} with one tool of its own.
 *
 * <p>Its rules are plain text so that every port has an answer a test can read: a file containing the word BROKEN does not build, nothing
 * has a symbol, and there is no test runtime.</p>
 */
public final class FakeLanguageProvider implements LanguageProvider, ToolSource {

    /** The name the language goes by in the engine's list of languages. */
    public static final String LANGUAGE = "fake";

    @Override
    public String language() {
        return LANGUAGE;
    }

    @Override
    public BuildVerdict verdict(ProviderContext context) {
        return new FakeBuildVerdict();
    }

    @Override
    public ChangePaths paths(ProviderContext context) {
        return new FakeChangePaths();
    }

    @Override
    public SymbolAnchors anchors(ProviderContext context) {
        return new FakeSymbolAnchors();
    }

    @Override
    public Optional<TestRuntime> tests(ProviderContext context) {
        return Optional.empty();
    }

    @Override
    public List<Tool> tools(ToolNeeds needs) {
        return List.of(new FakeEchoTool());
    }
}
