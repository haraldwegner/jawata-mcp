package org.jawata.mcp.learn;

import org.jawata.mcp.knowledge.LearnerEventStore;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One learner (Sprint 26): online logreg updated PER EVENT, the hand tree
 * rebuilt every {@link #REBUILD_EVERY} events, a rolling record gating
 * DECIDES vs SHADOW (with the demotion guard), and a hard serve-time budget
 * — over budget, the RULE answers and the model is advisory only. State
 * persists via the learner store.
 */
public final class Learner {

    static final int REBUILD_EVERY = 25;
    static final int RETAINED_SAMPLES = 1000;
    static final long SERVE_BUDGET_MS = 50;

    /** What a serve answered and who decided. */
    public record Advice(boolean positive, boolean modelDecided, double modelProbability,
                         String note) {
    }

    private final String name;
    private final LearnerEventStore store;
    private final OnlineLogreg logreg;
    private final HandTree tree = new HandTree();
    private final RollingRecord rolling;
    private final List<HandTree.Sample> retained = new ArrayList<>();
    private long eventsSeen;

    public Learner(String name, int features, LearnerEventStore store) {
        this.name = name;
        this.store = store;
        String state = store != null ? store.loadState("learner:" + name).orElse(null) : null;
        if (state != null && state.contains("§")) {
            String[] parts = state.split("§", 3);
            this.logreg = OnlineLogreg.deserialize(parts[0], features, 0.1);
            this.rolling = RollingRecord.deserialize(parts[1]);
            this.eventsSeen = Long.parseLong(parts[2]);
        } else {
            this.logreg = new OnlineLogreg(features, 0.1);
            this.rolling = new RollingRecord();
        }
    }

    public String name() {
        return name;
    }

    public long eventsSeen() {
        return eventsSeen;
    }

    public boolean decides() {
        return rolling.modelDecides();
    }

    /**
     * One labeled event: the model predicts FIRST (honest shadow record vs
     * the rule on the same event), then learns from it. Persists state.
     */
    public synchronized void observe(double[] x, boolean actualPositive, boolean ruleSaysPositive) {
        boolean modelSays = logreg.predict(x) >= 0.5;
        rolling.add(new RollingRecord.Entry(ruleSaysPositive, modelSays, actualPositive));
        logreg.update(x, actualPositive ? 1 : 0);
        retained.add(new HandTree.Sample(x.clone(), actualPositive ? 1 : 0));
        while (retained.size() > RETAINED_SAMPLES) {
            retained.remove(0);
        }
        eventsSeen++;
        if (eventsSeen % REBUILD_EVERY == 0) {
            tree.rebuild(retained);
        }
        persist();
    }

    /**
     * Serve within the budget: the model's verdict counts only while its
     * rolling record earns DECIDES; otherwise (shadow, or over budget) the
     * rule's answer stands and the model is a note.
     */
    public synchronized Advice serve(double[] x, boolean ruleSaysPositive) {
        long start = System.currentTimeMillis();
        double p = logreg.predict(x);
        boolean overBudget = System.currentTimeMillis() - start > SERVE_BUDGET_MS;
        boolean decides = !overBudget && rolling.modelDecides();
        boolean answer = decides ? p >= 0.5 : ruleSaysPositive;
        String note = decides ? "model decides (" + rolling.describe() + ")"
            : overBudget ? "over budget — rule answered; model advisory p=" + round(p)
            : "shadow (" + rolling.describe() + ") — rule answered; model p=" + round(p);
        return new Advice(answer, decides, p, note);
    }

    /** The /train status line for this learner. */
    public synchronized Map<String, Object> status() {
        Map<String, Object> s = new LinkedHashMap<>();
        s.put("learner", name);
        s.put("eventsSeen", eventsSeen);
        s.put("state", rolling.modelDecides() ? "DECIDES" : "SHADOW");
        s.put("rollingRecord", rolling.describe());
        s.put("logregUpdates", logreg.updates());
        s.put("treeTrainedOn", tree.trainedOn());
        return s;
    }

    /** A forced pass (the /train command): rebuild the tree now. */
    public synchronized void forcePass() {
        tree.rebuild(retained);
        persist();
    }

    private void persist() {
        if (store != null) {
            store.saveState("learner:" + name,
                logreg.serialize() + "§" + rolling.serialize() + "§" + eventsSeen);
        }
    }

    private static double round(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
