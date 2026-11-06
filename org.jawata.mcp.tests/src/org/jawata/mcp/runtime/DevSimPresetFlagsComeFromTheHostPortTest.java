package org.jawata.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.jawata.spi.host.HostRuns;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, the 28k ledger row A19-3: the debug agent and the recording flag that a host starts a program with are written ONCE, on the
 * host port, and the engine's own dev/sim preset reads them from there. Before, the Eclipse plug-in repeated both as string constants
 * because the preset's package is not visible to it, so a change of the engine's flags left the plug-in on the old ones and only the
 * agent option was held by a test. A test that holds the preset equal to the port is what keeps a drift out of either side.
 */
class DevSimPresetFlagsComeFromTheHostPortTest {

    @Test
    @DisplayName("the preset a host-started JVM gets carries exactly the port's debug agent and the port's recording flag")
    void thePresetCarriesThePortsTwoFlags() {
        List<String> args = DevSimPreset.jvmArgs();

        assertTrue(args.contains(HostRuns.DEBUG_AGENT), "the preset does not carry the port's debug agent: " + args);
        assertTrue(args.contains(HostRuns.RECORDING), "the preset does not carry the port's recording flag: " + args);
    }

    @Test
    @DisplayName("the preset for a JVM the debugger launches is the same two flags, held at the start: the agent differs by suspend=y and nothing else")
    void theLaunchPresetDiffersOnlyInHoldingTheProgram() {
        List<String> launch = DevSimPreset.jvmArgsForLaunch();

        assertTrue(launch.contains(HostRuns.DEBUG_AGENT.replace("suspend=n", "suspend=y")), "the launch preset's agent is not the port's with suspend=y: " + launch);
        assertTrue(launch.contains(HostRuns.RECORDING), "the launch preset lost the recording flag: " + launch);
        assertEquals(DevSimPreset.jvmArgs().size(), launch.size(), "the launch preset has a different number of flags than the host-started one");
    }

    @Test
    @DisplayName("CONTROL: the port's agent never holds the program at its start and listens on loopback, the two rules the contract states")
    void thePortsAgentIsLoopbackAndNeverHolds() {
        assertTrue(HostRuns.DEBUG_AGENT.contains("suspend=n"), HostRuns.DEBUG_AGENT);
        assertTrue(HostRuns.DEBUG_AGENT.contains("address=127.0.0.1:0"), HostRuns.DEBUG_AGENT);
    }
}
