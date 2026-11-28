package org.jawata.mcp.learn;

import java.util.List;

/**
 * What a sprint plan marks beside its chain of checked steps.
 *
 * @param name        the plan's name
 * @param titles      the titles of the chain steps, in chain order
 * @param checkpoints the chain indices marked as checkpoints
 * @param reserved    the steps reserved to the person
 */
public record PlanMarks(String name, List<String> titles, List<Integer> checkpoints, List<Reserved> reserved) {

    public PlanMarks {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("a plan needs a name that is not blank");
        }
        titles = List.copyOf(titles);
        checkpoints = List.copyOf(checkpoints);
        reserved = List.copyOf(reserved);
    }

    /**
     * A step reserved to the person.
     *
     * @param title what the step is called
     * @param after how many chain steps come before it
     */
    public record Reserved(String title, int after) {

        public Reserved {
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("a reserved step needs a title that is not blank");
            }
            if (after < 0) {
                throw new IllegalArgumentException("step '" + title + "' cannot come after " + after + " chain steps: a count is not below zero");
            }
        }
    }
}
