package org.jawata.mcp.learn;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * The rolling promotion gate (Sprint 26): a windowed confusion record of the
 * MODEL vs the HAND RULE on the same labeled events. The model DECIDES only
 * while it beats the rule on BOTH error kinds over the window (with minimum
 * volume); decay demotes it back to shadow — the guard that ships in 26.
 */
public final class RollingRecord {

    public record Entry(boolean ruleSaysPositive, boolean modelSaysPositive, boolean actualPositive) {
    }

    static final int WINDOW = 200;
    static final int MIN_VOLUME = 30;

    private final Deque<Entry> window = new ArrayDeque<>();

    public synchronized void add(Entry e) {
        window.addLast(e);
        while (window.size() > WINDOW) {
            window.removeFirst();
        }
    }

    public synchronized int volume() {
        return window.size();
    }

    /** True while the model beats the rule on BOTH error kinds at volume. */
    public synchronized boolean modelDecides() {
        if (window.size() < MIN_VOLUME) {
            return false;
        }
        int ruleFp = 0;
        int ruleFn = 0;
        int modelFp = 0;
        int modelFn = 0;
        for (Entry e : window) {
            if (e.ruleSaysPositive() && !e.actualPositive()) {
                ruleFp++;
            }
            if (!e.ruleSaysPositive() && e.actualPositive()) {
                ruleFn++;
            }
            if (e.modelSaysPositive() && !e.actualPositive()) {
                modelFp++;
            }
            if (!e.modelSaysPositive() && e.actualPositive()) {
                modelFn++;
            }
        }
        // BOTH error kinds: never worse on either, strictly better on at
        // least one — a tie is not a promotion.
        return modelFp <= ruleFp && modelFn <= ruleFn
            && (modelFp < ruleFp || modelFn < ruleFn);
    }

    /** "fp=…/fn=… (model) vs fp=…/fn=… (rule) over N" — the honest numbers. */
    public synchronized String describe() {
        int ruleFp = 0;
        int ruleFn = 0;
        int modelFp = 0;
        int modelFn = 0;
        for (Entry e : window) {
            if (e.ruleSaysPositive() && !e.actualPositive()) {
                ruleFp++;
            }
            if (!e.ruleSaysPositive() && e.actualPositive()) {
                ruleFn++;
            }
            if (e.modelSaysPositive() && !e.actualPositive()) {
                modelFp++;
            }
            if (!e.modelSaysPositive() && e.actualPositive()) {
                modelFn++;
            }
        }
        return "model fp=" + modelFp + "/fn=" + modelFn
            + " vs rule fp=" + ruleFp + "/fn=" + ruleFn
            + " over " + window.size();
    }

    /** Serialize the window compactly: one char-triple per entry. */
    public synchronized String serialize() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : window) {
            sb.append(e.ruleSaysPositive() ? '1' : '0')
              .append(e.modelSaysPositive() ? '1' : '0')
              .append(e.actualPositive() ? '1' : '0');
        }
        return sb.toString();
    }

    public static RollingRecord deserialize(String s) {
        RollingRecord r = new RollingRecord();
        if (s != null) {
            for (int i = 0; i + 2 < s.length() + 1 && i + 3 <= s.length(); i += 3) {
                r.add(new Entry(s.charAt(i) == '1', s.charAt(i + 1) == '1',
                    s.charAt(i + 2) == '1'));
            }
        }
        return r;
    }
}
