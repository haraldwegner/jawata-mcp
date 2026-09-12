package com.example;

/**
 * CONTROL ONE — the supertype that DICTATES the shape its two implementations share.
 *
 * <p>{@link FlatPricing} and {@link TieredPricing} have the same shape, the same two
 * collaborators and different bodies, and neither calls the other. The ONLY thing that
 * stops them being reported is that this interface declares the signature: it was not
 * derived twice, it was dictated once and implemented twice, which is what an interface is
 * for. That is the condition the detector calls IMPOSED.</p>
 */
public interface Pricing {

    Money quote(Order order);
}
