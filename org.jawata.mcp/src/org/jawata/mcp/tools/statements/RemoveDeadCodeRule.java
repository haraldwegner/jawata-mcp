package org.jawata.mcp.tools.statements;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.corext.fix.UnusedCodeFixCore;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.text.edits.TextEdit;

import java.util.HashMap;
import java.util.Map;

/**
 * Fowler — <b>Remove Dead Code</b> (row 34), a composed row on {@code apply_cleanup}.
 *
 * <p>Code nobody reaches costs every reader who has to work out that it is unreachable.
 * This removes the private members and local variables the compiler proves are never
 * used, and nothing else.</p>
 *
 * <h2>Whose analysis, and why not ours</h2>
 *
 * <p>jawata already ships an unused check — {@code find_unused_code} — and the plan's
 * recipe for this row was that check feeding the delete atom. This uses the COMPILER's
 * unused-problem set instead, and hands it to JDT's own removal. The two remain
 * independent computations of the same question, so the test takes a finding from our
 * detector and requires this rule to answer it.</p>
 *
 * <p>The reason for the deviation is that removing a member is not deleting its lines,
 * and every case below is one a node-list removal gets wrong silently:</p>
 *
 * <ul>
 *   <li>An unused local whose initializer has a SIDE EFFECT keeps the side effect — the
 *       declaration becomes a bare expression statement. Deleting the declaration would
 *       drop the call.</li>
 *   <li>A declaration with several fragments on one line is split, not truncated.</li>
 *   <li>A removed member's {@code @param} javadoc tag goes with it.</li>
 *   <li>A declaration that IS a control statement's body becomes an empty block, because
 *       {@code if (a) int x = 1;} cannot simply lose its statement.</li>
 * </ul>
 *
 * <h2>The scope is set by which warnings are enabled, not by the flags</h2>
 *
 * <p>This is worth stating precisely, because the two look interchangeable and are not.
 * JDT derives its removals from {@code CompilationUnit.getProblems()}, and it consults a
 * flag only for a problem that is actually in that set. So a flag guarding a warning the
 * compiler never raises decides nothing — it reads like a refusal and is inert.</p>
 *
 * <p>The reparse SEEDS from the project's own options and then forces two on, so the
 * reported set is the project's plus those two — not those two alone. That distinction is
 * the whole reason the three flags below differ from one another, and an earlier draft of
 * this paragraph got it wrong in both directions at once.</p>
 *
 * <p>Measured on this rule's own fixture, the parse reports exactly: unused private
 * method, unused private field, unused private type, unused local (twice), and unused
 * import. The first four come from the forced options; the import comes from the
 * project's. It does NOT report an unnecessary cast or an unused parameter, both of which
 * the fixture contains. So:</p>
 *
 * <ul>
 *   <li><b>The import refusal is real work.</b> The warning IS raised, JDT WOULD remove
 *       the import, and passing false is the only thing that stops it. That matters
 *       because {@code organize_imports} owns imports and this catalogue is explicitly
 *       non-overlapping with it — an import silently disappearing from a sweep named for
 *       dead code is a change the caller did not ask for.</li>
 *   <li><b>The cast and parameter flags refuse nothing here</b>, because their warnings
 *       are off. They stay false as a standing guard rather than an active one: a project
 *       that enables either would otherwise widen this cleanup without anybody choosing
 *       that. Their reasons are still the right reasons — an unnecessary cast is not
 *       unreachable code, and JDT's parameter removal RENAMES the method when dropping an
 *       argument would collide with an overload, which belongs to
 *       {@code change_method_signature} where a rename is the point rather than a
 *       surprise.</li>
 * </ul>
 *
 * <h2>The re-parse, which is not incidental either</h2>
 *
 * <p>The unused-member and unused-local warnings are enabled here rather than taken from
 * the project, because reading the project's settings would make this rule report
 * "nothing dead" on any project that has them switched off — a failure to look, returned
 * as an absence, which is the exact bug {@code SourceScan} exists to refuse two frames up
 * the stack.</p>
 *
 * <p>Problem detection is forced for the same reason: a resolved AST can be served from
 * cache with no problems attached, and an empty problem set is indistinguishable from
 * clean code. The cost is one extra parse per file for this kind only.</p>
 */
public final class RemoveDeadCodeRule implements CleanupRule {

    @Override
    public String kind() {
        return "remove_dead_code";
    }

    @Override
    public String describe() {
        return "remove_dead_code    — Remove Dead Code: delete private members and local variables\n"
            + "                        the compiler proves are never used — methods, constructors,\n"
            + "                        fields and nested types. An unused local whose initializer has\n"
            + "                        a side effect KEEPS the side effect as a statement. Unused\n"
            + "                        imports are deliberately left alone: organize_imports owns\n"
            + "                        them. Casts and parameters are out of scope — an unnecessary\n"
            + "                        cast is not unreachable code, and dropping a parameter can\n"
            + "                        rename the method, which is change_method_signature's job.";
    }

    @Override
    public TextEdit edit(CompilationUnit ast) throws Exception {
        if (!(ast.getTypeRoot() instanceof ICompilationUnit unit)) {
            return null;
        }
        CompilationUnit resolved = reparseReportingUnused(unit);
        if (resolved == null) {
            return null;
        }
        ICleanUpFix fix = UnusedCodeFixCore.createCleanUp(resolved,
            /* removeUnusedPrivateMethods      */ true,
            /* removeUnusedPrivateConstructors */ true,
            /* removeUnusedPrivateFields       */ true,
            /* removeUnusedPrivateTypes        */ true,
            /* removeUnusedLocalVariables      */ true,
            /* removeUnusedImports             */ false,
            /* removeUnusedCast                */ false,
            /* removeUnusedParameter           */ false);
        return fix == null ? null : fix.createChange(new NullProgressMonitor()).getEdit();
    }

    /**
     * The same unit, parsed so that its unused-code problems are actually present.
     *
     * <p>Both switches are load-bearing. The options make the compiler REPORT unused
     * private members and locals whatever the project thinks of them, which is this rule's
     * FLOOR — never its ceiling. The map starts as the project's own options, so a project
     * reporting more (unused imports, here) hands those problems to the fix engine too,
     * and the flags in {@link #edit} are what decide whether to act on them. The forced
     * detection makes it compute the problems rather than serve a cached AST that has
     * none.</p>
     */
    private static CompilationUnit reparseReportingUnused(ICompilationUnit unit) {
        IJavaProject project = unit.getJavaProject();
        Map<String, String> options = new HashMap<>(
            project == null ? JavaCore.getOptions() : project.getOptions(true));
        options.put(JavaCore.COMPILER_PB_UNUSED_PRIVATE_MEMBER, JavaCore.WARNING);
        options.put(JavaCore.COMPILER_PB_UNUSED_LOCAL, JavaCore.WARNING);

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        parser.setCompilerOptions(options);
        parser.setForceProblemDetection(true);
        return parser.createAST(null) instanceof CompilationUnit parsed ? parsed : null;
    }
}
