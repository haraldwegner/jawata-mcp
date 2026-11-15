package org.jawata.mcp.engine;

import java.util.Optional;
import java.util.Set;

import org.jawata.mcp.execution.BrainRoster;

/**
 * The agent brain settings this machine can supply when its host sets none (Sprint 28k, Amendment 3, D15): a host without the studio, the
 * Eclipse plug-in, asks here last, after what the person set and what the host was started with, so its chat and its hires have a brain
 * without anyone writing one down.
 */
public final class MachineAgents {

    /** The three settings this machine can supply: the principal's brain, the brains a hire may start, and the reviewer's. */
    public static final Set<String> KEYS = Set.of("jawata.agent.brain", "jawata.agent.hireBrains", "jawata.agent.reviewer");

    private MachineAgents() {
    }

    /**
     * What this machine supplies for one of {@link #KEYS}: Cursor's agent program when it is on the path, since that is the brain an
     * engine without the studio can start on its own. Worked out at each call, so a program installed after Eclipse started is found and
     * an absolute path is never stored.
     *
     * @param key a setting's name
     * @return the value, or empty when the key is not one of {@link #KEYS} or the program is not installed
     */
    public static Optional<String> setting(String key) {
        return KEYS.contains(key) ? Optional.ofNullable(BrainRoster.onThisMachine().agentSettings().settings().get(key)) : Optional.empty();
    }
}
