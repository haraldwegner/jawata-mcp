package org.jawata.mcp.learn;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Where a sprint plan stands in the ledger: how many chain steps have passed, which step and which checkpoint come next, and what is left for the
 * person. Plain data; it moves nothing.
 *
 * @param passed            the chain steps passed (the ledger's current step plus one)
 * @param total             the size of the chain
 * @param next              the next chain step; empty when every chain step has passed
 * @param nextCheckpoint    the first chain step marked as a checkpoint that has not passed; empty when there is none
 * @param forPerson         the titles of the person's steps, in the order they were entered
 * @param checkpointReached whether the last chain step that passed is marked as a checkpoint; false when nothing has passed
 * @param personNext        whether a person's step stands immediately before the next chain step; false when nothing remains to do
 * @param open              whether a chain step remains to pass
 */
public record Standing(int passed, int total, Optional<Next> next, Optional<NextCheckpoint> nextCheckpoint, List<String> forPerson,
        boolean checkpointReached, boolean personNext, boolean open) {

    /**
     * The next chain step.
     *
     * @param index the step's 0-based index in the chain, as the ledger shows it
     * @param title the step's title
     * @param tool  the tool whose answer is the step's check
     */
    public record Next(int index, String title, String tool) {
    }

    /**
     * The first checkpoint not yet passed.
     *
     * @param index the step's 0-based index in the chain
     * @param title the step's title
     */
    public record NextCheckpoint(int index, String title) {
    }

    /** This standing as a map for an answer: passed, total, next, nextCheckpoint, forPerson, open, checkpointReached, personNext, in that order. */
    public Map<String, Object> asMap() {
        Map<String, Object> map = new java.util.LinkedHashMap<>();
        map.put("passed", passed);
        map.put("total", total);
        map.put("next", next.map(step -> {
            Map<String, Object> shown = new java.util.LinkedHashMap<>();
            shown.put("index", step.index());
            shown.put("title", step.title());
            shown.put("tool", step.tool());
            return shown;
        }).orElse(null));
        map.put("nextCheckpoint", nextCheckpoint.map(checkpoint -> {
            Map<String, Object> shown = new java.util.LinkedHashMap<>();
            shown.put("index", checkpoint.index());
            shown.put("title", checkpoint.title());
            return shown;
        }).orElse(null));
        map.put("forPerson", forPerson);
        map.put("open", open);
        map.put("checkpointReached", checkpointReached);
        map.put("personNext", personNext);
        return map;
    }
}
