package com.example.port;

/**
 * Sprint 28g D10 — the interface an anonymous class wraps, named after the census's own case:
 * <em>"manager implements SLCallback itself so the strategy's forwarding anonymous class can
 * go"</em>.
 *
 * <p>Two methods rather than one, because a single-method wrapper is indistinguishable from a
 * lambda and the census's sites are multi-method callbacks — <em>"two overrides of the anonymous
 * SlotCallback"</em>, <em>"anonymous SlotCallback shrunk to two methods"</em>.</p>
 *
 * <p><strong>In a sub-package deliberately.</strong> With the interface beside the target, the
 * promotion needs no import and {@code ImportRewrite} does nothing — so the claim that the
 * operation adds the import it needs would have been exercised by nothing, which is the shape
 * this sprint has already caught twice (an API verified to EXIST and reported as working). From
 * here, promoting {@code com.example.SlotManager} must add
 * {@code import com.example.port.SlotListener;} or the file does not compile.</p>
 */
public interface SlotListener {

    void onFill(String order);

    void onCancel(String order);
}
