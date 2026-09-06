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
     * <p>This overrides {@link Tool#publishedKinds()}, which reads the schema. The two must
     * agree, and now do by construction: every routing door puts this method's result INTO its
     * schema enum, so the schema walk and the key set are two readings of one list. The
     * temporary test that asserted their equality per door was deleted for that reason.</p>
     *
     * <p><b>That sentence was FALSE when first written, and was made true rather than
     * softened.</b> Three of the six routing doors called this method; {@code extract} and
     * {@code move} each had a private {@code kinds()} reading their {@code delegates}
     * FIELD, and {@code apply_cleanup} a package-private {@code KINDS} constant reading
     * its {@code RULES} map — three second derivations of the same key set, each over the
     * structure {@link #delegates()} wraps rather than over that method. They derived correctly, so nothing published was wrong — but a private reader
     * that happens to derive is one edit away from being the hand-written list {@code
     * generate} was caught carrying, and the deletion above was justified on the claim that
     * no such edit is possible. All three now ask this method and their own readers are
     * gone.</p>
     *
     * <p><b>One door is still compared, and it is not a leftover.</b>
     * {@code refactor_to_pattern} keeps a hand-written constant because its {@code
     * patternKinds()} is {@code static} — the cure table reads it to decide which steps name a
     * real operation, and a static method cannot reach an instance's delegates. So there the
     * two lists are genuinely independent and {@code TheRoutingTableIsTheKindListTest} still
     * compares them.</p>
     */
    @Override
    default List<String> publishedKinds() {
        return List.copyOf(delegates().keySet());
    }

    /**
     * WHICH KINDS ARE STRUCTURAL — asked of the delegates, exactly as the kind list is.
     *
     * <p>The delegates already answer this about themselves ({@link KindDelegate#isStructural()}),
     * so a door that hand-writes the set is keeping a second copy of a fact it can project. That
     * is the same argument {@link #publishedKinds()} and {@link #withDelegateParameters} make on
     * this interface, and it is made for the third time here because the cost has now been paid
     * three times.</p>
     *
     * <p><b>THIS IS A GATE, WHICH IS WHY THE DEFAULT MATTERS RATHER THAN THE TIDINESS.</b>
     * {@link Tool#structuralKinds()} feeds the architect-involvement gate. Its base default is
     * EMPTY, so a door that never overrides it publishes every one of its operations as
     * non-structural and the gate stays silent on all of them — with nothing failing, which is
     * the only reason it can go unnoticed. {@code Tool.isStructural()}'s own javadoc records
     * exactly that happening once already: the gate went quiet for pull-up, push-down and the
     * method move, and no test said so.</p>
     *
     * <p><b>It was silent on two more doors when this was written, and an architect watch
     * measured it.</b> {@code data} and {@code hierarchy} overrode nothing, so
     * {@code data kind=encapsulate_collection} — which declares itself structural — was published
     * as ordinary. {@code change_method_signature} was about to make it eleven more: ten of its
     * eleven delegates declare themselves structural and the door overrides nothing, so
     * publishing its kinds as operations (M10) would have registered eleven signature-changing
     * operations as non-structural in the same change. A widening that quietly disarms a gate is
     * worse than the gap it closes, so the two land together.</p>
     *
     * <p>Three doors — {@code extract}, {@code inline} and {@code move} — each carried a
     * near-identical copy of this loop. They are gone; this is the one they all now answer
     * through.</p>
     */
    @Override
    default java.util.Set<String> structuralKinds() {
        java.util.Set<String> structural = new java.util.LinkedHashSet<>();
        delegates().forEach((kind, delegate) -> {
            if (delegate.isStructural()) {
                structural.add(kind);
            }
        });
        return java.util.Set.copyOf(structural);
    }

    /**
     * THE BACKSTOP: every parameter any delegate declares reaches the published contract,
     * whether or not someone remembered to curate it on the door.
     *
     * <p>Call it on the properties map a door has finished curating, just before publishing:
     * {@code schema.put("properties", withDelegateParameters(properties))}.</p>
     *
     * <p><b>{@code putIfAbsent}, deliberately, and in that order.</b> The door's own entries
     * win and keep their positions, because they carry something a delegate's schema cannot:
     * which KIND each parameter belongs to. {@code extract} says "method/variable/constant:
     * zero-based start line"; {@code ExtractMethodTool} says "Path to source file." Overlaying
     * the delegates on top would publish the poorer description for five kinds in order to fix
     * the sixth. So this adds only what is MISSING, and curating an entry becomes an
     * improvement to the wording rather than the difference between a documented parameter and
     * an invisible one.</p>
     *
     * <p><b>Two parameters are skipped</b>, and they are the ones every delegate also carries:
     * {@code projectKey} and {@code auto_apply} belong to the front door, and taking them from
     * a delegate would publish one delegate's wording for a parameter that is not its own. The
     * door adds them once, in its own terms, through its wrapper helpers.</p>
     *
     * <p><b>It lives here because four doors had written it out and a fifth had not.</b>
     * {@code inline} was the fifth, and the cost is on the record: row 36's
     * {@code accessorName} reached the delegate's schema and never the door's, so it ran for
     * anyone who knew the argument name and was invisible to everyone reading
     * {@code tools/list} — the same defect, in the same shape, as {@code extract kind=class}'s
     * five parameters one sprint earlier. A backstop that each door opts into by remembering
     * is not a backstop.</p>
     */
    default Map<String, Object> withDelegateParameters(Map<String, Object> properties) {
        for (KindDelegate delegate : delegates().values()) {
            delegate.parameterSchema().forEach((name, declared) -> {
                if (!"projectKey".equals(name) && !"auto_apply".equals(name)) {
                    properties.putIfAbsent(name, declared);
                }
            });
        }
        return properties;
    }
}
