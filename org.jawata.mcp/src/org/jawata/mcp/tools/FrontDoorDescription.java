package org.jawata.mcp.tools;

/**
 * ONE description algorithm for every front door, with the parts supplied by the door.
 *
 * <p>A front door's description is the whole documentation surface its clients can reach —
 * the registry registers the DOOR, not the delegates, so whatever a delegate says about
 * itself is unreachable and the door's text is all there is. Every door writes that text the
 * same way and writes it separately, which is why three of them were found describing kinds
 * they no longer had and omitting kinds they had gained.</p>
 *
 * <h2>HELD, not inherited — and the code decided that, not taste</h2>
 *
 * <p>The obvious shape is a base class every door extends. Java allows one superclass, and
 * {@code apply_cleanup} and {@code data} already have one each. A base class would therefore
 * have covered the doors that happened to be free and left the others outside — the same
 * "law with exceptions" this stage exists to remove, reintroduced by the mechanism meant to
 * remove it. So the assembler is an object a door HOLDS and delegates to, and inheritance is
 * left to the refactoring bases that were already using it.</p>
 *
 * <h2>The four regions</h2>
 *
 * <p>{@code preamble} · a generated {@code USAGE:} line · the per-kind block · {@code footer}.
 * Only the USAGE line is derived today: it is built from the door's own discriminator and its
 * published kinds, so a door cannot advertise a spelling it does not dispatch on, nor a kind
 * list that differs from its routing table.</p>
 *
 * <p><b>The per-kind block is still the door's own prose, and that is deliberate for this
 * step.</b> Deriving it needs each delegate's {@code kindSummary()} to carry the bullet a
 * reader sees, and moving that prose is its own migration step with its own gate. Doing both
 * at once would mean a change that alters the published text AND relocates the algorithm,
 * with no way to tell which half broke a golden file.</p>
 */
public final class FrontDoorDescription {

    /**
     * The one instance. A door holds a reference to this rather than constructing its own,
     * so "the description algorithm" is a thing with one address rather than a habit.
     */
    public static final FrontDoorDescription ASSEMBLER = new FrontDoorDescription();

    private FrontDoorDescription() {
    }

    /**
     * The door's full published description, assembled from the parts it supplies.
     *
     * <p>One blank line between regions, supplied HERE rather than by each part. A part that
     * carried its own leading or trailing blank line would make the separation a property of
     * whichever door was written last, which is how four descriptions come to be spaced three
     * different ways.</p>
     */
    public String describe(KindedTool door) {
        return door.preamble()
            + "\n\n" + usageLine(door)
            + "\n\n" + kindBlockFor(door)
            + "\n\n" + door.footer();
    }

    /**
     * The per-kind block: PROJECTED where the delegates carry their own summaries, and the
     * door's own text where they do not yet.
     *
     * <p>The fallback is what lets doors convert one at a time instead of all at once, and it
     * is decided by asking the delegates rather than by a list of converted door names — a
     * list would be one more hand-kept fact of exactly the kind being removed. A door whose
     * delegates all still answer with their own tool description has not moved its prose yet,
     * and keeps publishing what it publishes today.</p>
     */
    private String kindBlockFor(KindedTool door) {
        return door.kindBlock().isEmpty() ? kindBlockOf(door) : door.kindBlock();
    }

    /**
     * {@code USAGE: name(discriminator="<a|b|c>", tail)} — derived, never written.
     *
     * <p>The kinds come from the routing table and the spelling from the door itself, so the
     * two ways this line has gone wrong are both closed: a kind that ships without reaching
     * the line, and a line naming {@code kind} on the one door that dispatches on
     * {@code direction}.</p>
     */
    public String usageLine(KindedTool door) {
        return "USAGE: " + door.getName() + "(" + door.discriminator() + "=\"<"
            + String.join("|", door.publishedKinds()) + ">\"" + door.usageTail() + ")";
    }

    /**
     * The per-kind block, PROJECTED from the routing table — one bullet per delegate, in the
     * order the door publishes them, each carrying what that delegate says about itself.
     *
     * <p>This is what makes a kind impossible to ship undescribed: the bullet list and the
     * dispatch table become the same iteration. A kind added to the table appears here, a kind
     * removed disappears, and neither is something anyone can forget — because there is no
     * longer a separate place to forget it in.</p>
     *
     * <p><b>ONE layout rule for every door.</b> The hand-written blocks aligned their bullets
     * by eye and disagreed: {@code inline} padded its kind names to eight columns and then
     * broke its own alignment on {@code middle_man}, which is longer than the padding. Keeping
     * per-door alignment would put a column width in this class for somebody to maintain,
     * which is the kind of hand-kept constant the seam exists to remove. So the rule is: the
     * kind, an em dash, the summary, with continuation lines indented so a multi-line summary
     * stays visibly attached to its bullet.</p>
     */
    public String kindBlockOf(KindedTool door) {
        StringBuilder block = new StringBuilder();
        door.delegates().forEach((kind, delegate) -> {
            if (block.length() > 0) {
                block.append('\n');
            }
            block.append("- ").append(kind).append(" — ")
                .append(delegate.kindSummary().strip().replace("\n", "\n  "));
        });
        return block.toString();
    }
}
