package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Lazy Class</b>. A concrete class that does too little
 * to justify itself: at most {@code threshold} methods (default 2) AND low
 * fan-in (referenced by at most one other type). Conservative — skips
 * interfaces, enums, abstract classes, and classes that extend a superclass or
 * implement an interface (likely strategy/impl types). Pointed refactoring:
 * <b>Inline Class</b> / Collapse Hierarchy.
 */
public final class LazyClassDetector extends AbstractAstDetector {

    /**
     * Does any method this class declares override one it inherits?
     *
     * <p>Resolved through BINDINGS rather than matched by name and arity, which is the
     * by-name-key defect this sprint closed as a class three times — a name plus an arity
     * does not identify a method, and two overloads differing only in parameter type would
     * answer for each other.</p>
     *
     * <p>Unresolvable bindings answer TRUE, i.e. "assume it overrides". That is the
     * conservative direction for a DETECTOR: a missed finding costs a reader nothing, a false
     * one costs them the time to disprove it — the same rule {@code CqsDetector} states for
     * its own approximations.</p>
     */
    private static boolean overridesSomething(TypeDeclaration node) {
        ITypeBinding binding = node.resolveBinding();
        if (binding == null) {
            return true;
        }
        for (org.eclipse.jdt.core.dom.MethodDeclaration declared : node.getMethods()) {
            org.eclipse.jdt.core.dom.IMethodBinding mb = declared.resolveBinding();
            if (mb == null) {
                return true;
            }
            if (declared.isConstructor()) {
                continue;   // a constructor overrides nothing
            }
            for (ITypeBinding parent = binding.getSuperclass();
                 parent != null; parent = parent.getSuperclass()) {
                for (org.eclipse.jdt.core.dom.IMethodBinding inherited : parent.getDeclaredMethods()) {
                    if (mb.overrides(inherited)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    public LazyClassDetector() {
        super("lazy_class",
            "Lazy Class — a concrete standalone class with <= `threshold` methods (default 2) and "
                + "fan-in <= 1; points to Inline Class. Conservative (skips interfaces/enums/abstract "
                + "and classes in a hierarchy).",
            2);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        analyze(ast, filePath, service, threshold, out, new ScanDegradation());
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out, ScanDegradation degraded) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface() || Modifier.isAbstract(node.getModifiers())) {
                    return true;
                }
                // A SUBCLASS THAT OVERRIDES NOTHING IS THE OTHER HALF OF THIS SMELL, and
                // excluding it made one of this smell's two shipped cures unreachable.
                //
                // This used to return for ANY declared supertype, as "part of a hierarchy —
                // not a lazy leaf". Measured at the v4.2.0 dogfood: RemoveSubclassTool
                // (`inline kind=subclass`) refuses exactly the complement, saying "A class
                // with no superclass is Inline Class's case". The SAME AST predicate,
                // getSuperclassType(), read with opposite polarity — so no lazy_class finding
                // could ever reach that cure, and an agent offered both was sent between two
                // operations pointing at each other. The signed spec assigns row 38 (Remove
                // Subclass) to this detector, so the route was nominal rather than reachable.
                //
                // Fowler's Lazy Element is a class not doing enough to justify itself; a
                // subclass that overrides NOTHING is the canonical case and is precisely what
                // Remove Subclass cures. A class implementing an interface must implement its
                // methods, so it overrides something and is still excluded — by the rule
                // rather than by a special case.
                if (node.getSuperclassType() != null && overridesSomething(node)) {
                    return true;
                }
                if (node.getSuperclassType() == null && !node.superInterfaceTypes().isEmpty()) {
                    return true; // an interface implementor owes its methods to the interface
                }
                if (node.getMethods().length > threshold) {
                    return true;
                }
                ITypeBinding binding = node.resolveBinding();
                if (binding == null) {
                    // The verdict on this type depends on a binding we did not get —
                    // that is a suppressed candidate, not a non-finding.
                    degraded.report("lazy_class candidate '" + node.getName() + "' (" + filePath
                        + ") skipped: its type binding did not resolve");
                    return true;
                }
                if (binding.isEnum()) {
                    return true;
                }
                // -1 = search failed → do not flag; the failure is in `degraded`.
                int fanIn = SmellSearch.referencingTypeCount(binding, service, degraded);
                if (fanIn >= 0 && fanIn <= 1) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    // THE ADDRESS IS THE BINDING'S QUALIFIED NAME. The identifier reads
                    // fine in the sentence and is not an address — a simple name is shared
                    // by every class that uses it, so nothing can look it up, and the cure
                    // this finding names is an operation that must be pointed somewhere.
                    String symbol = org.jawata.mcp.models.CodeAddress.symbolOf(
                        node.resolveBinding());
                    out.add(new Finding(
                        "lazy_class", filePath, line, -1, "warning",
                        "Class '" + name + "' has <= " + threshold + " methods and low fan-in. "
                            + "Consider Inline Class.",
                        symbol == null ? name : symbol));
                }
                return true;
            }
        });
    }
}
