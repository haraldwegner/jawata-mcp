package org.jawata.mcp.tools.shared;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

/**
 * REL44-26 — a unit's compile problems, read so that they are actually present.
 *
 * <p>NOT {@code ICompilationUnit.reconcile}. Reconcile answers {@code null} for a unit that is
 * not a working copy, and in this headless server no file ever is one — so every reader that
 * took its problems from reconcile's AST saw none, on every file, and the three that did turned
 * that {@code null} into "no problems": {@code quick_fix list} answered {@code problemCount=0}
 * at a line the compiler had just reported, and both analyze kinds called a broken file clean.
 * GetDiagnosticsTool found the same fact in Sprint 21e and reads build markers instead.</p>
 *
 * <p>A parse with bindings computes the problems from the unit's current source whether or not
 * anything has it open. Problem detection is also FORCED, and that is a defensive setting, not a
 * measured need (REL44-30): with it off, the problems are still read — for a unit nobody has
 * open and for a working copy alike, which are the two shapes this server holds. The cached-AST
 * case it was written against is not shown to occur here.</p>
 */
public final class CompilationProblems {

    private CompilationProblems() {
    }

    /**
     * The unit's AST with bindings and its compile problems computed.
     *
     * @param cu the unit to read
     * @return the AST, or {@code null} when it could not be parsed at all — which a caller
     *     must report as "problems unknown", never as "no problems"
     */
    public static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setForceProblemDetection(true);
        return parser.createAST(null) instanceof CompilationUnit ast ? ast : null;
    }
}
