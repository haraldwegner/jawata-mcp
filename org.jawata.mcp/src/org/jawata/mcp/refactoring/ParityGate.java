package org.jawata.mcp.refactoring;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.mcp.change.ChangeContext;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.lang.Diagnostic;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sprint 18 — the compile half of the orchestration parity gate: build every
 * loaded project and report whether it is error-clean. The multi-step loop
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
     * Refresh + full-build every project the language owns and collect its error-severity
     * problems. {@code clean} is true iff none remain.
     */
    public static Result compile(ChangeContext context) {
        List<Diagnostic> errors = new ArrayList<>();
        NullProgressMonitor monitor = new NullProgressMonitor();
        for (String name : context.verdict().owned().projects()) {
            BuildScope project = BuildScope.of(name);
            String cause = "not built (the host's log names the cause)";
            Set<String> built;
            try {
                built = context.reconciler().rebuild(project, BuildKind.FULL, monitor);
            } catch (CoreException e) {
                built = Set.of();
                cause = e.getMessage();
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
