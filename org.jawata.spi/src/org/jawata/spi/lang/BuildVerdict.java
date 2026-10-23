package org.jawata.spi.lang;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.jawata.spi.host.BuildScope;

/**
 * The change lifecycle's question to a language: which errors exist right now? The ONE compile check per language (contract
 * revision 2.1, §4.5), answered at two granularities. Per FILE — what a single change is gated on, asked before and after it is
 * applied, with no build. Per PROJECT — what a multi-step plan is gated on, asked after the host's
 * {@link org.jawata.spi.host.Reconciler#rebuild rebuild}, reading what that build left behind.
 */
public interface BuildVerdict {

    /**
     * The problems in each named file now. Keys are the strings the language's {@link ChangePaths#display} produced. A file this
     * language does not own, or that does not exist at that path, has NO entry — that absence is how a created file is spotted; a
     * file that exists but cannot be read has an entry naming that, never an empty set.
     */
    Map<String, Set<SourceProblem>> problemsIn(List<String> files);

    /** Makes the language's model see what was just written to these files. Never throws. */
    void settle(List<String> files);

    /** The evidence a refusal over a file the change CREATED carries, already rendered; empty when there is none to give. */
    Optional<String> describeCreated(String file);

    /** The projects this language answers for now, by the names the host knows them by. */
    BuildScope owned();

    /**
     * The error-severity problems the last build left in these projects, in scope order. A project that cannot be read is answered
     * as a {@link Diagnostic} naming it, never skipped.
     */
    List<Diagnostic> errors(BuildScope scope, IProgressMonitor monitor);

    /** One problem in a file; {@code syntax} when the language reads it as a syntax error (the lifecycle undoes those always). */
    record SourceProblem(String message, boolean syntax) {
    }
}
