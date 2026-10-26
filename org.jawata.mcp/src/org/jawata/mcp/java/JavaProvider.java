package org.jawata.mcp.java;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.core.JavaBinding;
import org.jawata.core.ModelSource;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.engine.ToolNeeds;
import org.jawata.mcp.engine.ToolSource;
import org.jawata.mcp.knowledge.ExperienceRetrieval;
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
import org.jawata.mcp.tools.LoadProjectTool;
import org.jawata.mcp.tools.ProfileTool;
import org.jawata.mcp.tools.QuickFixTool;
import org.jawata.mcp.tools.RefactoringDoors;
import org.jawata.mcp.tools.SearchSymbolsTool;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ValidateSyntaxTool;
import org.jawata.mcp.tools.build.DependencyTool;
import org.jawata.mcp.tools.workflow.FormatTool;
import org.jawata.spi.host.EngineHost;
import org.jawata.spi.host.HostWorkspace;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.ChangePaths;
import org.jawata.spi.lang.LanguageProvider;
import org.jawata.spi.lang.LoadedWorkspace;
import org.jawata.spi.lang.ProviderContext;
import org.jawata.spi.lang.SymbolAnchors;
import org.jawata.spi.lang.TestRuntime;

/**
 * The Java language provider (contract §4.5, E8): the one object that hands the engine Java's compile check, its way of naming
 * changed files, its symbol anchors and its test runtime, all over the model the headless host loaded, and (E12) the tools that
 * only make sense over a Java model. {@code JawataApplication} assembles it directly until E19 registers it as a service. The model
 * is asked for at each call, not held, because a workspace may load or replace it after the provider exists.
 */
public final class JavaProvider implements LanguageProvider, ToolSource {

    private final Supplier<IJdtService> model;

    /** Where a project comes into the model, for a provider built directly; null when the host supplies it. */
    private final ModelSource source;

    /** Whether the engine owns the workspace, for a provider built directly; null when the host supplies it. */
    private final HostWorkspace owner;

    /** The host the component runtime hands this provider before it is published as a service; null for a provider built directly. */
    private volatile EngineHost host;

    public JavaProvider(Supplier<IJdtService> model) {
        this.model = model;
        this.source = null;
        this.owner = null;
    }

    /**
     * A provider built directly over the model source and workspace ownership a host would supply, so it publishes {@code load_project}
     * as the component-built one does.
     *
     * @param source where the Java model comes from and how a project comes into it
     * @param owner  whether the engine owns the workspace, which {@code load_project} asks
     */
    public JavaProvider(ModelSource source, HostWorkspace owner) {
        this.model = source::current;
        this.source = source;
        this.owner = owner;
    }

    /**
     * The constructor the component runtime uses (declarative service, {@code OSGI-INF/org.jawata.mcp.java.JavaProvider.xml}): the
     * model is whatever the host's Java binding holds at the moment of each ask, so the provider can exist before any project loads.
     */
    public JavaProvider() {
        this.model = () -> host.binding(JavaBinding.class).orElseThrow().model().current();
        this.source = null;
        this.owner = null;
    }

    /** The component runtime hands in the host the engine was built from; the provider is published once it has it. */
    void bindHost(EngineHost host) {
        this.host = host;
        JavaChange.hostedBy(host.reconciler(), host.writes());
        // The headless JDT-manipulation config (Sprint 23, Stage 5): the preference node, the JDT-UI-style defaults and the code-template
        // store that an IDE gets from JDT-UI activation. It is Java's, so it is set up when the Java provider is handed its host and not
        // when the engine assembles, which lets an engine with no Java assemble. E15: it stands down when the host did the setup itself.
        org.jawata.mcp.tools.shared.HeadlessJdtConfig.hostedBy(host.binding(JavaBinding.class).orElseThrow());
        org.jawata.mcp.tools.shared.HeadlessJdtConfig.ensureInitialized();
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

    /** Java's loaded workspace is its own service: the one object answers type queries and the neutral project questions. */
    @Override
    public Optional<LoadedWorkspace> workspace(ProviderContext context) {
        return Optional.ofNullable(model.get());
    }

    /** Java's problems in a source file: JDT's reconcile, or the last build's markers for a file that is not open. */
    @Override
    public Optional<org.jawata.spi.lang.SourceDiagnostics> diagnostics(ProviderContext context) {
        return Optional.of(new JavaSourceDiagnostics());
    }

    /** The compilation units of whatever model is loaded at the moment of the ask; empty while none is. */
    @Override
    public Optional<org.jawata.spi.lang.SourceUnits> sourceUnits(ProviderContext context) {
        IJdtService loaded = model.get();
        return loaded == null ? Optional.empty() : Optional.of(new JavaSourceUnits(loaded));
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
     * The tools that exist only over a Java model (contract §3, E12): navigation, search, analysis, the refactoring doors, the
     * quality and modernization sweeps, the debugger and profiler, the build-file and formatting tools. Built here, not by the
     * producer, so the engine's producer constructs none of them. Registers nothing. This is the engine's {@link ToolSource} slot
     * (E19): the engine asks every bound provider that has one.
     */
    @Override
    public List<Tool> tools(ToolNeeds needs) {
        Supplier<IJdtService> jdt = model;
        List<Tool> tools = new ArrayList<>();

        // The plan actions of the refactoring door are Java's: the plans are made of its refactorings. The door is built before any
        // language binds and reads the slot at each call.
        needs.plans().offer(new org.jawata.mcp.tools.PlanRefactoringTool(jdt, needs.changeCache(),
            new org.jawata.mcp.change.PlanStore(), needs.advisor()));

        // load_project brings a Java project into the model: the verb is Java's, and the host supplies where the model comes from and
        // whether the engine owns the workspace (a provider built directly brings its own).
        ModelSource loadFrom = source != null ? source
            : host == null ? null : host.binding(JavaBinding.class).map(JavaBinding::model).orElse(null);
        HostWorkspace owns = owner != null ? owner : host == null ? null : host.workspace();
        if (loadFrom != null && owns != null) {
            tools.add(new LoadProjectTool(loadFrom, owns));
        }

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
        tools.add(new FindQualityIssueTool(jdt, needs.storeSupplier()));
        tools.add(new FindStringLiteralsTool(jdt));

        // The interactive debugger is given the store: a breakpoint hit RECALLS what is recorded about its symbol. The profiler
        // shares the sessions the debugger opens.
        tools.add(new DebugTool(jdt, needs.runtimeSessions(), needs.runtimeArtifacts(),
            new ExperienceRetrieval(needs.experienceStore(), memoryAnchors()), needs.debugTurn()));
        tools.add(new ProfileTool(jdt, needs.runtimeSessions(), needs.runtimeArtifacts()));

        tools.add(new FindDuplicateCodeTool(jdt));
        tools.add(new FindModernizationTool(jdt));
        tools.add(new ApplyNullAnnotationsTool(jdt, needs.changeCache()));
        tools.add(new DependencyTool(jdt));
        tools.add(new FormatTool(jdt));
        tools.add(new QuickFixTool(jdt));

        return List.copyOf(tools);
    }
}
