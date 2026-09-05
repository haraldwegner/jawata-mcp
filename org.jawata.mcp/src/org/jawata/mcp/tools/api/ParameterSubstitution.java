package org.jawata.mcp.tools.api;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;

/**
 * What every row that REPLACES A PARAMETER WITH A QUERY has to establish, written once.
 *
 * <p>Rows 53 (Replace Parameter with Query) and 28 (Preserve Whole Object) do different things —
 * one removes a parameter another argument can answer for, the other removes several and adds the
 * object they were all pulled out of — but both end with the body asking a question where it used
 * to read a value, and both are then bound by the same fact about evaluation.</p>
 *
 * <h2>The rule, and it is Fowler's own caveat rather than a convenience</h2>
 *
 * <p>A parameter is evaluated ONCE, at the call. A query put in its place is evaluated once per
 * READ. Where the method changes the state the query reads, the reads answer differently and the
 * method computes something no caller asked for — and it compiles, so no gate below the row can
 * see it. Fowler says the same thing in one line: not when the query depends on state the function
 * modifies.</p>
 *
 * <p>Whether the query is pure is not decidable here, which is the reasoning row 45 gives for
 * refusing any call in a derived expression rather than trying to classify one. So the test is the
 * read count and the read's context, and the cost is stated rather than hidden: a genuinely pure
 * query read twice is refused too.</p>
 *
 * <p><b>This class exists at the moment the SECOND row needed the rule, not in advance.</b> The
 * alternative was a copy, and this repository had just finished merging five copies of a method
 * lookup that had drifted into two implementations while both were correct — which is how a
 * duplicate survives long enough to matter.</p>
 */
final class ParameterSubstitution {

    private ParameterSubstitution() {
    }

    /** Every READ of that parameter in the body — its own declaration is not one. */
    static List<SimpleName> readsOf(MethodDeclaration decl, IVariableBinding parameter) {
        List<SimpleName> found = new ArrayList<>();
        if (parameter == null || decl.getBody() == null) {
            return found;
        }
        decl.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.resolveBinding() instanceof IVariableBinding v
                    && v.isEqualTo(parameter)) {
                    found.add(node);
                }
                return true;
            }
        });
        return found;
    }

    /**
     * WHY the substitution would evaluate the query more than once, or {@code null} if it would
     * not. The answer is a phrase completing "'{@code x}' …", so a refusal names what it saw
     * rather than restating the rule.
     *
     * <p>Two shapes, and they are the same defect counted differently. SEVERAL reads become
     * several evaluations outright. ONE read inside a loop, a lambda or an anonymous class is
     * evaluated once per iteration or per invocation, which the read count alone cannot see — and
     * that second shape is not hypothetical: upstream's own instance of row 53's trigger
     * ({@code Feind.fightForTheSword}) has both at once.</p>
     */
    static String whyReadingIsRepeated(List<SimpleName> reads, MethodDeclaration decl) {
        if (reads.size() > 1) {
            return "is read " + reads.size() + " times in the body";
        }
        if (reads.size() == 1) {
            for (ASTNode n = reads.get(0); n != null && n != decl; n = n.getParent()) {
                String shape = switch (n) {
                    case org.eclipse.jdt.core.dom.WhileStatement ignored -> "a while loop";
                    case org.eclipse.jdt.core.dom.ForStatement ignored -> "a for loop";
                    case org.eclipse.jdt.core.dom.EnhancedForStatement ignored -> "a for-each loop";
                    case org.eclipse.jdt.core.dom.DoStatement ignored -> "a do-while loop";
                    case org.eclipse.jdt.core.dom.LambdaExpression ignored -> "a lambda";
                    case org.eclipse.jdt.core.dom.AnonymousClassDeclaration ignored ->
                        "an anonymous class";
                    case null, default -> null;
                };
                if (shape != null) {
                    return "is read inside " + shape + ", so its one read runs many times";
                }
            }
        }
        return null;
    }
}
