package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprint 17 (fork-original, §7 of {@code REFACTORING_LESSONS_LEARNED.md}) —
 * <b>Incomplete Delegation</b>. The mirror of over-delegation: code is moved
 * into an object, but the object is never given the <em>state</em> that makes it
 * the authority, so a collaborator keeps doing its job. The tell from §7 (the
 * Slot/SlotManager case): a loop over a collection whose body recovers the loop
 * element's identity via a {@code >= threshold}-deep getter chain on a
 * collaborator (e.g. {@code algo.request().symbol()}), used inside a guard —
 * an {@code O(n)} scan where the object should answer for itself. Pointed
 * refactoring: give the object its own state (its contract) + the manager a
 * {@code key -> items} index, collapsing the scan (Extract Class / Introduce
 * Index). Behaviour-preserving — §0 applies.
 */
public final class IncompleteDelegationDetector extends AbstractAstDetector {

    public IncompleteDelegationDetector() {
        super("incomplete_delegation",
            "Incomplete Delegation (fork-original, §7) — a for-each loop that recovers an element's "
                + "identity via a >= `threshold`-deep getter chain on a collaborator (default 2), inside "
                + "a guard; the O(n) scan means the object lacks its own state. Points to Extract Class / "
                + "introduce a key->items index.",
            2);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(EnhancedForStatement node) {
                String loopVar = node.getParameter().getName().getIdentifier();
                Statement body = node.getBody();

                // Roots = the loop variable plus any local derived from it by a getter
                // (e.g. `Algo algo = slot.getAlgo();`). Two passes catch transitive derivation.
                Set<String> roots = new HashSet<>();
                roots.add(loopVar);
                for (int pass = 0; pass < 2; pass++) {
                    body.accept(new ASTVisitor() {
                        @Override
                        public boolean visit(VariableDeclarationFragment vdf) {
                            if (vdf.getInitializer() instanceof MethodInvocation mi) {
                                String root = chainRoot(mi);
                                if (root != null && roots.contains(root)) {
                                    roots.add(vdf.getName().getIdentifier());
                                }
                            }
                            return true;
                        }
                    });
                }

                boolean[] hit = {false};
                body.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(IfStatement ifs) {
                        ifs.getExpression().accept(new ASTVisitor() {
                            @Override
                            public boolean visit(MethodInvocation mi) {
                                // only the outermost invocation of a chain
                                if (mi.getParent() instanceof MethodInvocation p
                                    && p.getExpression() == mi) {
                                    return true;
                                }
                                if (chainDepth(mi) >= threshold) {
                                    String root = chainRoot(mi);
                                    if (root != null && roots.contains(root)) {
                                        hit[0] = true;
                                    }
                                }
                                return true;
                            }
                        });
                        return true;
                    }
                });

                if (hit[0]) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    out.add(new Finding(
                        "incomplete_delegation", filePath, line, -1, "warning",
                        "Loop recovers an element's identity via a >= " + threshold + "-deep getter "
                            + "chain on a collaborator (the §7 tell) — an O(n) scan where the object "
                            + "should answer for itself. Give the object its own state + a key->items "
                            + "index (Extract Class).",
                        enclosingMethod(node)));
                }
                return true;
            }
        });
    }

    /** Number of chained method invocations in the receiver chain of {@code mi}. */
    private static int chainDepth(MethodInvocation mi) {
        int depth = 0;
        Expression e = mi;
        while (e instanceof MethodInvocation m) {
            depth++;
            e = m.getExpression();
        }
        return depth;
    }

    /** The simple name of the ultimate receiver of a call chain, or null if not a plain variable. */
    private static String chainRoot(MethodInvocation mi) {
        Expression e = mi;
        while (e instanceof MethodInvocation m) {
            e = m.getExpression();
        }
        return e instanceof SimpleName sn ? sn.getIdentifier() : null;
    }

    private static String enclosingMethod(ASTNode node) {
        ASTNode n = node.getParent();
        while (n != null && !(n instanceof MethodDeclaration)) {
            n = n.getParent();
        }
        return n instanceof MethodDeclaration md ? md.getName().getIdentifier() : null;
    }
}
