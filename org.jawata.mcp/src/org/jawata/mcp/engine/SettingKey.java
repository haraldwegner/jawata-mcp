package org.jawata.mcp.engine;

import java.util.Optional;

/**
 * One setting the engine reads (Sprint 28k, E17): its name, what it is when nothing sets it, and what it does. The list of all of them
 * is {@link SettingKeys#all()}, which is what a host's settings screen is built from and checked against.
 *
 * @param name        the dotted key, such as {@code jawata.backups.depth}
 * @param fallback    what applies when nothing sets it, in words, since several defaults are not a single value
 * @param meaning     what changing it does, in a sentence a person choosing the value can act on, the same whichever host the engine runs in;
 *                    the sentence about the environment variable is not in it (see {@link #meaningFor(boolean)}). A meaning that is one
 *                    clause carries no full stop, so that the sentence about the variable can follow it after a semicolon
 * @param environment the environment variable the engine that runs alone also reads when nothing sets the key, or null when it reads none
 * @param audience    who the setting is for, which decides whether a host's settings screen shows it to the person
 */
public record SettingKey(String name, String fallback, String meaning, String environment, Audience audience) {

    /** Who a setting is for. A host's settings screen shows the person the settings meant for the person and no others. */
    public enum Audience {
        /** The person chooses it: it belongs on the settings screen. */
        PERSON,
        /** The program that starts the engine sets it, and the person has nothing to choose. */
        LAUNCHER,
        /** A measurement arm of the engine's own development, which the person is never asked about. */
        EXPERIMENT
    }

    /** A key says who it is for: a key nobody classified would be shown to the person by a screen that cannot tell. */
    public SettingKey {
        if (audience == null) {
            throw new IllegalArgumentException("The setting '" + name + "' names no audience: say whether it is for the person, "
                + "the launcher or an experiment, so that a settings screen knows whether to show it");
        }
    }

    /**
     * The sentences that say what this setting does, put together for one kind of host. The engine that runs alone reads an environment
     * variable for some keys when nothing sets them, and says so; a host that has no such variable (an IDE) does not.
     *
     * @param readsEnvironment whether the host's settings come from the engine's own system properties and environment
     * @return the meaning, with the sentence about the environment variable when the host reads it and the key has one
     */
    public String meaningFor(boolean readsEnvironment) {
        boolean sentence = meaning.endsWith(".");
        if (!readsEnvironment || environment == null) {
            return sentence ? meaning : meaning + ".";
        }
        return meaning + (sentence ? " The" : "; the") + " environment variable " + environment + " is read when this is not set.";
    }

    /**
     * What the host says this setting is right now, or empty when it says nothing or only blanks. A reader asks the key, so a key that
     * is read is a key that is listed, and the form it is spelled in exists once.
     *
     * @return the host's value, trimmed
     */
    public Optional<String> value() {
        return EngineEnvironment.settings().get(name).map(String::trim).filter(v -> !v.isEmpty());
    }

    /**
     * The setting as a whole number, the way {@code Integer.getInteger} reads a system property: decimal, hex or octal, and anything
     * that is not a number counts as not set.
     *
     * @param ifUnset what applies when nothing sets it
     * @return the host's value, or {@code ifUnset}
     */
    public int intValue(int ifUnset) {
        return value().map(v -> {
            try {
                return Integer.decode(v);
            } catch (NumberFormatException notANumber) {
                return ifUnset;
            }
        }).orElse(ifUnset);
    }

    /**
     * The setting as a long, read like {@link #intValue(int)}.
     *
     * @param ifUnset what applies when nothing sets it
     * @return the host's value, or {@code ifUnset}
     */
    public long longValue(long ifUnset) {
        return value().map(v -> {
            try {
                return Long.decode(v);
            } catch (NumberFormatException notANumber) {
                return ifUnset;
            }
        }).orElse(ifUnset);
    }

    /**
     * Whether the setting is switched on, the way {@code Boolean.getBoolean} reads a system property: only {@code true}, in any case,
     * is on.
     *
     * @return true when the host says {@code true}
     */
    public boolean flag() {
        return value().map("true"::equalsIgnoreCase).orElse(false);
    }
}
