package org.jawata.mcp.runtime;

import com.sun.jdi.Bootstrap;
import com.sun.jdi.VirtualMachine;
import com.sun.jdi.connect.AttachingConnector;
import com.sun.jdi.connect.Connector;
import com.sun.tools.attach.AttachNotSupportedException;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcesses;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sprint 24 (D5) — finding, starting, and connecting to the JVMs on this
 * machine. The three ways a debug session begins.
 *
 * <p>Attaching is the default: ORB/JATS runs long headless sessions on the sim
 * box, and the interesting state is the state it is already in. Launching exists
 * for fixtures and replays.</p>
 */
public final class JvmTargets {

    private static final Logger log = LoggerFactory.getLogger(JvmTargets.class);

    /** JDWP announces its port on startup when we let the JVM choose one. */
    private static final Pattern LISTENING =
        Pattern.compile("Listening for transport dt_socket at address: (\\d+)");

    private JvmTargets() {
    }

    /**
     * The local JVMs, as the JDK itself sees them. Our own process is excluded —
     * offering to debug the debugger is a trap, not a feature.
     */
    public static List<Map<String, Object>> discover() {
        List<Map<String, Object>> found = new ArrayList<>();
        long self = ProcessHandle.current().pid();
        for (com.sun.tools.attach.VirtualMachineDescriptor vmd
                : com.sun.tools.attach.VirtualMachine.list()) {
            try {
                long pid = Long.parseLong(vmd.id());
                if (pid == self) {
                    continue;
                }
                Map<String, Object> jvm = new LinkedHashMap<>();
                jvm.put("pid", pid);
                jvm.put("displayName", vmd.displayName());
                // Say up front whether this one can be attached to at all. A JVM that
                // started without a debug agent can never be given one, so offering it
                // without saying so invites a failure we could have predicted.
                boolean debuggable = debugAddress(pid) != null;
                jvm.put("debuggable", debuggable);
                if (!debuggable) {
                    jvm.put("why", "started without a debug agent; one cannot be added to a "
                        + "running JVM. Relaunch it under the dev/sim preset to debug it.");
                }
                found.add(jvm);
            } catch (NumberFormatException e) {
                log.debug("Skipping a JVM with a non-numeric id: {}", vmd.id());
            }
        }
        return found;
    }

    /**
     * Launch a JVM under the dev/sim preset and connect to it.
     *
     * @param projectName the project the program belongs to, so the host's runtime for it runs the JVM; null runs it on the engine's own
     */
    public static VirtualMachine launch(String projectName, List<String> command, Path workingDirectory,
                                        List<String> extraJvmArgs, Process[] outProcess)
            throws Exception {
        return startAndAttach(launchCommand(projectName, command, extraJvmArgs), workingDirectory, outProcess);
    }

    /**
     * The command line of a launched target: the java of the project's runtime (the host's for that project, else the engine's own),
     * the dev/sim preset that holds the target before its first instruction, the caller's extra JVM arguments, then the program.
     *
     * @param projectName  the project the program belongs to, or null for the engine's own runtime
     * @param command      the program: class path, main class, arguments
     * @param extraJvmArgs further JVM arguments; may be null
     * @return the command, program first
     */
    public static List<String> launchCommand(String projectName, List<String> command, List<String> extraJvmArgs) {
        List<String> full = new ArrayList<>();
        full.add(ChildJvm.binary(projectName, "java").toString());
        // Held before its first instruction: the caller arms breakpoints, THEN starts it.
        // See DevSimPreset#jvmArgsForLaunch — a launched target that is already running
        // has already run past whatever you wanted to see.
        full.addAll(DevSimPreset.jvmArgsForLaunch());
        if (extraJvmArgs != null) {
            full.addAll(extraJvmArgs);
        }
        full.addAll(command);
        return full;
    }

    /**
     * Launch a target whose command is COMPLETE as given — an eclipse-rcp native
     * launcher, say, where the preset flags ride behind {@code -vmargs} and the
     * executable is not {@code java}. The caller owns the arg weaving; this owns
     * the same connect contract as {@link #launch}: the JVM must announce its
     * debug port (the preset must be IN the command), stdout is drained for the
     * target's life, and the returned VM is attached over loopback.
     */
    public static VirtualMachine launchRaw(List<String> fullCommand, Path workingDirectory,
                                           Process[] outProcess) throws Exception {
        return startAndAttach(new ArrayList<>(fullCommand), workingDirectory, outProcess);
    }

    private static VirtualMachine startAndAttach(List<String> full, Path workingDirectory,
                                                 Process[] outProcess) throws Exception {
        // The SPAWN crosses the boundary; the stream handling stays here,
        // because this target outlives the call — it is held before its first
        // instruction and drained for its whole life, which is not a
        // run-and-capture shape.
        HostCommand command = HostCommand.of(full);
        if (workingDirectory != null) {
            command = command.in(workingDirectory);
        }
        Process process = HostProcesses.system().start(command);
        outProcess[0] = process;

        BufferedReader out =
            new BufferedReader(new InputStreamReader(process.getInputStream()));
        int port = awaitPort(process, out);
        if (port <= 0) {
            // S9a M5a: the TREE, not just the root. A launch that never announced a port may
            // still have forked — a wrapper, or a JVM that re-execs — and killing the root
            // alone orphaned whatever it left.
            HostProcesses.reap(process);
            throw new IllegalStateException(
                "the launched JVM never announced a debug port — it is not debuggable");
        }
        // Keep draining stdout/stderr for the life of the target. A pipe holds ~64KB; a
        // program that writes past that with nobody reading blocks in write() FOREVER —
        // so a chatty target (a JATS replay logging per event, say) would stall the moment
        // its output filled the buffer, before doing anything the debugger came to see.
        // We stopped reading at the JDWP banner and never resumed. Sprint-24 audit (T1.6).
        drainForLife(process, out);
        return attachToPort(port);
    }

    /** Read and discard the target's output until it exits, so its stdout pipe never fills. */
    private static void drainForLife(Process process, BufferedReader alreadyOpen) {
        Thread drain = new Thread(() -> {
            try (BufferedReader reader = alreadyOpen) {
                while (reader.readLine() != null) {
                    // Discarded on purpose: the target's console is its own (the dev/sim
                    // preset quiets JIT/GC logging); we drain only to keep the pipe flowing.
                    // If a future feature wants this output, tee it to the session here.
                }
            } catch (IOException e) {
                log.debug("launched target output stream ended: {}", e.getMessage());
            }
        }, "jawata-target-stdout-drain");
        drain.setDaemon(true);
        drain.start();
    }

    /**
     * Attach to a JVM already running on this machine — <b>if it was started with
     * the debug agent</b>.
     *
     * <p>It cannot be given one now. OpenJDK's JDWP agent has no
     * {@code Agent_OnAttach} entry point, so debuggability is decided at launch and
     * cannot be retrofitted: a JVM that started without {@code -agentlib:jdwp} will
     * never be debuggable, for as long as it lives. (Verified against the runtime,
     * Sprint 24 Stage 7 — the JVM says so itself.)</p>
     *
     * <p>That is why the dev/sim preset exists, and it is also what makes this
     * sprint's safety model structural rather than merely stated: a production JVM,
     * started without the preset, is not debuggable AT ALL. The dangerous action is
     * not refused by policy — it is unreachable by construction.</p>
     */
    public static VirtualMachine attach(long pid) throws Exception {
        String address = awaitDebugAddress(pid, ASKING_PATIENCE);
        if (address == null) {
            throw new NotDebuggable(pid);
        }
        return attachToPort(Integer.parseInt(address.substring(address.lastIndexOf(':') + 1)));
    }

    /**
     * Whether the process already catches SIGQUIT, the signal the attach mechanism sends it. A JVM installs that handler while it
     * starts; until then the signal's default action ENDS the process, and the JDK 21 on the build machine sends it unchecked. That is
     * how the release gate's debugger test lost its program with exit code 131 and no output, and how "Run for jawata" followed by an
     * attach could end the person's program (2026-11-11). Read from /proc/&lt;pid&gt;/status where Linux has it; elsewhere the question
     * cannot be put, and the answer is yes, which is what asking did before.
     */
    static boolean catchesTheAttachSignal(long pid) {
        java.nio.file.Path status = java.nio.file.Path.of("/proc", Long.toString(pid), "status");
        if (!java.nio.file.Files.isReadable(status)) {
            return true;
        }
        try {
            for (String line : java.nio.file.Files.readAllLines(status)) {
                if (line.startsWith("SigCgt:")) {
                    long caught = Long.parseUnsignedLong(line.substring("SigCgt:".length()).trim(), 16);
                    return (caught & (1L << (3 - 1))) != 0; // SIGQUIT is signal 3, bit 2 of the mask
                }
            }
        } catch (IOException | NumberFormatException unreadable) {
            log.debug("could not read the caught signals of pid {}: {}", pid, unreadable.getMessage());
        }
        return true;
    }

    /** How long {@link #attach} keeps asking a JVM that cannot answer yet. A backstop against a JVM that never will, not a budget. */
    static final java.time.Duration ASKING_PATIENCE = java.time.Duration.ofSeconds(30);

    /**
     * The JVM's debug address, asked until it answers: null only when the JVM ANSWERED that it has no debug agent.
     *
     * <p>A JVM that cannot be asked yet — one that has only just started, or one on a loaded machine — is asked again until
     * {@code patience} runs out or it exits, and then the last failure is thrown. It is never reported as a JVM without an agent,
     * which it may well not be: "Run for jawata" attaches the moment the host has started the program, and the release gate's
     * suite reported {@code JVM_NOT_DEBUGGABLE} for a JVM started with {@code -agentlib:jdwp} (2026-11-11).</p>
     */
    static String awaitDebugAddress(long pid, java.time.Duration patience) throws Exception {
        long deadline = System.nanoTime() + patience.toNanos();
        while (true) {
            try {
                return askDebugAddress(pid);
            } catch (Exception couldNotAsk) {
                boolean alive = ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false);
                if (!alive || System.nanoTime() >= deadline) {
                    throw couldNotAsk;
                }
                Thread.sleep(200);
            }
        }
    }

    /** A JVM that was never prepared for a debugger, and now never can be. */
    public static final class NotDebuggable extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        public NotDebuggable(long pid) {
            super("JVM " + pid + " was not started with a debug agent, and one cannot be "
                + "added to a running JVM (OpenJDK's JDWP agent has no attach entry point). "
                + "Debuggability is decided at launch: start it under the dev/sim preset "
                + "(debug(action=launch)), or add -agentlib:jdwp to however it is started.");
        }
    }

    /** Where this JVM is listening for a debugger, or null if it never can. */
    public static String debugAddress(long pid) {
        try {
            return askDebugAddress(pid);
        } catch (Exception e) {
            log.debug("Probing pid {} for a debug agent failed: {}", pid, e.getMessage());
            return null;
        }
    }

    private static String askDebugAddress(long pid) throws AttachNotSupportedException, IOException {
        if (!catchesTheAttachSignal(pid)) {
            throw new AttachNotSupportedException("JVM " + pid + " does not catch SIGQUIT yet (it is still starting), and asking now would end it");
        }
        com.sun.tools.attach.VirtualMachine attached =
            com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
        try {
            String address = attached.getAgentProperties()
                .getProperty("sun.jdwp.listenerAddress");
            return address != null && address.contains(":") ? address : null;
        } finally {
            attached.detach();
        }
    }

    /** The target's own system properties — what it says about itself. */
    public static Map<String, String> systemProperties(long pid) {
        Map<String, String> properties = new LinkedHashMap<>();
        try {
            com.sun.tools.attach.VirtualMachine attached =
                com.sun.tools.attach.VirtualMachine.attach(String.valueOf(pid));
            try {
                attached.getSystemProperties()
                    .forEach((k, v) -> properties.put(String.valueOf(k), String.valueOf(v)));
            } finally {
                attached.detach();
            }
        } catch (Exception e) {
            log.debug("Reading system properties of pid {} failed: {}", pid, e.getMessage());
        }
        return properties;
    }

    /** What the debugger can actually do on this VM — asked, never assumed. */
    public static Map<String, Boolean> jdiCapabilities(VirtualMachine vm) {
        Map<String, Boolean> can = new LinkedHashMap<>();
        can.put("canRedefineClasses", vm.canRedefineClasses());
        can.put("canPopFrames", vm.canPopFrames());
        can.put("canWatchFieldAccess", vm.canWatchFieldAccess());
        can.put("canWatchFieldModification", vm.canWatchFieldModification());
        can.put("canForceEarlyReturn", vm.canForceEarlyReturn());
        can.put("canGetInstanceInfo", vm.canGetInstanceInfo());
        return can;
    }

    static VirtualMachine attachToPort(int port) throws Exception {
        AttachingConnector socket = Bootstrap.virtualMachineManager().attachingConnectors()
            .stream()
            .filter(c -> "dt_socket".equals(c.transport().name()))
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("no dt_socket attaching connector"));

        Map<String, Connector.Argument> args = socket.defaultArguments();
        args.get("hostname").setValue("127.0.0.1");
        args.get("port").setValue(String.valueOf(port));
        return socket.attach(args);
    }

    private static int awaitPort(Process process, BufferedReader out) throws Exception {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline && process.isAlive()) {
            String line = out.readLine();
            if (line == null) {
                break;
            }
            Matcher m = LISTENING.matcher(line);
            if (m.find()) {
                return Integer.parseInt(m.group(1));
            }
        }
        return -1;
    }
}
