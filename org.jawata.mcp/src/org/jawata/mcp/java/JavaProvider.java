package org.jawata.mcp.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.knowledge.ExperienceRetrieval;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.AnalyzeTool;
import org.jawata.mcp.tools.ApplyNullAnnotationsTool;
import org.jawata.mcp.tools.DebugTool;
import org.jawata.mcp.tools.FindDuplicateCodeTool;
import org.jawata.mcp.tools.FindFieldWritesTool;
import org.jawata.mcp.tools.FindModernizationTool;
import org.jawata.mcp.tools.FindPatternUsagesTool;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.jawata.mcp.tools.FindRefsTool;
import org.jawata.mcp.tools.FindStringLiteralsTool;
import org.jawata.mcp.tools.FindTestsTool;
import org.jawata.mcp.tools.GetAtPositionTool;
import org.jawata.mcp.tools.GetCallHierarchyTool;
import org.jawata.mcp.tools.GoToDefinitionTool;
import org.jawata.mcp.tools.InspectTool;
import org.jawata.mcp.tools.ProfileTool;
import org.jawata.mcp.tools.QuickFixTool;
import org.jawata.mcp.tools.RefactoringDoors;
import org.jawata.mcp.tools.SearchSymbolsTool;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ValidateSyntaxTool;
import org.jawata.mcp.tools.build.DependencyTool;
import org.jawata.mcp.tools.workflow.FormatTool;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;
import org.jawata.spi.lang.LanguageProvider;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The Java language provider (contract §4.5, E8): the one object that hands the engine Java's compile check, its way of naming
 * changed files, its symbol anchors and its test runtime, all over the model the headless host loaded, and (E12) the tools that
 * only make sense over a Java model. {@code JawataApplication} assembles it directly until E19 registers it as a service. The model
 * is asked for at each call, not held, because a workspace may load or replace it after the provider exists.
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
        return Optional.of(testRuntime());
    }

    /**
     * What the knowledge classes and the tools that hold them keep: Java's anchors over whatever model is loaded at the moment of
     * the ask, or null while none is. The one place {@link JavaSymbolAnchors#over} is called (E12, ledger 28k-C3-8), so a tool that
     * needs memory's anchors is handed this and never builds its own.
     */
    public Supplier<SymbolAnchors> memoryAnchors() {
        return JavaSymbolAnchors.over(model);
    }

    /**
     * The forked-run port over the model loaded at the moment of the call. The one place a {@link JavaTestRuntime} is built (E12,
     * ledger 28k-C3-8); {@link #tests} answers it too.
     */
    public TestRuntime testRuntime() {
        return new JavaTestRuntime(model.get());
    }

    /**
     * The context a change is applied under, built from what this provider and the host supply: the host's writes and reconciler,
     * this provider's verdict and paths. The headless host's own reconciler arrives with E18; until then the caller names one.
     */
    public ChangeContext changeContext(ProviderContext context) {
        return new ChangeContext(context.writes(), context.reconciler(), verdict(context), paths(context));
    }

    /**
     * What the Java tools take from the engine beyond the model, which they ask this provider for.
     *
     * @param changeCache     the staged refactoring changes and their undo handles
     * @param storeSupplier   the knowledge store, read at call time
     * @param experienceStore the knowledge store as it is now (the debugger recalls from it)
     * @param runtimeSessions the live debug and profile sessions
     * @param impactedTests   how {@code inspect} asks {@code run_tests} for the tests a change reaches
     */
    public record ToolNeeds(
            RefactoringChangeCache changeCache,
            Supplier<ExperienceStore> storeSupplier,
            ExperienceStore experienceStore,
            RuntimeSessionRegistry runtimeSessions,
            Function<JsonNode, ToolResponse> impactedTests) {
    }

    /**
     * The tools this provider adds, and the one of them the application keeps hold of.
     *
     * @param tools   the Java tools, unregistered, in no promised order (the producer fixes the published order)
     * @param quality kept for the watch engine's detector binding
     */
    public record Contribution(List<Tool> tools, FindQualityIssueTool quality) {
    }

    /**
     * The tools that exist only over a Java model (contract §3, E12): navigation, search, analysis, the refactoring doors, the
     * quality and modernization sweeps, the debugger and profiler, the build-file and formatting tools. Built here, not by the
     * producer, so the engine's producer constructs none of them; it names only {@link FindQualityIssueTool}, as the type of the
     * quality tool it keeps for the watch engine. Registers nothing. The contract's {@code ProviderContext}
     * joins this signature with the SPI member, at E19, when the engine hands providers their context.
     */
    public Contribution tools(ToolNeeds needs) {
        Supplier<IJdtService> jdt = model;
        List<Tool> tools = new ArrayList<>();

        // Core navigation, diagnostics-free analysis and call hierarchy.
        tools.add(new SearchSymbolsTool(jdt));
        tools.add(new GoToDefinitionTool(jdt));
        tools.add(new FindRefsTool(jdt));
        // The impacted-test selection is on the read-only door too: a hired Claude is refused run_tests in plan mode, and inspect
        // is read-only.
        InspectTool inspect = new InspectTool(jdt);
        inspect.setImpactedTests(needs.impactedTests());
        tools.add(inspect);
        tools.add(new AnalyzeTool(jdt));
        tools.add(new GetAtPositionTool(jdt));
        tools.add(new ValidateSyntaxTool(jdt));
        tools.add(new GetCallHierarchyTool(jdt));
        tools.add(new FindFieldWritesTool(jdt));
        tools.add(new FindTestsTool(jdt));

        // The refactoring tools that dispatch on nothing, and the doors, each from ONE source, so a gate can join against what is
        // registered. The store reaches rename_symbol as a SUPPLIER, read at call time, so a renamed member takes the knowledge
        // anchored at it.
        tools.addAll(RefactoringDoors.standalone(jdt, needs.changeCache(), needs.storeSupplier()));
        tools.addAll(RefactoringDoors.all(jdt, needs.changeCache()));

        tools.add(new FindPatternUsagesTool(jdt));
        // The detectors whose cure is a catalogue entry resolve their addresses from the store at scan time, so this does not depend
        // on whether the store is assigned yet.
        FindQualityIssueTool quality = new FindQualityIssueTool(jdt, needs.storeSupplier());
        tools.add(quality);
        tools.add(new FindStringLiteralsTool(jdt));

        // The interactive debugger is given the store: a breakpoint hit RECALLS what is recorded about its symbol. The profiler
        // shares the sessions the debugger opens.
        tools.add(new DebugTool(jdt, needs.runtimeSessions(), new RuntimeArtifactStore(),
            new ExperienceRetrieval(needs.experienceStore(), memoryAnchors())));
        tools.add(new ProfileTool(jdt, needs.runtimeSessions()));

        tools.add(new FindDuplicateCodeTool(jdt));
        tools.add(new FindModernizationTool(jdt));
        tools.add(new ApplyNullAnnotationsTool(jdt, needs.changeCache()));
        tools.add(new DependencyTool(jdt));
        tools.add(new FormatTool(jdt));
        tools.add(new QuickFixTool(jdt));

        return new Contribution(List.copyOf(tools), quality);
    }
}
