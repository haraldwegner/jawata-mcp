package org.jawata.mcp.change;

/**
 * Where a language offers its {@link PlanLifecycle} (Sprint 28k, Stage 9). The refactoring front door is built before any language
 * binds and reads the slot at the moment of each call; a language that has refactorings to plan offers it when its tools are built.
 */
public final class PlanSlot {

    private volatile PlanLifecycle offered;

    /** @param lifecycle the language's plan actions, replacing any offered before */
    public void offer(PlanLifecycle lifecycle) {
        this.offered = lifecycle;
    }

    /** @return what a language offered, or null while none has */
    public PlanLifecycle current() {
        return offered;
    }
}
