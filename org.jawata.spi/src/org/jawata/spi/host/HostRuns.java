package org.jawata.spi.host;

import java.util.List;

import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;

/**
 * Programs a host starts for the engine from the person's own run configurations (Sprint 28k contract §4.2 and §6.1, Sprint 28h S18): the
 * host starts the program with the debug agent and the recording option on a working copy, so the person's configuration is never
 * changed, and tells the engine the process id the debugger attaches by. A host that cannot run programs answers
 * {@link Instruments#runs()} with nothing, and the agent starts a program itself from a main class and a classpath.
 *
 * <p>As built it holds the two verbs "Run for jawata" has callers for: the agent asks for a run (path A), and the debugger's discovery
 * reads which programs the person started for jawata (path B). Running a PDE plug-in test through a host's launches is in the contract
 * and is added with the engine tool that asks for it, so the port holds only what something calls.</p>
 */
public interface HostRuns {

    /** The agent asked for the run (path A). */
    String AGENT = "agent";

    /** The person chose "Run for jawata" (path B). */
    String PERSON = "person";

    /**
     * The debug agent a program is started with for jawata: on loopback, on a port the JVM chooses, never holding the program at its start
     * (contract §6.1 rule 2). Written here once: the engine's dev/sim preset reads it from here and a host takes it from here, so neither
     * can drift from the other (28k ledger row A19-3).
     */
    String DEBUG_AGENT = "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0";

    /** The continuous, bounded flight recording a program is started with for jawata, which {@code profile jfr_dump} reads. */
    String RECORDING = "-XX:StartFlightRecording=name=jawata,settings=default,maxsize=256m,maxage=1h";

    /**
     * Starts the person's run configuration for jawata and answers once the program runs. The program is never held at its start, so
     * the caller attaches at once.
     *
     * @param configuration the name of the person's run configuration, as the host lists it
     * @param monitor       progress and cancellation of the start
     * @return the program that is running
     * @throws CoreException when there is no such configuration or the program could not be started or found
     */
    RunForJawata runForJawata(String configuration, IProgressMonitor monitor) throws CoreException;

    /** @return the programs started for jawata that are still running, whoever started them */
    List<RunForJawata> runningForJawata();

    /**
     * A program started for jawata.
     *
     * @param configuration the person's run configuration it was started from
     * @param pid           the operating-system process id the debugger attaches by
     * @param debugAddress  where its debug agent listens, or an empty text when the host does not know it (the debugger needs only the pid)
     * @param startedBy     {@link #AGENT} when the agent asked for it, {@link #PERSON} when the person chose "Run for jawata"
     */
    record RunForJawata(String configuration, long pid, String debugAddress, String startedBy) {
    }
}
