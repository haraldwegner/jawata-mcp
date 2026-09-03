package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.List;

/**
 * Fowler (2nd ed. ch.3) — <b>Global Data</b>.
 *
 * <p>Fowler's complaint is not about a keyword, it is about REACH: data that can be
 * modified from anywhere, so a wrong value gives you no place to look. Java has no
 * global variables, and the smell arrives instead as static mutable state — which
 * Fowler names explicitly, calling class variables the same problem with a smaller
 * radius.</p>
 *
 * <p>Two shapes are reported, and the second is the one that hides:</p>
 *
 * <ol>
 *   <li>A static field that is not final. The reference itself can be reassigned from
 *       anywhere that can see the field.</li>
 *   <li>A static FINAL field holding something mutable — a collection, a map, an array,
 *       a date, a StringBuilder. The reference cannot move and the contents can, so
 *       {@code final} reads as a guarantee it does not give. This is the disguised
 *       global, and it is why a rule written on {@code final} alone would report the
 *       easy half.</li>
 * </ol>
 *
 * <p><b>What it deliberately does NOT report.</b> A static final constant of an
 * immutable type — a String, a boxed number, an enum constant — is not global data;
 * it is a name for a value, and flagging those would bury the real findings under
 * every constant in the codebase. Immutability is judged by the declared type, which
 * is a claim about the common cases and not a proof: an application's own immutable
 * value type is indistinguishable from a mutable one without reading it, so it is
 * treated as safe rather than reported on suspicion.</p>
 *
 * <p><b>The threshold is the REACH, not a size.</b> At the default 0 the check reports
 * static mutable state that something outside its own class can see. At 1 it also
 * reports private static mutable state, which is shared between every instance of the
 * class and is a real Fowler class-variable finding, but is bounded by one file and so
 * is a different conversation. It is opt-in because the unbounded one is what a reader
 * can act on first.</p>
 *
 * <p>Cure: <b>Encapsulate Variable</b> — put the data behind a function, so the places
 * that can change it are the places you can count.</p>
 */
public final class GlobalDataDetector extends AbstractAstDetector {


    public GlobalDataDetector() {
        super("global_data",
            "Global Data — static mutable state: a static field that is not final, or a "
                + "static final field holding a mutable collection, array or builder "
                + "(final fixes the reference, not the contents). Points to Encapsulate "
                + "Variable. `threshold` is REACH, not size: 0 (default) reports state "
                + "visible outside its class; 1 also reports private static mutable "
                + "state, which is shared between instances but bounded by one file.",
            0);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        analyze(ast, filePath, service, threshold, out, new ScanDegradation());
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out, ScanDegradation degraded) {
        boolean includePrivate = threshold >= 1;
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration node) {
                int modifiers = node.getModifiers();
                if (!Modifier.isStatic(modifiers)) {
                    return true;
                }
                if (Modifier.isPrivate(modifiers) && !includePrivate) {
                    return true;
                }
                boolean isFinal = Modifier.isFinal(modifiers);
                String reason = isFinal ? mutableContentsReason(node.getType(), degraded, filePath)
                                        : "it is static and not final, so the reference itself"
                                            + " can be reassigned from anywhere that sees it";
                if (reason == null) {
                    return true;
                }
                int line = ast.getLineNumber(node.getStartPosition());
                for (Object fragment : node.fragments()) {
                    if (!(fragment instanceof VariableDeclarationFragment f)) {
                        continue;
                    }
                    String name = f.getName().getIdentifier();
                    out.add(new Finding(
                        "global_data", filePath, line, -1, "warning",
                        "Static mutable state '" + name + "': " + reason
                            + ". A wrong value here has no single place to look for the"
                            + " write that caused it. Consider Encapsulate Variable —"
                            + " put it behind a function so the writers can be counted.",
                        name));
                }
                return true;
            }
        });
    }

    /**
     * Why a static FINAL field is still global data, or null when it is not.
     *
     * <p>An array is always mutable whatever it holds, so it is answered first and
     * without a binding. For everything else the declared type's own name decides;
     * a type that does not resolve is a suppressed candidate rather than a
     * non-finding, and says so through {@code degraded}.</p>
     */
    private static String mutableContentsReason(Type type, ScanDegradation degraded,
                                                String filePath) {
        if (type.isArrayType()) {
            return "it is a static final ARRAY — final fixes the reference and every"
                + " element stays writable";
        }
        if (type.resolveBinding() == null) {
            degraded.report("global_data candidate in " + filePath + " skipped: the type of a"
                + " static final field did not resolve, so whether its contents are mutable"
                + " could not be decided");
            return null;
        }
        // The shared judgement — mutable_data asks the same question of a method's
        // return, and two copies of the list would disagree the first time either grew.
        String simple = MutableTypes.nameIfMutable(type);
        if (simple != null) {
            return "it is a static final " + simple + " — final fixes the reference and the"
                + " contents stay writable from anywhere that sees the field";
        }
        return null;
    }
}
