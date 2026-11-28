package org.jawata.mcp.access;

import java.util.ArrayList;
import java.util.List;

/**
 * 28h z31: which command lines run more than a single test. Each command of the line is judged by the program it starts, so a line that only
 * mentions a test run is not one. A build tool counts when it would run its tests and no single test is named. This reads what is written: a
 * script it does not know by name can still run anything.
 */
public final class TestCommands {

    /** Words in front of a program that only change how it runs. */
    private static final List<String> WRAPPERS = List.of("timeout", "nice", "nohup", "env", "time", "stdbuf", "command", "exec");

    private static final List<String> MAVEN_GOALS = List.of("test", "verify", "install", "package", "integration-test", "deploy");

    private TestCommands() {
    }

    /** Whether the command line runs more than a single test. */
    public static boolean runsMoreThanOneTest(String command) {
        return Shell.commands(command).stream().anyMatch(TestCommands::wider);
    }

    private static boolean wider(List<String> words) {
        int at = 0;
        while (at < words.size() && (words.get(at).matches("[A-Za-z_][A-Za-z0-9_]*=.*") || WRAPPERS.contains(name(words.get(at)))
                || words.get(at).startsWith("-") || words.get(at).matches("\\d+[smhd]?"))) {
            at++;
        }
        if (at >= words.size()) {
            return false;
        }
        String program = name(words.get(at));
        List<String> rest = words.subList(at + 1, words.size());
        if (program.equals("run-suite.sh") || program.equals("release-gate.sh")) {
            return true;
        }
        if (program.startsWith("jawata-hook-guard")) {
            int split = rest.indexOf("--");
            return split >= 0 && wider(rest.subList(split + 1, rest.size()));
        }
        List<String> plain = rest.stream().filter(word -> !word.startsWith("-")).toList();
        return switch (program) {
            case "bash", "sh", "zsh", "cmd", "cmd.exe", "powershell", "pwsh" -> shellRuns(rest);
            case "mvn", "mvnw", "mvnd" -> rest.stream().anyMatch(MAVEN_GOALS::contains)
                    && rest.stream().noneMatch(word -> word.startsWith("-Dtest=") || word.startsWith("-Dit.test=")
                            || word.equals("-DskipTests") || word.equals("-DskipTests=true") || word.equals("-Dmaven.test.skip=true"));
            case "gradle", "gradlew" -> rest.stream().anyMatch(word -> word.matches("(.*:)?(test|check|build)"))
                    && !rest.contains("--tests") && !(rest.contains("-x") && rest.contains("test"));
            case "cargo" -> !plain.isEmpty() && plain.get(0).equals("test") && !hasFilter(rest);
            case "npm", "yarn", "pnpm" -> !plain.isEmpty()
                    && (plain.get(0).equals("test") || plain.get(0).equals("run") && plain.size() > 1 && plain.get(1).startsWith("test"));
            case "pytest" -> plain.isEmpty() && !rest.contains("-k");
            case "go" -> !plain.isEmpty() && plain.get(0).equals("test") && !rest.contains("-run");
            case "node" -> rest.contains("--test") && plain.isEmpty();
            case "java" -> rest.contains("-runTests") && rest.stream().noneMatch(word -> word.startsWith("-Djawata.test.classlist="));
            default -> false;
        };
    }

    /** A shell runs what follows {@code -c} as a line of its own, or else the script it is given. */
    private static boolean shellRuns(List<String> rest) {
        for (int i = 0; i + 1 < rest.size(); i++) {
            if (rest.get(i).matches("(?i)-[a-z]*c|/c|-command")) {
                return runsMoreThanOneTest(String.join(" ", rest.subList(i + 1, rest.size())));
            }
        }
        List<String> script = new ArrayList<>(rest);
        while (!script.isEmpty() && script.get(0).startsWith("-")) {
            script.remove(0);
        }
        return !script.isEmpty() && wider(script);
    }

    /** Whether {@code cargo test} is given the name of a test, which is a word after it that is no option and no option's value. */
    private static boolean hasFilter(List<String> rest) {
        boolean afterTest = false;
        for (int i = 0; i < rest.size(); i++) {
            String word = rest.get(i);
            if (word.equals("test")) {
                afterTest = true;
            } else if (List.of("-p", "--package", "--manifest-path", "--features", "--target", "--bin", "--test", "-j").contains(word)) {
                i++;
            } else if (afterTest && !word.startsWith("-")) {
                return true;
            }
        }
        return false;
    }

    private static String name(String word) {
        return word.substring(Math.max(word.lastIndexOf('/'), word.lastIndexOf('\\')) + 1);
    }
}
