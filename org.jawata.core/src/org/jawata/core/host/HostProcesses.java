package org.jawata.core.host;

import java.io.IOException;
import java.util.List;

/**
 * Sprint 28a (1b, step M6) — the one place a process is launched, and the one
 * place that knows how an executable is spelled here.
 *
 * <p>Two jobs, and they belong together because they fail together. NAMING: on
 * Windows the maven wrapper is {@code mvnw.cmd} and maven itself is
 * {@code mvn.cmd}; on POSIX both lose the extension. LAUNCHING: a
 * {@code .cmd} cannot be spawned the way a native binary can, and JDK 21
 * hardened {@link ProcessBuilder} specifically around that. A caller that gets
 * the spelling right and the spawn wrong is exactly as broken as one that gets
 * neither, and before this port every caller made both decisions privately.</p>
 *
 * <p>An implementation must answer {@link #run} with an outcome that
 * distinguishes a failed SPAWN from a failed RUN — see {@link HostProcessOutcome}
 * for why that distinction is the difference between a useful diagnosis and a
 * misleading one.</p>
 */
public interface HostProcesses {

    /**
     * The names to try, in order, when looking for the executable {@code base}
     * on this host.
     *
     * <p>Windows: {@code base.cmd}, {@code base.exe}, {@code base.bat},
     * {@code base} — the extension order matters, because a tool shipped as
     * both a wrapper script and a binary should be reached through its wrapper.
     * POSIX: just {@code base}.</p>
     */
    List<String> executableCandidates(String base);

    /**
     * The names a build wrapper takes here — {@code mvnw.cmd} on Windows,
     * {@code mvnw} elsewhere — for {@code base} such as {@code "mvnw"} or
     * {@code "gradlew"}.
     */
    List<String> wrapperNames(String base);

    /**
     * Run to completion and capture the output.
     *
     * <p>Never throws for a process-level failure: a spawn that fails and a run
     * that fails are both {@link HostProcessOutcome} values, because both are
     * normal answers the caller must handle. {@link InterruptedException} is
     * the one exception, and it propagates because swallowing an interrupt is
     * how a cancel gets lost.</p>
     */
    HostProcessOutcome run(HostCommand command) throws InterruptedException;

    /**
     * Start a process and hand back the live handle, for targets that outlive
     * the call — a held JVM under the debugger, a forked test runner whose
     * streams are drained as it goes.
     *
     * @throws IOException when the process could not be STARTED; a process that
     *     started and later failed is not an exception, it is the caller's to
     *     observe through the handle
     */
    Process start(HostCommand command) throws IOException;

    /** The default implementation, backed by the real operating system. */
    static HostProcesses system() {
        return SystemHostProcesses.INSTANCE;
    }
}
