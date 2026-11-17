package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, D57 (j): the principal's brain has no shell, so what it still looks up (git status, history and the diff of the work, and the
 * running processes) comes from this tool, which changes nothing.
 */
class LookupToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final LookupTool lookup = new LookupTool();

    private static void git(Path dir, String... command) throws Exception {
        List<String> line = new java.util.ArrayList<>(List.of("git", "-C", dir.toString()));
        line.addAll(List.of(command));
        Process process = new ProcessBuilder(line).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), out);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> ask(String kind, Path folder) {
        ToolResponse answer = lookup.execute(JSON.createObjectNode().put("kind", kind).put("folder", folder == null ? "" : folder.toString()));
        assertTrue(answer.isSuccess(), () -> String.valueOf(answer.getError()));
        return (Map<String, Object>) answer.getData();
    }

    @Test
    @DisplayName("28h D57 (j): git status, history and diff of a repository are answered, and asking changes nothing")
    void gitIsLookedUp(@TempDir Path repo) throws Exception {
        git(repo, "init", "-q", "-b", "main");
        git(repo, "config", "user.name", "Person");
        git(repo, "config", "user.email", "person@example.invalid");
        Files.writeString(repo.resolve("a.txt"), "one\n");
        git(repo, "add", "a.txt");
        git(repo, "commit", "-q", "-m", "the first commit");
        Files.writeString(repo.resolve("a.txt"), "two\n");

        assertTrue(String.valueOf(ask("git_status", repo).get("output")).contains("a.txt"));
        assertTrue(String.valueOf(ask("git_log", repo).get("output")).contains("the first commit"));
        assertTrue(String.valueOf(ask("git_diff", repo).get("output")).contains("+two"));
        assertEquals("two\n", Files.readString(repo.resolve("a.txt")), "a lookup changed the file");
    }

    @Test
    @DisplayName("28h D57 (j): the running processes are listed, this test's own among them")
    @SuppressWarnings("unchecked")
    void processesAreListed() {
        List<Map<String, Object>> processes = (List<Map<String, Object>>) ask("processes", null).get("processes");

        long self = ProcessHandle.current().pid();
        assertTrue(processes.stream().anyMatch(p -> Long.valueOf(self).equals(p.get("pid"))), "this JVM is not among " + processes.size());
    }

    @Test
    @DisplayName("28h D57 (j): the tool says it changes no code, and an unknown kind is refused")
    void itChangesNoCodeAndRefusesTheUnknown() {
        assertFalse(lookup.changesCode());
        assertFalse(lookup.execute(JSON.createObjectNode().put("kind", "git_push")).isSuccess(), "a write was taken for a lookup");
    }
}
