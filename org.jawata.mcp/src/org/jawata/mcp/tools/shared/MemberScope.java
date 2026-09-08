package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.text.edits.CopySourceEdit;
import org.eclipse.text.edits.CopyTargetEdit;
import org.eclipse.text.edits.MoveSourceEdit;
import org.eclipse.text.edits.MoveTargetEdit;
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
 * <p>The premise once written here — "every Stage 3 rewrite is member-local, so each
 * rule's edits for a method lie inside it" — was asserted before it was checked, and it
 * is FALSE for five of the eight. Those five move code, and a move reaches past the
 * member by construction. They are refused with a reason rather than narrowed; three rows
 * can actually be confined. Writing the per-row test an audit asked for is what turned
 * that sentence from an assumption into a measurement.</p>
 *
 * <h2>What "inside" means, and the pairing that nearly broke it</h2>
 *
 * <p>A leaf edit is kept when its whole range lies within the member's source range.</p>
 *
 * <p><b>Except that not every leaf stands alone.</b> A rewrite that MOVES code emits a
 * pair — a source edit marking what travels and a target edit marking where it lands —
 * and keeping one without the other produces a target with nothing to insert. The first
 * version of this class treated every leaf as independent and did exactly that: four of
 * the eight sweep rows died with {@code MalformedTreeException: No source edit provided}
 * the moment a per-row test asked them to narrow. The class javadoc had warned about
 * half-applied edits in the paragraph above; the code did not implement the warning.</p>
 *
 * <p>So a pair whose halves fall on opposite sides of the member boundary is not
 * something to narrow. It is a rewrite that genuinely reaches outside the member the
 * caller named, and the honest answer is to REFUSE and say so — not to drop half of it,
 * and not to apply the whole thing to a file the caller asked about one method of.</p>
 */
public final class MemberScope {

    private MemberScope() {
    }

    /**
     * The reason a FIELD cannot be narrowed to this rule, or {@code null} — mcp#76.
     *
     * <p><b>The case, and why it is not an absence.</b> A caller pointed
     * {@code remove_dead_code} at {@code FindLargeClassesTool#log} — a field the compiler
     * itself reports unused. The rule rewrites unreachable STATEMENTS, so it correctly
     * produced nothing, and the sweep then answered {@code hasChanges: false} carrying its
     * own sentence: <i>"the scan was COMPLETE (1 file(s) examined), so this is a real absence,
     * not a failure to look."</i> True of a sweep that found nothing, false here.</p>
     *
     * <p>That sentence separates <i>found nothing</i> from <i>could not look</i>. This is a
     * third case and neither: <i>looked, and cannot act on this kind of target</i>. Because
     * the address was ACCEPTED, the caller had no signal anything was declined — and the
     * wording ruled out the one explanation that was true. A refusal at least says the case
     * was declined; this asserted the opposite, which is what makes it worse.</p>
     *
     * <p>The rule is asked rather than guessed at — see {@code CleanupRule.actsOnFields()}.
     * The message names the field and points at the two operations that CAN remove a member,
     * so the caller is handed the next step instead of a dead end.</p>
     *
     * @param actsOnFields what the rule itself says about whether it can touch a field
     * @return the refusal, or {@code null} when the target is not a field or the rule handles one
     */
    public static String refuseFieldTarget(CompilationUnit ast, int line, int column,
                                           String kind, boolean actsOnFields) {
        if (actsOnFields) {
            return null;
        }
        int offset = ast.getPosition(line + 1, Math.max(column, 0));
        if (offset < 0) {
            return null;
        }
        BodyDeclaration member = enclosingMember(new NodeFinder(ast, offset, 0).getCoveringNode());
        if (!(member instanceof org.eclipse.jdt.core.dom.FieldDeclaration)) {
            return null;
        }
        return "the target is a FIELD, and " + kind + " rewrites statements — it can never act"
            + " on one, so reporting 'nothing to clean up' here would state an absence about a"
            + " case that was not handled. Removing an unused field is not something any"
            + " cleanup does: use inline kind=variable where the reads can be folded in, or"
            + " remove it with the declaration's own edit.";
    }

    /**
     * The part of {@code edit} that falls inside the member at the given position.
     *
     * @return a restricted edit, or {@code null} when the position names no member or the
     *         rewrite touches nothing inside it — and null is the sweep's own word for
     *         "read it, nothing to change here", so the caller needs no new branch
     */
    public static Result restrict(TextEdit edit, CompilationUnit ast, int line, int column) {
        if (edit == null) {
            return Result.nothingToDo();
        }
        int offset = ast.getPosition(line + 1, Math.max(column, 0));
        if (offset < 0) {
            return Result.nothingToDo();
        }
        BodyDeclaration member = enclosingMember(new NodeFinder(ast, offset, 0).getCoveringNode());
        if (member == null) {
            return Result.nothingToDo();
        }
        int start = member.getStartPosition();
        int end = start + member.getLength();

        List<TextEdit> inside = new ArrayList<>();
        List<TextEdit> outside = new ArrayList<>();
        partition(edit, start, end, inside, outside);
        if (inside.isEmpty()) {
            return Result.nothingToDo();
        }

        // A TREE THAT MOVES CODE CANNOT BE REBUILT, and this is the part that took three
        // attempts to get right. A move is a linked pair, and TextEdit.copy() only
        // re-establishes the link when the COMMON ANCESTOR is copied — copying the halves
        // individually yields a source whose target is gone, which fails its own
        // consistency check at apply time with "No target edit provided".
        //
        // So there is no narrowing to do here, only a choice: if every edit already lies
        // inside the member, hand back the ORIGINAL tree untouched; if any lies outside,
        // this rewrite genuinely reaches past the member the caller named, and the honest
        // answer is to refuse rather than to break it or to widen silently.
        // ONLY what lies INSIDE decides. The first version also refused when a move sat
        // wholly outside the named member, which is over-broad — nothing outside is
        // copied, so an outside pair cannot be split by anything done here — and it made
        // the refusal message wrong, since that move crosses no boundary. A move whose
        // source IS inside lands in `inside`, so the straddling case is still caught.
        if (movesCode(inside)) {
            return outside.isEmpty()
                ? Result.of(edit)
                : Result.cannotConfine(
                    "This rewrite MOVES code across the boundary of the member you named,"
                        + " so it cannot be narrowed to it: half of a move is not a smaller"
                        + " change, it is a broken one. Re-run without line/column to apply"
                        + " it to the whole file, having read what it does.");
        }
        if (outside.isEmpty()) {
            return Result.of(edit);
        }
        MultiTextEdit restricted = new MultiTextEdit();
        for (TextEdit leaf : inside) {
            restricted.addChild(leaf.copy());
        }
        return Result.of(restricted);
    }

    /**
     * The outcome of narrowing: an edit to apply, nothing to do here, or a refusal.
     *
     * <p>Three states rather than a nullable edit, because "there was nothing to change in
     * that member" and "this change cannot be confined to that member" are opposite
     * answers, and collapsing them would report the second as the first — silently doing
     * nothing while reporting success.</p>
     */
    public record Result(TextEdit edit, String refusal) {

        static Result of(TextEdit edit) {
            return new Result(edit, null);
        }

        static Result nothingToDo() {
            return new Result(null, null);
        }

        static Result cannotConfine(String why) {
            return new Result(null, why);
        }
    }

    /** Does this set contain either half of a move or copy pair? */
    private static boolean movesCode(List<TextEdit> edits) {
        for (TextEdit leaf : edits) {
            if (leaf instanceof MoveSourceEdit || leaf instanceof MoveTargetEdit
                    || leaf instanceof CopySourceEdit || leaf instanceof CopyTargetEdit) {
                return true;
            }
        }
        return false;
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

    /**
     * Split the tree's edits into those inside the member and those outside it.
     *
     * <p>A move or copy SOURCE is not walked into, even though it has children: it is one
     * half of a pair and must be seen whole for {@link #movesCode} to recognise it.</p>
     */
    private static void partition(TextEdit edit, int start, int end,
                                  List<TextEdit> inside, List<TextEdit> outside) {
        boolean atomic = edit instanceof MoveSourceEdit || edit instanceof CopySourceEdit;
        if (!atomic && edit.hasChildren()) {
            for (TextEdit child : edit.getChildren()) {
                partition(child, start, end, inside, outside);
            }
            return;
        }
        int from = edit.getOffset();
        int to = from + edit.getLength();
        (from >= start && to <= end ? inside : outside).add(edit);
    }
}
