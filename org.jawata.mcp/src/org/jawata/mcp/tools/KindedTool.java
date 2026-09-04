package org.jawata.mcp.tools;

import java.util.List;
import java.util.Map;

/**
 * A FRONT DOOR: a tool that dispatches on a discriminator to one delegate per kind.
 *
 * <p>The routing table IS the kind list. That is the whole content of this interface, and it
 * is worth saying plainly because the codebase spent this sprint proving the alternative: a
 * door that holds a routing table AND a hand-written enum beside it has two homes for one
 * fact, and the second one goes stale without announcing itself. {@code extract}'s structural
 * list named two kinds while five had been added under it; {@code inline} declared none at
 * all while shipping three that change hierarchies.</p>
 *
 * <h2>What a door must be able to say</h2>
 *
 * <ul>
 *   <li>{@link #discriminator()} — the parameter it dispatches on. {@code kind} on most,
 *       {@code direction} on {@code hierarchy}, and the door is asked rather than assumed
 *       because a reader that assumed {@code kind} once made hierarchy's two operations
 *       invisible to the operation registry.</li>
 *   <li>{@link #delegates()} — kind name to delegate, in published order.</li>
 * </ul>
 *
 * <p>{@link #publishedKinds()} is then not a fact the door stores but a VIEW of the routing
 * table, which is why it is implemented here and should never be overridden: a door whose
 * published kinds could differ from what it dispatches to is precisely the defect the seam
 * removes.</p>
 *
 * <p><b>The map's value type is {@link KindDelegate}, not {@link Tool}</b>, and one door is
 * the reason: {@code apply_cleanup} dispatches to {@code CleanupRule}s, which are not tools.
 * A narrower type would have left that door outside the law with its hand-written copies
 * intact — the exception every later door could point at.</p>
 */
public interface KindedTool extends Tool {

    /**
     * The parameter this door dispatches on — {@code kind} for most, {@code direction} for
     * {@code hierarchy}.
     *
     * <p>Asked rather than assumed. A reader that hard-coded {@code kind} left
     * {@code hierarchy}'s {@code up} and {@code down} out of the operation registry entirely,
     * so a cure step could not name them; that is recorded in {@code ToolRegistry}'s own
     * comments and is why this is part of the role.</p>
     */
    String discriminator();

    /**
     * Every kind this door publishes, mapped to the delegate that performs it, in the order
     * the door publishes them.
     */
    Map<String, KindDelegate> delegates();

    /**
     * The published kinds — a VIEW of {@link #delegates()}, never a second list.
     *
     * <p>This overrides {@link Tool#publishedKinds()}, which reads the schema. For a door the
     * two must agree, and after the description seam lands the schema is itself assembled from
     * the delegates, so they agree by construction rather than by care. Until then, a
     * temporary test asserts the old schema walk and this key set are equal per door — the
     * step's own discriminating gate, and it is deleted once the assembly makes it a
     * tautology.</p>
     */
    @Override
    default List<String> publishedKinds() {
        return List.copyOf(delegates().keySet());
    }

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
     * The per-kind block, as the door writes it today.
     *
     * <p><b>TEMPORARY.</b> This is the one region still hand-written, and it exists so that
     * the algorithm can move without the published text moving in the same step. When each
     * delegate's {@code kindSummary()} carries its own bullet, this is deleted and the block
     * is projected from {@code delegates()} — at which point a kind cannot ship undescribed,
     * which is the defect the whole seam is aimed at.</p>
     */
    default String kindBlock() {
        return "";
    }

    /** The closing paragraphs — the contract notes every door repeats in its own words. */
    default String footer() {
        return "";
    }
}
