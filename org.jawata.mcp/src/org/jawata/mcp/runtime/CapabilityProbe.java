package org.jawata.mcp.runtime;

import org.jawata.mcp.runtime.profile.Jcmd;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Sprint 24 (D5) — capability discovery by <b>asking the JVM</b>, via {@code jcmd},
 * instead of inferring from the preset marker.
 *
 * <p>The capability report promises "read from the JVM, never assumed". Four of the six
 * capabilities used to be read off the {@code -Djawata.devsim.preset} marker alone, so a
 * hand-prepared JVM with real native-memory tracking (and no marker) was reported
 * {@code nativeMemoryTracking:false} — the exact lie the report exists to prevent. This
 * asks the running target directly: {@code JFR.check} for an active recording,
 * {@code VM.native_memory summary} for NMT, and the launch command line for the two
 * flag-derived readiness bits. Sprint-24 audit (T2.7).</p>
 *
 * <p>A capability we genuinely cannot determine — {@code jcmd} refused, timed out, or the
 * target is gone — is <b>omitted</b>, never guessed, so the report falls back to its honest
 * marker hint (or "unknown") rather than a fabricated answer.</p>
 */
public final class CapabilityProbe {

    private static final Logger log = LoggerFactory.getLogger(CapabilityProbe.class);

    private CapabilityProbe() {
    }

    /**
     * Ask the target what it can actually do. Returns only the capabilities we could
     * determine; a jcmd that could not answer leaves its key absent.
     *
     * @param pid a running JVM we can attach to with jcmd (same user)
     */
    public static Map<String, Boolean> probe(long pid) {
        Map<String, Boolean> discovered = new LinkedHashMap<>();
        if (pid <= 0) {
            return discovered;
        }

        // quietConsole (-Xlog:disable), profilerReady (-XX:+DebugNonSafepoints), and whether
        // flight recording was CONFIGURED at launch (-XX:StartFlightRecording) all read off the
        // ACTUAL launch command line — the JVM's own record of how it was started. One call, and
        // race-free: these flags do not change over the JVM's life.
        Boolean jfrFlagged = null;
        try {
            String cmd = Jcmd.run(pid, "VM.command_line");
            discovered.put("quietConsole", cmd.contains("-Xlog:disable"));
            discovered.put("profilerReady", cmd.contains("DebugNonSafepoints"));
            jfrFlagged = cmd.contains("StartFlightRecording");
        } catch (Exception e) {
            log.debug("VM.command_line on {} did not answer: {}", pid, e.getMessage());
        }

        // flightRecording — configured at launch (above) OR a recording actively running now
        // (JFR.check, which also catches an on-demand recording started with NO launch flag).
        // The launch-flag signal is what makes this race-free: a continuous recording is not
        // yet listed as "running" in the instant after the JVM is resumed, but the flag is
        // always in the command line, so a preset JVM reads true immediately.
        Boolean jfrRunning = null;
        try {
            String jfr = Jcmd.run(pid, "JFR.check").toLowerCase(Locale.ROOT);
            jfrRunning = !jfr.contains("no available recordings");
        } catch (Exception e) {
            log.debug("JFR.check on {} did not answer: {}", pid, e.getMessage());
        }
        if (jfrFlagged != null || jfrRunning != null) {
            discovered.put("flightRecording",
                Boolean.TRUE.equals(jfrFlagged) || Boolean.TRUE.equals(jfrRunning));
        }

        // nativeMemoryTracking — the summary prints when enabled, and says "Native memory
        // tracking is not enabled" (exit 0) otherwise. Fixed at launch, so stable (no race).
        // This is the one the audit's fixture proves: real NMT with no marker must read true.
        try {
            String nmt = Jcmd.run(pid, "VM.native_memory", "summary").toLowerCase(Locale.ROOT);
            discovered.put("nativeMemoryTracking", !nmt.contains("not enabled"));
        } catch (Exception e) {
            log.debug("VM.native_memory on {} did not answer: {}", pid, e.getMessage());
        }

        return discovered;
    }
}
