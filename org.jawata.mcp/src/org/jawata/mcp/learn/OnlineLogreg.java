package org.jawata.mcp.learn;

import java.util.Arrays;

/**
 * Hand-rolled online logistic regression (Sprint 26, the recorded model-class
 * ruling): one SGD step PER EVENT — the spam-filter rhythm. No dependencies,
 * serializable as a plain string, deterministic.
 */
public final class OnlineLogreg {

    private final double[] weights; // [bias, w0..wn]
    private final double learningRate;
    private long updates;

    public OnlineLogreg(int features, double learningRate) {
        this.weights = new double[features + 1];
        this.learningRate = learningRate;
    }

    /** One SGD step on one labeled event ({@code label} 1 = positive class). */
    public void update(double[] x, int label) {
        double p = predict(x);
        double err = label - p;
        weights[0] += learningRate * err;
        for (int i = 0; i < x.length && i + 1 < weights.length; i++) {
            weights[i + 1] += learningRate * err * x[i];
        }
        updates++;
    }

    /** P(label=1 | x). */
    public double predict(double[] x) {
        double z = weights[0];
        for (int i = 0; i < x.length && i + 1 < weights.length; i++) {
            z += weights[i + 1] * x[i];
        }
        return 1.0 / (1.0 + Math.exp(-z));
    }

    public long updates() {
        return updates;
    }

    /** State round-trip: "updates|w0,w1,...". */
    public String serialize() {
        StringBuilder sb = new StringBuilder().append(updates).append('|');
        for (int i = 0; i < weights.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(weights[i]);
        }
        return sb.toString();
    }

    public static OnlineLogreg deserialize(String state, int features, double learningRate) {
        OnlineLogreg m = new OnlineLogreg(features, learningRate);
        try {
            String[] parts = state.split("\\|", 2);
            m.updatesSet(Long.parseLong(parts[0]));
            String[] ws = parts[1].split(",");
            for (int i = 0; i < ws.length && i < m.weights.length; i++) {
                m.weights[i] = Double.parseDouble(ws[i]);
            }
        } catch (Exception e) {
            // A corrupt state resets to fresh — cold start, never a crash.
            Arrays.fill(m.weights, 0);
            m.updatesSet(0);
        }
        return m;
    }

    private void updatesSet(long n) {
        this.updates = n;
    }
}
