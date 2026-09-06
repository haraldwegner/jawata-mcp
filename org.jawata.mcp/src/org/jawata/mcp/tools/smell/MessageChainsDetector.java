package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.List;

/**
 * Sprint 17 (Fowler) — <b>Message Chains</b>. A navigation chain
 * {@code a.b().c().d()} longer than {@code threshold} (default 3) couples the
 * caller to the whole path. Only the outermost call of a chain is reported.
 * Pointed refactoring: <b>Hide Delegate</b>.
 */
public final class MessageChainsDetector extends AbstractAstDetector {

    public MessageChainsDetector() {
        super("message_chains",
            "Message Chains — method-call chains longer than `threshold` (default 3), e.g. "
                + "a.b().c().d(); points to Hide Delegate.",
            3);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                // Only handle the outermost invocation of a chain.
                if (node.getParent() instanceof MethodInvocation parent
                    && parent.getExpression() == node) {
                    return true;
                }
                int length = 0;
                Expression e = node;
                while (e instanceof MethodInvocation mi) {
                    length++;
                    e = mi.getExpression();
                }
                if (length > threshold) {
                    int line = ast.getLineNumber(node.getStartPosition());
                    out.add(new Finding(
                        "message_chains", filePath, line, -1, "warning",
                        "Method-call chain of length " + length + " (threshold " + threshold
                            + "). Consider Hide Delegate.",
                        enclosingSymbol(node)));
                }
                return true;
            }
        });
    }

    /**
     * WHERE THE CHAIN SITS, as an address rather than an identifier.
     *
     * <p>Hide Delegate is performed on the METHOD that reads through the chain, so that is
     * what the finding names, qualified. A chain in a field initializer has no enclosing
     * method at all — there the enclosing TYPE is the narrowest true address, and it is
     * given rather than a null, because a finding carrying no symbol is one no door can be
     * pointed at and this detector's own cure needs one.</p>
     */
    private static String enclosingSymbol(ASTNode node) {
        for (ASTNode n = node.getParent(); n != null; n = n.getParent()) {
            if (n instanceof MethodDeclaration method) {
                return SmellAddress.qualifiedOr(
                    org.jawata.mcp.models.CodeAddress.symbolOf(method.resolveBinding()),
                    method.getName().getIdentifier());
            }
            if (n instanceof TypeDeclaration type) {
                return SmellAddress.qualifiedOr(SmellAddress.owner(type),
                    type.getName().getIdentifier());
            }
        }
        return null;
    }
}
