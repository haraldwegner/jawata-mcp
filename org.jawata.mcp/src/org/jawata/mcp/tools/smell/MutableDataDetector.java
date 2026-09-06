package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Fowler (2nd ed. ch.3) — <b>Mutable Data</b>, in the one shape that has no setter to
 * find.
 *
 * <p>A method hands back a field that is a collection, a map or an array, by name and
 * unwrapped. Every caller now holds the object's internal state and can change it, and
 * the class it belongs to will never know. There is no mutator to narrow, no setter to
 * remove, and a search for writes to the field finds only the class's own — which is
 * exactly why this needs its own reading.</p>
 *
 * <h2>How this differs from {@code encapsulation}, which is the neighbouring kind</h2>
 *
 * <p>They answer different questions and neither subsumes the other, so a reader should
 * know which one spoke:</p>
 *
 * <ul>
 *   <li>{@code encapsulation} asks WHO WRITES: it searches the corpus for types outside
 *       the class that assign a field or call a method that assigns it. It is a
 *       statement about what the code actually does, it costs two JDT searches per
 *       field, and it is silent about a leak nobody has exploited yet.</li>
 *   <li>This kind asks WHAT IS HANDED OUT: it reads one method body and needs no search
 *       at all. It reports the leak whether or not anyone has taken it, because the
 *       accessor IS the defect — a caller that mutates the returned list is doing
 *       nothing wrong, and the class published the opportunity.</li>
 * </ul>
 *
 * <h2>What counts as a leak, and what deliberately does not</h2>
 *
 * <p>Only a BARE return of the field matches: {@code return items;} or
 * {@code return this.items;}. Anything wrapped is not a finding and needs no special
 * case to exclude, because it is not a bare name — {@code Collections.unmodifiableList},
 * {@code List.copyOf}, {@code new ArrayList<>(items)}, {@code items.clone()} and
 * {@code Arrays.copyOf} all fall out on their own. That is the point of matching the
 * shape rather than reasoning about it: the safe forms are safe BECAUSE they are not
 * this shape.</p>
 *
 * <p>Private methods are skipped — a leak to your own class is not a leak. Static
 * fields are skipped as well: static mutable state is a real problem with its own kind,
 * {@code global_data}, and reporting it twice under two names would make a reader fix
 * it once and see it again.</p>
 *
 * <p>Cure: <b>Encapsulate Collection</b> — return a read-only view and put the adds and
 * removes on the owning class, where they can be seen.</p>
 */
public final class MutableDataDetector extends AbstractAstDetector {

    public MutableDataDetector() {
        super("mutable_data",
            "Mutable Data — a non-private method returns a collection, map or array FIELD "
                + "directly and unwrapped, so every caller can change the object's internal "
                + "state and the object never finds out. A wrapped or copied return "
                + "(unmodifiableList / copyOf / new ArrayList<>(..) / clone) is not this "
                + "shape and is not reported. Points to Encapsulate Collection. `threshold` "
                + "is the minimum number of such accessors on one class before it is "
                + "reported (default 1). Distinct from `encapsulation`, which searches for "
                + "types that actually WRITE a field; this reads what a class hands out.",
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
                // name -> the leak sentence, in declaration order.
                Map<String, String> leaks = new LinkedHashMap<>();
                List<Integer> lines = new ArrayList<>();
                for (MethodDeclaration method : node.getMethods()) {
                    String leak = leakOf(method, degraded, filePath);
                    if (leak != null) {
                        leaks.put(method.getName().getIdentifier(), leak);
                        lines.add(ast.getLineNumber(method.getName().getStartPosition()));
                    }
                }
                if (leaks.size() < minimum) {
                    return true;
                }
                // THE OWNER IS THE BINDING'S QUALIFIED NAME. `MutableDataTargets#getItems`
                // reads fine and is not an address: a simple type name is shared by every
                // package that uses it, so nothing can look the member up. The cure this
                // finding names is an operation, and an operation has to be pointed
                // somewhere.
                org.eclipse.jdt.core.dom.ITypeBinding owner = node.resolveBinding();
                String qualified = owner == null ? null
                    : org.jawata.mcp.models.CodeAddress.symbolOf(owner);
                String prefix = qualified == null ? node.getName().getIdentifier() : qualified;
                int i = 0;
                for (Map.Entry<String, String> e : leaks.entrySet()) {
                    out.add(new Finding("mutable_data", filePath, lines.get(i++), -1, "warning",
                        "Method '" + e.getKey() + "' of class '"
                            + node.getName().getIdentifier() + "' " + e.getValue()
                            + " Every caller can change the object's state through it, and the"
                            + " object never finds out — there is no setter here to remove."
                            + " Consider Encapsulate Collection: return a read-only view and"
                            + " put the modifications on this class.",
                        prefix + "#" + e.getKey()));
                }
                return true;
            }
        });
    }

    /** The leak sentence for a method that hands out a mutable field, else null. */
    private static String leakOf(MethodDeclaration method, ScanDegradation degraded,
                                 String filePath) {
        if (Modifier.isPrivate(method.getModifiers()) || method.getBody() == null) {
            return null;
        }
        String[] found = new String[2];   // [0] field name, [1] mutable type name
        method.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                if (found[0] != null) {
                    return false;
                }
                IVariableBinding field = returnedField(node);
                if (field == null) {
                    return false;
                }
                if (Modifier.isStatic(field.getModifiers())) {
                    // global_data owns this one; two names for one fix is worse than one.
                    return false;
                }
                String mutable = MutableTypes.nameIfMutable(field.getType());
                if (mutable != null) {
                    found[0] = field.getName();
                    found[1] = mutable;
                }
                return false;
            }
        });
        if (found[0] == null) {
            return null;
        }
        return "returns the " + found[1] + " field '" + found[0] + "' directly.";
    }

    /**
     * The FIELD a return statement hands back bare, or null.
     *
     * <p>A bare {@code SimpleName} or {@code this.name}. Anything else — a call, a
     * construction, a conditional — is not this shape, which is how every safe wrapping
     * excludes itself without being listed.</p>
     */
    private static IVariableBinding returnedField(ReturnStatement node) {
        IVariableBinding binding = switch (node.getExpression()) {
            case SimpleName name when name.resolveBinding() instanceof IVariableBinding v -> v;
            case FieldAccess access -> access.resolveFieldBinding();
            case null, default -> null;
        };
        return binding != null && binding.isField() ? binding : null;
    }
}
