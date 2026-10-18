package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Sprint 28g S22, D43 — <b>which lanes a call may search</b>, chosen BEFORE the search.
 *
 * <p>The lane was a nullable string that meant "every lane" when it was absent. That null is why
 * the lane came to be applied in four separate places AFTER the search, each added later to plug
 * a leak the one before it left: a row ranked twelfth behind eleven rows of another lane never
 * arrived, because the cut to eleven was taken over the whole store before any lane was looked
 * at. A scope cannot be empty, and it is built only from lane names that exist, so there is no
 * value left that means "whatever matched".</p>
 *
 * <p>His words are accepted as names: {@code lessons} is the experience lane and {@code business}
 * is the domain lane (the two he used when ruling on the lanes, 2026-10-17). The wire name
 * remains the one stored in {@code experience_entry.lane}.</p>
 *
 * <p>There is no scope the engine chooses for a caller. A call that names no lane is refused with
 * the list of lanes, so every scope in the system was written down by whoever asked.</p>
 *
 * @param lanes the lanes to search, in lane order; never empty
 */
public record LaneScope(Set<KnowledgeLane> lanes) {

    /** What a caller sees when a call names no lane, or a lane that does not exist. */
    public static final class Refused extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    public LaneScope {
        if (lanes == null || lanes.isEmpty()) {
            throw new Refused("A call must name at least one lane. " + known());
        }
        lanes = java.util.Collections.unmodifiableSet(EnumSet.copyOf(lanes));
    }

    /** The lanes a call may ask for, as one sentence a refusal can carry. */
    public static String known() {
        List<String> parts = new ArrayList<>();
        for (KnowledgeLane lane : KnowledgeLane.values()) {
            String alias = aliasOf(lane);
            parts.add(alias == null ? lane.wire() : lane.wire() + " (also: " + alias + ")");
        }
        return "Lanes: " + String.join(", ", parts) + ". Name them as lane=<one> or lanes=<a,b>.";
    }

    private static String aliasOf(KnowledgeLane lane) {
        return switch (lane) {
            case EXPERIENCE -> "lessons";
            case DOMAIN -> "business";
            default -> null;
        };
    }

    /** A scope of exactly these lanes, named by the caller. */
    public static LaneScope of(KnowledgeLane first, KnowledgeLane... rest) {
        EnumSet<KnowledgeLane> set = EnumSet.of(first, rest);
        return new LaneScope(set);
    }

    /**
     * Every lane of KNOWLEDGE, named by the caller. The transcript is not knowledge written down
     * but the discussion kept (Sprint 28g S21), asked on three occasions only, so a call that
     * means it says {@code transcript} and "all" never reaches it.
     */
    public static LaneScope everyLane() {
        return new LaneScope(EnumSet.complementOf(EnumSet.of(KnowledgeLane.TRANSCRIPT)));
    }

    /**
     * Parse what a caller wrote: one lane, or several separated by commas.
     *
     * @param text the caller's words
     * @return the scope they named
     * @throws Refused when the text is blank or names a lane that does not exist
     */
    public static LaneScope parse(String text) {
        if (text == null || text.isBlank()) {
            throw new Refused("A call must name at least one lane. " + known());
        }
        Set<KnowledgeLane> set = EnumSet.noneOf(KnowledgeLane.class);
        for (String raw : text.split(",")) {
            String name = raw.strip().toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            if (name.equals(ALL)) {
                set.addAll(everyLane().lanes());
                continue;
            }
            set.add(byName(name));
        }
        return new LaneScope(set);
    }

    /** Parse the several names of a {@code lanes} list. */
    public static LaneScope parse(Collection<String> names) {
        return parse(names == null ? null : String.join(",", names));
    }

    /** The explicit name for every lane at once; a caller still has to write it. */
    public static final String ALL = "all";

    private static KnowledgeLane byName(String name) {
        for (KnowledgeLane lane : KnowledgeLane.values()) {
            if (lane.wire().equals(name) || name.equals(aliasOf(lane))) {
                return lane;
            }
        }
        throw new Refused("'" + name + "' is not a lane. " + known());
    }

    /** Whether this scope searches {@code lane}. */
    public boolean contains(KnowledgeLane lane) {
        return lanes.contains(lane);
    }

    /**
     * One value per lane in this scope, in lane order — the per-lane gather the retrieval used to
     * write out by hand at each site (a count per lane, a score map per lane, an empty bucket per
     * lane). The scope owns its lanes, so the walk over them is its behaviour and not a loop
     * repeated in its callers.
     *
     * @param valueOf computes the value for one lane
     * @return the values keyed by lane, in lane order
     */
    public <T> java.util.Map<KnowledgeLane, T> byLane(java.util.function.Function<KnowledgeLane, T> valueOf) {
        java.util.Map<KnowledgeLane, T> out = new java.util.LinkedHashMap<>();
        for (KnowledgeLane lane : lanes) {
            out.put(lane, valueOf.apply(lane));
        }
        return out;
    }

    /** The wire names of the lanes, in lane order. */
    public List<String> wires() {
        List<String> out = new ArrayList<>();
        for (KnowledgeLane lane : lanes) {
            out.add(lane.wire());
        }
        return out;
    }
}
