package org.jawata.spi.lang;

import java.util.Optional;

/**
 * One language the engine serves (contract §4.5, revision 2.1): Java in 28k, C++ in Sprint 35. The engine asks it four things —
 * how to check a change, how to name changed files, how memory finds a symbol, and what a test run needs. A provider that needs no
 * host binding is the test of this SPI: it runs the same in every host.
 *
 * <p>The contract's fifth member, {@code tools(ProviderContext)}, joins at E12 with the one tool producer: its element type is the
 * engine's tool, which this package may not import (rule R10) until that type has a neutral home.</p>
 */
public interface LanguageProvider {

    /** The language's name: "java", later "cpp". */
    String language();

    /** The language's compile check, at file and project granularity. */
    BuildVerdict verdict(ProviderContext context);

    /** How the language names the files a change touches. */
    ChangePaths paths(ProviderContext context);

    /** How memory anchors free text to this language's symbols and finds them. */
    SymbolAnchors anchors(ProviderContext context);

    /** What a forked test run of one of its projects needs; empty when the language has no tests to run. */
    Optional<TestRuntime> tests(ProviderContext context);

    /**
     * The projects this language has loaded, in plain types; empty while it has none loaded or holds no projects at all (the
     * language's answer is read at the moment of each ask, because a workspace may load or replace its model after the provider exists).
     */
    default Optional<LoadedWorkspace> workspace(ProviderContext context) {
        return Optional.empty();
    }
}
