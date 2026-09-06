package org.jawata.mcp.refactoring;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Name;
import org.eclipse.jdt.core.dom.TagElement;
import org.eclipse.jdt.core.dom.TextElement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;

import java.util.ArrayList;
import java.util.List;

/**
 * Javadoc links to a type that a refactoring is about to DELETE.
 *
 * <p>A refactoring that removes a type leaves every {@code {@link Thattype}} in the files it
 * rewrites pointing at nothing. THE COMPILE GATE CANNOT SEE THIS: javadoc is a comment, so
 * the change compiles, passes parity, and ships a broken cross-reference that only surfaces
 * later under {@code -Xdoclint}.</p>
 *
 * <p>This exists as its own class because the fault belongs to DELETION, not to any one
 * operation. {@link org.jawata.mcp.refactoring.atoms.DeleteAtom} has THREE production callers
 * — {@code InlineClassTool} (row 17), {@code RemoveSubclassTool} (row 38) and
 * {@code CollapseHierarchyTool} (row 4) — and when this was first written inside one of them,
 * the second had the same defect and nothing said so. The third inherited the answer instead
 * of rediscovering the question, which is the whole argument for the class.</p>
 *
 * <p><b>This paragraph said "exactly two" until a C7 round-2 audit read it, and contradicted
 * itself two lines later by mentioning a third.</b> It is the same shape as the stale claim
 * that same audit round was repairing in {@code DeleteAtom} — a count about the world, written
 * in a comment, with nothing that fails when it stops being true. Fixed here by the same
 * measurement that fixed the other: {@code get_call_hierarchy} on {@code DeleteAtom#delete}.</p>
 *
 * <h2>Unwrapped, not deleted</h2>
 *
 * <p>The tag becomes the bare type name. The sentence around it stays true — the type did
 * exist, and its behaviour is now somewhere the reader can be told about — and rewriting
 * somebody's prose is not a refactoring's business.</p>
 *
 * <h2>The scope this does NOT cover, stated rather than left to be discovered</h2>
 *
 * <p>Only the files the caller is already rewriting. A {@code @link} in a file with no code
 * reference to the deleted type is not reached, because nothing in these operations opens
 * that file. Closing that would be a workspace-wide javadoc sweep and a different decision.</p>
 */
public final class DeletedTypeLinks {

    private DeletedTypeLinks() {
    }

    /**
     * Unwrap every {@code @link} to {@code typeName} in this unit, into the given rewrite.
     *
     * @return how many were unwrapped, so a caller can report it rather than assume it
     */
    public static int unwrapIn(CompilationUnit ast, String typeName, ASTRewrite rewrite) {
        List<TagElement> tags = new ArrayList<>();
        // ASTVisitor(true) — the no-argument constructor does NOT enter doc comments, so a
        // visitor written the usual way walks straight past every javadoc tag in the file
        // and reports, truthfully, that it found none.
        ast.accept(new ASTVisitor(true) {
            @Override
            public boolean visit(TagElement node) {
                if ("@link".equals(node.getTagName()) && node.fragments().size() == 1
                        && node.fragments().get(0) instanceof Name name
                        && typeName.equals(name.getFullyQualifiedName())) {
                    tags.add(node);
                }
                return true;
            }
        });
        for (TagElement tag : tags) {
            TextElement plain = ast.getAST().newTextElement();
            plain.setText(typeName);
            rewrite.replace(tag, plain, null);
        }
        return tags.size();
    }
}
