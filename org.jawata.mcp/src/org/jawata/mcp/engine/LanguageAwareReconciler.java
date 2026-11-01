package org.jawata.mcp.engine;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.host.Reconciler;
import org.jawata.spi.host.SyncReport;
import org.jawata.spi.lang.LanguageProvider;

/**
 * Sprint 28h S14 (D15): the host's reconciler, handed the source suffixes of the languages the engine serves at the moment of every
 * call. The per-call disk-sync scan used to look for {@code .java} only, written in the scan itself, so a language other than Java had
 * no channel by which a change on disk reached it; now each bound language names the suffixes of its own files
 * ({@link LanguageProvider#sourceExtensions()}) and the scan looks for those and nothing else. The providers are asked on every call,
 * because a language may bind after the engine starts and leave again; with none bound the scan has no suffix, and so no file.
 */
public final class LanguageAwareReconciler implements Reconciler {

    private final Reconciler host;
    private final Supplier<List<LanguageProvider>> languages;

    public LanguageAwareReconciler(Reconciler host, Supplier<List<LanguageProvider>> languages) {
        this.host = host;
        this.languages = languages;
    }

    /** The union of what every language bound now declares for its source files. */
    Set<String> sourceExtensions() {
        Set<String> union = new HashSet<>();
        for (LanguageProvider language : languages.get()) {
            union.addAll(language.sourceExtensions());
        }
        return Set.copyOf(union);
    }

    @Override
    public SyncReport syncBeforeCall() throws CoreException {
        return host.syncBeforeCall(sourceExtensions());
    }

    @Override
    public SyncReport syncBeforeCall(Set<String> sourceExtensions) throws CoreException {
        return host.syncBeforeCall(sourceExtensions);
    }

    @Override
    public Set<String> rebuild(BuildScope scope, BuildKind kind, IProgressMonitor monitor) throws CoreException {
        return host.rebuild(scope, kind, monitor);
    }
}
