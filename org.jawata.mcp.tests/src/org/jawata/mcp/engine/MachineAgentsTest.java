package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.Set;

import org.jawata.mcp.execution.BrainRoster;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Sprint 28k, Amendment 3, D15: what this machine supplies for the agent brain settings a host without the studio leaves unset. */
class MachineAgentsTest {

    @Test
    @DisplayName("the machine answers the three agent brain settings as the roster finds them, and no other setting")
    void theMachineAnswersTheThreeAgentSettingsAsTheRosterDoes() {
        assertEquals(Set.of("jawata.agent.brain", "jawata.agent.hireBrains", "jawata.agent.reviewer"), MachineAgents.KEYS);
        for (String key : MachineAgents.KEYS) {
            assertEquals(Optional.ofNullable(BrainRoster.onThisMachine().agentSettings().settings().get(key)), MachineAgents.setting(key), key);
        }
        assertEquals(Optional.empty(), MachineAgents.setting(SettingKeys.RUNTIME_DIR.name()), "a setting about folders is not the machine's to answer");
        assertEquals(Optional.empty(), MachineAgents.setting("jawata.agent.skillsDir"), "an agent setting outside the three is not answered either");
    }
}
