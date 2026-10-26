package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.mcp.tools.shared.SourceScan;

/**
 * THE ADDRESS A DETECTOR EMITS, in the three shapes detectors actually hold.
 *
 * <p>Every detector knows exactly what it found — it is holding the AST node, and usually
 * the resolved binding too. What they EMITTED was the identifier: {@code onCode},
 * {@code label}, {@code ShotgunTarget}. That reads correctly in the sentence and is not an
 * address, because a simple name is shared by every class that uses it, so nothing can look
 * it up. A finding therefore said "here is the fix" and carried nothing the fix could be
 * pointed at.</p>
 *
 * <p>{@link SourceScan#symbolOf} does the rendering; this is the three ways of GETTING the
 * binding, kept in one place so a detector adds one call rather than its own resolution —
 * which is how the five copies of a type lookup this sprint already merged came about.</p>
 */
public final class SmellAddress {

    private SmellAddress() {
    }

    /**
     * The qualified name, or the identifier when the binding did not resolve.
     *
     * <p>The fallback is a DEGRADATION and is worth naming as one: on a file whose imports
     * do not resolve there is no qualified name to give, and the identifier at least keeps
     * the message readable. It will fail {@code CodeAddress.complete()}, so the finding
     * renders CONSIDER and says the detector could not address it — which is true, and is
     * the honest outcome rather than a silently unusable instruction.</p>
     */
    static String qualifiedOr(String qualified, String identifier) {
        return qualified == null || qualified.isBlank() ? identifier : qualified;
    }

    /** The qualified name of the type being visited. */
    static String owner(TypeDeclaration node) {
        return node == null ? null : SourceScan.symbolOf(node.resolveBinding());
    }

    /**
     * WHERE A NODE SITS, as an address rather than an identifier — the one answer to "which
     * member is this in" that every emitter uses.
     *
     * <p>The narrowest enclosing METHOD, qualified. A node in a field initializer or an
     * initializer block has no enclosing method at all; there the enclosing TYPE is the
     * narrowest true address, and it is given rather than a null, because a finding carrying
     * no symbol is one no door can be pointed at and one the architect watch can only key on
     * its line — which is how a block that merely moved down a file was reported as new.</p>
     *
     * <p>Two copies of this lived in this package — one here that stopped at the method and
     * returned a null, one in the message-chains detector that fell back to the type — and the
     * architect watch's producer would have been a third. This is the survivor, with the
     * fallback the second copy had, because the null of the first is the case the watch cannot
     * key on.</p>
     *
     * @return the qualified symbol, the identifier where the binding did not resolve, or null
     *         when the node sits in no method and no type
     */
    public static String enclosingSymbol(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) {
                return qualifiedOr(SourceScan.symbolOf(method.resolveBinding()),
                    method.getName().getIdentifier());
            }
            if (at instanceof TypeDeclaration type) {
                return qualifiedOr(owner(type), type.getName().getIdentifier());
            }
        }
        return null;
    }
}
