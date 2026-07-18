package org.jawata.mcp.learn;

import java.util.ArrayList;
import java.util.List;

/**
 * Hand-rolled decision tree (Sprint 26, the recorded model-class ruling):
 * REBUILT every N events from the retained sample — seconds of CPU, never a
 * training service. Depth-bounded, split on the feature/threshold minimizing
 * misclassification. The interpretable comparator beside the online logreg.
 */
public final class HandTree {

    public record Sample(double[] x, int label) {
    }

    private static final int MAX_DEPTH = 3;
    private static final int MIN_SPLIT = 8;

    private Node root;
    private int trainedOn;

    public void rebuild(List<Sample> samples) {
        root = build(samples, 0);
        trainedOn = samples.size();
    }

    public int trainedOn() {
        return trainedOn;
    }

    /** Majority-probability of the positive class at the sample's leaf. */
    public double predict(double[] x) {
        Node n = root;
        if (n == null) {
            return 0.5;
        }
        while (n.feature >= 0) {
            n = x[n.feature] <= n.threshold ? n.left : n.right;
        }
        return n.positiveShare;
    }

    private static final class Node {
        int feature = -1;
        double threshold;
        Node left;
        Node right;
        double positiveShare = 0.5;
    }

    private Node build(List<Sample> samples, int depth) {
        Node node = new Node();
        if (samples.isEmpty()) {
            return node;
        }
        long positives = samples.stream().filter(s -> s.label() == 1).count();
        node.positiveShare = (double) positives / samples.size();
        if (depth >= MAX_DEPTH || samples.size() < MIN_SPLIT
                || positives == 0 || positives == samples.size()) {
            return node;
        }
        int bestFeature = -1;
        double bestThreshold = 0;
        int bestError = Integer.MAX_VALUE;
        int featureCount = samples.get(0).x().length;
        for (int f = 0; f < featureCount; f++) {
            for (Sample s : samples) {
                double t = s.x()[f];
                int error = splitError(samples, f, t);
                if (error < bestError) {
                    bestError = error;
                    bestFeature = f;
                    bestThreshold = t;
                }
            }
        }
        if (bestFeature < 0) {
            return node;
        }
        List<Sample> left = new ArrayList<>();
        List<Sample> right = new ArrayList<>();
        for (Sample s : samples) {
            (s.x()[bestFeature] <= bestThreshold ? left : right).add(s);
        }
        if (left.isEmpty() || right.isEmpty()) {
            return node;
        }
        node.feature = bestFeature;
        node.threshold = bestThreshold;
        node.left = build(left, depth + 1);
        node.right = build(right, depth + 1);
        return node;
    }

    private static int splitError(List<Sample> samples, int f, double t) {
        int lPos = 0;
        int lNeg = 0;
        int rPos = 0;
        int rNeg = 0;
        for (Sample s : samples) {
            boolean left = s.x()[f] <= t;
            if (s.label() == 1) {
                if (left) {
                    lPos++;
                } else {
                    rPos++;
                }
            } else {
                if (left) {
                    lNeg++;
                } else {
                    rNeg++;
                }
            }
        }
        return Math.min(lPos, lNeg) + Math.min(rPos, rNeg);
    }
}
