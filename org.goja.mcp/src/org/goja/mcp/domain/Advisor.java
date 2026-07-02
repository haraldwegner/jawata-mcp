package org.goja.mcp.domain;

import java.util.List;

/**
 * Sprint 18 — the learning seam between the orchestration loop and the (future,
 * Sprint 21) knowledge store, kept to a two-method interface so Sprint 18 ships
 * <b>stateless but store-aware</b>. The loop CONSULTS on the way in
 * ({@link #adviseBefore}) and EMITS on the way out ({@link #record}). The default
 * {@link NoOpAdvisor} makes both no-ops; Sprint 21 supplies the store-backed
 * implementation that FILLS the seam. See {@link Outcome} for the emitted value.
 */
public interface Advisor {

    /**
     * Consulted before planning/applying a refactoring of {@code kind} on
     * {@code target}. Returns advisory notes (known hazards, prior failed
     * attempts, invariants) to weigh into the plan, or an empty list when nothing
     * is known. Advisory only — never a hard gate ("mandatory retrieval, not
     * mandatory obedience").
     */
    List<String> adviseBefore(String kind, String target);

    /** Emitted at cycle completion — the store's write path (no-op until Sprint 21). */
    void record(Outcome outcome);
}
