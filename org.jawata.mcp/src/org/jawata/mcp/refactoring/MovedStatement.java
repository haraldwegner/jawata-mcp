package org.jawata.mcp.refactoring;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * A statement's own source text, and renaming the VARIABLES in it without touching
 * anything that merely looks like one.
 *
 * <p>Rows 25 and 26 move a statement across a call boundary, and a name means something
 * different on the other side: what the callee calls {@code message} the caller passed as
 * some expression of its own. Both directions therefore rewrite names in the moved text,
 * and both were first written as a regular-expression replace over that text.</p>
 *
 * <h2>Why a word-boundary regex is wrong here, with the case that proves it</h2>
 *
 * <p>Upstream's own statement is
 * {@code LOGGER.info("Updating inventory for message: {}", message.getId());} — and the
 * word {@code message} appears INSIDE THE STRING LITERAL. A caller whose local is named
 * {@code msg} would have had its log text rewritten to "Updating inventory for msg", which
 * compiles, passes every gate, and quietly changes what an operator reads. The fork slice
 * that found this does not catch it, because upstream happens to use the same word on both
 * sides and the replacement is a no-op there; the hazard is in the mechanism, not in the
 * example.</p>
 *
 * <p>So the replacement is driven by the AST instead. Every {@link SimpleName} the parser
 * produced is spliced by its own offset, and a name is only a candidate when its binding is
 * a VARIABLE — which excludes method names, type names, and every character inside a
 * literal or a comment, because the parser never made a node for those.</p>
 */
public final class MovedStatement {

    private MovedStatement() {
    }

    /** The statement exactly as it is written in its file. */
    public static String sourceOf(String fileSource, Statement statement) {
        return raw(fileSource, statement).trim();
    }

    /**
     * The statement's source with the named VARIABLES replaced.
     *
     * @param replacements old identifier to the text that replaces it; a name absent from
     *                     the map, and every non-variable name, is left alone
     */
    public static String withVariablesRenamed(String fileSource, Statement statement,
                                              Map<String, String> replacements) {
        if (replacements.isEmpty()) {
            return sourceOf(fileSource, statement);
        }
        List<SimpleName> names = new ArrayList<>();
        statement.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                // The tail of `Foo.BAR` or `this.bar` names a member of something already
                // written down; replacing it would rewrite somebody else's expression.
                if (node.getParent() instanceof org.eclipse.jdt.core.dom.QualifiedName qualified
                        && qualified.getName() == node) {
                    return true;
                }
                if (node.getParent() instanceof org.eclipse.jdt.core.dom.FieldAccess access
                        && access.getName() == node) {
                    return true;
                }
                if (replacements.containsKey(node.getIdentifier())
                        && node.resolveBinding() instanceof IVariableBinding) {
                    names.add(node);
                }
                return true;
            }
        });
        // Spliced back to front, so an earlier replacement cannot move a later offset.
        names.sort(Comparator.comparingInt(SimpleName::getStartPosition).reversed());
        int base = statement.getStartPosition();
        StringBuilder out = new StringBuilder(raw(fileSource, statement));
        for (SimpleName name : names) {
            int at = name.getStartPosition() - base;
            out.replace(at, at + name.getLength(), replacements.get(name.getIdentifier()));
        }
        return out.toString().trim();
    }

    private static String raw(String fileSource, Statement statement) {
        return fileSource.substring(statement.getStartPosition(),
            statement.getStartPosition() + statement.getLength());
    }
}
