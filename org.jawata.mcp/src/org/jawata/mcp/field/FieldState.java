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
 * (D9) whether the reminders are silenced.
 *
 * <p><b>No reminder bookkeeping lives here.</b> It once did — a
 * {@code remindedAt} timestamp and a {@code strikes} counter — and nothing in
 * production ever wrote them, while {@code FieldTool} handed them to agents as
 * fact: an agent read {@code strikes: 0} forever while the real count advanced
 * in {@code reminded.log} (28b closing audit, F2). The append-only ledger is
 * the single truth; {@link #reminderStrikes(Path)} folds it.</p>
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

    /** The append-only reminder ledger, beside the state in the same
     *  directory. THE single truth about reminder strikes. */
    static final String REMINDER_LEDGER = "reminded.log";

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

    public FieldState withNudges(boolean on) {
        this.nudges = on;
        return this;
    }

    public FieldState withSilenced(boolean on) {
        this.silenced = on;
        return this;
    }

    /** Marks a shape reported: it stops nudging and stops counting as news.
     *  The reminder strikes are reset by the ledger marker
     *  {@link #recordReportUsed} appends, never by a counter held here. */
    public FieldState withPosted(String shapeKey) {
        if (shapeKey != null && !shapeKey.isBlank()) {
            posted.add(shapeKey);
        }
        return this;
    }

    /**
     * Appends the {@code /report}-was-used marker to the reminder ledger (D9).
     *
     * <p>The ledger is APPEND-ONLY and shared with the hook, which appends its
     * own {@code shown} lines: three processes touch this lane, so a
     * read-modify-write from any of them would lose another's record. The
     * hook counts strikes as the lines since the last reset.</p>
     */
    public void recordReportUsed(Path dir) {
        try {
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(REMINDER_LEDGER),
                System.currentTimeMillis() + "\treset\n",
                java.nio.file.StandardOpenOption.CREATE,
                java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            log.error("Reminder-reset marker NOT written at {} — the reminders will keep"
                + " counting this use as unanswered", dir, e);
        }
    }

    /**
     * Unanswered reminders: the {@code shown} lines since the last
     * {@code reset} in the append-only ledger (D9).
     *
     * <p>THE LEDGER IS THE ONLY TRUTH HERE. The state file used to carry a
     * copy, but nothing in production ever wrote it while this tool reported
     * it to agents as fact — so an agent read {@code strikes: 0} forever while
     * the real count advanced (28b closing audit, F2). The fold below is the
     * same one the hook does in {@code jawata-hook/src/field.rs
     * ::reminder_ledger} and studio does in {@code field_view.rs}: three
     * processes append to this file, so none of them may hold a counter.</p>
     *
     * @param dir the {@code <workspace>/field} directory
     * @return the strike count; 0 when the ledger is absent or unreadable
     */
    public static int reminderStrikes(Path dir) {
        Path ledger = dir.resolve(REMINDER_LEDGER);
        if (!Files.exists(ledger)) {
            return 0;
        }
        int strikes = 0;
        try {
            for (String line : Files.readAllLines(ledger)) {
                int tab = line.indexOf('\t');
                if (tab < 0) {
                    continue;   // a half-written line loses itself, never the fold
                }
                String kind = line.substring(tab + 1).trim();
                if ("shown".equals(kind)) {
                    strikes++;
                } else if ("reset".equals(kind)) {
                    strikes = 0;
                }
            }
        } catch (IOException e) {
            log.error("Reminder ledger unreadable at {} — reporting 0 strikes, which"
                + " under-reports rather than inventing a count", ledger, e);
            return 0;
        }
        return strikes;
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
            + ",\"posted\":[" + shapes + "]}";
    }

    public static Path file(Path dir) {
        return dir.resolve("state.json");
    }
}
