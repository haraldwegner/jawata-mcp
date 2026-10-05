package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostOS;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.learn.GateOutcome;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.LearnerEvent;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28g S9a M5a — one hired run end to end, against the step's own gate
 * clause verbatim: <em>brief issued, gates run, journalled, a proposal record on
 * disk</em>. Each of the four is a separate assertion, because three of them can
 * hold while the fourth silently does not.
 *
 * <p>Driven by a shell script, so no model, API key or network is involved. The
 * script dialect exists for exactly this: a plain-text brain DECLARES its cost
 * and tool steps, so a fixture can produce any shape a real one could.</p>
 */
class HiredRunProposesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HostProcesses host = HostProcesses.system();
    private Path dir;
    private H2ExperienceStore store;
    private LearnerEventStore events;
    private RuntimeArtifactStore artifacts;
    private PlanLedger plans;

    @BeforeEach
    void setUp() throws Exception {
        assumeTrue(!HostOS.current().isWindows(), "the brain fixture is a POSIX shell script");
        dir = Files.createTempDirectory("jawata-hired-run-");
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
        artifacts = new RuntimeArtifactStore(dir.resolve("artifacts"));
        plans = new PlanLedger();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best effort; the temp dir is the OS's problem after this
                    }
                });
            }
        }
    }

    private HostCommand brain(String body) throws Exception {
        Path file = Files.createTempFile(dir, "brain-", ".sh");
        Files.writeString(file, "#!/bin/sh\n" + body + "\n");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        return HostCommand.of(file.toString());
    }

    private static JsonNode json(String text) {
        try {
            return JSON.readTree(text);
        } catch (Exception e) {
            throw new IllegalStateException("fixture json is not json: " + text, e);
        }
    }

    private static List<GatedStep> twoSteps() {
        return List.of(
            new GatedStep(0, "compile_workspace", json("{\"scope\":\"main\"}")),
            new GatedStep(1, "run_tests", json("{}")));
    }

    /** A gate that answered and found nothing. */
    private static GateOutcome passed(GatedStep step) {
        return new GateOutcome(step.tool(), step.gateArguments(), 0, true);
    }

    @Test
    @DisplayName("M5a's gate clause, all four: the brief is issued, the gates run, the run is journalled, and the record is on disk")
    void oneHiredRunEndToEnd() throws Exception {
        HostCommand command = brain("""
            echo "I would rename the field and add a test."
            echo "TOOL-STEPS: 4"
            echo "COST-USD: 0.23"
            """);
        HiredRun run = new HiredRun(host, artifacts, plans, events);

        HiredRun.Proposal proposal = run.issue(
            new ScriptDialect(command.executable(), List.of()), command,
            "Tidy the ledger's naming.", HiredBrain.Ceilings.of(Duration.ofSeconds(20)),
            twoSteps(), HiredRunProposesTest::passed);

        String record = Files.readString(proposal.record());
        assertAll(
            // 1. THE BRIEF WAS ISSUED — and the record carries it, so a reader can
            // tell what the brain was actually asked.
            () -> assertTrue(record.contains("Tidy the ledger's naming."),
                "the record must carry the brief it was issued"),
            () -> assertTrue(record.contains("I would rename the field"),
                "and what came back"),

            // 2. THE GATES RAN, both of them, and the LEDGER ruled.
            () -> assertEquals(2, proposal.gates().size(), "both steps' gates ran"),
            () -> assertEquals(2, proposal.stepsPassed(),
                "and the ledger advanced the plan past both"),
            () -> assertTrue(proposal.complete()),

            // 3. JOURNALLED. Nobody watched this happen, so the row is the only
            // thing that makes it answerable afterwards.
            () -> assertEquals(1L, events.countByKind()
                .getOrDefault(LearnerEvent.KIND_HIRED_RUN, 0L),
                "one row for one run"),

            // 4. A PROPOSAL RECORD ON DISK, listed as an artifact like any other.
            () -> assertTrue(Files.exists(proposal.record()), "the record is a file"),
            () -> assertTrue(artifacts.list().contains(proposal.artifactId()),
                "and it is an artifact the existing tooling lists"),
            () -> assertEquals(Boolean.FALSE,
                artifacts.readManifest(proposal.artifactId()).orElseThrow().get("applied"),
                "THE HEADLINE: a hired run PROPOSES. Nothing was applied, and the manifest"
                    + " says so where a reader of the artifact alone will see it"),
            () -> assertTrue(record.contains("NOTHING HERE WAS APPLIED"),
                "and the record says it in words too"),

            // THE CONTROL for the crashed-brain case: this run exited cleanly, so the
            // record must NOT carry the did-not-exit-cleanly line. Without this, emitting
            // that line unconditionally would pass every assertion over there.
            () -> assertFalse(record.contains("IT DID NOT EXIT CLEANLY"),
                "a clean run's record must not claim it died"),
            () -> assertFalse(record.contains("THE PLAN STOPPED HERE"),
                "nor that the plan stopped, when every step got through"));
    }

    @Test
    @DisplayName("a FAILING gate stops the proposal short, and the record says which step and why")
    void aFailingGateStopsItShort() throws Exception {
        HostCommand command = brain("echo \"done\"");
        HiredRun run = new HiredRun(host, artifacts, plans, events);

        // Step 0's gate passes; step 1's answers and finds two errors.
        HiredRun.Proposal proposal = run.issue(
            new ScriptDialect(command.executable(), List.of()), command,
            "A change that does not compile.", HiredBrain.Ceilings.of(Duration.ofSeconds(20)),
            twoSteps(),
            step -> step.index() == 0 ? passed(step)
                : new GateOutcome(step.tool(), step.gateArguments(), 2, true));

        assertAll(
            () -> assertEquals(1, proposal.stepsPassed(), "only the first step got through"),
            () -> assertFalse(proposal.complete()),
            () -> assertEquals(2, proposal.gates().size(),
                "both gates ran — it is the LEDGER that refused the second, not a skipped call"),
            () -> assertTrue(Files.readString(proposal.record()).contains("2 error(s)"),
                "and the record names what the failing gate found"),
            () -> assertTrue(Files.readString(proposal.record()).contains("THE PLAN STOPPED HERE"),
                "and the LEDGER's own reason for stopping, which the per-gate lines cannot"
                    + " give: a gate can report cleanly and the ledger still refuse"));
    }

    @Test
    @DisplayName("a gate that did NOT ANSWER stops it too, and the record does not read its zero as a pass")
    void aRefusedGateStopsItAndSaysSo() throws Exception {
        // The defect this whole chain is shaped around: a refused gate reports
        // ZERO errors, so a record that printed "passed" here would be lying with
        // a true number.
        HostCommand command = brain("echo \"done\"");
        HiredRun run = new HiredRun(host, artifacts, plans, events);

        HiredRun.Proposal proposal = run.issue(
            new ScriptDialect(command.executable(), List.of()), command,
            "A change whose gate could not run.", HiredBrain.Ceilings.of(Duration.ofSeconds(20)),
            twoSteps(),
            step -> new GateOutcome(step.tool(), step.gateArguments(), 0, false));

        String record = Files.readString(proposal.record());
        assertAll(
            () -> assertEquals(0, proposal.stepsPassed(), "nothing got through"),
            () -> assertTrue(record.contains("DID NOT ANSWER"),
                "the record says which case this is: " + record),
            () -> assertFalse(record.contains("step 0 (compile_workspace): passed"),
                "and it must NOT read a refused gate's zero as a pass"),
            () -> assertTrue(record.contains("THE PLAN STOPPED HERE"),
                "and the ledger's reason is in the record: " + record));
    }

    @Test
    @DisplayName("a run with no journal still proposes, and the record is unchanged by its absence")
    void aRunWithoutAJournalStillProposes() throws Exception {
        HostCommand command = brain("echo \"done\"");
        HiredRun run = new HiredRun(host, artifacts, plans, null);

        HiredRun.Proposal proposal = run.issue(
            new ScriptDialect(command.executable(), List.of()), command,
            "No journal wired.", HiredBrain.Ceilings.of(Duration.ofSeconds(20)),
            twoSteps(), HiredRunProposesTest::passed);

        assertAll(
            () -> assertTrue(Files.exists(proposal.record()),
                "the proposal is the deliverable; the journal is bookkeeping"),
            () -> assertEquals(2, proposal.stepsPassed()),
            () -> assertEquals(0L, events.countByKind()
                .getOrDefault(LearnerEvent.KIND_HIRED_RUN, 0L),
                "and the CONTROL for the journal assertion above: with no store wired, no row"
                    + " — so that assertion is about the journal and not about the run"));
    }

    @Test
    @DisplayName("a crashed brain's record names the EXIT STATUS — not the manifest")
    void aCrashedBrainsRecordNamesTheExitStatus() throws Exception {
        // The record used to point at the manifest for this, so a brain that SPOKE and
        // then died rendered exactly like one that answered: saidNothing() can only speak
        // for a run that produced no text at all. Separating a crash from silence is the
        // whole point of the outcome's shape, and it has to be legible in the file a
        // human reads.
        HostCommand command = brain("""
            echo "I got half way through."
            echo "Traceback: the adapter died" >&2
            exit 9
            """);
        HiredRun run = new HiredRun(host, artifacts, plans, events);

        HiredRun.Proposal proposal = run.issue(
            new ScriptDialect(command.executable(), List.of()), command,
            "A brief whose brain dies.", HiredBrain.Ceilings.of(Duration.ofSeconds(20)),
            twoSteps(), HiredRunProposesTest::passed);

        String record = Files.readString(proposal.record());
        Map<String, Object> manifest =
            artifacts.readManifest(proposal.artifactId()).orElseThrow();
        assertAll(
            () -> assertTrue(record.contains("I got half way through."),
                "PROOF OF LIFE: it really did speak before dying. Without this the case"
                    + " would be the silent one, which saidNothing() already covers"),
            () -> assertTrue(record.contains("IT DID NOT EXIT CLEANLY"),
                "and the RECORD says so, rather than pointing elsewhere: " + record),
            () -> assertTrue(record.contains("exit code 9"),
                "naming the status, which used to live only in the manifest"),
            () -> assertEquals(9, ((Number) manifest.get("exitCode")).intValue(),
                "the manifest still carries it — the record does not replace it"));
    }

    @Test
    @DisplayName("a reaped run's record reports NO cost rather than zero")
    void aReapedRunsRecordDoesNotInventACost() throws Exception {
        // Rust defect 2, carried through to the thing a human reads: cost arrives
        // on the terminal event, so a run killed on its clock never reports one.
        HostCommand command = brain("""
            echo "starting"
            sleep 60
            echo "COST-USD: 99.99"
            """);
        HiredRun run = new HiredRun(host, artifacts, plans, events);

        HiredRun.Proposal proposal = run.issue(
            new ScriptDialect(command.executable(), List.of()), command,
            "A run that will be reaped.", HiredBrain.Ceilings.of(Duration.ofSeconds(2)),
            twoSteps(), HiredRunProposesTest::passed);

        Map<String, Object> manifest =
            artifacts.readManifest(proposal.artifactId()).orElseThrow();
        assertAll(
            () -> assertTrue(proposal.outcome().timedOut(), "the wall clock reaped it"),
            () -> assertEquals(null, manifest.get("costUsd"),
                "ABSENT in the manifest, which a caller must not round to zero"),
            () -> assertEquals(Boolean.FALSE, manifest.get("sawTerminalCost"),
                "and the flag says which case it is"),
            () -> assertTrue(Files.readString(proposal.record())
                    .contains("NOT REPORTED (absent, which is not zero)"),
                "the record says it in words, because a human reads that and not the manifest"));
    }
}
