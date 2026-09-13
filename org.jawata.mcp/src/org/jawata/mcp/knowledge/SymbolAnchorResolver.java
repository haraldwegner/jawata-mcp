package org.jawata.mcp.knowledge;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.IJdtService;

/**
 * Sprint 21e (item A): turns an entry's text into at most ONE grounded symbol anchor.
 *
 * Pipeline: {@link AnchorCandidates} parsing → JDT resolution gate
 * ({@link IJdtService#resolveUniqueSourceType}) → TYPE-LEVEL dominance by mention count
 * (strict max wins; tie or zero → no anchor) → the member is appended only when the
 * dominant type's mentions agree on exactly one member AND that member exists on the
 * resolved type. Candidates that resolve to the same FQN merge ("SlotManager" and
 * "pipeline.SlotManager" are one type), so qualification never splits dominance.
 *
 * <p>Instances are SHORT-LIVED — create one per load/backfill run: the token→type memo
 * spans a run (an entry corpus repeats the same tokens) but must not outlive project
 * loads/removals.</p>
 */
final class SymbolAnchorResolver {

    private final Supplier<IJdtService> service;
    /** token → resolved unique source type; containsKey with null value = memoized miss. */
    private final Map<String, IType> memo = new HashMap<>();

    SymbolAnchorResolver(Supplier<IJdtService> service) {
        this.service = service;
    }

    /** Resolve {@code text} to its one grounded anchor ("pkg.Type" or "pkg.Type#member"), or empty. */
    Optional<String> resolve(String text) {
        List<AnchorCandidates.Candidate> candidates = AnchorCandidates.extract(text);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }
        IJdtService svc = service == null ? null : service.get();
        if (svc == null) {
            return Optional.empty();
        }

        Map<String, Resolved> byFqn = new LinkedHashMap<>();
        for (AnchorCandidates.Candidate c : candidates) {
            IType t = memoizedResolve(svc, c.typeToken());
            if (t == null) {
                continue;
            }
            String fqn;
            try {
                fqn = t.getFullyQualifiedName('.');
            } catch (Exception e) {
                continue;
            }
            Resolved r = byFqn.computeIfAbsent(fqn, k -> new Resolved(t));
            r.mentions += c.mentions();
            for (String m : c.members()) {
                if (!r.members.contains(m)) {
                    r.members.add(m);
                }
            }
        }
        if (byFqn.isEmpty()) {
            return Optional.empty();
        }

        String dominantFqn = null;
        Resolved dominant = null;
        boolean tie = false;
        for (Map.Entry<String, Resolved> e : byFqn.entrySet()) {
            if (dominant == null || e.getValue().mentions > dominant.mentions) {
                dominant = e.getValue();
                dominantFqn = e.getKey();
                tie = false;
            } else if (e.getValue().mentions == dominant.mentions) {
                tie = true;
            }
        }
        if (tie) {
            return Optional.empty();
        }
        if (dominant.members.size() == 1 && memberExists(dominant.type, dominant.members.get(0))) {
            return Optional.of(dominantFqn + "#" + dominant.members.get(0));
        }
        return Optional.of(dominantFqn);
    }

    private IType memoizedResolve(IJdtService svc, String token) {
        if (memo.containsKey(token)) {
            return memo.get(token);
        }
        IType t;
        try {
            t = svc.resolveUniqueSourceType(token);
        } catch (Exception e) {
            t = null;
        }
        memo.put(token, t);
        return t;
    }

    private static boolean memberExists(IType type, String member) {
        return memberOn(type, member) != null;
    }

    /**
     * The field or method named {@code member} on {@code type}, or null.
     *
     * <p><b>Its one caller is {@link #memberExists}, which discards the element</b> — the
     * paragraph explaining why this returns an {@code IMember} rather than a boolean has moved
     * to {@link #memberOnOrThrow}, which is the method that answer is for. A C9 round found it
     * here, describing a caller this method no longer has.</p>
     *
     * <p>A field wins over a method of the same name, which Java permits; the anchor form
     * {@code Type#name} cannot tell them apart, and the field is the cheaper lookup.</p>
     *
     * <h2>THE TWO CALLERS NEED DIFFERENT ANSWERS, and this method gives one of them</h2>
     *
     * <p>An unreadable type answers null here rather than throwing, and the javadoc used to
     * justify that with "a member that cannot be read is, <b>for every caller here</b>, a
     * member that is not there". That was true when the only caller was {@link #memberExists},
     * an auto-anchoring heuristic where declining to anchor beats anchoring wrongly. Stage 7
     * added a second caller — {@code ExperienceRetrieval.resolvePointer}, which backs a
     * user-facing REFUSAL — and for that one the merge is exactly the sprint's own defect: a
     * member lookup that FAILED would be reported to an author as "the anchored member is
     * gone", which is "I could not check" rendered as "it does not exist".</p>
     *
     * <p>A C9 audit demonstrated it rather than arguing it: with a throw injected here, the
     * control case whose member provably EXISTS was refused with that absence wording. So the
     * strict form below is what a refusal asks, and this forgiving one stays exactly as it was
     * for the heuristic that wants it. {@code IType.getMethods()} declares
     * {@code JavaModelException}, so this is a live failure mode and not a theoretical one.</p>
     */
    static IMember memberOn(IType type, String member) {
        try {
            return memberOnOrThrow(type, member);
        } catch (Exception e) {
            // Deliberately swallowed FOR THIS CALLER ONLY: memberExists is choosing whether to
            // narrow an anchor nobody asked for down to a MEMBER, and there an unreadable type
            // must decline rather than guess. A caller that REFUSES on the answer must use the
            // strict form.
            //
            // "Decline" is the member, not the anchor — corrected at C9 round 4, which read
            // what `resolve()` actually does with a false answer: it falls through and returns
            // the bare TYPE anchor. So the cost of swallowing here is a coarser anchor, never
            // an absent one, which is what makes the trade right for this caller and wrong for
            // a refusal.
            return null;
        }
    }

    /**
     * The same lookup, but a failure to read is a failure rather than an absence.
     *
     * <p>Answers null only when the type was read and carries no such member. Anything that
     * stopped the lookup from completing propagates, so a caller can tell the two apart —
     * which is the whole distinction this sprint is about, and which {@link #memberOn} merges
     * on purpose for its own caller.</p>
     *
     * <p>The same split already exists one layer down and for the same stated reason:
     * {@code JdtServiceImpl.findType} tracks a failed model lookup and throws rather than
     * answering null, because "reporting null would let the caller claim an absence over a
     * lookup that never completed". This is that rule applied to the member half.</p>
     *
     * <p><b>It hands back the ELEMENT rather than a yes</b> because
     * {@code ExperienceRetrieval.resolvePointer} needs the member itself — a job's live
     * location is the member's line and not its type's — and the alternative was a second copy
     * of this lookup one class away. This repository has paid for that alternative repeatedly:
     * six private copies of one type lookup, five of one declaration lookup, and the recorded
     * lesson is that a cure written where only its own file can reach it removes the one
     * instance that would have made the class visible.</p>
     *
     * <p><b>"No such member" means no such FIELD OR METHOD</b>, which is what the two lookups
     * below consult. A nested TYPE written as {@code pkg.Outer#Inner} therefore answers null
     * and reads as absent. That is pre-existing rather than new — the forgiving form has always
     * consulted exactly these two — and unreachable from automatic anchoring, which never emits
     * that shape; it is stated because the caller turns this answer into a refusal that names
     * the member.</p>
     */
    static IMember memberOnOrThrow(IType type, String member) throws JavaModelException {
        IField f = type.getField(member);
        if (f.exists()) {
            return f;
        }
        for (IMethod m : type.getMethods()) {
            if (member.equals(m.getElementName())) {
                return m;
            }
        }
        return null;
    }

    private static final class Resolved {
        final IType type;
        int mentions;
        final List<String> members = new ArrayList<>();

        Resolved(IType type) {
            this.type = type;
        }
    }
}
