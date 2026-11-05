package org.jawata.mcp.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * How many times the loop has paid for another try at one slice of work (Sprint 28h, S8, D12). The loop reads it before it steps: an
 * attempt within the ceiling is admitted and counted, the one after it is refused until the person says otherwise. The slice is the unit
 * of control, so a slice that keeps failing stops costing while another goes on being tried.
 */
public final class AttemptLedger {

    /**
     * What the loop was told about one more attempt.
     *
     * @param allowed whether the attempt may be made
     * @param used    how many attempts the slice has now had, this one included when it was allowed
     * @param ceiling how many it may have without the person's word
     */
    public record Admission(boolean allowed, int used, int ceiling) {
    }

    private final int ceiling;
    private final Map<String, Map<String, Integer>> used = new HashMap<>();

    /** A ledger that admits {@code ceiling} attempts of a slice. */
    public AttemptLedger(int ceiling) {
        if (ceiling < 1) {
            throw new IllegalArgumentException("a ceiling below one admits no attempt at all: " + ceiling);
        }
        this.ceiling = ceiling;
    }

    /**
     * Counts and admits an attempt of {@code slice} made in {@code session}, or refuses it when the slice has had all it may in that
     * session. The same slice in another session has its own count.
     */
    public synchronized Admission admit(String session, String slice) {
        Map<String, Integer> mine = used.computeIfAbsent(session, key -> new HashMap<>());
        int had = mine.getOrDefault(slice, 0);
        if (had >= ceiling) {
            return new Admission(false, had, ceiling);
        }
        mine.put(slice, had + 1);
        return new Admission(true, had + 1, ceiling);
    }

    /** The person spoke in {@code session}: every slice of that session starts again; no other session's count moves. */
    public synchronized void personSpoke(String session) {
        used.remove(session);
    }
}
