package com.example;

/**
 * A caller of a setter from outside the declaring class's constructors.
 *
 * <p>It exists in its own file because the refusal it produces is about a call the declaring
 * class cannot see, and a caller nested inside the target would not be that.</p>
 */
public class SettingMethodDesk {

    public void rebook(SettingMethodTargets.Booking booking, String reference) {
        booking.setReference(reference);
    }
}
