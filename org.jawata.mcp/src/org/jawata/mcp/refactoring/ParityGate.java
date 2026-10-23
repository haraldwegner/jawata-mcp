package org.jawata.mcp.refactoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 18 — the compile half of the orchestration parity gate: build the projects
 * a step touched (28k E7c: and those that require them) and report whether they are error-clean. The multi-step loop
 * (Stage 5) runs this <b>after every step</b>; a non-clean result rolls the step
 * (and the plan) back. Deliberately lean and self-contained (it asks the host to
 * refresh and full-build, then the language for the errors the build left, rather
 * than depending on the tools layer — refactoring must not depend on tools). Test
 * execution is a separate, opt-in gate the loop layers on top; this is the fast
 * structural backstop that catches a step that fails to compile.
 *
 * <p>Since 28k E7 the two halves are ports: the host's {@link ChangeContext#reconciler()
 * rebuild} and the language's {@link ChangeContext#verdict() errors}, one project at a time
 * in the order the language owns them. A project the rebuild did not answer as built is a
 * build failure, never a clean project (the rebuild answers names, not causes; the host's
 * log carries the cause).</p>
 */
public final class ParityGate {

    private static final Logger log = LoggerFactory.getLogger(ParityGate.class);

    /** Gate outcome: {@code clean} iff no error-severity problem markers remain. */
    public record Result(boolean clean, int errorCount, List<Diagnostic> errors) {}

    private ParityGate() {}

    /**
     * Rebuild the projects the language says the changed files reach, the way it says, and collect their error-severity
     * problems. {@code clean} is true iff none remain. Only what is touched is built (Sprint 28k E7c, his word): the language
     * answers, from the files a step changed, the projects that own them and the ones that require those, built
     * incrementally; with no files to go by it answers every project it owns, in full.
     *
     * @param changedFiles the files the step just changed, as the language names them; empty builds everything in full
     */
    public static Result compile(ChangeContext context, List<String> changedFiles) {
        List<Diagnostic> errors = new ArrayList<>();
        NullProgressMonitor monitor = new NullProgressMonitor();
        BuildVerdict.Rebuild rebuild = context.verdict().rebuildAfter(changedFiles);
        for (String name : rebuild.scope().projects()) {
            BuildScope project = BuildScope.of(name);
            String cause = "not built (the host's log names the cause)";
            Set<String> built;
            try {
                built = context.reconciler().rebuild(project, rebuild.kind(), monitor);
            } catch (CoreException | RuntimeException e) {
                // Any failure of a build is a failed gate: the plan loop rolls the plan back on it. An unchecked one
                // escaping here would leave earlier steps applied and unrolled (28k C2, auditor finding A).
                built = Set.of();
                cause = e.getMessage() != null ? e.getMessage() : e.toString();
            }
            if (!built.contains(name)) {
                log.warn("ParityGate build failed on '{}': {}", name, cause);
                errors.add(new Diagnostic(null, -1, "build failed on " + name + ": " + cause));
                continue;
            }
            errors.addAll(context.verdict().errors(project, monitor));
        }
        return new Result(errors.isEmpty(), errors.size(), errors);
    }
}
