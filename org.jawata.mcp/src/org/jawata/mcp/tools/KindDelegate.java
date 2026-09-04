package org.jawata.mcp.tools;

import java.util.Map;

/**
 * ONE KIND OF A FRONT DOOR, able to answer for itself.
 *
 * <p>A front door dispatches on a discriminator — {@code kind} on most, {@code direction} on
 * {@code hierarchy} — to a delegate that does the work. Today the door holds a routing table
 * and, separately, a hand-written enum, a hand-written description and a hand-written set of
 * which kinds are structural; every one of those is a second home for something the delegate
 * already knows, and each has gone stale at least once. This role is what a delegate must be
 * able to say so that none of those copies is needed.</p>
 *
 * <h2>Why this is not simply {@code Tool}</h2>
 *
 * <p><b>One door's delegates are not tools at all.</b> {@code apply_cleanup} dispatches to
 * {@code CleanupRule}s, which have no name, no schema and no execute of their own. A role
 * typed to {@code Tool} would exclude that door from the seam and leave it the one place
 * where the copies must stay — which is how a law acquires an exception the next door can
 * point at. So the role is declared over what a KIND owes its door, not over what a tool is,
 * and a delegate that happens to be a {@code Tool} implements it beside its tool interface.</p>
 *
 * <p>The other direction matters too: a delegate's {@link Tool#getName()} is its own tool
 * name, which is NOT its kind. {@code ExtractMethodTool} is reached as {@code kind=method},
 * and the two strings differ on nearly every delegate in the codebase — so {@link #kindName()}
 * cannot be derived from the tool and has to be stated.</p>
 *
 * <h2>The fourth method is defaulted, and that is a decision the census forced</h2>
 *
 * <p>Whether a kind is STRUCTURAL — whether it changes a signature or a hierarchy, which is
 * what makes the architect-involvement gate fire — is declared three different ways across the
 * ten doors measured in {@code ARCHITECTURE-front-door-census.md}: per KIND on some, per TOOL
 * on others, and nowhere at all on four of them. An abstract fourth method would assume one of
 * those shapes and be wrong for every door that chose another, and it would force a value onto
 * the four doors that have never had the fact — inventing an answer where none was ever
 * given.</p>
 *
 * <p>So it defaults to false, which is the same answer those four doors give today by having
 * no override. What the default buys is the other end: a door whose kinds DO declare it can
 * derive its structural set from its delegates rather than keeping a parallel list of kind
 * names. One of those lists was found naming two kinds while five had been added under it.</p>
 */
public interface KindDelegate {

    /**
     * The discriminator value that reaches this delegate — {@code method}, {@code superclass},
     * {@code up}. NOT the delegate's own tool name, which differs on nearly all of them.
     */
    String kindName();

    /**
     * What this kind does, in the door's published description.
     *
     * <p>This is the text a client reads to choose a kind, and it is the half that has gone
     * stale most often: the delegate documents itself fully, the door repeats a summary of it
     * by hand, and only the door's copy is reachable — because the registry registers the
     * door, not the delegate. Whatever a delegate says here is what the door will say.</p>
     */
    String kindSummary();

    /**
     * The parameters this kind takes, as a JSON-schema fragment.
     *
     * <p>Empty when the kind adds nothing to the door's common parameters, which is the
     * ordinary case; a kind that needs its own argument declares it here rather than in the
     * door's hand-assembled schema.</p>
     */
    Map<String, Object> parameterSchema();

    /**
     * Does this kind change a SIGNATURE or a HIERARCHY?
     *
     * <p>Defaults to false — see the class note. False is what a door that never declared the
     * fact already answers, so defaulting invents nothing; declaring it lets a door derive
     * its structural set instead of keeping a second list of kind names beside the routing
     * table.</p>
     */
    default boolean isStructural() {
        return false;
    }
}
