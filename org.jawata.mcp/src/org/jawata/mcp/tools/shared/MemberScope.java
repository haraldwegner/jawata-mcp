package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.text.edits.MultiTextEdit;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Narrow a whole-file rewrite to the ONE MEMBER a caller pointed at.
 *
 * <h2>The clause this exists for</h2>
 *
 * <p>Sprint 28d-rescue's per-row contract says every operation must be "callable straight
 * from the finding that names it, by symbol name and by file position". A C3 audit found
 * that clause unmet on all nine rows for one reason: {@code apply_cleanup} published
 * {@code kind} and {@code filePath} and nothing else, so a finding pointing at one method
 * rewrote every occurrence in the file. A caller who asked about one method got the file.</p>
 *
 * <h2>Why the narrowing is here and not in the ten rules</h2>
 *
 * <p>Because it is the same narrowing every time. A rule computes the edits a file needs;
 * which of them the caller asked for is a property of the caller's position, not of the
 * rewrite. Pushing it into the rules would put ten copies of one range test behind ten
 * chances to get it subtly different — and the rules are exactly where this codebase has
 * already paid for duplicated predicates.</p>
 *
 * <p>This works because every Stage 3 rewrite is member-local: each rule's edits for a
 * method lie inside that method. A future rule that edits across members must not use
 * this, and would need its own answer — which is the honest limit rather than a hidden
 * one.</p>
 *
 * <h2>What "inside" means, and the one boundary case</h2>
 *
 * <p>A leaf edit is kept when its whole range lies within the member's source range. A
 * zero-length INSERT exactly at the member's end offset is kept too: it is an addition
 * the rewrite makes to that member's last line, and dropping it would leave the rest of
 * the same rewrite behind — a half-applied edit is worse than none.</p>
 */
public final class MemberScope {

    private MemberScope() {
    }

    /**
     * The part of {@code edit} that falls inside the member at the given position.
     *
     * @return a restricted edit, or {@code null} when the position names no member or the
     *         rewrite touches nothing inside it — and null is the sweep's own word for
     *         "read it, nothing to change here", so the caller needs no new branch
     */
    public static TextEdit restrict(TextEdit edit, CompilationUnit ast, int line, int column) {
        if (edit == null) {
            return null;
        }
        int offset = ast.getPosition(line + 1, Math.max(column, 0));
        if (offset < 0) {
            return null;
        }
        BodyDeclaration member = enclosingMember(new NodeFinder(ast, offset, 0).getCoveringNode());
        if (member == null) {
            return null;
        }
        int start = member.getStartPosition();
        int end = start + member.getLength();

        List<TextEdit> kept = new ArrayList<>();
        collectLeavesWithin(edit, start, end, kept);
        if (kept.isEmpty()) {
            return null;
        }
        MultiTextEdit restricted = new MultiTextEdit();
        for (TextEdit leaf : kept) {
            restricted.addChild(leaf.copy());
        }
        return restricted;
    }

    /** The declaration the position sits in — a method, a field, or a nested type. */
    private static BodyDeclaration enclosingMember(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof BodyDeclaration declaration) {
                return declaration;
            }
        }
        return null;
    }

    private static void collectLeavesWithin(TextEdit edit, int start, int end, List<TextEdit> out) {
        if (edit.hasChildren()) {
            for (TextEdit child : edit.getChildren()) {
                collectLeavesWithin(child, start, end, out);
            }
            return;
        }
        int from = edit.getOffset();
        int to = from + edit.getLength();
        if (from >= start && to <= end) {
            out.add(edit);
        }
    }
}
