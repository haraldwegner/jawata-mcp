package org.jawata.mcp.java;

import java.util.Optional;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;
import org.jawata.spi.lang.LanguageProvider;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;

/**
 * The Java language provider (contract §4.5, E8): the one object that hands the engine Java's compile check, its way of naming
 * changed files, its symbol anchors and its test runtime, all over the model the headless host loaded. {@code JawataApplication}
 * assembles it directly until E19 registers it as a service. The model is asked for at each call, not held, because a workspace
 * may load or replace it after the provider exists.
 */
public final class JavaProvider implements LanguageProvider {

    private final Supplier<IJdtService> model;

    public JavaProvider(Supplier<IJdtService> model) {
        this.model = model;
    }

    @Override
    public String language() {
        return "java";
    }

    @Override
    public BuildVerdict verdict(ProviderContext context) {
        return new JavaBuildVerdict(model.get());
    }

    @Override
    public ChangePaths paths(ProviderContext context) {
        return new JavaChangePaths(model.get());
    }

    @Override
    public SymbolAnchors anchors(ProviderContext context) {
        return new JavaSymbolAnchors(model.get());
    }

    @Override
    public Optional<TestRuntime> tests(ProviderContext context) {
        return Optional.of(new JavaTestRuntime(model.get()));
    }

    /**
     * The context a change is applied under, built from what this provider and the host supply: the host's writes and reconciler,
     * this provider's verdict and paths. The headless host's own reconciler arrives with E18; until then the caller names one.
     */
    public ChangeContext changeContext(ProviderContext context) {
        return new ChangeContext(context.writes(), context.reconciler(), verdict(context), paths(context));
    }
}
