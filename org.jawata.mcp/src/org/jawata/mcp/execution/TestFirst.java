package org.jawata.mcp.execution;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The rule for a developer that builds new behaviour: the test is written and seen failing before the production code, and the tests pass
 * after it (Sprint 28h Stage 27 (z5)).
 *
 * <p>It is read from what the worker's own stream shows: its editor writes, in order, and what each of its {@code run_tests} calls showed,
 * which the engine kept ({@link TestRuns}). Only a brain's own editor writes logic, so only an editor write counts as a change to production
 * code; a structural change made through a jawata tool is not new behaviour. A red is any run in which a test ran and failed: which test it
 * was is not read, so the rule cannot tell the new test's failure from an old one's.</p>
 */
public final class TestFirst {

    /** What a {@code run_tests} call of the worker showed. */
    public enum Run {
        /** Tests ran and at least one failed. */
        RED,
        /** Tests ran and none failed. */
        GREEN,
        /** The call ran no test: a compile error, a refusal, a status or coverage call, or a run that found no test. */
        NOT_RUN
    }

    /** The argument names the brains' editors give the file they write. */
    private static final List<String> FILE_ARGUMENTS = List.of("file_path", "filePath", "path");

    private TestFirst() {
    }

    /** What is wrong with the order in which a worker wrote tests, ran them and wrote production code; empty when it is right. */
    public static List<String> violations(List<ToolCall> made, List<Run> runs) {
        List<String> violations = new ArrayList<>();
        int runIndex = 0;
        boolean redSeen = false;
        boolean beforeRedNamed = false;
        int lastProduction = -1;
        String lastProductionFile = null;
        boolean greenSinceLastProduction = false;
        for (int i = 0; i < made.size(); i++) {
            ToolCall call = made.get(i);
            if ("run_tests".equals(call.bareName())) {
                Run run = runIndex < runs.size() ? runs.get(runIndex) : Run.NOT_RUN;
                runIndex++;
                redSeen |= run == Run.RED;
                greenSinceLastProduction |= run == Run.GREEN && lastProduction >= 0;
                continue;
            }
            Optional<String> file = writtenFile(call).filter(path -> !isTestPath(path) && !path.endsWith(".md"));
            if (file.isEmpty()) {
                continue;
            }
            if (!redSeen && !beforeRedNamed) {
                violations.add("call " + (i + 1) + " (" + call.bareName() + " on " + file.get()
                    + "): production code was written before a test was seen failing");
                beforeRedNamed = true;
            }
            lastProduction = i;
            lastProductionFile = file.get();
            greenSinceLastProduction = false;
        }
        if (lastProduction >= 0 && !greenSinceLastProduction) {
            violations.add("no passing run of the tests came after the last production change (call " + (lastProduction + 1) + " on "
                + lastProductionFile + ")");
        }
        return List.copyOf(violations);
    }

    /** Whether the path is a test file: by the folder or the name the languages of this project give test code. */
    static boolean isTestPath(String path) {
        String[] segments = path.replace('\\', '/').split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            String folder = segments[i];
            if (folder.equals("test") || folder.equals("tests") || folder.equals("__tests__")
                    || folder.endsWith(".tests") || folder.endsWith(".test")) {
                return true;
            }
        }
        String name = segments[segments.length - 1];
        return name.endsWith("Test.java") || name.endsWith("Tests.java") || name.endsWith("IT.java")
            || name.endsWith("Test.kt") || name.endsWith("Tests.kt")
            || name.contains(".test.") || name.contains(".spec.") || name.contains("_test.") || name.startsWith("test_");
    }

    /** The file a brain's own editor call writes, or empty for a call that is no write or names no file. */
    static Optional<String> writtenFile(ToolCall call) {
        if (!RunEffects.isCliEditor(call.bareName())) {
            return Optional.empty();
        }
        for (String name : FILE_ARGUMENTS) {
            JsonNode value = call.arguments().path(name);
            if (value.isTextual() && !value.asText().isBlank()) {
                return Optional.of(value.asText());
            }
        }
        return Optional.empty();
    }
}
