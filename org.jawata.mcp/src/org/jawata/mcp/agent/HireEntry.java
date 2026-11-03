package org.jawata.mcp.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * One hire as the host's Agent Control shows it (Sprint 28h, S9, E3): the entry the agent carries in {@code _meta["jawata/hire"]} on the
 * tool call that is the hire. The figures are what the hire's own answer reported and nothing the agent estimated: a cost the brain did
 * not send is absent, never 0, and tokens are 0 only until the hire has answered.
 *
 * @param planId       the hire's identity on the wire: the id of the brain's tool call that is the hire
 * @param seat         the seat that was hired
 * @param brain        the brain the seat's file names, or none when the file could not be read
 * @param model        the model the seat's file names, or none
 * @param state        {@value #RUNNING}, {@value #DONE} or {@value #FAILED}
 * @param elapsedMs    how long the hire has run, or ran
 * @param tokens       what the hire's brain reported spending, 0 until it answered or when it reported none
 * @param costUsd      the dollar cost, only when the brain sent one
 * @param task         the brief the hire was given
 * @param parentPlanId the hire of the seat that hired this one, when that hire is one the agent saw; absent for a root
 * @param brainSessionId the brain's own session id for this hire, from the hire's answer; absent while it runs and for a brain that has none
 * @param verdict      the person's answer to the hire's proposal, {@value #ACCEPTED} or {@value #REJECTED}; absent until the person has answered
 */
public record HireEntry(String planId, String seat, Optional<String> brain, Optional<String> model, String state, long elapsedMs,
        long tokens, OptionalDouble costUsd, String task, Optional<String> parentPlanId, Optional<String> brainSessionId,
        Optional<String> verdict, int level) {

    /** An entry at the top of the hierarchy (level 0), whatever else it knows. */
    public HireEntry(String planId, String seat, Optional<String> brain, Optional<String> model, String state, long elapsedMs,
            long tokens, OptionalDouble costUsd, String task, Optional<String> parentPlanId, Optional<String> brainSessionId,
            Optional<String> verdict) {
        this(planId, seat, brain, model, state, elapsedMs, tokens, costUsd, task, parentPlanId, brainSessionId, verdict, 0);
    }

    /** An entry the person has not yet answered a proposal of. */
    public HireEntry(String planId, String seat, Optional<String> brain, Optional<String> model, String state, long elapsedMs,
            long tokens, OptionalDouble costUsd, String task, Optional<String> parentPlanId, Optional<String> brainSessionId) {
        this(planId, seat, brain, model, state, elapsedMs, tokens, costUsd, task, parentPlanId, brainSessionId, Optional.empty());
    }

    /** An entry whose brain has given no session id. */
    public HireEntry(String planId, String seat, Optional<String> brain, Optional<String> model, String state, long elapsedMs,
            long tokens, OptionalDouble costUsd, String task, Optional<String> parentPlanId) {
        this(planId, seat, brain, model, state, elapsedMs, tokens, costUsd, task, parentPlanId, Optional.empty());
    }

    public static final String RUNNING = "running";
    public static final String DONE = "done";
    public static final String FAILED = "failed";
    public static final String ACCEPTED = "accepted";
    public static final String REJECTED = "rejected";

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * The one line a client with no jawata view shows for this hire (contract E3): the seat, then the brain and the model where they are known,
     * then the state, for example {@code developer · claude-code · sonnet — running}. A brain or model that is not known is left out.
     */
    public String title() {
        List<String> parts = new ArrayList<>();
        parts.add(seat);
        brain.ifPresent(parts::add);
        model.ifPresent(parts::add);
        return String.join(" · ", parts) + " — " + state;
    }

    /** The entry as the wire carries it: only the fields that are known, so a missing cost or parent is absent and never a placeholder. */
    public ObjectNode toMeta() {
        ObjectNode meta = JSON.createObjectNode();
        meta.put("planId", planId);
        meta.put("seat", seat);
        brain.ifPresent(b -> meta.put("brain", b));
        model.ifPresent(m -> meta.put("model", m));
        meta.put("state", state);
        meta.put("elapsedMs", elapsedMs);
        meta.put("tokens", tokens);
        costUsd.ifPresent(c -> meta.put("costUsd", c));
        meta.put("task", task);
        parentPlanId.ifPresent(p -> meta.put("parentPlanId", p));
        brainSessionId.ifPresent(s -> meta.put("brainSessionId", s));
        verdict.ifPresent(v -> meta.put("verdict", v));
        meta.put("level", level);
        return meta;
    }
}
