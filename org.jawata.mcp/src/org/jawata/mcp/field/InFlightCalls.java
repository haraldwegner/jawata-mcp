package org.jawata.mcp.field;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CALLS THAT HAVE STARTED AND NOT COME BACK — jawata-mcp#42.
 *
 * <h2>The gap this fills</h2>
 *
 * <p>{@link FieldRecorder} records a call when it FINISHES: the event tap fires on the
 * response, so the shape, the error code and the latency bucket are all read off an answer
 * that exists. A call that never returns produces no answer, therefore no row, therefore no
 * shape — <b>so the single worst thing jawata can do to a user is the one thing the
 * recording cannot see</b>, and {@code /report} could not surface it however carefully the
 * seat was driven.</p>
 *
 * <p>Measured 2026-08-21: two {@code inspect(kind=landmarks)} calls hung and were abandoned
 * by the client; the pile immediately afterwards reported three shapes over 1303 events and
 * 24 failures, and <i>no {@code inspect/landmarks} row at all</i>. A returned error is
 * louder in the recording than a hang, which is exactly backwards.</p>
 *
 * <h2>What it does, and what it deliberately does not</h2>
 *
 * <p>{@link #started} on the way in, {@link #finished} on the way out, both from the one
 * choke that already wraps every call. Whatever is left is outstanding. {@link #outstanding}
 * answers the ones older than a threshold, so an ordinary in-progress call — every call is
 * in flight for its own duration, including the one asking the question — is not reported
 * as a hang.</p>
 *
 * <p><b>It holds SHAPES, like everything else in this package: a tool name and a
 * discriminator, both already sanitized by {@link Token}, and a start time.</b> No
 * arguments, no paths, no session content — the {@code /report} seat drafts a public issue
 * body straight from these answers.</p>
 *
 * <p><b>The bound, stated rather than left to be discovered:</b> this is process-local. A
 * call outstanding when the resident DIES is not recovered — the entry dies with it. That
 * is the harder half and it is not claimed here. What is covered is the measured case: the
 * client abandons the call, the resident lives on, and the next {@code field(action=pile)}
 * can now say what is stuck instead of showing a pile that looks healthy.</p>
 */
public final class InFlightCalls {

    /**
     * How long a call must have been outstanding before it is reported. Below this every
     * answer would carry the call currently being served, which is noise rather than news.
     */
    public static final long DEFAULT_STUCK_MS = 60_000;

    /**
     * One outstanding call, as shapes only.
     *
     * <p>The components are {@link Token}s rather than Strings for the reason the whole
     * package uses that type: the leak class dies at the TYPE level rather than in a
     * filter someone has to remember to apply. A path or a message cannot be constructed
     * into this record at all.</p>
     */
    public record Call(Token tool, Token kind, long startedMs) {

        /** How long it has been outstanding, at the moment of asking. */
        public long outstandingMs(long now) {
            return now - startedMs;
        }

        /**
         * The pile's shape key for this call, in the same {@code tool/kind/code} form the
         * error shapes use — so a hang RANKS BESIDE the returned failures rather than
         * arriving in a vocabulary of its own that a reader has to learn.
         */
        public String shape() {
            return tool.value() + "/" + kind.value() + "/NEVER_RETURNED";
        }
    }

    private final Map<Long, Call> live = new ConcurrentHashMap<>();
    private final AtomicLong ids = new AtomicLong();

    /**
     * Record that a call has started.
     *
     * @return the ticket to hand back to {@link #finished}. A caller that loses it leaves
     *         the entry outstanding, which is the correct failure direction: a false hang
     *         is visible and can be investigated, a missed one is the defect this exists
     *         to remove.
     */
    public long started(String tool, String kind) {
        long id = ids.incrementAndGet();
        live.put(id, new Call(Token.of(tool), Token.of(kind), System.currentTimeMillis()));
        return id;
    }

    /**
     * Test seam — a call that started at a given moment, so a test can produce one that
     * is genuinely OLD without sleeping for the stuck threshold.
     *
     * <p>Package-private for the reason {@code JawataApplication}'s load-state hooks are:
     * a public test-only member is what the hollow-wiring gate refuses. It exists because
     * the alternative was worse — publishing a {@code stuckMs} parameter on
     * {@code field(action=pile)} purely so a test could reach the branch would have
     * widened the client-facing surface to suit the test.</p>
     */
    long startedAt(String tool, String kind, long whenMs) {
        long id = ids.incrementAndGet();
        live.put(id, new Call(Token.of(tool), Token.of(kind), whenMs));
        return id;
    }

    /** Record that the call came back — however it came back. */
    public void finished(long id) {
        live.remove(id);
    }

    /** Calls outstanding longer than {@code stuckMs}, longest first. */
    public List<Call> outstanding(long stuckMs) {
        long now = System.currentTimeMillis();
        List<Call> stuck = new ArrayList<>();
        for (Call call : live.values()) {
            if (call.outstandingMs(now) >= stuckMs) {
                stuck.add(call);
            }
        }
        stuck.sort(Comparator.comparingLong(Call::startedMs));
        return List.copyOf(stuck);
    }

    /** How many calls are in flight right now, stuck or not — for a status surface. */
    public int liveCount() {
        return live.size();
    }
}
