package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostOS;
import org.jawata.core.host.HostProcessOutcome;
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

    /**
     * A process boundary that REMEMBERS the command line it was asked to start, and then starts a
     * harmless stand-in instead.
     *
     * <p>Why it exists: every brain a {@link SeatHire} can construct is a real vendor CLI, so
     * driving the seat form for real would spawn {@code claude} from a unit test. Recording the
     * argv is what makes the seat's own declaration observable without that — the flags under test
     * are the ones this product produced, and the process that actually runs is a shell script.</p>
     */
    private static final class RecordsTheArgv implements HostProcesses {

        private final HostProcesses real;
        private final HostCommand standIn;
        private List<String> asked;

        RecordsTheArgv(HostProcesses real, HostCommand standIn) {
            this.real = real;
            this.standIn = standIn;
        }

        List<String> asked() {
            return asked == null ? List.of() : asked;
        }

        @Override
        public Process start(HostCommand command) throws IOException {
            this.asked = command.argv();
            return real.start(standIn);
        }

        @Override
        public HostProcessOutcome run(HostCommand command) throws InterruptedException {
            return real.run(command);
        }

        @Override
        public List<String> executableCandidates(String base) {
            return real.executableCandidates(base);
        }

        @Override
        public List<String> wrapperNames(String base) {
            return real.wrapperNames(base);
        }
    }

    @Test
    @DisplayName("S16-38: the HIRE refuses a seat whose brain is not contained by a mechanism, whichever caller hands it a SeatHire")
    void theHireItselfRefusesAnUncontainedBrainWithoutTheSeatsOptIn() throws Exception {
        // The hire tool checks this first, with a friendlier answer. The scheduler (and any caller
        // after it) reaches HiredRun without passing the tool, so the check has to live where every
        // path passes. Nothing may be spawned, planned, artifacted or journalled on a refusal.
        HostCommand standIn = brain("echo '{\"type\":\"result\",\"result\":\"done\"}'");
        RecordsTheArgv spy = new RecordsTheArgv(host, standIn);
        HiredRun run = new HiredRun(spy, artifacts, plans, events);
        SeatHire cursor = SeatHire.read("---\nname: pm\nbrain: cursor\nttl_secs: 30\n---\n\nbody\n");

        IllegalArgumentException refused = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalArgumentException.class,
            () -> run.issue(cursor, standIn, "Do it.", twoSteps(), HiredRunProposesTest::passed));

        assertAll(
            () -> assertTrue(refused.getMessage().contains("accepts_uncontained_writes"),
                "the way out is named: " + refused.getMessage()),
            () -> assertTrue(refused.getMessage().contains("INSTRUCTION"), refused.getMessage()),
            () -> assertEquals(List.of(), spy.asked(), "and no process was started"),
            () -> assertEquals(List.of(), artifacts.list(), "no artifact was made"),
            () -> assertEquals(0L, events.countByKind().getOrDefault(LearnerEvent.KIND_HIRED_RUN, 0L),
                "and nothing was journalled, because nothing ran"));
    }

    @Test
    @DisplayName("S16-38: the same seat WITH its opt-in is hired by the same call")
    void theSameSeatWithItsOptInIsHired() throws Exception {
        HostCommand standIn = brain("echo '{\"type\":\"result\",\"result\":\"done\",\"total_cost_usd\":0.01}'");
        HiredRun run = new HiredRun(new RecordsTheArgv(host, standIn), artifacts, plans, events);
        SeatHire cursor = SeatHire.read(
            "---\nname: pm\nbrain: cursor\naccepts_uncontained_writes: true\nttl_secs: 30\n---\n\nbody\n");

        HiredRun.Proposal proposal = run.issue(cursor, standIn, "Do it.", twoSteps(),
            HiredRunProposesTest::passed);

        assertTrue(Files.exists(proposal.record()), "the hire ran and wrote its record");
    }

    @Test
    @DisplayName("D37: a seat's own declared brain, tools and model reach the command line that is started")
    void aSeatsDeclarationReachesTheRunItHires() throws Exception {
        // THE STAND-IN SPEAKS THE SEAT'S OWN BRAIN, not the script dialect's declarations. The spy
        // replaces the PROCESS and not the DIALECT — which is the point, since the dialect is what
        // the seat chose — so a stand-in emitting `TOOL-STEPS: 3` would be unreadable to claude's
        // reader and the step count would come back zero. These two lines are claude's measured
        // shape: three tool_use BLOCKS in one turn, then the terminal event with its cost.
        HostCommand standIn = brain("""
            echo '{"type":"assistant","message":{"role":"assistant","content":[{"type":"text","text":"I would catalogue the execution package."},{"type":"tool_use"},{"type":"tool_use"},{"type":"tool_use"}]}}'
            echo '{"type":"result","result":"catalogued","total_cost_usd":0.11}'
            """);
        RecordsTheArgv spy = new RecordsTheArgv(host, standIn);
        HiredRun run = new HiredRun(spy, artifacts, plans, events);

        // A seat of the shape jawata-studio ships, plus the brain field D37 adds.
        SeatHire seat = SeatHire.read("""
            ---
            name: cataloguer
            brain: claude-code
            accepts_uncontained_writes: true
            model: claude-sonnet-5
            tools: [search_symbols, inspect, experience]
            ttl_secs: 30
            max_tool_steps: 25
            cost_budget_usd: 1.5
            ---

            You are the cataloguer seat.
            """);

        HiredRun.Proposal proposal = run.issue(seat, standIn, "Catalogue the execution package.",
            twoSteps(), HiredRunProposesTest::passed);
        List<String> argv = spy.asked();
        int allowlist = argv.indexOf("--allowedTools");

        assertAll(
            // THE WIRING D37 IS FOR. Before it, this method passed an empty list with a comment
            // saying nothing held a seat's tool set; the seat held it all along and nothing read it.
            () -> assertTrue(allowlist >= 0, "the seat's tools reached the command line: " + argv),
            () -> assertEquals(List.of("search_symbols", "inspect", "experience"),
                argv.subList(allowlist + 1, argv.size()),
                "in the seat's own order, each its own argument: " + argv),
            // The brain the seat NAMED, not one derived from a model that happens to say "claude".
            () -> assertEquals("claude", argv.get(0), argv.toString()),
            () -> assertTrue(argv.contains("claude-sonnet-5"),
                "and the model it asked that brain for: " + argv),
            // The seat's ceilings travelled too, and BOTH are measured against what came back:
            // three tool_use blocks against the seat's ceiling of 25, and $0.11 against its
            // budget of $1.50. Neither fired, which is what makes the run a clean one rather
            // than a bound that was never reached.
            () -> assertEquals(3, proposal.outcome().toolSteps()),
            () -> assertEquals(0.11, proposal.outcome().costUsd().orElseThrow(), 1e-9),
            () -> assertTrue(proposal.outcome().stoppedBy().isEmpty(),
                "neither of the seat's two ceilings fired"),
            // And this seat's budget CAN fire, because it chose the one brain that reports
            // dollars — the other half of the pair the sibling case pins.
            () -> assertTrue(seat.budgetReport().orElseThrow().contains("ENFORCEABLE"),
                seat.budgetReport().orElseThrow()),
            // And the record a human reads names the attachment, which is the clause the seat
            // form inherits from step 1 rather than re-stating.
            () -> assertTrue(Files.readString(proposal.record()).contains("--allowedTools"),
                "the proposal says how this brain was told which tools to use"),
            // 4.4.1: THE SEAT'S RULES REACH THE BRAIN. Until then the prompt was the brief alone and
            // the stance below the frontmatter was read by nothing — a hired cataloguer ran as a
            // generic agent holding someone else's brief.
            () -> assertTrue(argv.get(2).contains("You are the cataloguer seat."),
                "the stance is in what the brain is asked: " + argv.get(2)),
            () -> assertTrue(argv.get(2).indexOf("You are the cataloguer seat.")
                    < argv.get(2).indexOf("Catalogue the execution package."),
                "the rules come before the brief: " + argv.get(2)),
            () -> assertFalse(argv.get(2).contains("cost_budget_usd"),
                "and the frontmatter is not: " + argv.get(2)),
            () -> assertTrue(Files.readString(proposal.record()).contains(
                "You are the cataloguer seat."),
                "the record shows what the brain was actually asked, not half of it"));
    }

    @Test
    @DisplayName("4.4.1: a hire given the resident's endpoint starts the brain with ONLY that server, and an allowlist in the names it sees")
    void aHireGivenTheResidentsEndpointTalksToThatResidentAlone() throws Exception {
        HostCommand standIn = brain("""
            echo '{"type":"result","result":"done","total_cost_usd":0.01}'
            """);
        RecordsTheArgv spy = new RecordsTheArgv(host, standIn);
        SelfEndpoint self = new SelfEndpoint("http://127.0.0.1:41234/mcp", "tok-123");
        HiredRun run = new HiredRun(spy, artifacts, plans, events, self);

        SeatHire seat = SeatHire.read("""
            ---
            name: cataloguer
            tools: [search_symbols, inspect]
            ttl_secs: 30
            ---

            You are the cataloguer seat.
            """);

        run.issue(seat, standIn, "Catalogue it.", twoSteps(), HiredRunProposesTest::passed);
        List<String> argv = spy.asked();
        int config = argv.indexOf("--mcp-config");
        int allowlist = argv.indexOf("--allowedTools");

        assertAll(
            // MEASURED in Sprint 28g: without these the brain loaded the user's every MCP server,
            // setting and hook — $2.26 for a one-word answer — and its allowlist of bare jawata
            // names matched nothing it could see.
            () -> assertTrue(argv.contains("--strict-mcp-config"), argv.toString()),
            () -> assertTrue(config >= 0, "the resident is handed over as the server: " + argv),
            () -> assertTrue(argv.get(config + 1).contains("http://127.0.0.1:41234/mcp")
                    && argv.get(config + 1).contains("Bearer tok-123"),
                "with its own URL and token: " + argv.get(config + 1)),
            () -> assertEquals("project", argv.get(argv.indexOf("--setting-sources") + 1),
                "and none of the user's settings or hooks: " + argv),
            // The CLI's own tools (Bash, Write, Agent, …) are not the seat's to hold: measured on
            // a real PM hire, they were used although the seat's list named none of them.
            () -> assertEquals("", argv.get(argv.indexOf("--tools") + 1),
                "and none of the CLI's built-in tools: " + argv),
            () -> assertEquals(List.of("mcp__jawata__search_symbols", "mcp__jawata__inspect"),
                argv.subList(allowlist + 1, argv.size()),
                "the allowlist says the names the brain actually sees: " + argv));
    }

    @Test
    @DisplayName("...and a hire with no endpoint (stdio) keeps the user's own setup and the bare names")
    void aHireWithNoEndpointLeavesTheBrainsSetupAlone() throws Exception {
        HostCommand standIn = brain("""
            echo '{"type":"result","result":"done","total_cost_usd":0.01}'
            """);
        RecordsTheArgv spy = new RecordsTheArgv(host, standIn);
        HiredRun run = new HiredRun(spy, artifacts, plans, events);

        // The opt-in is the seat's word that it may run on a brain no mechanism contains (claude
        // without an endpoint is held only by an instruction): this case is about the ARGV the
        // dialect builds with none, not about whether the hire is allowed.
        run.issue(SeatHire.read(
                "---\nname: c\naccepts_uncontained_writes: true\ntools: [inspect]\nttl_secs: 30\n---\n"),
            standIn, "Catalogue it.", twoSteps(), HiredRunProposesTest::passed);
        List<String> argv = spy.asked();

        assertAll(
            () -> assertFalse(argv.contains("--strict-mcp-config"), argv.toString()),
            () -> assertFalse(argv.contains("--mcp-config"), argv.toString()),
            () -> assertFalse(argv.contains("--tools"), argv.toString()),
            () -> assertEquals("inspect", argv.get(argv.indexOf("--allowedTools") + 1),
                argv.toString()));
    }

    @Test
    @DisplayName("...and a seat whose budget cannot fire on its brain says so rather than going quiet")
    void aSeatsUnenforceableBudgetIsReported() {
        // Measured: only Claude Code publishes a dollar cost. A seat declaring a budget for one of
        // the other three has written down a bound nothing can compare against — and the hire is
        // still bounded, by its wall clock and steps, which is what the report must not obscure.
        SeatHire onCursor = SeatHire.read("""
            ---
            name: watcher
            brain: cursor
            cost_budget_usd: 1.5
            ttl_secs: 30
            max_tool_steps: 10
            ---
            """);
        SeatHire onClaude = SeatHire.read("""
            ---
            name: watcher
            brain: claude-code
            cost_budget_usd: 1.5
            ttl_secs: 30
            max_tool_steps: 10
            ---
            """);

        assertAll(
            () -> assertTrue(onCursor.budgetReport().orElseThrow().contains("CANNOT FIRE"),
                onCursor.budgetReport().orElseThrow()),
            () -> assertTrue(onClaude.budgetReport().orElseThrow().contains("ENFORCEABLE"),
                onClaude.budgetReport().orElseThrow()),
            // S16-16: ENFORCEABLE is true of when the run STOPS, not of what it SPENDS. Claude
            // reports its whole cost in one terminal event, so a single-turn hire measured at
            // $2.26 against a $0.25 ceiling was stopped correctly and could not be prevented. The
            // report must say so, or "ENFORCEABLE" reads as a cap on spend.
            () -> assertTrue(onClaude.budgetReport().orElseThrow()
                    .contains("CANNOT PREVENT THE SPEND"),
                "the report says what the ceiling cannot do: "
                    + onClaude.budgetReport().orElseThrow()),
            // The ceiling is carried either way: this product does not edit a human's written
            // intent because a vendor publishes no cost.
            () -> assertEquals(1.5, onCursor.ceilings().maxCostUsd(), 1e-9));
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
            // THE HEADLINE, now READ from the census instead of asserted. This run's working
            // directory is not a git tree, so no census could be taken, and the honest answer is
            // that nothing is established — never "applied: false". The three states are pinned in
            // HiredRunStatesWhatItAppliedTest.
            () -> assertNull(
                artifacts.readManifest(proposal.artifactId()).orElseThrow().get("applied"),
                "no census, so the manifest does not claim nothing was applied"),
            () -> assertTrue(record.contains("NOT ESTABLISHED"),
                "and the record says it in words too: " + record),
            () -> assertTrue(record.contains("- writes: "),
                "and the record states what stops this brain writing: " + record),
            () -> assertEquals("NONE", ((java.util.Map<?, ?>) artifacts
                    .readManifest(proposal.artifactId()).orElseThrow().get("writeContainment"))
                    .get("basis"),
                "a script has no containment of the hire's making, and the manifest says so"),

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
                    + " give: a gate can report cleanly and the ledger still refuse"),
            // THE REASON'S CONTENT, not just its header. Matching the header alone would
            // stay green against a hardcoded or wrong reason, and the ledger's distinct
            // wordings are the whole payload — this one only it emits.
            () -> assertTrue(Files.readString(proposal.record()).contains("so it did not pass"),
                "the ledger's own words for an answering gate that found errors"));
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
                "and the ledger's reason is in the record: " + record),
            () -> assertTrue(record.contains("zero error count says nothing about the tree"),
                "and it is the NON-ANSWER reason rather than the error-count one — the two"
                    + " send a reader to different places, which is why they are separate"));
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
