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

    /** The errors and warnings it finds in a source file without a build; empty when the language has no such check. */
    default Optional<SourceDiagnostics> diagnostics(ProviderContext context) {
        return Optional.empty();
    }

    /** The source files it holds and what they declare, for the experience store to describe; empty when it holds none to describe. */
    default Optional<SourceUnits> sourceUnits(ProviderContext context) {
        return Optional.empty();
    }

    /**
     * Checks what this language declares about its own tools against what the engine now publishes, at the moment every language the
     * host serves has published its tools. A disagreement is thrown, and the engine then fails the load and says so rather than serve
     * a cure or a catalogue row that would be refused at the front door. A language that declares nothing of the kind keeps this.
     *
     * @throws RuntimeException when what the language declares and what is published disagree
     */
    default void verifyPublishedSurface() {
    }
}
