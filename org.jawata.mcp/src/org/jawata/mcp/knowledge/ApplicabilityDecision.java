package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Sprint 28c D2 — similarity NOMINATES, an explicit decision ANSWERS.
 *
 * <p>The store's measured failure was not that it retrieved badly. It was that
 * it could not abstain: seven nonsense design questions each came back with the
 * maximum eleven "In a similar situation" candidates, and the policy that
 * produced them says in its own javadoc that no threshold separates nonsense
 * from answers. A pile of near-neighbours rendered to an agent IS an answer,
 * whatever it is labelled, because the agent has nothing else to go on.</p>
 *
 * <p>So the two are split into two operations that cannot be confused:</p>
 * <ul>
 *   <li>{@code nominate} ranks candidates and returns a {@code queryId}. Its
 *       result is never {@code match} — ranking is an ordering, not a claim
 *       that anything fits.</li>
 *   <li>{@code decide} takes that {@code queryId} and the ids the caller
 *       actually judged applicable. A non-empty selection is a {@code match};
 *       an empty one is an {@code absence}, which is a real answer and the one
 *       the nonsense questions must produce.</li>
 * </ul>
 *
 * <p><b>Why the queryId exists, rather than accepting bare ids.</b> Without it
 * a caller could "select" any entry in the store and have it returned as a
 * vouched answer, which would put the old pile back through a door labelled as
 * a decision. A selection is only meaningful about the candidates it was
 * offered, so ids that were never nominated are REFUSED rather than quietly
 * dropped — dropping them would turn a caller's mistake into a smaller, still
 * confident answer.</p>
 *
 * <p><b>State is per-process and bounded.</b> A nomination is a short-lived
 * conversation between one caller and one resident; it is not knowledge and is
 * never persisted. The register keeps the most recent {@link #MAX_OPEN} and
 * evicts the oldest, so a caller that never decides cannot grow the resident.
 * An evicted or unknown id is refused with that reason — never treated as an
 * empty selection, because "you waited too long" and "nothing applied" are
 * different answers and only one of them is knowledge.</p>
 */
public final class ApplicabilityDecision {

    /**
     * How many nominations stay open at once.
     *
     * <p>A nomination is answered in the same exchange that opened it, so this
     * is a leak bound rather than a working-set size. It is small deliberately:
     * a resident serving several clients holds tens of bytes per open query,
     * and an unbounded map here is the shape that turns a forgetful caller into
     * a memory incident.</p>
     */
    public static final int MAX_OPEN = 256;

    /** What a decision produced. */
    public enum Result {
        /** The caller judged at least one candidate applicable. */
        MATCH,
        /** The caller judged none applicable — an answer, not a failure. */
        ABSENCE
    }

    /**
     * A decision's outcome.
     *
     * @param result   match or absence
     * @param selected the entry ids the caller judged applicable, in the order
     *     they were nominated; empty for an absence
     * @param question the question the nomination was opened for, carried so a
     *     journal entry or a response can say what was being asked
     */
    public record Decision(Result result, List<String> selected, String question) {

        public Decision {
            selected = List.copyOf(selected);
        }

        /** True when the caller found nothing applicable. */
        public boolean isAbsence() {
            return result == Result.ABSENCE;
        }
    }

    /** Why a decide call could not be honoured. Never a silent empty selection. */
    public record Refusal(String reason) {
    }

    private record Open(String question, List<String> candidateIds) {
    }

    private final Map<String, Open> open = new LinkedHashMap<>() {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Open> eldest) {
            return size() > MAX_OPEN;
        }
    };

    /**
     * Register a ranking and return the id the decision must quote.
     *
     * <p>The candidate order is preserved: it is the ranking, and a caller
     * reading it is entitled to know which the engine thought closest. That is
     * ALL the ordering claims — closest is not applicable.</p>
     *
     * @param question     the question asked, in the caller's own words
     * @param candidateIds the ranked entry ids; may be empty, which is itself a
     *     legitimate nomination — a question with no near-neighbours at all
     * @return the query id to quote when deciding
     */
    public synchronized String nominate(String question, List<String> candidateIds) {
        String queryId = UUID.randomUUID().toString();
        open.put(queryId, new Open(question, List.copyOf(candidateIds)));
        return queryId;
    }

    /** The candidates a nomination offered, or empty when it is unknown or evicted. */
    public synchronized Optional<List<String>> candidatesOf(String queryId) {
        Open o = open.get(queryId);
        return o == null ? Optional.empty() : Optional.of(o.candidateIds());
    }

    /** How many nominations are currently open. */
    public synchronized int openCount() {
        return open.size();
    }

    /**
     * Turn a selection into an answer.
     *
     * <p>The nomination is CONSUMED: deciding twice on one query is refused,
     * because a second decision is either a caller mistake or a retry after a
     * response was lost, and answering it again would silently endorse whichever
     * arrived last.</p>
     *
     * @param queryId     the id {@link #nominate} returned
     * @param selectedIds the candidates the caller judged applicable; empty is
     *     the honest answer when none did
     * @return the decision, or a refusal naming why it could not be made
     */
    public synchronized Object decide(String queryId, List<String> selectedIds) {
        if (queryId == null || queryId.isBlank()) {
            return new Refusal("a decision must quote the query_id its nomination returned;"
                + " without one there is nothing to check the selection against, and any"
                + " entry in the store could be returned as a vouched answer.");
        }
        Open o = open.remove(queryId);
        if (o == null) {
            return new Refusal("query_id '" + queryId + "' is not open. It was either already"
                + " decided, or evicted after " + MAX_OPEN + " newer nominations. Ask again"
                + " and decide on the fresh candidates — this is NOT an absence, and"
                + " reporting it as one would record 'nothing applied' about a question"
                + " nobody answered.");
        }

        List<String> selected = selectedIds == null ? List.of() : selectedIds;
        Set<String> offered = Set.copyOf(o.candidateIds());
        List<String> unknown = new ArrayList<>();
        for (String id : selected) {
            if (!offered.contains(id)) {
                unknown.add(id);
            }
        }
        if (!unknown.isEmpty()) {
            return new Refusal("selected " + unknown + ", which this nomination did not offer."
                + " A selection is only meaningful about the candidates it was shown; ids from"
                + " anywhere else would let a caller vouch for an entry the engine never"
                + " nominated. Nothing was decided.");
        }

        // Order by the RANKING, not by the order the caller happened to list them:
        // the response reads as an answer, and an answer's order should be the
        // engine's one defensible claim rather than an artifact of the request.
        List<String> ordered = new ArrayList<>();
        for (String id : o.candidateIds()) {
            if (selected.contains(id)) {
                ordered.add(id);
            }
        }
        return new Decision(ordered.isEmpty() ? Result.ABSENCE : Result.MATCH,
            ordered, o.question());
    }
}
