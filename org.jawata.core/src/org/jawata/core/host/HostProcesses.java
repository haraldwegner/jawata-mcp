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

    /**
     * Destroy a process AND the tree below it (Sprint 28g S9a M5a) — the member this
     * interface was missing, and the reason three callers leak grandchildren today.
     *
     * <p>Launching lives here; killing what was launched did not. The idiom — sweep the
     * descendants, then kill the root — was copied into <b>six</b> production sites, and
     * <b>three more</b> killed the root ALONE: {@code ProjectImporter}, {@code JvmTargets} and
     * {@code profile/GdbAdapter}. Those three leak every grandchild, which is exactly what a
     * wrapper script leaves behind, and nothing could have told them: there was no member to
     * forget, only an idiom to remember. Six copies of a two-line sequence and three places
     * that remembered half of it is the shape this interface exists to remove.</p>
     *
     * <h2>The order is SNAPSHOT, ROOT, THEN CHILDREN, and it is not the order it replaces</h2>
     *
     * <p>Every copied site did children-first-then-root, which leaves a window: between the
     * sweep and the root's death the root is still running and can fork once more, and that
     * child is in nobody's list. Taking the descendant snapshot first and killing the root
     * SECOND closes it — the root cannot spawn after it is dead, and the children are already
     * in hand, so nothing is lost by killing them last. Same outcome, one fewer race.</p>
     *
     * <h2>What this CANNOT do, stated rather than discovered</h2>
     *
     * <p>{@link ProcessHandle#descendants()} walks the parent relation, so a child that
     * double-forks — or calls {@code setsid} — is reparented away and this reaps nothing of it.
     * Closing that needs a process GROUP, and {@link ProcessBuilder} has no process-group API
     * at all (measured on JDK 21: 38 members, none). <b>The obvious fix is worse than the
     * gap.</b> Prefixing the spawn with {@code setsid} makes it fork and exit, so
     * {@code Process#toHandle()} names an already-dead process: {@code descendants()} comes
     * back EMPTY and {@code destroyForcibly()} kills nothing at all, while
     * {@code waitFor(timeout)} reports a tidy completion of a process still running — the very
     * failure {@code HostProcessesContractTest} records catching once already. So the detach
     * must never ship without a group kill beside it, and a group kill is a per-OS kernel fact
     * that no amount of reading settles. <b>Deferred to Sprint 28's platform round</b>, with
     * its probe named: one contract test per matrix OS that spawns a double-forking child and
     * asserts the grandchild's pid is gone after a reap. Until then this is the honest ceiling,
     * and it is strictly more containment than the nine sites had between them.</p>
     *
     * <p>Static because the sweep is OS-INDEPENDENT today and three of the nine call sites hold
     * no {@code HostProcesses} instance to call it on. The day a group kill lands it becomes
     * per-OS behaviour and moves onto the instance beside {@link #start}; that is a change with
     * a reason rather than a shape chosen in advance.</p>
     *
     * @param process the process to reap; a null or already-dead one is a no-op, because a
     *     caller reaping twice on two exit paths is normal and must not have to check
     */
    static void reap(Process process) {
        if (process == null) {
            return;
        }
        List<ProcessHandle> children = process.descendants().toList();
        process.destroyForcibly();
        children.forEach(ProcessHandle::destroyForcibly);
    }

    /** The default implementation, backed by the real operating system. */
    static HostProcesses system() {
        return SystemHostProcesses.INSTANCE;
    }

    /**
     * The real implementation, answering for a NAMED operating system rather
     * than this one.
     *
     * <p>Its purpose is the sprint's whole thesis: a caller's per-OS behaviour
     * should be testable on every runner. Before the boundary, a test could
     * only exercise the branch belonging to the machine it ran on — which is
     * why {@code ProjectImporterTest} passed a hardcoded {@code false} at three
     * of four call sites and so never checked the Windows spellings even when
     * running on Windows.</p>
     *
     * <p>Naming is pure logic, so it is honest to drive it this way. LAUNCHING
     * is not: a spawn's behaviour belongs to the real host and is proven per
     * environment by {@code HostProcessesContractTest}.</p>
     */
    static HostProcesses forOs(HostOS os) {
        return new SystemHostProcesses(os);
    }
}
