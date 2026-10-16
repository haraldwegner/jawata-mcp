package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.TreeSet;

import org.jawata.mcp.fixtures.InertShippedTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The how-to map is keyed by tool NAME, and a string is not touched by a reference-updating
 * refactoring (Sprint 28g C9a-1). Every key must be a tool that ships, so a renamed or retired tool
 * cannot leave its one-line how-to behind to be pushed at an agent for a tool that is gone.
 */
class ToolHowToNamesOnlyShippedToolsTest {

    @Test
    @DisplayName("every tool the how-to map is keyed by ships")
    void everyHowToKeyIsAShippedTool() {
        Set<String> stale = new TreeSet<>(ToolHowTo.toolsWithAHowTo());
        stale.removeAll(InertShippedTools.names());

        assertEquals(Set.of(), stale, "how-to entries for tools that do not ship");
    }
}
