package com.example;

/**
 * Sprint 28g D10 — the TARGET. It already has the methods the wrapper forwards to it, which is
 * what makes deleting the wrapper equivalent rather than lossy, and it does NOT declare the
 * interface — that is the edit.
 *
 * <p>Named after the census's own site: <em>"manager implements SLCallback itself so the
 * strategy's forwarding anonymous class can go"</em>.</p>
 */
public class SlotManager {

    private final StringBuilder log = new StringBuilder();

    public void onFill(String order) {
        log.append("fill:").append(order).append(' ');
    }

    public void onCancel(String order) {
        log.append("cancel:").append(order).append(' ');
    }

    /** Not forwarded by any wrapper; here so the target is not ONLY the interface's methods. */
    public String log() {
        return log.toString();
    }
}
