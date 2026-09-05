package org.jawata.mcp.tools.api;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.ISourceRange;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.internal.corext.refactoring.code.IntroduceParameterRefactoring;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;

/**
 * What rows 55 and 27 share: turning an expression inside a method into a parameter, through
 * JDT's own Introduce Parameter engine, with the naming done in the ONE window where it
 * survives.
 *
 * <p>The two rows differ only in WHICH expression they address — row 55 names a call, row 27
 * names a literal — and a second copy of the sequence below is what this class exists to
 * prevent. It was extracted when the second row needed it, not written in advance.</p>
 *
 * <h2>The naming window, which is the whole reason this is not three lines inline</h2>
 *
 * <p>{@code setParameterName} writes through to a {@code ParameterInfo} that JDT does not build
 * until {@code checkInitialConditions} has run — calling it before throws
 * {@link NullPointerException} out of the engine — and {@code checkFinalConditions} SNAPSHOTS
 * the name, so setting it afterwards is silently discarded and JDT's own guess ships instead.
 * The only window is BETWEEN the two checks. {@code RefactoringEngine.propose} runs
 * {@code checkAllConditions}, which is both, so it has no such window: the checks run here and
 * the BUILT change goes to the pipeline through {@code PreparedRefactoring}, leaving the
 * compile gate, the parity check and the undo handle exactly as they were.</p>
 *
 * <p>Stage 6 row 49 recorded the same shape on a different engine — a setter that must follow a
 * condition check, and is inert before it — which is what makes this a trap rather than an
 * accident of one engine.</p>
 */
final class IntroducedParameter {

    private IntroducedParameter() {
    }

    /**
     * The outcome of configuring the engine: a built change, or the status that refused it.
     *
     * @param change  the change to hand to the pipeline; null when {@code status} is fatal
     * @param status  JDT's own verdict, fatal or not
     * @param name    the parameter's final name, as the engine settled it
     */
    record Configured(Change change, RefactoringStatus status, String name) {

        boolean isRefused() {
            return status.hasFatalError() || change == null;
        }

        String refusal() {
            return status.getMessageMatchingSeverity(RefactoringStatus.FATAL);
        }
    }

    /**
     * Run the engine over {@code target}, naming the new parameter {@code parameterName} when
     * one is given, and build the change.
     *
     * @param unit          the compilation unit declaring the method
     * @param target        the expression that becomes the parameter
     * @param parameterName the caller's name for it, or null to keep JDT's guess
     */
    static Configured configure(ICompilationUnit unit, ASTNode target, String parameterName)
            throws Exception {
        IntroduceParameterRefactoring refactoring = new IntroduceParameterRefactoring(
            unit, target.getStartPosition(), target.getLength());

        RefactoringStatus status = refactoring.checkInitialConditions(new NullProgressMonitor());
        if (status.hasFatalError()) {
            return new Configured(null, status, null);
        }
        if (parameterName != null && !parameterName.isBlank()) {
            refactoring.setParameterName(parameterName);
        }
        status.merge(refactoring.checkFinalConditions(new NullProgressMonitor()));
        if (status.hasFatalError()) {
            return new Configured(null, status, null);
        }
        String settled = refactoring.getAddedParameterInfo() == null
            ? parameterName : refactoring.getAddedParameterInfo().getNewName();
        return new Configured(refactoring.createChange(new NullProgressMonitor()), status,
            settled);
    }

    /**
     * Every node inside {@code method}'s own body that {@code matches}.
     *
     * <p>The method is located by its ELEMENT's own source range rather than by searching the
     * file for its name — the identity join this sprint adopted after a name key resolved to a
     * sibling class declaring the same member. Nodes inside nested lambdas and anonymous
     * classes are INCLUDED: an expression written there is still written in this method, and
     * JDT decides for itself whether it can be lifted.</p>
     */
    static <T extends ASTNode> List<T> within(CompilationUnit ast, IMethod method,
                                              Class<T> kind,
                                              java.util.function.Predicate<T> matches)
            throws Exception {
        List<T> found = new ArrayList<>();
        ISourceRange range = method.getSourceRange();
        if (range == null || range.getOffset() < 0) {
            return found;
        }
        ASTNode declaration = NodeFinder.perform(ast, range.getOffset(), range.getLength());
        if (declaration == null) {
            return found;
        }
        declaration.accept(new org.eclipse.jdt.core.dom.ASTVisitor() {
            @Override
            public void postVisit(ASTNode node) {
                if (kind.isInstance(node) && matches.test(kind.cast(node))) {
                    found.add(kind.cast(node));
                }
            }
        });
        return found;
    }

    static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
