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
    default View seats() {
        return seats(Map.of());
    }

    /**
     * The seats as the engine would use them with these values in place of the stored ones: what a settings screen shows while a change is
     * held for its Apply.
     *
     * @param proposed the values of {@link SettingKeys#SEATS_OFF}, {@link SettingKeys#SEATS_BRAINS} and {@link SettingKeys#SEATS_WORKFLOW} by key;
     *                 a key left out keeps its stored value
     * @return the seats and what could not be used
     */
    View seats(Map<String, String> proposed);

    /**
     * One agent program as this machine has it now.
     *
     * @param id                      the brain's name, as a seat names it
     * @param label                   the name a person reads
     * @param installed               whether the program answered
     * @param login                   {@code loggedIn}, {@code notLoggedIn} or {@code unknown}
     * @param reason                  its own words when it is not logged in or not installed, or why the check could not answer
     * @param loginCommand            what to type in a terminal to log in
     * @param models                  the models it names for itself; absent where it names none
     * @param hirable                 whether a hire can start it
     * @param takesModel              whether a hire passes it a model
     * @param writesHeldByInstruction whether its writes are held only by an instruction to the model
     */
    record Brain(String id, String label, boolean installed, String login, Optional<String> reason, String loginCommand, Optional<List<String>> models,
        boolean hirable, boolean takesModel, boolean writesHeldByInstruction) {
    }

    /**
     * What one test run through a brain showed.
     *
     * @param brain        the brain
     * @param ok           whether it answered
     * @param answer       what it answered
     * @param modelsUsed   the models the run reported using, most expensive first
     * @param costUsd      the cost it reported, where it reports one
     * @param seconds      how long the run took
     * @param error        its own words when the run failed
     * @param loginCommand what to type in a terminal to log in
     */
    record Probe(String brain, boolean ok, Optional<String> answer, List<String> modelsUsed, Optional<Double> costUsd, double seconds, Optional<String> error,
        String loginCommand) {
    }

    /**
     * Every agent program a seat could be given, asked of the programs on this machine now. Starts a short program per brain, so a screen asks
     * it off its interface thread.
     *
     * @return the brains
     */
    List<Brain> brains();

    /**
     * A one-word run through a brain, so a screen keeps a choice only if it answers. It costs a real run (Claude Code: under a cent) and can
     * take up to two minutes; a brain or model the engine does not know is refused with an {@link IllegalArgumentException} saying which.
     *
     * @param brain the brain's name
     * @param model the model to ask for, or null for the brain's own default
     * @return what the run showed
     */
    Probe probe(String brain, String model);

    /**
     * Whether the engine would accept these values.
     *
     * @param proposed the values of {@link SettingKeys#SEATS_OFF}, {@link SettingKeys#SEATS_BRAINS} and {@link SettingKeys#SEATS_WORKFLOW} by key;
     *                 a key left out keeps its stored value
     * @return the engine's refusal in words, or nothing when the values can be stored
     */
    Optional<String> check(Map<String, String> proposed);
}
