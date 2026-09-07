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
                    int start = node.getStartPosition();
                    out.add(new Finding(
                        "message_chains", filePath, ast.getLineNumber(start),
                        columnOf(ast, start), "warning",
                        "Method-call chain of length " + length + " (threshold " + threshold
                            + "). Consider Hide Delegate.",
                        enclosingSymbol(node)));
                }
                return true;
            }
        });
    }

    /**
     * THE CHAIN'S OWN COLUMN, 1-BASED — and without it this finding's cure cannot be run.
     *
     * <p>This emitted the literal {@code -1}, which reads as harmless because the finding
     * already names the enclosing method and every OTHER routed door resolves that name. Hide
     * Delegate does not: it acts on ONE chain, and a method may contain several, so it needs a
     * position and refuses a bare method symbol with <i>"no two-deep call chain at that
     * position"</i>. {@link org.jawata.mcp.models.CodeAddress#arguments()} then made the gap
     * total rather than partial — correctly, on its own rule that a line without a column is
     * not a position and half of one is worse than none — so the rendered cure carried no
     * coordinates at all while the detector was holding the exact offset.</p>
     *
     * <p><b>The +1 is the whole subtlety.</b> A finding's coordinates are 1-based and
     * {@code CodeAddress.of(Finding)} subtracts one from each; JDT's {@code getLineNumber} is
     * already 1-based, and its {@code getColumnNumber} is NOT — it is 0-based. Emitting it raw
     * would put the caret one character to the left of the chain, and worse, a chain starting
     * at column 0 would arrive as {@code 0}, which that factory reads as "not applicable" and
     * discards. Both failures compile, run, and are wrong; the same base mismatch is on record
     * in this sprint from the other direction, where a producer subtracted a line twice.</p>
     *
     * @return the 1-based column, or {@code -1} when the position does not resolve — the
     *         domain's own "not applicable", which renders CONSIDER rather than an
     *         instruction that cannot be followed
     */
    private static int columnOf(CompilationUnit ast, int startPosition) {
        int zeroBased = ast.getColumnNumber(startPosition);
        return zeroBased < 0 ? -1 : zeroBased + 1;
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
