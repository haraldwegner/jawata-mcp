package org.jawata.mcp.agent;

import java.util.HashMap;
import java.util.Map;

/**
 * How many times the loop has paid for another try at one slice of work (Sprint 28h, S8, D12). It counts and refuses nothing: the count is
 * shown, and whoever leads the work decides whether another try is worth it (28h z132, his ruling: no limits).
 */
public final class AttemptLedger {

    private final Map<String, Map<String, Integer>> used = new HashMap<>();

    /** Counts one more attempt of {@code slice} made in {@code session} and answers how many it has now had there. */
    public synchronized int admit(String session, String slice) {
        return used.computeIfAbsent(session, key -> new HashMap<>()).merge(slice, 1, Integer::sum);
    }

    /** The person spoke in {@code session}: every slice of that session starts again; no other session's count moves. */
    public synchronized void personSpoke(String session) {
        used.remove(session);
    }
}
