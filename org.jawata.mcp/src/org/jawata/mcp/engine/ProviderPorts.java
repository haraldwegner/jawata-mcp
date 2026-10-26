package org.jawata.mcp.engine;

import java.util.Objects;
import java.util.Optional;
import java.util.function.Supplier;

import org.jawata.spi.lang.LanguageProvider;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;

/**
 * What the tools that name no language hold of the languages (Sprint 28k, E19; contract §4.5): memory's symbol anchors and the
 * forked-run port, each read at the moment of the ask from the first bound provider that has one, and answering null while none
 * does. A tool holding these never names a provider, and starts working when a language arrives.
 *
 * @param anchors the first bound provider's anchors, or null while no language has anything to anchor to
 * @param tests   the first bound provider's test runtime, or null while no language can run tests
 * @param languages the names of the languages bound now, in the order they bound; empty while none is
 */
public record ProviderPorts(Supplier<SymbolAnchors> anchors, Supplier<TestRuntime> tests, Supplier<java.util.List<String>> languages, java.util.function.Supplier<org.jawata.spi.lang.LoadedWorkspace> workspace, java.util.function.Supplier<org.jawata.spi.lang.BuildVerdict> verdict, java.util.function.Supplier<org.jawata.spi.lang.SourceDiagnostics> diagnostics) {

    /** The ports over the languages bound on {@code board} now and later, each provider asked under {@code context}. */
    public static ProviderPorts over(EngineWhiteboard board, ProviderContext context) {
        return new ProviderPorts(
            () -> board.providers().stream()
                .map(provider -> provider.anchors(context))
                .filter(Objects::nonNull)
                .findFirst().orElse(null),
            () -> board.providers().stream()
                .map((LanguageProvider provider) -> provider.tests(context))
                .flatMap(Optional::stream)
                .findFirst().orElse(null),
            () -> board.providers().stream().map(LanguageProvider::language).toList(),
            () -> board.providers().stream()
                .map((LanguageProvider provider) -> provider.workspace(context))
                .flatMap(Optional::stream)
                .findFirst().orElse(org.jawata.spi.lang.LoadedWorkspace.NONE),
            () -> board.providers().stream()
                .map((LanguageProvider provider) -> provider.verdict(context))
                .filter(Objects::nonNull)
                .findFirst().orElse(null),
            () -> board.providers().stream()
                .map((LanguageProvider provider) -> provider.diagnostics(context))
                .flatMap(Optional::stream)
                .findFirst().orElse(null));
    }
}
