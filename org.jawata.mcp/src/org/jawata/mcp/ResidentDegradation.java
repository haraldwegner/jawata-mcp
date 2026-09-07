package org.jawata.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * EVERY DEGRADED STATE THIS RESIDENT IS IN, IN ONE PLACE — the answer to
 * jawata-mcp#12, and the mechanism the degradation stamp
 * ({@code ARCHITECTURE-degradation-stamp-28e.md}) needs to exist at all.
 *
 * <h2>Why a registry rather than each component saying so in its own answer</h2>
 *
 * <p>jawata is a tool for agents, and an agent routes around friction silently: if a
 * capability degrades and the agent does not complain, nobody ever notices. Before this
 * class, five components each wrote their own DEGRADED sentence into their own response —
 * the experience store's in-memory fallback, the retrieval ranking with unreadable meaning
 * lanes, the cure lookup's missing catalogue namespaces, the AST scan that could not read
 * every file. Each is correct, and each is invisible to a caller using ANY OTHER tool.
 * A store that has silently fallen back to non-persistent memory is a fact about the whole
 * resident, and a caller running {@code find_references} has as much need of it as one
 * running {@code experience}.</p>
 *
 * <p>So the channel is the RESPONSE ITSELF, on every call, because that is the one channel
 * that provably reaches the agent — see the seat rule about controls that can act in time.
 * {@code ToolRegistry.callTool} stamps {@link #stamp()} into every response it returns,
 * success and refusal alike, and {@code health_check} mirrors {@link #notices()}.</p>
 *
 * <h2>Two halves, and the difference matters</h2>
 *
 * <p><b>DERIVED</b> — the workspace's own load state is read from
 * {@link JawataApplication#getLoadingState()} at the moment the question is asked. It is
 * NOT copied in here, because a copy is a second source of truth for one fact and this
 * sprint has already paid for that shape more than once: it would go stale exactly when
 * the state changed, which is the only moment it matters.</p>
 *
 * <p><b>DECLARED</b> — a component whose degradation has no other home calls
 * {@link #declare} and, when it recovers, {@link #cure}. This half needs the discipline
 * the derived half gets for free: a declaration that is never cured is a permanent false
 * alarm, and an alarm nobody can clear is one nobody reads.</p>
 */
public final class ResidentDegradation {

    /**
     * The DECLARED half. Sorted so the stamp is stable across calls — an agent seeing the
     * same resident state must see the same sentence, or it reads as a change.
     */
    private static final Map<String, String> DECLARED = new ConcurrentSkipListMap<>();

    /** The key the workspace load state would use if it were declared rather than derived. */
    public static final String WORKSPACE = "workspace";

    private ResidentDegradation() {
    }

    /**
     * Declare a degraded state under a stable key, replacing any previous notice for it.
     *
     * @param key    a stable identifier for the STATE, not for the occurrence — a component
     *               that degrades twice for the same reason declares the same key, so the
     *               stamp does not grow a line per event
     * @param notice one line, in a caller's terms: what is degraded, and what it costs them.
     *               Not a stack trace and not an internal name
     */
    public static void declare(String key, String notice) {
        if (key == null || key.isBlank() || notice == null || notice.isBlank()) {
            return;
        }
        DECLARED.put(key, notice.strip());
    }

    /** Withdraw a declared state, because it was cured. A key that is not declared is a no-op. */
    public static void cure(String key) {
        if (key != null) {
            DECLARED.remove(key);
        }
    }

    /**
     * Every notice the resident currently owes a caller — the derived workspace state first,
     * because it is the one that changes what every other answer MEANS, then the declared
     * ones in stable key order.
     */
    public static List<String> notices() {
        List<String> all = new ArrayList<>();
        String workspace = workspaceNotice();
        if (workspace != null) {
            all.add(workspace);
        }
        all.addAll(DECLARED.values());
        return List.copyOf(all);
    }

    /**
     * The workspace's own contribution, DERIVED from the load state rather than stored.
     *
     * <p>{@code LOADING} is a degraded state and not merely a busy one: a tool that answers
     * during a load answers over the projects that happen to be up, and says so nowhere —
     * which is jawata-mcp#21, where {@code project(action=list)} returned {@code success:true}
     * with an empty array while {@code health_check} knew perfectly well the workspace was
     * still loading.</p>
     */
    private static String workspaceNotice() {
        ProjectLoadingState state = JawataApplication.getLoadingState();
        if (state == ProjectLoadingState.LOADING) {
            return "the workspace is still LOADING — this answer covers only the projects "
                + "loaded so far, so an absence in it is not yet a real absence. "
                + "health_check reports when the load finishes.";
        }
        if (state == ProjectLoadingState.FAILED) {
            String why = JawataApplication.getLoadingError();
            return "the workspace FAILED to load"
                + (why == null || why.isBlank() ? "" : " (" + why + ")")
                + " — answers are over whatever loaded, and a whole-workspace question "
                + "cannot be complete. load_project reinstalls it.";
        }
        return null;
    }

    /** Whether the resident owes a caller anything at all. */
    public static boolean any() {
        return !notices().isEmpty();
    }

    /**
     * The one-line stamp for a response's meta, or {@code null} when nothing is degraded.
     *
     * <p>Null rather than an empty string on purpose: a response carrying an empty
     * {@code DEGRADED:} line would make "nothing is wrong" and "we did not check" render
     * identically, which is the exact confusion this whole mechanism exists to remove.</p>
     */
    public static String stamp() {
        List<String> notices = notices();
        if (notices.isEmpty()) {
            return null;
        }
        return "DEGRADED: " + String.join("  ·  ", notices);
    }

    /**
     * Test hook — a declaration left behind by one test stamps every later test's responses.
     * Package-private for the reason {@code JawataApplication}'s own test hooks are.
     */
    static void clearForTest() {
        DECLARED.clear();
    }
}
