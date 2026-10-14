package org.jawata.mcp.coverage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28g D41, third clause — the suite's accepted aborts answered from the file that holds
 * them. The case behind it: a hired test manager reported "no queryable quarantine list, every
 * date and reason empty" about a repository whose {@code build/expected-aborts.*} files are that
 * list, because the seat's instruments read Java only.
 */
class SuitePolicyTest {

    private static final String LIST = """
        # Sprint 28a — the aborts Linux is ALLOWED to have, by REASON.
        # A header paragraph; it is not the reason for any pattern.

        # The embedding corpus is a large artifact not carried in the repository;
        # the tests that need it say so and skip.
        no corpus at -Djawata.embed.corpus

        # Two patterns written together share the comment above them.
        CPU profile too thin
        sampler returned too few samples

        a pattern nobody explained
        """;

    @Test
    void eachPatternTakesTheCommentBlockDirectlyAboveItAsItsReason() {
        List<Map<String, Object>> entries =
            SuitePolicy.parse(LIST.lines().toList(), "linux", "expected-aborts.linux");

        assertEquals(4, entries.size(), String.valueOf(entries));
        assertEquals("no corpus at -Djawata.embed.corpus", entries.get(0).get("pattern"));
        assertEquals("The embedding corpus is a large artifact not carried in the repository; "
            + "the tests that need it say so and skip.", entries.get(0).get("reason"),
            "the header paragraph above a blank line must NOT become the first pattern's reason");
        assertEquals("Two patterns written together share the comment above them.",
            entries.get(1).get("reason"));
        assertEquals(entries.get(1).get("reason"), entries.get(2).get("reason"),
            "consecutive patterns under one comment share its reason");
        assertNull(entries.get(3).get("reason"),
            "a pattern with no comment says it has none; it does not borrow the one above the gap");
        assertEquals("linux", entries.get(0).get("os"));
        assertEquals(6, entries.get(0).get("line"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void readsEveryListUnderBuildAndNothingElse(@TempDir Path root) throws IOException {
        Path build = Files.createDirectories(root.resolve("build"));
        Files.writeString(build.resolve("expected-aborts.linux"), LIST);
        Files.writeString(build.resolve("expected-aborts.windows"), "# why\nwin only\n");
        Files.writeString(build.resolve("expected-aborts.linux.bak"), "# stale\nold pattern\n");
        Files.writeString(build.resolve("notes.txt"), "not a list\n");

        Map<String, Object> policy = SuitePolicy.read(root);

        assertEquals(Boolean.TRUE, policy.get("found"));
        List<Map<String, Object>> files = (List<Map<String, Object>>) policy.get("files");
        assertEquals(List.of("linux", "windows"), files.stream().map(f -> f.get("os")).toList(),
            "a backup beside a list is not a list, and a file of another name is not read at all");
        assertEquals(5, policy.get("acceptedAbortCount"));
        assertNull(files.get(0).get("lastChanged"));
        assertTrue(String.valueOf(files.get(0).get("lastChangedUnavailableBecause")).contains("git"),
            "no history is reported with its reason, never as a missing field");
    }

    @Test
    void noListIsAnAbsenceNotAnEmptyList(@TempDir Path root) {
        Map<String, Object> policy = SuitePolicy.read(root);

        assertEquals(Boolean.FALSE, policy.get("found"));
        assertEquals(0, policy.get("acceptedAbortCount"));
        assertTrue(String.valueOf(policy.get("reason")).contains("absence"),
            "an empty acceptedAborts beside found=false must say which of the two it is");
    }

    @SuppressWarnings("unchecked")
    @Test
    void aListInAGitTreeCarriesTheDateItsFileLastChanged(@TempDir Path root) throws Exception {
        git(root, "init", "-q");
        Path build = Files.createDirectories(root.resolve("build"));
        Files.writeString(build.resolve("expected-aborts.linux"), "# why\nsome pattern\n");
        git(root, "add", ".");
        git(root, "-c", "user.email=t@example.org", "-c", "user.name=t", "commit", "-q", "-m", "x");

        Map<String, Object> file =
            ((List<Map<String, Object>>) SuitePolicy.read(root).get("files")).get(0);

        assertTrue(String.valueOf(file.get("lastChanged")).matches("\\d{4}-\\d{2}-\\d{2}"),
            "a committed list has the date of its last commit; got: " + file);
        assertFalse(file.containsKey("lastChangedUnavailableBecause"), String.valueOf(file));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aListGitHasNeverSeenHasNoDateAndSaysSo(@TempDir Path root) throws Exception {
        git(root, "init", "-q");
        Path build = Files.createDirectories(root.resolve("build"));
        Files.writeString(build.resolve("expected-aborts.linux"), "# why\nsome pattern\n");

        Map<String, Object> file =
            ((List<Map<String, Object>>) SuitePolicy.read(root).get("files")).get(0);

        assertNull(file.get("lastChanged"),
            "an uncommitted file has no commit date; inventing one would be the wrong answer");
        assertTrue(file.containsKey("lastChangedUnavailableBecause"), String.valueOf(file));
    }

    private static void git(Path in, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", in.toString()));
        command.addAll(List.of(args));
        Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        assertEquals(0, p.waitFor(), "git " + String.join(" ", args));
    }

    @Test
    void theAnswerClaimsNothingAboutTheThresholdStoreItNeverOpened(@TempDir Path root) {
        Map<String, Object> policy = SuitePolicy.read(root);

        assertFalse(policy.containsKey("coverageThreshold"),
            "a 'none set' here would be a claim about a store this reading did not look at");
    }
}
