package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.mcp.contract.FakeHost;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.learn.PlannedStep;
import org.jawata.mcp.step.PlanId;
import org.jawata.spi.host.EngineDirectories;
import org.jawata.spi.host.EngineHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h item 9 (i) slice 2: the engine's one plan ledger keeps the sprint plans a pm enters in the workspace's own folder, and is the
 * in-memory one when the host gives no workspace folder.
 */
class JawataEnginePlanLedgerTest {

    @TempDir
    Path scratch;

    private static PlannedStep checked(String title, String tool) {
        return new PlannedStep(title, tool, new ObjectMapper().createObjectNode(), null, false, false);
    }

    @Test
    @DisplayName("the engine's ledger writes plan-ledger.json in the host's workspace folder, and a second ledger holds the plan with the same id")
    void theEnginesLedgerIsKeptInTheWorkspaceFolder() {
        FakeHost host = new FakeHost(scratch.resolve("engine"), Map.of());
        PlanLedger first = JawataEngine.planLedgerFor(host);
        PlanId id = first.openSprint("sprint", List.of(checked("A", "compile_workspace")));

        PlanLedger second = JawataEngine.planLedgerFor(host);

        assertEquals(true, Files.isRegularFile(host.directories().workspaceRoot().orElseThrow().resolve("plan-ledger.json")));
        assertEquals(1, second.planCount());
        assertEquals(id, second.view().get(0).id());
    }

    /** A host that answers only its folders, and those answer only that the workspace has no folder of its own. */
    private static EngineHost hostWithoutAWorkspaceFolder() {
        EngineDirectories folders = (EngineDirectories) Proxy.newProxyInstance(EngineDirectories.class.getClassLoader(),
            new Class<?>[] {EngineDirectories.class}, (proxy, method, arguments) -> "workspaceRoot".equals(method.getName()) ? Optional.empty() : null);
        return (EngineHost) Proxy.newProxyInstance(EngineHost.class.getClassLoader(), new Class<?>[] {EngineHost.class},
            (proxy, method, arguments) -> "directories".equals(method.getName()) ? folders : null);
    }

    @Test
    @DisplayName("a host with no workspace folder gets a ledger in memory: it accepts a sprint plan and writes no plan-ledger.json anywhere")
    void aHostWithoutAWorkspaceFolderGetsALedgerInMemory() throws IOException {
        PlanLedger ledger = JawataEngine.planLedgerFor(hostWithoutAWorkspaceFolder());

        ledger.openSprint("sprint", List.of(checked("A", "compile_workspace")));

        assertEquals(1, ledger.planCount());
        try (var files = Files.walk(scratch)) {
            assertEquals(List.of(), files.filter(file -> file.getFileName().toString().equals("plan-ledger.json")).toList());
        }
    }
}
