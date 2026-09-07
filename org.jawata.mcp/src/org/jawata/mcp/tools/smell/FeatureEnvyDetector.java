package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 17 (Fowler) — <b>Feature Envy</b>. A method that talks to a single
 * foreign type's members more than its own is "envious" of that type. Counts
 * resolved member accesses (method calls + field reads) per declaring type; if
 * one foreign type is accessed more than the method's own type AND at least
 * {@code threshold} times (default 3), flags it. Pointed refactoring:
 * <b>Move Method</b> to the envied type. Library calls ({@code java.*},
 * {@code javax.*}) and statics are ignored.
 */
public final class FeatureEnvyDetector extends AbstractAstDetector {

    /**
     * The envied type must beat the method's own type by at least this margin —
     * v1.2.1 raised the bar from "strictly greater" because the v1.2.0 dogfood
     * showed methods that barely edge their own type flooding the results
     * (test sources, now excluded by default, were the other half).
     */
    private static final int ENVY_MARGIN = 2;

    public FeatureEnvyDetector() {
        super("feature_envy",
            "Feature Envy — a method accessing one foreign type's members more than its own "
                + "(min `threshold` foreign accesses, default 3); points to Move Method.",
            3);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding mb = node.resolveBinding();
                if (mb == null || mb.getDeclaringClass() == null || node.getBody() == null) {
                    return true;
                }
                String ownType = mb.getDeclaringClass().getErasure().getQualifiedName();
                Map<String, Integer> foreign = new HashMap<>();
                int[] ownCount = {0};

                node.getBody().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(MethodInvocation mi) {
                        IMethodBinding b = mi.resolveMethodBinding();
                        if (b != null && !isStatic(b.getModifiers())) {
                            tally(declaringName(b.getDeclaringClass()), ownType, foreign, ownCount);
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(FieldAccess fa) {
                        IVariableBinding b = fa.resolveFieldBinding();
                        if (b != null && b.getDeclaringClass() != null) {
                            tally(declaringName(b.getDeclaringClass()), ownType, foreign, ownCount);
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(QualifiedName qn) {
                        if (qn.resolveBinding() instanceof IVariableBinding vb
                            && vb.isField() && vb.getDeclaringClass() != null) {
                            tally(declaringName(vb.getDeclaringClass()), ownType, foreign, ownCount);
                        }
                        return true;
                    }
                });

                // ONLY A TYPE THE METHOD COULD ACTUALLY BE MOVED TO.
                //
                // Found by dogfooding v4.1.0 on this repository: `JdtServiceImpl#addProject`
                // was reported as envying LoadedProject, and `move kind=method` — the very
                // operation the message names — answered "several possible targets: pathUtils,
                // projectImporter, searchService, workspaceManager". LoadedProject was not
                // among them, because that door moves a method onto the type of a PARAMETER or
                // a FIELD, and `addProject` does not hold a LoadedProject: it CONSTRUCTS one
                // and hands it back.
                //
                // So the finding named a destination the cure cannot accept, and a reader who
                // followed it reached a refusal. Counting accesses says a method leans on
                // another type; it does not say the method can go there, and those are
                // different claims. This is the same shape as the command-and-query detector's
                // C4 repair, where a method POPULATING an object it was about to return read as
                // a method mutating state — in both cases the object is the method's OUTPUT,
                // not a collaborator it reaches through.
                //
                // Narrowing rather than widening is deliberate: a missed smell costs a reader
                // nothing, and a false one costs them the time to disprove it.
                java.util.Set<String> reachable = new java.util.HashSet<>();
                for (Object p : node.parameters()) {
                    if (p instanceof org.eclipse.jdt.core.dom.SingleVariableDeclaration svd
                        && svd.resolveBinding() != null) {
                        addErasure(reachable, svd.resolveBinding().getType());
                    }
                }
                for (IVariableBinding f : mb.getDeclaringClass().getDeclaredFields()) {
                    addErasure(reachable, f.getType());
                }

                String enviedType = null;
                int enviedCount = 0;
                for (Map.Entry<String, Integer> e : foreign.entrySet()) {
                    if (e.getValue() > enviedCount && reachable.contains(e.getKey())) {
                        enviedCount = e.getValue();
                        enviedType = e.getKey();
                    }
                }
                if (enviedType != null && enviedCount >= threshold
                    && enviedCount >= ownCount[0] + ENVY_MARGIN) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    String name = node.getName().getIdentifier();
                    out.add(new Finding(
                        "feature_envy", filePath, line, -1, "warning",
                        "Method '" + name + "' accesses " + simpleName(enviedType) + " members "
                            + enviedCount + " times vs " + ownCount[0] + " of its own. Consider Move "
                            + "Method to " + simpleName(enviedType) + ".",
                        // ownType is the declaring class's qualified name, resolved at the
                        // top of this visit. The identifier alone names a method that could
                        // be in any class; `move kind=method` has to be told which.
                        ownType + "#" + name));
                }
                return true;
            }
        });
    }

    private static boolean isStatic(int modifiers) {
        return java.lang.reflect.Modifier.isStatic(modifiers);
    }

    /**
     * Record a declared type under the same spelling {@link #declaringName} produces.
     *
     * <p>Both sides of the reachability test must be the ERASURE's qualified name or the
     * comparison silently never matches: the tally keys on the erasure, so a field declared
     * as {@code List<Car>} recorded raw would never equal the {@code java.util.List} the
     * tally counted. A mismatch here fails SILENTLY — the type is simply never proposed —
     * which is the quiet direction and the reason it is stated rather than left to the reader.
     */
    private static void addErasure(java.util.Set<String> into, ITypeBinding type) {
        if (type != null && type.getErasure() != null) {
            String name = type.getErasure().getQualifiedName();
            if (name != null && !name.isEmpty()) {
                into.add(name);
            }
        }
    }

    private static String declaringName(ITypeBinding declaring) {
        if (declaring == null) {
            return null;
        }
        return declaring.getErasure().getQualifiedName();
    }

    private static void tally(String type, String ownType, Map<String, Integer> foreign, int[] ownCount) {
        if (type == null || type.isEmpty()) {
            return;
        }
        if (type.equals(ownType)) {
            ownCount[0]++;
        } else if (!type.startsWith("java.") && !type.startsWith("javax.")) {
            foreign.merge(type, 1, Integer::sum);
        }
    }

    private static String simpleName(String fqn) {
        int dot = fqn.lastIndexOf('.');
        return dot >= 0 ? fqn.substring(dot + 1) : fqn;
    }
}
