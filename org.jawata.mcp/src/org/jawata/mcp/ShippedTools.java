package org.jawata.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.core.JavaBinding;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.engine.ProjectLoadingState;
import org.jawata.mcp.engine.ProviderPorts;
import org.jawata.mcp.engine.ToolNeeds;
import org.jawata.mcp.engine.ToolSource;
import org.jawata.mcp.execution.SelfEndpoint;
import org.jawata.mcp.field.InFlightCalls;
import org.jawata.mcp.knowledge.EmbeddingIndex;
import org.jawata.mcp.knowledge.ExperienceAdvisor;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.CompileWorkspaceTool;
import org.jawata.mcp.tools.ExperienceTool;
import org.jawata.mcp.tools.FieldTool;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.jawata.mcp.tools.GetDiagnosticsTool;
import org.jawata.mcp.tools.HealthCheckTool;
import org.jawata.mcp.tools.HireTool;
import org.jawata.mcp.tools.LoadProjectTool;
import org.jawata.mcp.tools.ProjectTool;
import org.jawata.mcp.tools.RefactoringTool;
import org.jawata.mcp.tools.RefreshWorkspaceTool;
import org.jawata.mcp.tools.RunTestsTool;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ToolRegistry;
import org.jawata.spi.host.HostWorkspace;

/**
 * THE ONE PRODUCER OF THE TOOLS THIS PRODUCT SHIPS (Sprint 28g C9a-1).
 *
 * <p>The list used to be written inside {@code JawataApplication.registerTools}, a private method
 * with one caller and 46 callees, so nothing could ask which tools ship. Every question about the
 * shipped set was answered by a hand-kept copy of it: a count in a script, a count in the studio's
 * help, a name set in the registry. Those copies drifted (a probe said 43 while the product shipped
 * 44, the studio's help said 45, a manifest said 63) because the only honest source was a running
 * jar. The refactoring doors had the same disease and the same cure: {@code RefactoringDoors.all}.
 * This is that cure for the whole set.</p>
 *
 * <p>It builds the tools and registers NOTHING. {@code ToolRegistry.register} publishes into the
 * process-wide operation registry, so a caller that only wants the names must not register; the
 * application registers what this returns, in the order it returns it.</p>
 *
 * <p>Since Sprint 28k (E12, E19) the list has two sources. The tools that name no language, and the verbs
 * whose language answer comes through a provider port, are built here over {@link ProviderPorts}. The tools that exist only
 * over one language's model come from the {@link ToolSource}s the engine hands in, and the ports themselves (memory's anchors, the
 * test runtime) from the language providers behind {@link ProviderPorts}; this producer names no provider and builds no port, and
 * the one language-specific tool class it still names is {@code FindQualityIssueTool}, the type of {@code Built.quality}, which
 * the application keeps for the watch engine's detector binding. {@link PublishedOrder} puts the two sources in the order the
 * product publishes.</p>
 *
 * <p>What the tools need from the running application is {@link Wiring}: suppliers read at call time
 * wherever the application's state changes after boot (the workspace service, the store), and the
 * few objects that are built once. An application that has booted passes the real ones; a test
 * passes inert ones and gets the same tools.</p>
 */
public final class ShippedTools {

    private ShippedTools() {
    }

    /**
     * Everything the tools take from the application.
     *
     * @param java               what the host supplies for Java: the workspace service (read at call time, since a load replaces
     *                           it) and how {@code load_project} brings a project into it
     * @param workspace          whether the engine owns the workspace, which {@code load_project} and {@code project} ask
     * @param projectLoaded     whether a workspace is loaded
     * @param toolCount          how many tools are registered, for {@code health_check}
     * @param loadingState       the workspace's load state
     * @param loadingError       the reason a load failed, or null
     * @param fieldDir           where the field recording lives
     * @param inFlight           the registry's own record of calls still running
     * @param changeCache        the staged refactoring changes and their undo handles
     * @param experienceStore    the store as it is NOW (the debugger and the advisor take it directly)
     * @param storeSupplier      the store, read at call time (the rename and the detectors take this)
     * @param embeddingIndex     the semantic index the advisor is given, read when the tools are built
     * @param runtimeSessions    the live debug and profile sessions
     * @param learnerEvents      the learner-event journal, which may answer null on a degraded resident
     * @param registry           the registry the tools are registered in, for the tool that gates through it
     * @param selfEndpoint       this resident's own endpoint, null on stdio and before it listens
     * @param defaultMemoryRoots where the experience tool loads memory files from by default
     */
    public record Wiring(
            JavaBinding java,
            HostWorkspace workspace,
            Supplier<Boolean> projectLoaded,
            Supplier<Integer> toolCount,
            Supplier<ProjectLoadingState> loadingState,
            Supplier<String> loadingError,
            Supplier<Path> fieldDir,
            Supplier<InFlightCalls> inFlight,
            RefactoringChangeCache changeCache,
            ExperienceStore experienceStore,
            Supplier<ExperienceStore> storeSupplier,
            Supplier<EmbeddingIndex> embeddingIndex,
            RuntimeSessionRegistry runtimeSessions,
            Supplier<LearnerEventStore> learnerEvents,
            Supplier<ToolRegistry> registry,
            Supplier<SelfEndpoint> selfEndpoint,
            Supplier<List<Path>> defaultMemoryRoots, Supplier<org.jawata.spi.host.Reconciler> reconciler, org.jawata.mcp.runtime.RuntimeArtifactStore runtimeArtifacts, org.jawata.mcp.coverage.CoverageService coverage, org.jawata.spi.host.DebugTurn debugTurn) {

        /** The workspace service as the tools take it: asked at call time, because a load replaces it. */
        public Supplier<IJdtService> jdt() {
            // A host that serves no Java has no model: every tool that asks sees none, which is what 'no project loaded' already means.
            return java == null ? () -> null : java.model()::current;
        }
    }

    /**
     * The tools, in registration order, and the three the application keeps hold of.
     *
     * @param tools      every tool built so far, unregistered
     * @param quality    kept for the watch engine's detector binding; null until a language contributes it
     * @param advisor    kept so the quality ledger reaches the pre-advice surface
     * @param experience kept for the automatic refresh after a project load
     * @param needs      what the languages' tools are handed beyond their own model
     */
    public record Built(
            List<Tool> tools,
            FindQualityIssueTool quality,
            ExperienceAdvisor advisor,
            ExperienceTool experience,
            ToolNeeds needs) {
    }

    /**
     * The tools that name no language, over the ports the languages offer. Built once, when the engine assembles: the engine keeps
     * the advisor and the experience tool, which it wires further, so these objects must not be built again when a language arrives.
     */
    public static Built neutral(Wiring w, ProviderPorts ports) {
        RunTestsTool runTests = new RunTestsTool(ports.workspace(), ports.tests(), w.coverage());

        // The plan lifecycle consults and records against the knowledge store through the SAME advisor the pre-advice surface uses,
        // so it is given the embedding index, not left keyword-only.
        ExperienceAdvisor advisor = new ExperienceAdvisor(w.experienceStore(), ports.anchors(), w.embeddingIndex().get());

        // The local experience/knowledge store's front door.
        ExperienceTool experience = ExperienceTool.withAnchors(ports.sourceUnits(), w.experienceStore(), w.defaultMemoryRoots(), ports.anchors());

        // Where a language offers the plan actions of the refactoring door, which is built now and reads the slot at each call.
        org.jawata.mcp.change.PlanSlot plans = new org.jawata.mcp.change.PlanSlot();
        ToolNeeds needs = new ToolNeeds(
            w.changeCache(), w.storeSupplier(), w.experienceStore(), w.runtimeSessions(), runTests::impactedTests,
            w.runtimeArtifacts(), w.debugTurn(), plans, advisor);
        return new Built(List.copyOf(neutralTools(w, runTests, experience, ports, plans)), null, advisor, experience, needs);
    }

    public static Built published(Built neutral, List<Tool> tools) {
        FindQualityIssueTool quality = tools.stream()
            .filter(FindQualityIssueTool.class::isInstance).map(FindQualityIssueTool.class::cast).findFirst().orElse(null);
        return new Built(List.copyOf(PublishedOrder.arrange(tools)), quality, neutral.advisor(), neutral.experience(), neutral.needs());
    }

    /**
     * The tools that name no language, and the verbs whose language answer comes through a provider port: health, the workspace
     * verbs, the field lane, the build and test verbs, the hire path, the refactoring lifecycle and memory.
     *
     * @param ports what the tools hold of the languages: {@code health_check} lists the bound ones through it
     */
    private static List<Tool> neutralTools(Wiring w, RunTestsTool runTests, ExperienceTool experience, ProviderPorts ports, org.jawata.mcp.change.PlanSlot plans) {
        Supplier<IJdtService> jdt = w.jdt();
        List<Tool> tools = new ArrayList<>();

        // health_check answers with the registry's own count and the application's load state.
        tools.add(new HealthCheckTool(w.projectLoaded(), w.toolCount(), w.loadingState(),
            w.loadingError(), ports.workspace(), ports.languages()));

        // load_project supplies the existing service so it can reuse it (clear-and-load on top) and
        // installs a fresh one on first call, which is what lets add_project / remove_project operate
        // on the same workspace.
        if (w.java() != null) {
            tools.add(new LoadProjectTool(w.java().model(), w.workspace()));
        }

        // project(action) collapses list/add/remove_project; load_project stays separate because it
        // installs the workspace service.
        tools.add(new ProjectTool(ports.workspace(), w.workspace()));

        // The field lane's one front door. Needs no loaded project: it answers about jawata's own
        // use. The registry's in-flight record lets `pile` report calls that never came back.
        tools.add(new FieldTool(w.fieldDir(), w.inFlight()));

        tools.add(new GetDiagnosticsTool(ports.workspace(), ports.diagnostics()));
        tools.add(new CompileWorkspaceTool(ports.workspace(), ports.verdict(), w.reconciler()));
        tools.add(runTests);

        // The hire path. The REGISTRY supplier breaks a real cycle (the tool sits in the registry its
        // own gates run through, so the gate runner is built per call); the JOURNAL supplier may
        // answer null, because a degraded resident has no journal, and the tool is still published
        // and refuses with that reason; the third is this resident's own endpoint.
        tools.add(new HireTool(HostProcesses.system(),
            w.runtimeArtifacts(),
            new PlanLedger(),
            w.learnerEvents(), w.registry(), w.selfEndpoint(), w::experienceStore));

        tools.add(new RefreshWorkspaceTool(ports.workspace(), ports.verdict(), w.reconciler()));
        tools.add(new RefactoringTool(ports.workspace(), w.changeCache(), ports.changes(), plans::current));
        tools.add(experience);

        return tools;
    }
}
