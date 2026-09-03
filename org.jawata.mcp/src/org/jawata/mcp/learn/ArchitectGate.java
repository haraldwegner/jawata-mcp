package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The architect-involvement gate (Sprint 26a, D3b) — the DETERMINISTIC rule that
 * replaces Sprint 26's edit-switch model (retired in D4). One question: does
 * this edit need an architect's eyes, or is it plain? It fires the review steer
 * when the mutate delta:
 * <ul>
 *   <li>introduces a code smell — {@code smellFound}, supplied by the watch
 *       engine's per-delta detector run (the same detectors, not a second
 *       surface);</li>
 *   <li>is structural by shape — the tool changes a signature or hierarchy
 *       ({@link #STRUCTURAL_TOOLS}, or {@code extract} of a superclass/
 *       interface);</li>
 *   <li>is large — more than {@code locThreshold} changed lines in the diff
 *       (default 500, tuned in dogfood).</li>
 * </ul>
 * A plain edit (statement-local, no smell, small, non-structural) passes
 * untouched — no steer. Pure rule, no ML: the same delta always yields the same
 * decision, so it is auditable and never needs training.
 */
public final class ArchitectGate {

    /** Default changed-LoC threshold — a plan-time constant, tuned in dogfood. */
    public static final int DEFAULT_LOC_THRESHOLD = 500;

    private final int locThreshold;
    private final org.jawata.mcp.refactoring.OperationRegistry registry;

    public ArchitectGate(int locThreshold) {
        this(locThreshold, org.jawata.mcp.refactoring.OperationRegistry.theRegistry());
    }

    /**
     * The registry this gate asks what an operation IS.
     *
     * <p>Injectable because the gate now HAS a dependency, and a dependency taken from a
     * global is one a test cannot supply. It used to answer from two literal sets in this
     * file, which needed nothing — and which went silently stale the first time a tool
     * was renamed. Trading "needs nothing" for "needs the registry" is the whole repair;
     * hiding that behind a singleton would leave the gate's own tests passing over an
     * empty registry, which is a differently-shaped version of the same blindness.</p>
     */
    public ArchitectGate(int locThreshold, org.jawata.mcp.refactoring.OperationRegistry registry) {
        this.locThreshold = locThreshold;
        this.registry = registry;
    }

    /**
     * @param tool       the tool that just answered
     * @param arguments  its arguments (to read {@code extract}'s kind)
     * @param response   its response (the diff + filesModified are the delta)
     * @param smellFound whether the watch engine's detectors flagged a smell on
     *                   this delta
     * @return the architect-review steer to append, or {@code null} for a plain edit
     */
    public String evaluate(String tool, JsonNode arguments, ToolResponse response, boolean smellFound) {
        if (response == null || !response.isSuccess() || filesModified(response) == 0) {
            return null;   // not a mutate — nothing to gate
        }
        List<String> reasons = new ArrayList<>();
        if (smellFound) {
            reasons.add("introduces a code smell");
        }
        if (isStructural(tool, arguments)) {
            reasons.add("changes a signature or hierarchy");
        }
        int loc = changedLoc(response);
        if (loc > locThreshold) {
            reasons.add("is large (" + loc + " changed lines, over the " + locThreshold + " threshold)");
        }
        if (reasons.isEmpty()) {
            return null;   // a plain edit — no architect needed
        }
        return "🏛 ARCHITECT REVIEW — this edit " + String.join(", and ", reasons)
            + ". Involve the architect (/refactor) before it lands. A staged edit"
            + " (auto_apply=false) is reviewable now — apply only after the review;"
            + " a hand-edit is already on disk, so review it before you build on it.";
    }

    /**
     * ASKED OF THE REGISTRY, not of a list kept here.
     *
     * <p>This used to be two {@code Set.of(...)} literals — four tool names and two
     * extract kinds. Stage 1 retired two of those four names when it folded and renamed
     * tools, and this gate went silent for pull-up, push-down and the method move with
     * nothing failing anywhere: a list of names in this package cannot know that a name
     * has stopped existing. The tools declare what they are now, and an operation that
     * is retired takes its classification with it.</p>
     *
     * <p>The KIND is asked in its qualified spelling. A bare {@code method} is published
     * by three front doors and means something different in each, so classifying the
     * bare name would make an extracted local method structural because a moved instance
     * method is.</p>
     */
    private boolean isStructural(String tool, JsonNode arguments) {
        if (registry.isStructural(tool)) {
            return true;
        }
        String kind = arguments == null ? "" : arguments.path("kind").asText("");
        return !kind.isBlank() && registry.isStructural(
            org.jawata.mcp.refactoring.OperationRegistry.qualify(tool, kind));
    }

    /** Changed lines in the response's unified diff (+/− lines, excluding headers). */
    static int changedLoc(ToolResponse response) {
        if (!(response.getData() instanceof Map<?, ?> map) || !(map.get("diff") instanceof String diff)) {
            return 0;   // no diff to measure — the size trigger stays silent (honest)
        }
        int loc = 0;
        for (String line : diff.split("\n", -1)) {
            if (line.isEmpty()) {
                continue;
            }
            char c = line.charAt(0);
            if ((c == '+' && !line.startsWith("+++"))
                    || (c == '-' && !line.startsWith("---"))) {
                loc++;
            }
        }
        return loc;
    }

    private static int filesModified(ToolResponse response) {
        if (response.getData() instanceof Map<?, ?> map
                && map.get("filesModified") instanceof List<?> files) {
            return files.size();
        }
        return 0;
    }
}
