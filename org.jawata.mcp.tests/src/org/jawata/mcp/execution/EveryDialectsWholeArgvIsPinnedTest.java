package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * S16-22: a dialect's argv is its contract with a vendor, and every flag in it is pinned.
 *
 * <p>Adding {@code --permission-mode} to claude's argv turned no test red — 96 classes stayed green
 * over a change to the most important argument this product sends — because the dialect's own
 * tests asserted the events it reads and never the command it builds, and the spy that stands in
 * for the process checks containment, not argv. The first fix pinned that one flag. This pins the
 * WHOLE list, in the configurations the hire path uses, so adding, removing, reordering or
 * changing the value of any flag is a red test rather than a silent change to what a vendor CLI is
 * asked to do.</p>
 *
 * <p>Whole lists, not {@code contains}: a containment check passes on any element and cannot see
 * an order, a duplicate or a missing value.</p>
 */
class EveryDialectsWholeArgvIsPinnedTest {

    private static final String BRIEF = "describe the package";

    @Test
    @DisplayName("claude, read-only, with no resident endpoint: the user's own setup is kept, by design")
    void claudeWithoutAnEndpoint() {
        assertEquals(List.of("claude", "-p", BRIEF, "--output-format", "stream-json",
                "--permission-mode", "plan", "--verbose", "--max-turns", "12",
                "--allowedTools", "inspect", "search_symbols"),
            new ClaudeCodeDialect().command(BRIEF, List.of("inspect", "search_symbols")));
    }

    @Test
    @DisplayName("claude, read-only, given the hiring resident: only that server, none of the user's settings, no built-in tools")
    void claudeWithTheResidentsEndpoint() {
        SelfEndpoint self = new SelfEndpoint("http://127.0.0.1:1/mcp", "t0ken");

        assertEquals(List.of("claude", "-p", BRIEF, "--output-format", "stream-json",
                "--permission-mode", "plan", "--verbose", "--max-turns", "7",
                "--model", "claude-sonnet-5",
                "--strict-mcp-config", "--mcp-config", self.claudeMcpConfig(),
                "--setting-sources", "project", "--tools", "",
                "--allowedTools", "mcp__jawata__inspect"),
            new ClaudeCodeDialect("claude", 7, "claude-sonnet-5", true, self)
                .command(BRIEF, List.of("inspect")));
    }

    @Test
    @DisplayName("claude, write-permitted by explicit request: manual permission mode and nothing else changes")
    void claudeThatMayWrite() {
        assertEquals(List.of("claude", "-p", BRIEF, "--output-format", "stream-json",
                "--permission-mode", "manual", "--verbose", "--max-turns", "12"),
            new ClaudeCodeDialect("claude", 12, null, false).command(BRIEF, List.of()));
    }

    @Test
    @DisplayName("codex: exec, JSONL, the read-only sandbox, the untrusted-directory skip, the brief LAST")
    void codex() {
        assertEquals(List.of("codex", "exec", "--json", "--sandbox", "read-only",
                "--skip-git-repo-check", BRIEF),
            new CodexDialect().command(BRIEF, List.of("shell")));
        assertEquals(List.of("codex", "exec", "--json", "--sandbox", "workspace-write",
                "--skip-git-repo-check", BRIEF),
            new CodexDialect("codex", "workspace-write").command(BRIEF, List.of()));
    }

    @Test
    @DisplayName("cursor-agent: print, stream-json, trust, plan mode and the sandbox, the model, the brief LAST")
    void cursor() {
        assertEquals(List.of("cursor-agent", "--print", "--output-format", "stream-json",
                "--trust", "--mode", "plan", "--sandbox", "enabled", BRIEF),
            new CursorDialect().command(BRIEF, List.of("Read")));
        assertEquals(List.of("cursor-agent", "--print", "--output-format", "stream-json",
                "--trust", "--model", "auto", BRIEF),
            new CursorDialect("cursor-agent", false, "auto").command(BRIEF, List.of()));
    }

    @Test
    @DisplayName("copilot: JSONL, both write tools denied, built-in servers off, only the seat's tools")
    void copilot() {
        assertEquals(List.of("copilot", "-p", BRIEF, "--output-format", "json",
                "--deny-tool", "write", "--deny-tool", "shell",
                "--disable-builtin-mcps",
                "--available-tools", "view", "grep"),
            new CopilotDialect().command(BRIEF, List.of("view", "grep")));
        assertEquals(List.of("copilot", "-p", BRIEF, "--output-format", "json",
                "--disable-mcp-server", "some-local-server"),
            new CopilotDialect("copilot", false, List.of("some-local-server"), false)
                .command(BRIEF, List.of()));
    }
}
