package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 28c D14 — what a session has already been shown, so it is not shown again.
 *
 * <p>The recall hook fires on every prompt. Without this, a long conversation about
 * one subject re-injects the same three entries on every turn: the agent learns to
 * skim the block, and the one turn where the store has something NEW looks exactly
 * like the twenty before it. Repetition is how a channel stops being read.</p>
 *
 * <p><b>In memory, and bounded twice.</b> A session's set is capped, and the number
 * of live sessions is capped — both by eviction of the oldest, because this is a
 * courtesy and must never be the reason a resident runs out of memory. Losing the
 * set costs a repeat, which is the failure it exists to reduce rather than one it
 * creates. It is deliberately NOT persisted: a session is a conversation, and a
 * conversation does not survive the resident.</p>
 *
 * <p><b>Withholding is not absence, and the caller must say so.</b> This returns the
 * entries not yet seen; it is the caller's job to report that something was withheld.
 * An entry dropped because the reader already has it and an entry the store does not
 * have are opposite facts, and a silent drop renders the first as the second.</p>
 */
final class SessionShown {

    /** Entry ids remembered per session. Beyond this the oldest are forgotten. */
    static final int MAX_IDS_PER_SESSION = 200;

    /** Live sessions remembered. Beyond this the least recently used is evicted. */
    static final int MAX_SESSIONS = 64;

    private static final Map<String, Set<String>> SEEN =
        new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Set<String>> eldest) {
                return size() > MAX_SESSIONS;
            }
        };

    private SessionShown() {
    }

    /**
     * The entries this session has not been shown, in the order given — and they
     * are recorded as shown by the act of returning them.
     *
     * <p>Recording here rather than at the render site is deliberate: the two are
     * the same event, and separating them is how a set drifts from what was
     * actually injected.</p>
     *
     * @param session the session id; a blank one is not tracked at all
     * @param entries the candidate rows, each carrying an {@code id}
     * @return the subset not previously returned for this session
     */
    static List<Map<String, Object>> unseen(String session, List<Map<String, Object>> entries) {
        if (session == null || session.isBlank() || entries == null || entries.isEmpty()) {
            return entries == null ? List.of() : entries;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        synchronized (SEEN) {
            Set<String> ids = SEEN.computeIfAbsent(session, s -> new LinkedHashSet<>());
            for (Map<String, Object> e : entries) {
                String id = String.valueOf(e.get("id"));
                if (ids.contains(id)) {
                    continue;
                }
                out.add(e);
                ids.add(id);
            }
            while (ids.size() > MAX_IDS_PER_SESSION) {
                java.util.Iterator<String> it = ids.iterator();
                it.next();
                it.remove();
            }
        }
        return out;
    }

    /** Forget a session — used by tests, and harmless in production. */
    static void forget(String session) {
        synchronized (SEEN) {
            SEEN.remove(session);
        }
    }
}
