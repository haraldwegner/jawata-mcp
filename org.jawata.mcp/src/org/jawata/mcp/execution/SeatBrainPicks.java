package org.jawata.mcp.execution;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The brain and model the person picked for a seat in the Agent Control panel (Sprint 28h, S9; his ruling 2026-10-31: each agent row's brain is
 * a control whose pick overwrites that agent's brain).
 *
 * <p>A pick applies to the NEXT hire of the seat and to every hire after it until it is changed: a process already started cannot change its
 * brain, so the pick is the seat's standing choice and the running hire is left alone. The agent face receives the pick and the hire tool
 * reads it, and the two meet in this one object because the engine's settings are read-only.</p>
 */
public final class SeatBrainPicks {

    /** What was picked: the brain and the model; a blank model leaves the brain's own default. */
    public record Pick(SeatHire.Brain brain, String model) {
        public Pick {
            model = model == null ? "" : model.strip();
        }
    }

    private static final SeatBrainPicks SHARED = new SeatBrainPicks();

    private final Map<String, Pick> picks = new ConcurrentHashMap<>();

    /** The one store of picks in this process, which the agent face writes and the hire tool reads. */
    public static SeatBrainPicks shared() {
        return SHARED;
    }

    /** Sets the standing choice of this seat, replacing an earlier one. */
    public void pick(String seat, SeatHire.Brain brain, String model) {
        picks.put(seat, new Pick(brain, model));
    }

    /** What was picked for this seat, or none when nobody picked. */
    public Optional<Pick> forSeat(String seat) {
        return Optional.ofNullable(picks.get(seat));
    }

    /** Forgets every pick, so a test or a restarted panel starts from the seat files again. */
    public void clear() {
        picks.clear();
    }
}
