package org.jawata.mcp.tools;

import java.util.List;
import java.util.Map;

/**
 * A front door that ROUTES: one delegate per kind, and the routing table is the kind list.
 *
 * <p>That is the whole content of this interface, and it is worth saying plainly because the
 * codebase spent this sprint proving the alternative: a door that holds a routing table AND a
 * hand-written enum beside it has two homes for one fact, and the second one goes stale
 * without announcing itself. {@code extract}'s structural list named two kinds while five had
 * been added under it; {@code inline} declared none at all while shipping three that change
 * hierarchies.</p>
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
 *
 * <h2>Routing is the SMALLER half, and the split is deliberate</h2>
 *
 * <p>Describing itself is the other half, and it lives in {@link FrontDoor}, which this
 * extends. They were one interface until the tenth door needed one without the other:
 * {@code refactoring} publishes an {@code action} discriminator and a hand-written
 * description, but its verbs are lifecycle operations over a change some other door produced,
 * so they must not enter the operation namespace. It implements {@link FrontDoor} alone.</p>
 *
 * <p>The consequence worth knowing: {@code tool instanceof KindedTool} now means exactly what
 * {@code ToolRegistry}'s hand-written {@code refactoring} exclusion means, which is what lets
 * that constant be retired rather than merely re-spelled (Stage 9, M10).</p>
 */
public interface KindedTool extends FrontDoor {

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
}
