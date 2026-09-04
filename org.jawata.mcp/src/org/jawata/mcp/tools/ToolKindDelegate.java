package org.jawata.mcp.tools;

import java.util.Map;

/**
 * A {@link KindDelegate} that is also a {@link Tool} — which is every delegate but one door's.
 *
 * <p>M3a's instruction is that existing delegate tools "implement it over what they already
 * hold", and this is that sentence as code: a delegate tool already publishes a description
 * and a schema, so two of the role's four methods are DERIVED here once instead of being
 * written out on each of the forty-odd delegates. Repeating the derivation per class would
 * have been forty chances for one of them to derive it differently, which is the same defect
 * this stage is removing one level up.</p>
 *
 * <p>What each delegate still states for itself is {@link KindDelegate#kindName()}, because it
 * cannot be derived: a delegate's {@link Tool#getName()} is its own tool name, and the two
 * differ on nearly every one — {@code ExtractMethodTool} is reached as {@code kind=method}.</p>
 *
 * <p><b>The kind name is also the routing map's KEY, and that is a duplication worth naming
 * rather than hiding.</b> A door maps {@code "method"} to this delegate and the delegate says
 * {@code "method"} back. The role needs it because a delegate handed to anything other than
 * its own door — a test, the publishing surface — must be able to say what kind it is without
 * the map. The two spellings are kept honest by a check that the key equals the delegate's
 * own answer, so they cannot drift apart silently; without that check this would be exactly
 * the second home for one fact that the stage exists to remove.</p>
 *
 * <p>{@code apply_cleanup}'s rules do NOT come through here — they are not tools — and they
 * implement the bare role directly. That is the whole reason the role is not typed to
 * {@code Tool}.</p>
 */
public interface ToolKindDelegate extends KindDelegate, Tool {

    /**
     * The delegate's own description, which is the fullest account of the kind that exists.
     *
     * <p>Today it is unreachable to a client: the registry registers the DOOR, so what a
     * caller reads is the door's hand-written bullet about this kind rather than this text.
     * Forwarding it here is what lets the door stop keeping that bullet.</p>
     */
    @Override
    default String kindSummary() {
        return getDescription();
    }

    /**
     * ONE answer, because both supertypes ask the same question.
     *
     * <p>{@link Tool} and {@link KindDelegate} each default {@code isStructural()}, and Java
     * refuses to inherit two unrelated defaults of one signature — so this has to be resolved
     * here rather than left implicit. It resolves TOWARD the tool's own declaration, because
     * the two are not merely same-named: both ask whether the operation changes a signature or
     * a hierarchy, which is what makes the architect-involvement gate fire. A delegate that
     * already answers that as a tool must not answer it differently as a kind.</p>
     *
     * <p>The consequence is the point of the step: a door's structural set can be DERIVED from
     * its delegates, but only once the structural delegates say so themselves. Until each one
     * declares it, deriving would yield an empty set and silence the gate — which is the exact
     * failure the hand-written sets were found in, one of them naming two kinds while five had
     * been added under it.</p>
     */
    @Override
    default boolean isStructural() {
        return Tool.super.isStructural();
    }

    /** The delegate's own parameters, read off the schema it already publishes. */
    @Override
    default Map<String, Object> parameterSchema() {
        Object properties = getInputSchema().get("properties");
        if (properties instanceof Map<?, ?> map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> typed = (Map<String, Object>) map;
            return typed;
        }
        return Map.of();
    }
}
