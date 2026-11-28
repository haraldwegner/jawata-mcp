package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Optional;
import java.util.function.Function;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h z31 piece 5 (z215): jawata's own {@code shell} tool changes code as far as a seat is concerned, so a read-only seat is not offered it;
 * a run's record counts its calls as shell calls all the same, because what a command did is not in the tool's name.
 */
class JawataShellIsAShellTest {

    private static final String SHELL = "mcp__jawata-javata-dev__shell";

    /** The engine's answer as the registry gives it: its shell says it changes code. */
    private static final Function<String, Optional<Boolean>> ENGINE = bare -> "shell".equals(bare) ? Optional.of(true) : Optional.empty();

    private static ToolCall shell(String command) {
        return new ToolCall(SHELL, new ObjectMapper().createObjectNode().put("command", command).put("folder", "/tmp"));
    }

    @Test
    @DisplayName("28h z215: a run's calls are sorted with jawata's shell on the shell line, not among the code changes")
    void theClassificationFilesItAsAShell() {
        RunEffects.Classification c = RunEffects.classify(List.of(SHELL, "shell"), 0, ENGINE, Optional.empty());

        assertAll(
            () -> assertEquals(List.of(SHELL, "shell"), c.shell()),
            () -> assertEquals(List.of(), c.changesCode()),
            () -> assertFalse(shell("ls").changesCode(ENGINE), "the instruction score counts a shell call as a code change"));
    }

    @Test
    @DisplayName("28h z215: a command on Java in jawata's shell is reported as shell on Java, and not as a jawata call")
    void theCardReportsItAsShellOnJava() {
        ToolUse use = ToolUse.of(List.of(shell("cat src/Foo.java # jawata-fallback: a comment line"), shell("ls")), 2, "shell"::equals);

        assertAll(
            () -> assertEquals(1, use.shellOnJava().size()),
            () -> assertEquals(0, use.jawata()),
            () -> assertEquals(1, use.other()));
    }

    @Test
    @DisplayName("28h z215: the test-first check does not stand down for a run that used jawata's shell: a file no editor named is reported")
    void theTestFirstCheckStillSeesAShellWrite() {
        List<String> unseen = TestFirst.unseenWrites(List.of(shell("python3 write.py")), Optional.of(List.of("src/Foo.java")), ENGINE,
            path -> Optional.of(false));

        assertEquals(1, unseen.size(), String.valueOf(unseen));
    }
}
