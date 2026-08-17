package org.jawata.mcp.field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * The field lane's small mutable state (Sprint 28b, D3/D4/D9): which error
 * shapes have been posted, whether the in-session nudge is switched off, and
 * (D9) whether the reminders are silenced plus their bookkeeping.
 *
 * <p><b>One home, three readers.</b> The file sits beside the pile in
 * {@code <workspace>/field/}: the resident writes it through {@code FieldTool},
 * studio writes it from the seat-lane tile, and the hook binary READS it to
 * decide whether to nudge. Writes are atomic (temp file + {@code ATOMIC_MOVE})
 * because two writers exist and a half-written state would silence a channel
 * for reasons nobody could reconstruct.</p>
 *
 * <p><b>The two switches are DISTINCT</b> (the plan's C1 amendment):
 * {@code nudges} governs the in-session line (D4), {@code silenced} governs
 * the periodic reminders (D9). Conflating them would let one user gesture turn
 * off a channel they never asked about.</p>
 */
public final class FieldState {

    private static final Logger log = LoggerFactory.getLogger(FieldState.class);

    /** The in-session nudge is on unless the user switched it off. */
    private boolean nudges = true;
    /** The periodic reminders are on unless the user answered "go silent". */
    private boolean silenced = false;
    /** Shapes already reported — they never nudge or remind again. */
    private final Set<String> posted = new LinkedHashSet<>();
    /** D9 bookkeeping: when the last reminder was shown, and how many have
     *  gone unanswered since the last {@code /report} use. */
    private long remindedAtMillis = 0;
    private int strikes = 0;

    private FieldState() {
    }

    public boolean nudges() {
        return nudges;
    }

    public boolean silenced() {
        return silenced;
    }

    public Set<String> posted() {
        return Set.copyOf(posted);
    }

    public long remindedAtMillis() {
        return remindedAtMillis;
    }

    public int strikes() {
        return strikes;
    }

    public FieldState withNudges(boolean on) {
        this.nudges = on;
        return this;
    }

    public FieldState withSilenced(boolean on) {
        this.silenced = on;
        return this;
    }

    /** Marks a shape reported: it stops nudging and stops counting as news,
     *  and a {@code /report} use resets the reminder strikes (D9). */
    public FieldState withPosted(String shapeKey) {
        if (shapeKey != null && !shapeKey.isBlank()) {
            posted.add(shapeKey);
        }
        this.strikes = 0;
        return this;
    }

    /** The state at {@code dir}; defaults (nudges on, not silenced) when the
     *  file is absent or unreadable — a missing state must never silence. */
    public static FieldState read(Path dir) {
        FieldState state = new FieldState();
        Path file = file(dir);
        if (!Files.exists(file)) {
            return state;
        }
        try {
            String content = Files.readString(file);
            state.nudges = !content.contains("\"nudges\":false");
            state.silenced = content.contains("\"silenced\":true");
            state.remindedAtMillis = longField(content, "\"remindedAt\":");
            state.strikes = (int) longField(content, "\"strikes\":");
            int from = content.indexOf("\"posted\":[");
            if (from >= 0) {
                int to = content.indexOf(']', from);
                for (String raw : content.substring(from + 10, to).split(",")) {
                    String shape = raw.replace("\"", "").trim();
                    if (!shape.isEmpty()) {
                        state.posted.add(shape);
                    }
                }
            }
        } catch (IOException | RuntimeException e) {
            log.error("Field state unreadable at {} — defaults apply (nudges on)", file, e);
        }
        return state;
    }

    /** Writes atomically: temp file, then a move. Returns false and says so on
     *  failure — a silently-lost switch is a setting the user thinks they set. */
    public boolean write(Path dir) {
        Path file = file(dir);
        try {
            Files.createDirectories(dir);
            Path temp = Files.createTempFile(dir, "state", ".tmp");
            Files.writeString(temp, toJson());
            try {
                Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException e) {
            log.error("FIELD STATE WRITE FAILED at {} — the switch the user set is NOT saved",
                file, e);
            return false;
        }
    }

    /** D9: records that a reminder was shown now, counting one unanswered
     *  strike (reset by {@link #withPosted}). */
    public FieldState withReminderShown(long nowMillis) {
        this.remindedAtMillis = nowMillis;
        this.strikes = strikes + 1;
        return this;
    }

    String toJson() {
        StringBuilder shapes = new StringBuilder();
        for (String shape : posted) {
            if (shapes.length() > 0) {
                shapes.append(',');
            }
            shapes.append('"').append(shape).append('"');
        }
        return "{\"nudges\":" + nudges
            + ",\"silenced\":" + silenced
            + ",\"remindedAt\":" + remindedAtMillis
            + ",\"strikes\":" + strikes
            + ",\"posted\":[" + shapes + "]}";
    }

    public static Path file(Path dir) {
        return dir.resolve("state.json");
    }

    /** One numeric field out of the flat state document; 0 when absent or
     *  unparseable — a missing number is never a reason to fail a read. */
    private static long longField(String content, String key) {
        int from = content.indexOf(key);
        if (from < 0) {
            return 0;
        }
        from += key.length();
        int to = from;
        while (to < content.length()
                && (Character.isDigit(content.charAt(to)) || content.charAt(to) == '-')) {
            to++;
        }
        try {
            return Long.parseLong(content.substring(from, to));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
