package org.jawata.mcp.execution;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * First names for the hires of one conversation (Sprint 28h, Stage 24, D55): two project managers read as "Claudia" and "James" instead of
 * "pm" and "pm", so a person can tell them apart and say which one to stop.
 *
 * <p>A conversation is named by a key, the id of the caller that issues the hires. The names of one key come in a fixed order that depends only
 * on the key, so the same conversation replayed shows the same names, and no name repeats inside a conversation until the list is used up;
 * after that a name carries a number ("Claudia 2"), so it stays unique.</p>
 */
public final class HireNames {

    /** The names, shipped with the engine. Their count and the step below share no factor, so every name is reached before any repeats. */
    private static final List<String> LIST = List.of(
        "Claudia", "James", "Marta", "Joe", "Ines", "Tomas", "Greta", "Pavel", "Lena", "Omar",
        "Sofia", "Henrik", "Amara", "Luca", "Nadia", "Felix", "Yara", "Viktor", "Elena", "Ravi",
        "Hanna", "Diego", "Mira", "Anton", "Leila", "Bruno", "Petra", "Karim", "Alma", "Stefan",
        "Noor", "Emil", "Zoe", "Marek", "Ida", "Jonas", "Selma", "Rafael", "Vera", "Oskar");

    private static final int STEP = 7;

    private static final HireNames SHARED = new HireNames();

    private final Map<String, Integer> given = new HashMap<>();

    /** The one set of names of this engine, which the hire registry draws from. */
    public static HireNames shared() {
        return SHARED;
    }

    /** The next name of this conversation: never one already given to it, and the same sequence every time for the same key. */
    public synchronized String assign(String conversationKey) {
        String key = conversationKey == null ? "" : conversationKey;
        int count = given.merge(key, 1, Integer::sum) - 1;
        int start = Math.floorMod(key.hashCode(), LIST.size());
        String name = LIST.get((start + count * STEP) % LIST.size());
        int round = count / LIST.size();
        return round == 0 ? name : name + " " + (round + 1);
    }
}
