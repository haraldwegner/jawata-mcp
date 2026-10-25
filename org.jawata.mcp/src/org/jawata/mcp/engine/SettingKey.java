package org.jawata.mcp.engine;

import java.util.Optional;

/**
 * One setting the engine reads (Sprint 28k, E17): its name, what it is when nothing sets it, and what it does. The list of all of them
 * is {@link SettingKeys#all()}, which is what a host's settings screen is built from and checked against.
 *
 * @param name     the dotted key, such as {@code jawata.backups.depth}
 * @param fallback what applies when nothing sets it, in words, since several defaults are not a single value
 * @param meaning  what changing it does, in a sentence a person choosing the value can act on
 */
public record SettingKey(String name, String fallback, String meaning) {

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
