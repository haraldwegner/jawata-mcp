package org.jawata.mcp.fixtures;

import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.mcp.change.PlanLifecycle;
import org.jawata.mcp.change.PlanStore;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.domain.Advisor;
import org.jawata.mcp.domain.NoOpAdvisor;
import org.jawata.mcp.java.JavaChange;
import org.jawata.mcp.tools.PlanRefactoringTool;
import org.jawata.mcp.tools.RefactoringTool;

/**
 * The refactoring lifecycle over a Java service, wired the way the application wires it (Sprint 28k, Stage 9): the change context is
 * Java's own ({@link JavaChange#contextFor}) and the plan actions are the ones the Java provider offers. The tools take these as
 * ports now, so a test that drives them over a Java service builds them here.
 */
public final class JavaLifecycle {

    private JavaLifecycle() {
    }

    /** What a change against this service is applied under. */
    public static Supplier<ChangeContext> changes(IJdtService service) {
        return () -> JavaChange.contextFor(service);
    }

    /** The lifecycle door over this service, with no store behind the plan advice. */
    public static RefactoringTool door(IJdtService service, RefactoringChangeCache cache) {
        return door(service, cache, new NoOpAdvisor());
    }

    /** The lifecycle door over this service, with the plan lifecycle consulting and recording through {@code advisor}. */
    public static RefactoringTool door(IJdtService service, RefactoringChangeCache cache, Advisor advisor) {
        PlanLifecycle plans = new PlanRefactoringTool(() -> service, cache, new PlanStore(), advisor);
        return new RefactoringTool(() -> service, cache, changes(service), () -> plans);
    }
}
