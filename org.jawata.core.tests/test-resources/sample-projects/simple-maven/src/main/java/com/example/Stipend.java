package com.example;

/**
 * Row 59 REFUSES this — the type code is not read through an accessor.
 *
 * <p>Its constants are a real group and its field holds one of them, but nothing returns the
 * code, so a generated subclass would have nothing to override and the whole change would be
 * inert. That is Fowler's own first step missing, and row 59 names it rather than performing it
 * silently as a hidden half.</p>
 */
public class Stipend {

    public static final int BAND_JUNIOR = 0;
    public static final int BAND_SENIOR = 1;

    private final int band;

    public Stipend(int band) {
        this.band = band;
    }

    /** Reads the code but does not RETURN it, so it is not the accessor a subclass overrides. */
    public String describe() {
        return band == BAND_JUNIOR ? "junior" : "senior";
    }
}
