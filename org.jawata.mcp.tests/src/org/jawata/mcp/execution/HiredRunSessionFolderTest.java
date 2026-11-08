package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.access.SessionFolders;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.step.GateOutcome;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h S13 (D20, ACL4): a hired brain's session, the plan id it presents to the resident, is concerned with the folder it was hired in
 * for as long as it works. The observation is taken from a gate that runs while the stand-in ACP agent is still working (the agent reports an
 * editing call, waits, and only then answers), so the binding is read in the middle of the run and again after it.
 */
class HiredRunSessionFolderTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String SEAT = "---\nname: cataloguer\nbrain: cursor\naccepts_uncontained_writes: true\nttl_secs: 60\nmax_tool_steps: 8\n---\n\nbody\n";

    private H2ExperienceStore store;
    private Path dir;
    private PlanLedger plans;
    private final SessionFolders folders = new SessionFolders();
    private AcpBrainSession.Program agent;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("jawata-hire-folder-").toRealPath();
        store = H2ExperienceStore.openMemory();
        plans = new PlanLedger();
        try (InputStream in = HiredRunSessionFolderTest.class.getResourceAsStream("/test-resources/acp/FakeAcpAgent.java.txt")) {
            assertNotNull(in, "the stand-in agent is not among the test resources");
            Files.write(dir.resolve("FakeAcpAgent.java"), in.readAllBytes());
        }
        Path java = Path.of(System.getProperty("java.home"), "bin", "java");
        agent = new AcpBrainSession.Program(List.of(java.toString(), dir.resolve("FakeAcpAgent.java").toString()),
            Map.of("FAKE_ACP_STATE", dir.toString()));
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
        try (var walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private HiredRun.Proposal hire(HiredRun run, List<Optional<Path>> seenDuring) throws Exception {
        HiredRun.GateRunner gates = gate -> {
            // The newest plan in the ledger is this hire's; its id is the session the brain presents.
            List<PlanLedger.PlanView> views = plans.view();
            seenDuring.add(folders.of(views.get(views.size() - 1).id().value()));
            return new GateOutcome(gate.tool(), gate.gateArguments(), 0, true);
        };
        return run.issue(SeatHire.read(SEAT), HireSite.in(dir), "EDITS:1",
            List.of(new GatedStep(0, "compile_workspace", JSON.createObjectNode())), gates, List.of(), Optional.of(agent));
    }

    @Test
    @DisplayName("ACL4: while a hire's brain works its session is bound to the working directory the hire gave it, and the binding is gone when the run ends")
    void aHiresSessionIsBoundForTheRunOnly() throws Exception {
        HiredRun run = new HiredRun(HostProcesses.system(), new RuntimeArtifactStore(dir.resolve("artifacts")), plans,
            new LearnerEventStore(store), null, null, folders);
        List<Optional<Path>> seenDuring = new ArrayList<>();

        HiredRun.Proposal proposal = hire(run, seenDuring);

        assertAll(
            // the registry keeps a place under its one name (case folded where the filesystem ignores case, as on macOS), so the folder is
            // compared as the same place, not as the same spelling
            () -> assertEquals(1, seenDuring.size(), "the gate that ran during the brain's work was not asked once"),
            () -> assertTrue(seenDuring.get(0).isPresent() && java.nio.file.Files.isSameFile(seenDuring.get(0).get(), dir),
                "the gate that ran during the brain's work did not see the hire's folder: " + seenDuring),
            () -> assertTrue(folders.of(proposal.plan().value()).isEmpty(), "the binding outlived the run"));
    }

    @Test
    @DisplayName("ACL4, the control: a run built without the registry binds nothing, so the registry is the only thing that did")
    void withoutTheRegistryNothingIsBound() throws Exception {
        HiredRun run = new HiredRun(HostProcesses.system(), new RuntimeArtifactStore(dir.resolve("artifacts")), plans,
            new LearnerEventStore(store));
        List<Optional<Path>> seenDuring = new ArrayList<>();

        hire(run, seenDuring);

        assertEquals(List.of(Optional.empty()), seenDuring);
    }
}
