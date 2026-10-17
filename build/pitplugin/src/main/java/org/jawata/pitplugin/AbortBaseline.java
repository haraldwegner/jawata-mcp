package org.jawata.pitplugin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashSet;
import java.util.Set;

/**
 * Which tests abort BEFORE any mutation, shared between PIT's two JVMs through a file.
 *
 * <p>PIT runs every test once unmutated (while it discovers them, in the coverage JVM) and then again
 * per mutation, in another JVM. A test that aborts in the first run, because an environment it assumes
 * is absent, aborts in every mutation run too, and reporting that as the mutation's doing would blame
 * every mutant it covers. {@link AbortBaselineListener} therefore writes the ids of the tests it saw
 * abort, one per line, to the file named by {@link #PROPERTY}; the mutation run reads it. Without the
 * property the plugin behaves as PIT's stock one: the file is the engine's way of switching the extra
 * reporting on.</p>
 */
final class AbortBaseline {

    /** Names the file the engine gave this PIT run for the tests that abort unmutated. */
    static final String PROPERTY = "jawata.pit.abortBaseline";

    private static final String MUTATION_WORKER = "org.pitest.mutationtest.execute.MutationTestWorker";

    private static volatile Set<String> before;

    private AbortBaseline() {
    }

    static boolean enabled() {
        return file() != null;
    }

    /** Whether the calling thread is inside PIT's mutation run, as opposed to its coverage run. */
    static boolean inMutationRun() {
        return StackWalker.getInstance().walk(frames ->
            frames.anyMatch(frame -> MUTATION_WORKER.equals(frame.getClassName())));
    }

    static synchronized void record(String uniqueId) {
        Path file = file();
        try {
            Files.writeString(file, uniqueId + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new IllegalStateException("cannot record the aborting test " + uniqueId + " in " + file, e);
        }
    }

    static boolean abortedBeforeMutation(String uniqueId) {
        Set<String> read = before;
        if (read == null) {
            synchronized (AbortBaseline.class) {
                if (before == null) {
                    before = readBaseline(file());
                }
                read = before;
            }
        }
        return read.contains(uniqueId);
    }

    private static Set<String> readBaseline(Path file) {
        try {
            return Files.exists(file) ? new HashSet<>(Files.readAllLines(file, StandardCharsets.UTF_8)) : Set.of();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read the baseline of aborting tests from " + file, e);
        }
    }

    private static Path file() {
        String path = System.getProperty(PROPERTY);
        return path == null || path.isBlank() ? null : Path.of(path);
    }
}
