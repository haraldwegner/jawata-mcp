package org.jawata.mcp.refactoring;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.Refactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The JDT implementation of {@link RefactoringEngine} — the only one. Drives an
 * LTK {@link Refactoring} through {@code checkAllConditions} →
 * {@code createChange}, mapping the result to a {@link CheckedChange}.
 *
 * <p>Refusal threshold: {@link RefactoringStatus#hasError()} (ERROR or FATAL).
 * WARNING-level advisories are not refusals — they ride along on the applicable
 * change for the caller to surface. The compile-verify gate in
 * {@link org.jawata.mcp.tools.AbstractApplyingRefactoringTool} is the backstop
 * for anything a warning-level precondition let through that nonetheless breaks
 * compilation.</p>
 */
public final class JdtRefactoringEngine implements RefactoringEngine {

    private static final Logger log = LoggerFactory.getLogger(JdtRefactoringEngine.class);

    @Override
    public CheckedChange propose(Refactoring refactoring, String label) {
        // jdt.core.manipulation reads preference nodes + code templates the IDE
        // would have initialized on activation; a headless embedder must do it
        // itself (idempotent) — the same guard ExtractMethodTool runs before
        // its engine, without which the first condition check IAEs/NPEs.
        HeadlessJdtConfig.ensureInitialized();
        try {
            RefactoringStatus status =
                refactoring.checkAllConditions(new NullProgressMonitor());
            if (status.hasError()) {
                // The engine analyzed the request and its preconditions refuse
                // it. The reasons ARE the answer — a precise refusal beats a
                // half-applied edit the compile gate would only partly catch.
                return CheckedChange.refused(status);
            }
            Change change = refactoring.createChange(new NullProgressMonitor());
            if (change == null) {
                RefactoringStatus fail = new RefactoringStatus();
                fail.addFatalError(label + ": createChange() returned null");
                return CheckedChange.refused(fail);
            }
            return CheckedChange.applicable(change, status);
        } catch (Exception e) {
            log.warn("{} engine threw: {}", label, e.getMessage(), e);
            RefactoringStatus fail = new RefactoringStatus();
            fail.addFatalError(label + ": " + e.getMessage());
            return CheckedChange.refused(fail);
        }
    }
}
