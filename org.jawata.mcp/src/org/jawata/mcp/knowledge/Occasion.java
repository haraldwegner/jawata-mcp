package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * ARCH-C22-5 — <b>which lanes of the store each occasion asks</b>, owned by the engine.
 *
 * <p>A call names its lanes before the search ({@link LaneScope}). Every client used to spell its
 * own lane list: the hook crate holds a table, the studio and the shell templates it writes spell
 * literals, and the seat and skill texts spell lists for the agent to type. Those are mirrors
 * held together by sweeps that only DETECT drift. An occasion names the situation the call is
 * made in, and the engine answers which lanes that situation asks, so a client that sends the
 * occasion spells no lane at all and cannot drift from this table.</p>
 *
 * <p>The occasion decides the lanes; nothing guesses a lane from a message's wording, and nothing
 * here asks the code lane on a typed message. The code lane answers "do we already have something
 * that does this?" and is asked by job, never to read code.</p>
 *
 * <p>The request still accepts {@code lane} / {@code lanes} and the two forms are mutually
 * exclusive: a call that names both has said two things. A client moves from the lane list to the
 * occasion once the engine it talks to knows the occasion (a request carrying only an occasion
 * is refused by an engine that does not); the table below is what the hook crate's
 * {@code jawata_hook::lanes} mirrors until it does.</p>
 */
public enum Occasion {

    /**
     * A message the user typed: what was learned, and the accepted rules whose situation the
     * message names (Sprint 28g D45) — small enough to ask every time, and a rule recorded after
     * the build reaches the next message with no change to a client.
     */
    TYPED_MESSAGE("typed_message", KnowledgeLane.EXPERIENCE, KnowledgeLane.RULES),

    /**
     * An agent asking, in its own words, what was learned about a symbol or a symptom: the lessons
     * and nothing else. It is its own occasion because a typed message now also asks the rules
     * (Sprint 28g D45), and a seat that wants the lessons about one member does not want every rule
     * whose situation its cue happens to share.
     */
    LESSON_LOOKUP("lesson_lookup", KnowledgeLane.EXPERIENCE),

    /** A tool call naming a {@code Type#member}: the lessons about it, and what it is FOR. */
    TOOL_CALL("tool_call", KnowledgeLane.EXPERIENCE, KnowledgeLane.CODE),

    /**
     * The recall gate: records anchored at exactly the member. A job row there is a description
     * and would gate every call, so the code lane is not part of it.
     */
    RECALL_GATE("recall_gate", KnowledgeLane.EXPERIENCE, KnowledgeLane.DOMAIN),

    /** After a compaction: what he said last, so a compaction forgets nothing. */
    AFTER_COMPACTION("after_compaction", KnowledgeLane.TRANSCRIPT),

    /** A design question, asked by a seat: the lessons and the catalogued patterns. */
    DESIGN_QUESTION("design_question", KnowledgeLane.EXPERIENCE, KnowledgeLane.PATTERNS),

    /** "Does something already do this?", asked by a seat: the catalogued jobs, by job. */
    DOES_IT_EXIST("does_it_exist", KnowledgeLane.CODE);

    private final String wire;
    private final LaneScope scope;

    Occasion(String wire, KnowledgeLane first, KnowledgeLane... rest) {
        this.wire = wire;
        this.scope = LaneScope.of(first, rest);
    }

    /** The name a request carries. */
    public String wire() {
        return wire;
    }

    /** The lanes this occasion asks. */
    public LaneScope scope() {
        return scope;
    }

    /** The occasions a request may name, each with its lanes, as one sentence a refusal can carry. */
    public static String known() {
        List<String> parts = new ArrayList<>();
        for (Occasion o : values()) {
            parts.add(o.wire + " (" + String.join(", ", o.scope.wires()) + ")");
        }
        return "Occasions: " + String.join("; ", parts) + ". Name one as occasion=<name>.";
    }

    /**
     * The occasion a caller named.
     *
     * @throws LaneScope.Refused when the name is not an occasion, with the occasions listed
     */
    public static Occasion named(String name) {
        String wanted = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        for (Occasion o : values()) {
            if (o.wire.equals(wanted)) {
                return o;
            }
        }
        throw new LaneScope.Refused("'" + wanted + "' is not an occasion. " + known());
    }
}
