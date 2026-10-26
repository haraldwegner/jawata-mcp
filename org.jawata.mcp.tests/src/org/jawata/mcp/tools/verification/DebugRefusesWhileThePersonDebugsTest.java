package org.jawata.mcp.tools.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;

import org.jawata.mcp.contract.FakeHost;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28k, Stage 9 (contract §4.2, A3-2): the person wins the debugger. While the person has a debug launch of their own, the agent
 * neither starts a program nor attaches to one, and says why; the moment the person's launch is over, the tool answers as it always did.
 */
class DebugRefusesWhileThePersonDebugsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path scratch;

    private DebugTool toolOver(FakeHost.Turn turn) {
        return new DebugTool(() -> null, new RuntimeSessionRegistry(), new RuntimeArtifactStore(scratch.resolve("runtime")), null, turn);
    }

    @Test
    @DisplayName("launch and attach are refused with a reason while the person is debugging")
    void launchAndAttachAreRefusedWhileThePersonDebugs() throws Exception {
        FakeHost.Turn turn = new FakeHost.Turn();
        turn.personStartsDebugging();
        DebugTool tool = toolOver(turn);

        ToolResponse launch = tool.execute(JSON.readTree("{\"action\":\"launch\",\"mainClass\":\"p.Main\",\"classpath\":\".\"}"));
        ToolResponse attach = tool.execute(JSON.readTree("{\"action\":\"attach\",\"pid\":1}"));

        assertFalse(launch.isSuccess());
        assertEquals("PERSON_IS_DEBUGGING", launch.getError().getCode());
        assertFalse(attach.isSuccess());
        assertEquals("PERSON_IS_DEBUGGING", attach.getError().getCode());
        assertNotNull(launch.getError().getHint(), "the refusal says what to do next");
    }

    @Test
    @DisplayName("while the person is not debugging the same call is answered by the tool itself, not refused for the person")
    void theAgentsTurnIsNotRefused() throws Exception {
        DebugTool tool = toolOver(new FakeHost.Turn());

        ToolResponse attach = tool.execute(JSON.readTree("{\"action\":\"attach\"}"));

        assertEquals("INVALID_PARAMETER", attach.getError().getCode(), "the pid is what is missing, which is the tool's own answer");
    }
}
