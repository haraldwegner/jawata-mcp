package org.jawata.spi.host;

/**
 * Something the engine tells the person who is using it (Sprint 28k, contract §4.2): what went wrong or what changed, in one
 * sentence, without a protocol. A host shows it the way its own surface shows things (the headless host writes it to its log, Eclipse
 * to the status bar); the engine neither knows nor cares where it appears.
 *
 * @param level how much the person should care
 * @param text  what to tell them, one sentence a person can act on
 */
public record Notice(Level level, String text) {

    /** How much a notice matters. */
    public enum Level {
        /** Something happened that needs no action. */
        INFO,
        /** Something is degraded, and the engine carries on. */
        WARNING,
        /** Something failed, and the person's next step is theirs. */
        ERROR
    }

    /**
     * @param text what to tell the person
     * @return a warning
     */
    public static Notice warning(String text) {
        return new Notice(Level.WARNING, text);
    }

    /**
     * @param text what to tell the person
     * @return an error
     */
    public static Notice error(String text) {
        return new Notice(Level.ERROR, text);
    }
}
