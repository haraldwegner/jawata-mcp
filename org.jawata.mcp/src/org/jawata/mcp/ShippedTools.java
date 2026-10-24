package org.jawata.mcp;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.execution.SelfEndpoint;
import org.jawata.mcp.field.InFlightCalls;
import org.jawata.mcp.java.JavaProvider;
import org.jawata.mcp.knowledge.EmbeddingIndex;
import org.jawata.mcp.knowledge.ExperienceAdvisor;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
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
import org.jawata.spi.lang.SymbolAnchors;

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
 * <p>Since Sprint 28k (E12) the list has two sources. The tools that name no language, and the verbs
 * whose language answer comes through a provider port, are built here. The tools that exist only
 * over a Java model, and the ports themselves (memory's anchors, the test runtime), come from
 * {@link JavaProvider}; this producer builds no port, and the one Java tool class it still names is
 * {@code FindQualityIssueTool}, the type of {@code Built.quality}, which the application keeps for the watch engine's detector
 * binding. {@link PublishedOrder} puts the two sources in the order the product publishes.</p>
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
     * @param jdt                the workspace service, read at call time (it is replaced by a load)
     * @param installJdt         how {@code load_project} installs a service
     * @param projectLoaded      whether a workspace is loaded
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
            Supplier<IJdtService> jdt,
            Consumer<IJdtService> installJdt,
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
            Supplier<List<Path>> defaultMemoryRoots, Supplier<org.jawata.spi.host.Reconciler> reconciler) {
    }

    /**
     * The tools, in registration order, and the three the application keeps hold of.
     *
     * @param tools      every shipped tool, unregistered
     * @param quality    kept for the watch engine's detector binding
     * @param advisor    kept so the quality ledger reaches the pre-advice surface
     * @param experience kept for the automatic refresh after a project load
     */
    public record Built(
            List<Tool> tools,
            FindQualityIssueTool quality,
            ExperienceAdvisor advisor,
            ExperienceTool experience) {
    }

    /** Build the shipped tools from the wiring. Registers nothing. */
    public static Built build(Wiring w) {
        // Until E19 registers the provider as a service it is assembled here, over the same model supplier the tools use.
        JavaProvider java = new JavaProvider(w.jdt());
        Supplier<SymbolAnchors> anchors = java.memoryAnchors();

        RunTestsTool runTests = new RunTestsTool(w.jdt(), java::testRuntime);

        // The plan lifecycle consults and records against the knowledge store through the SAME advisor the pre-advice surface uses,
        // so it is given the embedding index, not left keyword-only.
        ExperienceAdvisor advisor = new ExperienceAdvisor(w.experienceStore(), anchors, w.embeddingIndex().get());

        // The local experience/knowledge store's front door.
        ExperienceTool experience = ExperienceTool.withAnchors(w.jdt(), w.experienceStore(), w.defaultMemoryRoots(), anchors);

        List<Tool> tools = new ArrayList<>(neutralTools(w, runTests, advisor, experience));
        JavaProvider.Contribution contribution = java.tools(new JavaProvider.ToolNeeds(
            w.changeCache(), w.storeSupplier(), w.experienceStore(), w.runtimeSessions(), runTests::impactedTests));
        tools.addAll(contribution.tools());

        return new Built(List.copyOf(PublishedOrder.arrange(tools)), contribution.quality(), advisor, experience);
    }

    /**
     * The tools that name no language, and the verbs whose language answer comes through a provider port: health, the workspace
     * verbs, the field lane, the build and test verbs, the hire path, the refactoring lifecycle and memory.
     */
    private static List<Tool> neutralTools(Wiring w, RunTestsTool runTests, ExperienceAdvisor advisor,
            ExperienceTool experience) {
        Supplier<IJdtService> jdt = w.jdt();
        List<Tool> tools = new ArrayList<>();

        // health_check answers with the registry's own count and the application's load state.
        tools.add(new HealthCheckTool(w.projectLoaded(), w.toolCount(), w.loadingState(),
            w.loadingError(), jdt));

        // load_project supplies the existing service so it can reuse it (clear-and-load on top) and
        // installs a fresh one on first call, which is what lets add_project / remove_project operate
        // on the same workspace.
        tools.add(new LoadProjectTool(jdt, w.installJdt()));

        // project(action) collapses list/add/remove_project; load_project stays separate because it
        // installs the workspace service.
        tools.add(new ProjectTool(jdt));

        // The field lane's one front door. Needs no loaded project: it answers about jawata's own
        // use. The registry's in-flight record lets `pile` report calls that never came back.
        tools.add(new FieldTool(jdt, w.fieldDir(), w.inFlight()));

        tools.add(new GetDiagnosticsTool(jdt));
        tools.add(new CompileWorkspaceTool(jdt, w.reconciler()));
        tools.add(runTests);

        // The hire path. The REGISTRY supplier breaks a real cycle (the tool sits in the registry its
        // own gates run through, so the gate runner is built per call); the JOURNAL supplier may
        // answer null, because a degraded resident has no journal, and the tool is still published
        // and refuses with that reason; the third is this resident's own endpoint.
        tools.add(new HireTool(jdt, HostProcesses.system(),
            new RuntimeArtifactStore(),
            new PlanLedger(),
            w.learnerEvents(), w.registry(), w.selfEndpoint(), w::experienceStore));

        tools.add(new RefreshWorkspaceTool(jdt, w.reconciler()));
        tools.add(new RefactoringTool(jdt, w.changeCache(), advisor));
        tools.add(experience);

        return tools;
    }
}
