package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** Signature/hierarchy-affecting refactorings — structural by their nature. */
    private static final Set<String> STRUCTURAL_TOOLS = Set.of(
        "change_method_signature", "move_in_hierarchy", "move_method",
        "refactor_to_pattern");

    /** Extract kinds that change the hierarchy (vs a local method/variable). */
    private static final Set<String> STRUCTURAL_EXTRACT_KINDS = Set.of("superclass", "interface");

    /** Default changed-LoC threshold — a plan-time constant, tuned in dogfood. */
    public static final int DEFAULT_LOC_THRESHOLD = 500;

    private final int locThreshold;

    public ArchitectGate(int locThreshold) {
        this.locThreshold = locThreshold;
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

    private static boolean isStructural(String tool, JsonNode arguments) {
        if (STRUCTURAL_TOOLS.contains(tool)) {
            return true;
        }
        if ("extract".equals(tool) && arguments != null) {
            return STRUCTURAL_EXTRACT_KINDS.contains(arguments.path("kind").asText(""));
        }
        return false;
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
