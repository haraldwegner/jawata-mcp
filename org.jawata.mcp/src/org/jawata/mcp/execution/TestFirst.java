package org.jawata.mcp.execution;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;

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
    private static final List<String> FILE_ARGUMENTS = List.of("file_path", "filePath", "path", "notebook_path");

    private TestFirst() {
    }

    /**
     * What is wrong with the order in which a worker wrote tests, ran them and wrote production code; empty when it is right. Each new test the
     * worker writes asks for its own red, so a second behaviour is held to the order the first was.
     *
     * @param root the folder the worker ran in, or null when it is not known: the folders that mark a test are read below it, so a project
     *             that lives under a folder named {@code test} still has production code
     * @param modelSaysTest for a file in full, whether the loaded model marks it as test code, or empty when the model does not hold the
     *                      place: the model decides where it holds the file, and the path rule only where it does not (28h-C27-24)
     */
    public static List<String> violations(List<ToolCall> made, List<Run> runs, Path root, Function<String, Optional<Boolean>> modelSaysTest) {
        List<String> violations = new ArrayList<>();
        int runIndex = 0;
        boolean redSeen = false;
        boolean beforeRedNamed = false;
        boolean testWritten = false;
        String heldWrite = null;
        int lastProduction = -1;
        String lastProductionFile = null;
        boolean greenSinceLastProduction = false;
        for (int i = 0; i < made.size(); i++) {
            ToolCall call = made.get(i);
            if ("run_tests".equals(call.bareName())) {
                Run run = runIndex < runs.size() ? runs.get(runIndex) : Run.NOT_RUN;
                runIndex++;
                if (heldWrite != null && run != Run.RED) {
                    // A run that ran no test (the tool failed, as it does on Maven and Eclipse plug-in projects) is not a passing run, and is said so.
                    violations.add(run == Run.NOT_RUN ? heldWrite + ", and the test run after it showed no result (it ran no test)" : heldWrite);
                    beforeRedNamed = true;
                }
                heldWrite = null;
                redSeen |= run == Run.RED;
                greenSinceLastProduction |= run == Run.GREEN && lastProduction >= 0;
                continue;
            }
            Optional<String> written = writtenFile(call);
            if (written.filter(path -> isTest(path, root, modelSaysTest)).isPresent()) {
                testWritten = true;
                redSeen = false;
                continue;
            }
            Optional<String> file = written.filter(path -> !path.endsWith(".md"));
            if (file.isEmpty()) {
                continue;
            }
            if (!redSeen && !beforeRedNamed) {
                String beforeRed = "call " + (i + 1) + " (" + call.bareName() + " on " + file.get()
                    + "): production code was written before a test was seen failing";
                if (!testWritten) {
                    violations.add(beforeRed);
                    beforeRedNamed = true;
                } else if (heldWrite == null) {
                    // After its test, a first write is the stub a test needs to fail for its behaviour and not for a missing method, since no
                    // tool adds an empty method (met live, 28h Stage 27 (z5)): it stands only if the next run is red.
                    heldWrite = beforeRed;
                }
            }
            lastProduction = i;
            lastProductionFile = file.get();
            greenSinceLastProduction = false;
        }
        if (heldWrite != null) {
            violations.add(heldWrite);
        }
        if (lastProduction >= 0 && !greenSinceLastProduction) {
            violations.add("no passing run of the tests came after the last production change (call " + (lastProduction + 1) + " on "
                + lastProductionFile + ")");
        }
        return List.copyOf(violations);
    }

    /**
     * The production files the run changed that none of the worker's editor writes names, when the worker also wrote in a way this check
     * cannot read: a shell command, or an editor call that names no file (a patch). Such a file was written past the order this check reads,
     * so each one is a violation (28h-C27-22). Empty when the tree was not censused, when nothing unreadable was called, or when a jawata tool
     * that changes code was called, since its changes name no write and could not be told from the shell's.
     *
     * @param changed       the files the run changed, from the repository's root, or empty when the tree could not be censused
     * @param changesCode   for a tool's bare name, whether the engine's tool of that name changes code; empty for a name the engine has not
     * @param modelSaysTest for a changed file as the census names it, whether the loaded model marks it as test code, or empty when the model
     *                      does not hold the place, when the path rule decides
     */
    public static List<String> unseenWrites(List<ToolCall> made, Optional<List<String>> changed,
            Function<String, Optional<Boolean>> changesCode, Function<String, Optional<Boolean>> modelSaysTest) {
        if (changed.isEmpty() || made.stream().anyMatch(call -> !RunEffects.isCliShell(call.bareName())
                && changesCode.apply(call.bareName()).orElse(false))) {
            return List.of();
        }
        boolean unreadable = made.stream().anyMatch(call -> RunEffects.isCliShell(call.bareName())
            || RunEffects.isCliEditor(call.bareName()) && writtenFile(call).isEmpty());
        if (!unreadable) {
            return List.of();
        }
        List<String> named = made.stream().map(TestFirst::writtenFile).flatMap(Optional::stream).map(path -> path.replace('\\', '/')).toList();
        List<String> unseen = new ArrayList<>();
        for (String file : changed.get()) {
            String path = file.replace('\\', '/');
            if (modelSaysTest.apply(path).orElseGet(() -> isTestPath(path)) || OwnWrites.isDocument(path)
                    || named.stream().anyMatch(write -> write.equals(path) || write.endsWith("/" + path))) {
                continue;
            }
            unseen.add(path + " was changed by a write the test-first check cannot read (a shell command or a patch), so the order of its"
                + " test and its code is not known");
        }
        return List.copyOf(unseen);
    }

    /**
     * Whether a file a worker wrote is test code: what the loaded model says of it, and only for a place the model does not hold (a project
     * the engine has not loaded, a file that is no source of one) the path rule (28h-C27-24). A relative path is given to the model in full,
     * from the folder the worker ran in.
     */
    private static boolean isTest(String path, Path root, Function<String, Optional<Boolean>> modelSaysTest) {
        String inFull = path;
        try {
            if (root != null && !Path.of(path).isAbsolute()) {
                inFull = root.resolve(path).toString();
            }
        } catch (java.nio.file.InvalidPathException unusual) {
            inFull = path;
        }
        return modelSaysTest.apply(inFull).orElseGet(() -> isTestPath(below(path, root)));
    }

    /** The path below the worker's folder when it lies in it, else as it was named. */
    private static String below(String path, Path root) {
        if (root == null) {
            return path;
        }
        try {
            Path named = Path.of(path);
            Path base = root.toAbsolutePath().normalize();
            return named.isAbsolute() && named.normalize().startsWith(base) ? base.relativize(named.normalize()).toString() : path;
        } catch (java.nio.file.InvalidPathException unusual) {
            return path;
        }
    }

    /** Whether the path is a test file: by the folder or the name the languages of this project give test code. */
    static boolean isTestPath(String path) {
        String[] segments = path.replace('\\', '/').split("/");
        for (int i = 0; i < segments.length - 1; i++) {
            String folder = segments[i];
            // a folder of fixtures is test material too (C27-24): a file there is read by a test, and is no production code
            if (folder.equals("test") || folder.equals("tests") || folder.equals("__tests__")
                    || folder.endsWith(".tests") || folder.endsWith(".test")
                    || folder.startsWith("test-") || folder.equals("testdata") || folder.equals("fixtures") || folder.equals("__fixtures__")) {
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
