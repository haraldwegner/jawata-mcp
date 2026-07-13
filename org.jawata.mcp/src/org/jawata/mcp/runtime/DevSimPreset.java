package org.jawata.mcp.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 24 (D5) — <b>the one host-controlled switch</b>. A JVM is either
 * prepared for the toolkit or it is not, and that is the host's decision, made
 * once, at launch. Not six flags an agent has to remember, and not something
 * jawata can quietly turn on behind the operator's back.
 *
 * <p>This is also where the sprint's safety model lives, and it is a topology,
 * not a policy: production runs no agent and exposes no debug channel, so the
 * dangerous action is not <em>refused</em> — it is <em>unrepresentable</em>. The
 * debug port binds to loopback; nothing here reaches off the machine.</p>
 *
 * <p>Six capabilities, one switch:</p>
 * <ol>
 *   <li><b>debug</b> — JDWP on loopback (the interactive debugger)</li>
 *   <li><b>flightRecording</b> — continuous JFR, bounded (profiles, phase 3)</li>
 *   <li><b>jmx</b> — local JMX (live state)</li>
 *   <li><b>nativeMemoryTracking</b> — NMT summary (native triage)</li>
 *   <li><b>profilerReady</b> — the sampler can attach</li>
 *   <li><b>quietConsole</b> — no unsolicited JIT/GC spam on stdout</li>
 * </ol>
 */
public final class DevSimPreset {

    /** The capabilities the preset prepares. A report names every one, present or not. */
    public static final List<String> CAPABILITIES = List.of(
        "debug", "flightRecording", "jmx", "nativeMemoryTracking",
        "profilerReady", "quietConsole");

    private DevSimPreset() {
    }

    /**
     * The JVM flags the host adds to prepare a dev/sim target. The debug port is
     * left to the JVM (address 0) and announced on startup — a fixed port is a
     * collision waiting to happen when two sessions run at once.
     */
    public static List<String> jvmArgs() {
        List<String> args = new ArrayList<>();
        // 1. JDWP — LOOPBACK ONLY, and never suspending on our account: attaching
        //    must not change whether the program runs.
        args.add("-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0");
        // 2. Flight recording, continuous and BOUNDED — an unbounded recording on a
        //    long-running sim is a disk leak.
        args.add("-XX:StartFlightRecording=name=jawata,settings=default,maxsize=256m,maxage=1h");
        // 3. Local JMX. No authentication because there is no remote surface to
        //    authenticate: the connector is local-only.
        args.add("-Dcom.sun.management.jmxremote");
        args.add("-Dcom.sun.management.jmxremote.local.only=true");
        // 4. Native memory tracking — summary is enough to triage, and detail costs.
        args.add("-XX:NativeMemoryTracking=summary");
        // 5. Profiler readiness: keep the frame pointer so a sampler can walk stacks.
        args.add("-XX:+UnlockDiagnosticVMOptions");
        args.add("-XX:+DebugNonSafepoints");
        // 6. Quiet console — diagnostics go to the tools that ask for them, not to
        //    the program's stdout, where they would corrupt whatever it prints.
        args.add("-Xlog:disable");
        // A marker, so a session can tell a preset-prepared JVM from any other.
        args.add("-D" + MARKER + "=true");
        return args;
    }

    /**
     * The preset, for a JVM <b>we</b> launch to debug — identical except that the
     * program is held before its first instruction.
     *
     * <p>The difference matters and it is not cosmetic. {@link #jvmArgs()} prepares a
     * JVM the HOST starts (a sim you will later attach to): there, {@code suspend=n} is
     * the whole point — attaching must not change whether the program runs. But when
     * the debugger itself launches the target, {@code suspend=n} means the program is
     * already running by the time anyone can arm a breakpoint, so the first iterations
     * are simply gone and a breakpoint on the first line of {@code main} can never be
     * hit. That is not a debugger.</p>
     *
     * <p>So a launched target starts SUSPENDED, and the caller arms its breakpoints and
     * then explicitly starts the program. This is what every IDE does, and it is what
     * makes "run to the third iteration" mean the third — not the third one we happened
     * to catch.</p>
     */
    public static List<String> jvmArgsForLaunch() {
        List<String> args = new ArrayList<>(jvmArgs());
        args.replaceAll(arg -> arg.startsWith("-agentlib:jdwp")
            ? "-agentlib:jdwp=transport=dt_socket,server=y,suspend=y,address=127.0.0.1:0"
            : arg);
        return args;
    }

    /** How a preset-prepared JVM identifies itself to us. */
    public static final String MARKER = "jawata.devsim.preset";

    /**
     * What this JVM can actually do — read from the target itself, never assumed
     * from the flags we think we passed. An attached JVM we did not launch is the
     * normal case, and it will honestly report the capabilities it lacks.
     *
     * @param systemProperties the target's own system properties (via the attach API)
     * @param jdiCapabilities  what the JDI connection reports it can do
     */
    public static Map<String, Object> report(Map<String, String> systemProperties,
                                             Map<String, Boolean> jdiCapabilities) {
        Map<String, Object> report = new LinkedHashMap<>();

        // We are talking to it over JDWP, so the debug channel is present by
        // construction — no need to infer it from a flag.
        report.put("debug", true);

        if (systemProperties.isEmpty()) {
            // The target could not be asked. That happens for a JVM we launched and are
            // still HOLDING before its first instruction: the attach channel is serviced
            // by the target itself, and a target that has not run cannot service anything.
            //
            // So we say we do not know. Reporting `false` here would be a lie of exactly
            // the kind this report exists to prevent — "flightRecording: false" on a JVM
            // launched WITH flight recording. Unknown is not the same as absent, and the
            // truth arrives the moment the program starts.
            for (String capability : CAPABILITIES) {
                report.putIfAbsent(capability, null);
            }
            report.put("presetPrepared", null);
            report.put("capabilitiesUnread", true);
            report.put("why", "the target has not run yet (held at start), so it cannot "
                + "answer what it can do. Start it with debug(action=resume); these are "
                + "read from the JVM itself the moment it can be asked.");
            report.putAll(jdiCapabilities);
            return report;
        }

        report.put("flightRecording", hasFlightRecording(systemProperties));
        report.put("jmx", systemProperties.containsKey("com.sun.management.jmxremote"));
        report.put("nativeMemoryTracking", nmtEnabled(systemProperties));
        report.put("profilerReady", systemProperties.containsKey("jdk.attach.allowAttachSelf")
            || Boolean.parseBoolean(systemProperties.getOrDefault(MARKER, "false")));
        report.put("quietConsole", Boolean.parseBoolean(
            systemProperties.getOrDefault(MARKER, "false")));
        report.put("presetPrepared", Boolean.parseBoolean(
            systemProperties.getOrDefault(MARKER, "false")));

        // What the debugger itself can do here. Honest per-JVM: pop-frame and
        // hot-swap are NOT universal, and a session that pretends otherwise fails
        // later, further from the cause.
        report.putAll(jdiCapabilities);
        return report;
    }

    private static boolean hasFlightRecording(Map<String, String> systemProperties) {
        // A JVM launched with StartFlightRecording carries it; one that did not may
        // still start a recording on demand, which phase 3 will exercise.
        return systemProperties.keySet().stream()
            .anyMatch(k -> k.startsWith("jdk.jfr"))
            || Boolean.parseBoolean(systemProperties.getOrDefault(MARKER, "false"));
    }

    private static boolean nmtEnabled(Map<String, String> systemProperties) {
        return Boolean.parseBoolean(systemProperties.getOrDefault(MARKER, "false"));
    }
}
