package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.tools.shared.EncapsulationAudit;

import java.util.List;

/**
 * Sprint 28d — <b>broken encapsulation</b>, as a sweep kind. This detector adds
 * no analysis: it PROMOTES the composed audit that Sprint 22a already shipped
 * behind {@code analyze(kind="encapsulation")}, which answers only about one
 * type you already suspect. A question you must already know to ask never finds
 * the leak you did not know about, so the same computation
 * ({@link EncapsulationAudit}) is run here over every class a scan examines.
 *
 * <h2>The decision rule</h2>
 * <p>A field is flagged when its <b>poke-set is non-empty</b> — i.e. at least
 * {@code threshold} (default 1) types outside the declaring class can change its
 * value, either by writing it directly or by calling a method of the declaring
 * class whose body writes it. That second half is the whole point: it catches the
 * private-field-behind-a-public-setter leak, which a direct write search
 * ({@code find_field_writes}) reports as internal-only. Pointed refactorings:
 * <b>Encapsulate Field</b>, <b>Remove Setting Method</b>, <b>Change Value to
 * Reference</b> — or narrow the mutator's visibility.</p>
 *
 * <h2>The exclusions, and why each one is excluded</h2>
 * <ul>
 *   <li><b>Constructors are not mutators here.</b> The on-demand tool counts
 *       them, and for one named type that is defensible. In a sweep it is fatal:
 *       a constructor assigning a field would put every caller of
 *       {@code new Foo(...)} into that field's poke-set, so every field of every
 *       instantiated class in the corpus would be flagged and the kind would carry
 *       no information. Initialisation at construction is how the object comes to
 *       exist, not mutation of its encapsulated state.</li>
 *   <li><b>{@code final} fields.</b> They cannot be reassigned after
 *       construction, so no mutator can exist and any poke-set the search
 *       produces would be an artefact of a constructor write.</li>
 *   <li><b>{@code static} fields.</b> The poke-set is a statement about an
 *       object's encapsulated state. Mutable global state is a real problem, but
 *       a different one, and it already has its own kinds
 *       ({@code singleton}, {@code temporary_field}).</li>
 *   <li><b>Interfaces, enums, records and annotation types.</b> Only
 *       {@code class} declarations are visited (JDT models the other three as
 *       distinct AST nodes). A record's components are its published API by
 *       construction, and an interface's fields are constants.</li>
 *   <li><b>Fields whose type binding or Java-model element does not resolve.</b>
 *       Suppressed, and REPORTED to the scan's degradation channel — a
 *       suppressed candidate is not an absence.</li>
 * </ul>
 *
 * <p>Precision over recall in one further direction: a public non-final field
 * that nobody outside writes is <em>not</em> flagged. It is a latent leak, not an
 * actual one, and the audit reports what the corpus actually does. Widen the
 * scan (drop {@code filePath}) before reading a zero as a clean bill of health —
 * the poke-set is only as large as the code the search can see.</p>
 *
 * <p>Cost note: the audit runs two JDT searches per candidate field, so a
 * whole-corpus sweep of this kind is markedly slower than the purely structural
 * detectors. Scope it with {@code filePath} when you can.</p>
 */
public final class EncapsulationDetector extends AbstractAstDetector {

    public EncapsulationDetector() {
        super("encapsulation",
            "Broken encapsulation — a non-final, non-static field whose EFFECTIVE external "
                + "mutators number >= `threshold` (default 1): types outside the declaring class "
                + "that write it directly OR call a method of that class whose body writes it "
                + "(the private-field-behind-a-public-setter leak a direct write search misses). "
                + "Points to Encapsulate Field / Remove Setting Method. Constructors, final "
                + "fields and static fields are excluded.",
            1);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        analyze(ast, filePath, service, threshold, out, new ScanDegradation());
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out, ScanDegradation degraded) {
        int minimum = Math.max(1, threshold);
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface()) {
                    return true;
                }
                ITypeBinding binding = node.resolveBinding();
                if (binding == null) {
                    degraded.report("encapsulation candidate '" + node.getName() + "' (" + filePath
                        + ") skipped: its type binding did not resolve");
                    return true;
                }
                if (!(binding.getJavaElement() instanceof IType type)) {
                    degraded.report("encapsulation candidate '" + binding.getQualifiedName()
                        + "' skipped: the binding has no IType in the Java model, so the "
                        + "poke-set search could not run");
                    return true;
                }
                audit(node, type);
                return true;
            }

            private void audit(TypeDeclaration node, IType type) {
                List<EncapsulationAudit.FieldAudit> audits;
                try {
                    audits = EncapsulationAudit.auditType(type, service, false);
                } catch (Exception e) {
                    // A failed search is NOT "no external mutators". Suppress the
                    // verdict for this type and say so.
                    degraded.report("encapsulation audit FAILED for " + type.getElementName()
                        + ": " + e.getClass().getSimpleName()
                        + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                    return;
                }
                for (EncapsulationAudit.FieldAudit audit : audits) {
                    if (audit.isFinal() || audit.isStatic()
                        || audit.pokeSetCount() < minimum) {
                        continue;
                    }
                    out.add(new Finding("encapsulation", filePath,
                        ast.getLineNumber(node.getName().getStartPosition()), -1, "warning",
                        message(node.getName().getIdentifier(), audit),
                        node.getName().getIdentifier() + "#" + audit.field()));
                }
            }
        });
    }

    private static String message(String className, EncapsulationAudit.FieldAudit audit) {
        StringBuilder message = new StringBuilder()
            .append("Field '").append(audit.field()).append("' of class '").append(className)
            .append("' is encapsulated in name only");
        if (audit.isPrivate()) {
            message.append(" (it is private, yet)");
        }
        message.append(": ").append(audit.pokeSetCount())
            .append(" type(s) outside the class can change its value");
        if (!audit.directExternalWriters().isEmpty()) {
            message.append(" — written directly by ").append(audit.directExternalWriters());
        }
        if (!audit.externalMutatorCallers().isEmpty()) {
            message.append(" — reached through mutator(s) ").append(audit.mutatingMethods())
                .append(" called by ").append(audit.externalMutatorCallers());
        }
        return message.append(". Consider Encapsulate Field / Remove Setting Method, or narrow "
            + "the mutator's visibility so the state changes only where it is owned.").toString();
    }
}
