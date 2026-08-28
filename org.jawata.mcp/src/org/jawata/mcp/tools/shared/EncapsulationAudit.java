package org.jawata.mcp.tools.shared;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.jawata.core.IJdtService;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprint 28d — the <b>composed encapsulation audit</b>, lifted out of
 * {@code AnalyzeEncapsulationTool} so the same computation answers both the
 * on-demand {@code analyze(kind="encapsulation")} question and the sweep kind
 * {@code find_quality_issue(kind="encapsulation")}. Sprint 22a P1-c wrote it;
 * this sprint only moved it and gave it one parameter (see
 * {@code countConstructorsAsMutators}). The rule itself is unchanged.
 *
 * <p>{@code find_field_writes} alone answers "who writes this field directly",
 * which reports INTERNAL-ONLY for the classic leak: a private field guarded by
 * a public setter. The field is only ever written inside its own class (by the
 * setter), yet any external caller of that setter is effectively mutating it.
 * This audit closes the gap by composing, per field, the EFFECTIVE external
 * mutators:</p>
 *
 * <pre>
 *   poke-set(field) = { external types that write the field directly }
 *                   &cup; { external types that call a method of the class
 *                       whose body writes the field }
 * </pre>
 *
 * <p>A field with a non-empty poke-set is an encapsulation leak — external code
 * can change its value, directly or through a mutator.</p>
 *
 * <h2>Constructors: the one knob, and why it exists</h2>
 * <p>A constructor that assigns a field is, by this definition, a "mutating
 * method", so <em>every</em> external caller of {@code new Foo(...)} lands in
 * the poke-set of every field the constructor initialises. For the on-demand
 * audit that is arguably the literal answer to the question asked about one
 * named type, and {@code AnalyzeEncapsulationTool} keeps it
 * ({@code countConstructorsAsMutators = true}) so its behaviour is byte-identical
 * to Sprint 22a's. For a whole-corpus SWEEP it is useless: it flags every
 * field of every class anyone instantiates, which is nearly all of them. The
 * sweep detector therefore passes {@code false} — initialisation at
 * construction is not mutation of encapsulated state; it is how the object
 * comes to exist.</p>
 */
public final class EncapsulationAudit {

    /** Search cap, carried over unchanged from the Sprint 22a implementation. */
    private static final int MAX_MATCHES = 1000;

    private EncapsulationAudit() {
    }

    /**
     * The audit of one field: who can change it, and by which route.
     *
     * @param field                  the field's simple name
     * @param isPrivate              whether it is declared {@code private}
     * @param isFinal                whether it is declared {@code final}
     * @param isStatic               whether it is declared {@code static}
     * @param directExternalWriters  types outside the owner that assign it directly
     * @param mutatingMethods        methods OF THE OWNER whose bodies write it
     * @param externalMutatorCallers types outside the owner that call one of those
     * @param pokeSet                the union — the effective external mutators
     */
    public record FieldAudit(String field,
                             boolean isPrivate,
                             boolean isFinal,
                             boolean isStatic,
                             List<String> directExternalWriters,
                             List<String> mutatingMethods,
                             List<String> externalMutatorCallers,
                             List<String> pokeSet) {

        /** True when external code can change this field's value by some route. */
        public boolean leak() {
            return !pokeSet.isEmpty();
        }

        /** Size of the poke-set — how many distinct external types can change it. */
        public int pokeSetCount() {
            return pokeSet.size();
        }
    }

    /**
     * Audit every field declared by {@code type}.
     *
     * @param countConstructorsAsMutators whether a constructor that assigns a
     *        field counts as a mutator of it (see the class javadoc — {@code true}
     *        preserves the on-demand tool's Sprint 22a behaviour; {@code false} is
     *        what a sweep needs)
     * @throws CoreException if the Java model or a JDT search fails — a failure is
     *         propagated, never turned into an empty (and therefore clean-looking)
     *         result
     */
    public static List<FieldAudit> auditType(IType type, IJdtService service,
                                             boolean countConstructorsAsMutators)
            throws CoreException {
        List<FieldAudit> out = new ArrayList<>();
        for (IField field : type.getFields()) {
            out.add(auditField(type, field, service, countConstructorsAsMutators));
        }
        return out;
    }

    private static FieldAudit auditField(IType type, IField field, IJdtService service,
                                         boolean countConstructorsAsMutators)
            throws CoreException {
        // Direct writers of the field, partitioned into external types and
        // (internal) methods of this class that write it = the mutators.
        Set<String> directExternalWriters = new LinkedHashSet<>();
        Set<IMethod> mutators = new LinkedHashSet<>();
        for (SearchMatch write : service.getSearchService().findWriteAccesses(field, MAX_MATCHES)) {
            IType writingType = enclosingType(write);
            if (writingType != null && !sameType(writingType, type)) {
                directExternalWriters.add(writingType.getFullyQualifiedName());
                continue;
            }
            IMethod writingMethod = enclosingMethod(write);
            if (writingMethod == null || writingMethod.getDeclaringType() == null
                    || !sameType(writingMethod.getDeclaringType(), type)) {
                continue;
            }
            if (!countConstructorsAsMutators && writingMethod.isConstructor()) {
                continue;
            }
            mutators.add(writingMethod);
        }

        // External callers of the mutators = the poke set reached through setters.
        Set<String> externalMutatorCallers = new LinkedHashSet<>();
        for (IMethod mutator : mutators) {
            for (SearchMatch call : service.getSearchService()
                    .findReferences(mutator, IJavaSearchConstants.REFERENCES, MAX_MATCHES)) {
                IType callingType = enclosingType(call);
                if (callingType != null && !sameType(callingType, type)) {
                    externalMutatorCallers.add(callingType.getFullyQualifiedName());
                }
            }
        }

        Set<String> pokeSet = new LinkedHashSet<>(directExternalWriters);
        pokeSet.addAll(externalMutatorCallers);

        int flags = field.getFlags();
        return new FieldAudit(field.getElementName(),
            Flags.isPrivate(flags), Flags.isFinal(flags), Flags.isStatic(flags),
            List.copyOf(directExternalWriters),
            mutators.stream().map(IMethod::getElementName).toList(),
            List.copyOf(externalMutatorCallers),
            List.copyOf(pokeSet));
    }

    private static IType enclosingType(SearchMatch match) {
        if (match.getElement() instanceof IJavaElement element) {
            return (IType) element.getAncestor(IJavaElement.TYPE);
        }
        return null;
    }

    private static IMethod enclosingMethod(SearchMatch match) {
        if (match.getElement() instanceof IJavaElement element) {
            return (IMethod) element.getAncestor(IJavaElement.METHOD);
        }
        return null;
    }

    private static boolean sameType(IType a, IType b) {
        return a != null && b != null
            && a.getFullyQualifiedName().equals(b.getFullyQualifiedName());
    }
}
