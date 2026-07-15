package org.jawata.mcp.refactoring;

import org.eclipse.ltk.core.refactoring.Refactoring;

/**
 * The refactoring-engine seam (Sprint 25, D1). A {@code RefactoringEngine}
 * turns a fully-configured refactoring into a {@link CheckedChange}: it runs
 * that transformation's OWN preconditions and, if they permit it, produces the
 * applicable {@link org.eclipse.ltk.core.refactoring.Change}. It does not apply
 * the change and it does not verify the result — application and the
 * compile-verify gate belong to the caller
 * ({@link org.jawata.mcp.tools.AbstractApplyingRefactoringTool}).
 *
 * <h2>Port contract</h2>
 * <ul>
 *   <li><b>Input</b> — a {@link Refactoring} the caller has already configured
 *       from a resolved TARGET (a Java element or source selection) and
 *       validated PARAMS (the new name, the extracted method's name, …). In the
 *       spec's terms this is {@code propose(Target, Params)}: the caller binds
 *       target and params into the LTK refactoring and the engine takes it from
 *       there. Tool-level input validation (blank name, reserved word, missing
 *       position) is the caller's and happens before this call.</li>
 *   <li><b>Output</b> — a {@link CheckedChange}: an applicable change (with any
 *       WARNING-level advisories from the preconditions) or a refusal carrying
 *       the ERROR/FATAL precondition reasons. The change, once applied by the
 *       caller, yields the diff and undo the tool reports; verification is
 *       external.</li>
 *   <li><b>Implementations</b> — {@link JdtRefactoringEngine} is the only one;
 *       it drives JDT/LTK refactorings, the same engines behind the Eclipse
 *       IDE's Refactor menu. The seam exists so the historically hand-rolled
 *       transforms migrate onto real engines one tool at a time, without each
 *       tool re-deriving the check-conditions → create-change → collect-status
 *       protocol.</li>
 * </ul>
 */
public interface RefactoringEngine {

    /**
     * Run {@code refactoring}'s preconditions and, if they permit it, build its
     * change.
     *
     * @param refactoring a fully-configured LTK refactoring (target + params
     *                    already bound in by the caller)
     * @param label       a short human-readable summary for diagnostics/logging
     * @return an applicable {@link CheckedChange}, or a refusal with reasons;
     *         never {@code null}
     */
    CheckedChange propose(Refactoring refactoring, String label);
}
