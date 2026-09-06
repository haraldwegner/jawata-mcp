package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.mcp.models.CodeAddress;

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
 * <p>{@link CodeAddress#symbolOf} does the rendering; this is the three ways of GETTING the
 * binding, kept in one place so a detector adds one call rather than its own resolution —
 * which is how the five copies of a type lookup this sprint already merged came about.</p>
 */
final class SmellAddress {

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
        return node == null ? null : CodeAddress.symbolOf(node.resolveBinding());
    }

    /** The qualified name of the method a node sits inside, or null if it sits in none. */
    static String enclosingSymbol(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) {
                return CodeAddress.symbolOf(method.resolveBinding());
            }
        }
        return null;
    }
}
