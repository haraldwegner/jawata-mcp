package com.example.debug;

import java.io.PrintWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Sprint 24 (D13/Stage 18) fixture — a program that runs normally, writing its
 * own structured application log, then deliberately RAISES AN ALARM at a known
 * iteration: the generic, fixture-raised alarm contract {@code incident_arm}
 * watches for. The alarm payload NAMES the symbol the fixture itself considers
 * responsible — jawata orchestrates and captures; the target declares what
 * actually went wrong, same division of responsibility R5 draws for replay.
 *
 * <p>Args: {@code logFile alarmFile}.</p>
 */
public final class IncidentTarget {

    private static final int ALARM_AT_ITERATION = 15;

    public static void main(String[] args) throws Exception {
        Path logFile = Path.of(args[0]);
        Path alarmFile = Path.of(args[1]);

        try (PrintWriter log = new PrintWriter(Files.newBufferedWriter(logFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND))) {
            int iteration = 0;
            while (true) {
                iteration++;
                log.println("iteration " + iteration + " ok");
                log.flush();
                if (iteration == ALARM_AT_ITERATION) {
                    raiseAlarm(log, alarmFile);
                }
                Thread.sleep(100);
            }
        }
    }

    /** The alarming condition — named so the bundle's summary can name it too. */
    private static void raiseAlarm(PrintWriter log, Path alarmFile) throws Exception {
        log.println("ALARM: threshold breach detected in checkThreshold()");
        log.flush();
        String payload = "{\"symbol\":\"com.example.debug.IncidentTarget#checkThreshold\","
            + "\"reason\":\"simulated threshold breach\"}";
        Files.writeString(alarmFile, payload);
    }

    /** The (simulated) alarming method — named in the alarm payload above. */
    static boolean checkThreshold(int value, int limit) {
        return value > limit;
    }

    private IncidentTarget() {
    }
}
