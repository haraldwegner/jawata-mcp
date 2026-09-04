package org.jawata.mcp.tools;

/**
 * A published tool whose description is ASSEMBLED rather than written — the parts it hands
 * to {@link FrontDoorDescription}.
 *
 * <h2>Why this is not simply {@link KindedTool}</h2>
 *
 * <p>Nine of the ten front doors dispatch to one delegate per kind, and for those the two
 * roles coincide. {@code refactoring} is the tenth and it splits them. It is a
 * kind-dispatching tool by every structural test — a published {@code action} discriminator,
 * four typed delegate fields, a hand-written description — but its actions are LIFECYCLE
 * VERBS over a change the other doors produced, not transformations of code. Seven actions
 * dispatch to four delegates, so they are not per-delegate kinds, and admitting them to the
 * operation namespace would publish {@code apply} and {@code undo} as things a cure step
 * could name.</p>
 *
 * <p>So {@code refactoring} takes the DESCRIPTION seam and not the routing seam: it
 * implements this and not {@link KindedTool}. That separation is the whole reason the
 * assembler is HELD rather than inherited — under a shared superclass a door could not take
 * one half without the other, and the half it did not want would arrive anyway.</p>
 *
 * <p>It also makes {@code instanceof KindedTool} mean exactly what {@code ToolRegistry}'s
 * hand-written name exclusion means today, which is what lets that constant be retired at
 * all (Stage 9, M10).</p>
 *
 * <h2>The regions</h2>
 *
 * <p>{@link #preamble()} · a generated {@code USAGE:} line, from {@link #discriminator()} and
 * the door's published kinds, optionally continued by {@link #usageNote()} ·
 * {@link #kindBlockLeadIn()} and the per-kind block · {@link #footer()}. Every one defaults
 * to empty, so a door adopts the seam when its own migration step converts it rather than
 * every door having to move at once.</p>
 */
public interface FrontDoor extends Tool {

    /**
     * The parameter this door dispatches on — {@code kind} for most, {@code direction} for
     * {@code hierarchy}, {@code action} for {@code refactoring}.
     *
     * <p>Asked rather than assumed. A reader that hard-coded {@code kind} left
     * {@code hierarchy}'s {@code up} and {@code down} out of the operation registry entirely,
     * so a cure step could not name them; that is recorded in {@code ToolRegistry}'s own
     * comments and is why this is part of the role.</p>
     */
    String discriminator();

    /**
     * The opening paragraph — what this door is for, before any kind is named.
     *
     * <p>Defaulted empty so that a door adopts the description seam when its own step
     * converts it, rather than every door having to move at once. A door that has not
     * adopted keeps its own {@code getDescription()} and never asks the assembler.</p>
     */
    default String preamble() {
        return "";
    }

    /**
     * What follows the discriminator in the generated {@code USAGE:} line — the common
     * parameters, as a leading-comma fragment such as {@code ", filePath=..., line=..."}.
     */
    default String usageTail() {
        return "";
    }

    /**
     * Text appended DIRECTLY to the generated {@code USAGE:} line, for a door whose usage
     * section is more than one line.
     *
     * <p>{@code apply_cleanup} publishes four call shapes — whole project, one file, a
     * position, a symbol — and a paragraph explaining which narrowings a moving rewrite
     * refuses. That is a usage section, not a usage line, and the generated line is only its
     * first shape. Appended with no separator so the door can continue the same line (its
     * first shape carries a trailing annotation) or start a new one.</p>
     *
     * <p>It is a region rather than a special case in the algorithm, which is the whole
     * bargain of the held assembler: it stays one method for every door, and what differs is
     * what each door hands it.</p>
     */
    default String usageNote() {
        return "";
    }

    /**
     * A line introducing the bullet list, for the doors that have one.
     *
     * <p>{@code extract} opens its block with "Kinds and their params (all ZERO-BASED
     * coordinates):" — not a bullet, and not part of any delegate's story, so a pure
     * projection would silently drop it. It is a door-level fact and stays a door-level
     * fact; most doors have none and inherit the empty default.</p>
     */
    default String kindBlockLeadIn() {
        return "";
    }

    /**
     * The per-kind block, WRITTEN by the door rather than projected from its delegates.
     *
     * <p>A {@link KindedTool} leaves this empty and the assembler projects the block from
     * {@code delegates()} — which is what makes a kind impossible to ship undescribed, the
     * defect the whole seam is aimed at.</p>
     *
     * <p>It survives for the door that has no such projection to fall back on.
     * {@code refactoring}'s seven actions reach four delegates, so there is no one delegate
     * per action to ask, and that arity is the same fact that keeps it out of
     * {@link KindedTool}.</p>
     */
    default String kindBlock() {
        return "";
    }

    /** The closing paragraphs — the contract notes every door repeats in its own words. */
    default String footer() {
        return "";
    }
}
