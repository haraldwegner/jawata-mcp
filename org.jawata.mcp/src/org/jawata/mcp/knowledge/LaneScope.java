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
 * <p>{@link #derived} marks a scope the engine chose because the caller named none. It exists for
 * the span between this class arriving and the refusal of a call with no lane (architecture step
 * M6), and for the one place that must still say so to a caller: the note that a lane was not
 * searched.</p>
 *
 * @param lanes   the lanes to search, in lane order; never empty
 * @param derived true when the engine chose the scope rather than the caller
 */
public record LaneScope(Set<KnowledgeLane> lanes, boolean derived) {

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
        return new LaneScope(set, false);
    }

    /** Every lane, named by the caller. */
    public static LaneScope everyLane() {
        return new LaneScope(EnumSet.allOf(KnowledgeLane.class), false);
    }

    /**
     * The scope a call gets when it named none: today's rule, applied BEFORE the search. A cue
     * that names a symbol or a package asks about code and may be answered by any lane; any
     * other cue must not be answered out of the code lane.
     */
    public static LaneScope derivedFor(boolean codeShaped) {
        EnumSet<KnowledgeLane> set = EnumSet.allOf(KnowledgeLane.class);
        if (!codeShaped) {
            set.remove(KnowledgeLane.CODE);
        }
        return new LaneScope(set, true);
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
            set.add(byName(name));
        }
        return new LaneScope(set, false);
    }

    /** Parse the several names of a {@code lanes} list. */
    public static LaneScope parse(Collection<String> names) {
        return parse(names == null ? null : String.join(",", names));
    }

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

    /** The wire names of the lanes, in lane order. */
    public List<String> wires() {
        List<String> out = new ArrayList<>();
        for (KnowledgeLane lane : lanes) {
            out.add(lane.wire());
        }
        return out;
    }

    /** True when this scope names exactly one lane, and that lane is {@code lane}. */
    public boolean isOnly(KnowledgeLane lane) {
        return lanes.size() == 1 && lanes.contains(lane);
    }
}
