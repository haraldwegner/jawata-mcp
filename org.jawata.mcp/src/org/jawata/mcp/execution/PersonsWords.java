package org.jawata.mcp.execution;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * What the person last typed, kept by the key the principal brain presents on its tool calls (Sprint 28h, D57 (j)). A step that is the person's
 * own decision, a release above all, is taken only when his own message asks for it: the release tool reads here whether the message that
 * started the principal's turn says "release", and an agent's words, a hire's report or a seat's text never reach this record, because only the
 * agent face's prompt from the host writes it.
 */
public final class PersonsWords {

    private static final Map<String, String> LAST = new ConcurrentHashMap<>();

    /** The word itself, as a whole word in any case: "release", "Release it", "RELEASE." but not "released" or "prerelease". */
    private static final Pattern RELEASE = Pattern.compile("(?<![\\p{L}\\p{N}])release(?![\\p{L}\\p{N}])");

    private PersonsWords() {
    }

    /** Records the message the person typed to the conversation whose principal brain presents this key. */
    public static void said(String brainKey, String text) {
        if (brainKey != null && !brainKey.isBlank() && text != null) {
            LAST.put(brainKey, text);
        }
    }

    /** The last message the person typed to the principal presenting this key; none when nothing was recorded for it. */
    public static Optional<String> lastSaid(String brainKey) {
        return brainKey == null ? Optional.empty() : Optional.ofNullable(LAST.get(brainKey));
    }

    /** Whether the person's last message to the principal presenting this key gives the release word. */
    public static boolean gaveTheReleaseWord(String brainKey) {
        return lastSaid(brainKey).map(text -> RELEASE.matcher(text.toLowerCase(Locale.ROOT)).find()).orElse(false);
    }
}
