package org.goja.mcp.refactoring;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Sprint 18 — session-scoped store of in-flight refactoring {@link Plan}s, mirroring
 * {@link RefactoringChangeCache}: bounded (LRU, cap {@value #CAPACITY}), TTL-expired
 * lazily on access ({@value #TTL_MILLIS} ms). A plan created by
 * {@code refactoring(action=plan)} is retrieved by {@code apply_plan} /
 * {@code inspect_plan} / {@code undo_plan} via its {@code planId}. In-memory only —
 * no persistence (that is the Sprint-21 knowledge store, a separate concern).
 */
public final class PlanStore {

    private static final long TTL_MILLIS = 3_600_000L; // 1h
    private static final int CAPACITY = 50;

    private final LinkedHashMap<String, Plan> plans = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Plan> eldest) {
            return size() > CAPACITY;
        }
    };

    public synchronized Plan create(String kind, String target, List<PlanStep> steps, List<String> advice) {
        evictExpired();
        String id = "plan-" + UUID.randomUUID();
        Plan plan = new Plan(id, kind, target, steps, advice, System.currentTimeMillis());
        plans.put(id, plan);
        return plan;
    }

    public synchronized Optional<Plan> get(String planId) {
        evictExpired();
        return Optional.ofNullable(plans.get(planId));
    }

    public synchronized int size() {
        evictExpired();
        return plans.size();
    }

    private void evictExpired() {
        long now = System.currentTimeMillis();
        plans.entrySet().removeIf(e -> now - e.getValue().insertedAtMillis() > TTL_MILLIS);
    }
}
