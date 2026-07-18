package org.jawata.mcp.learn;

import org.jawata.mcp.knowledge.LearnerEventStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The seven learners (Sprint 26) behind one service: edit switch, seat
 * switch, policy dial, architect pre-filter, noise budget, watch-firing and
 * message-gating learners. Serving seams: the edit switch backs the
 * steering/guard decision the hand rules own today; the seat switch emits a
 * seat suggestion on ambiguous asks — each only while its rolling record
 * earns DECIDES, shadow otherwise. Exposed to clients as
 * {@code experience(kind=train|learner_status)} — no new tool.
 */
public final class LearnerService {

    public static final String EDIT_SWITCH = "edit-switch";
    public static final String SEAT_SWITCH = "seat-switch";
    public static final String POLICY_DIAL = "policy-dial";
    public static final String PRE_FILTER = "architect-pre-filter";
    public static final String NOISE_BUDGET = "architect-noise-budget";
    public static final String FIRING = "watch-firing";
    public static final String GATING = "message-gating";

    private static final List<String> ALL = List.of(EDIT_SWITCH, SEAT_SWITCH,
        POLICY_DIAL, PRE_FILTER, NOISE_BUDGET, FIRING, GATING);

    private final Map<String, Learner> learners = new LinkedHashMap<>();
    private final LearnerEventStore events;

    public LearnerService(LearnerEventStore events) {
        this.events = events;
        for (String name : ALL) {
            learners.put(name, new Learner(name, FeatureVector.NAMES.size(), events));
        }
    }

    public Learner learner(String name) {
        return learners.get(name);
    }

    /** The edit switch's serving seam: structural (true) routes to the gated
     * refactoring path. The rule's verdict always accompanies the ask. */
    public Learner.Advice adviseEdit(String before, String after, boolean ruleSaysStructural) {
        double[] x = FeatureVector.extract(before, after);
        return learners.get(EDIT_SWITCH).serve(x, ruleSaysStructural);
    }

    /** An observed, labeled edit — the continuous learning feed. */
    public void observeEdit(String before, String after, boolean actuallyStructural,
            boolean ruleSaidStructural) {
        double[] x = FeatureVector.extract(before, after);
        learners.get(EDIT_SWITCH).observe(x, actuallyStructural, ruleSaidStructural);
    }

    /** The /train command core: a forced pass over every learner + the report. */
    public Map<String, Object> train() {
        for (Learner l : learners.values()) {
            l.forcePass();
        }
        return status();
    }

    /** The learner_status report — headline numbers first, honest counters. */
    public Map<String, Object> status() {
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("totalEvents", events != null ? events.totalEvents() : 0);
        report.put("eventsByKind", events != null ? events.countByKind() : Map.of());
        report.put("failedWrites", events != null ? events.failedWrites() : 0);
        List<Map<String, Object>> perLearner = new ArrayList<>();
        for (Learner l : learners.values()) {
            perLearner.add(l.status());
        }
        report.put("learners", perLearner);
        return report;
    }
}
