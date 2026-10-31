package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S7a: the seat's allowlist answers a brain's permission request by the tool's name. Cursor's agent, run live against the
 * engine, titles a tool of the resident "jawata-health_check: health_check", a form neither the bare name nor Claude's
 * {@code mcp__jawata__health_check} covers, so its first real call was rejected. This reads that form and only that form.
 */
class AcpToolTitleTest {

    @Test
    @DisplayName("S7a: the resident's tool is read out of Cursor's title, and a tool of another server, a built-in or a title with a command after the colon is not taken for one of ours")
    void theResidentsToolIsReadOutOfCursorsTitle() {
        assertEquals("health_check", AcpBrainSession.residentToolIn("jawata-health_check: health_check"));
        assertEquals("inspect", AcpBrainSession.residentToolIn("jawata: inspect"));
        assertNull(AcpBrainSession.residentToolIn("other-inspect: inspect"), "a tool of another server was taken for ours");
        assertNull(AcpBrainSession.residentToolIn("Read file"), "a built-in tool was taken for ours");
        assertNull(AcpBrainSession.residentToolIn("jawata-x: rm -rf /"), "a command after the colon was taken for a tool name");
        assertNull(AcpBrainSession.residentToolIn("MCP: tool"));
    }
}
