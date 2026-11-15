package org.jawata.mcp.engine;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * What a host's settings screen may ask the engine about seats (Sprint 28k, Amendment 3, D15). It lists the seats with their on or off, brain
 * and place in the workflow as the engine would use them, and it checks a change the person is about to make. It writes nothing, seat files
 * least of all: a host stores the three settings named in {@link SettingKeys} itself. It is not a published tool, so no agent can reach it.
 */
public interface SeatSettings {

    /**
     * One seat as the engine would use it now.
     *
     * @param name        the seat's name
     * @param command     the word a person types for it, without the slash, when it has one
     * @param purpose     what the seat is for, in the seat file's own words; empty when the file declares none
     * @param on          whether the seat can be offered and hired
     * @param brain       the brain a hire of it starts
     * @param model       the model asked of that brain, when one is named
     * @param parent      the seat that hires it, or none for main
     * @param role        lead, worker or reviewer
     * @param escalatesTo where a task it could not do goes next, when anywhere
     * @param when        when the seat is meant to be used, in the seat file's own words; empty when the file declares none
     */
    record Seat(String name, Optional<String> command, String purpose, boolean on, String brain, Optional<String> model, Optional<String> parent,
        String role, Optional<String> escalatesTo, String when) {
    }

    /**
     * The seats and what could not be used.
     *
     * @param seats  every seat that could be read, sorted by name
     * @param errors one line for each seat file, stored setting or workflow the engine could not use, each naming what it refused
     */
    record View(List<Seat> seats, List<String> errors) {

        /** Both lists are copied, so a view is not changed by whoever built it. */
        public View {
            seats = List.copyOf(seats);
            errors = List.copyOf(errors);
        }
    }

    /** The seats as the engine would use them with the settings stored now. */
    View seats();

    /**
     * Whether the engine would accept these values.
     *
     * @param proposed the values of {@link SettingKeys#SEATS_OFF}, {@link SettingKeys#SEATS_BRAINS} and {@link SettingKeys#SEATS_WORKFLOW} by key;
     *                 a key left out keeps its stored value
     * @return the engine's refusal in words, or nothing when the values can be stored
     */
    Optional<String> check(Map<String, String> proposed);
}
