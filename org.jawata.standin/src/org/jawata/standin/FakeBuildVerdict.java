package org.jawata.standin;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.core.runtime.IProgressMonitor;
import org.jawata.spi.host.BuildScope;
import org.jawata.spi.lang.BuildVerdict;
import org.jawata.spi.lang.Diagnostic;

/**
 * The stand-in language's build verdict (Sprint 28k, Stage 8; contract §18 ring 1): the language has no compiler, so its rule is plain
 * text. A file that contains the word {@value #BROKEN} does not build; every other file does. That is enough to prove the change lifecycle
 * is gated by the language's verdict and not by Java's.
 */
public final class FakeBuildVerdict implements BuildVerdict {

    /** The word that makes a file a problem. */
    public static final String BROKEN = "BROKEN";

    @Override
    public Map<String, Set<SourceProblem>> problemsIn(List<String> files) {
        Map<String, Set<SourceProblem>> problems = new LinkedHashMap<>();
        for (String file : files) {
            if (isBroken(file)) {
                Set<SourceProblem> found = new HashSet<>();
                // Syntax-category: the lifecycle counts only these against a file the change created (it cannot resolve siblings yet).
                found.add(new SourceProblem("the file contains the word " + BROKEN, true));
                problems.put(file, found);
            }
        }
        return problems;
    }

    @Override
    public void settle(List<String> files) {
        // Nothing is compiled, so nothing needs to settle before the next ask.
    }

    @Override
    public Optional<String> describeCreated(String file) {
        return isBroken(file) ? Optional.of("the new file contains the word " + BROKEN) : Optional.empty();
    }

    @Override
    public BuildScope owned() {
        return BuildScope.of();
    }

    @Override
    public List<Diagnostic> errors(BuildScope scope, IProgressMonitor monitor) {
        return List.of();
    }

    private static boolean isBroken(String file) {
        try {
            return Files.isRegularFile(Path.of(file)) && Files.readString(Path.of(file)).contains(BROKEN);
        } catch (IOException e) {
            return false;
        }
    }
}
