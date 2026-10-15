package org.jawata.mcp.tools;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.core.host.HostProcesses;
import org.jawata.mcp.execution.BrainDialect;
import org.jawata.mcp.execution.HireSite;
import org.jawata.mcp.execution.HiredBrain;
import org.jawata.mcp.execution.HiredRun;
import org.jawata.mcp.execution.RunEffects;
import org.jawata.mcp.execution.SeatHire;
import org.jawata.mcp.execution.SelfEndpoint;
import org.jawata.mcp.execution.ToolGateRunner;
import org.jawata.mcp.execution.WriteContainment;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.learn.GatedStep;
import org.jawata.mcp.learn.LearnerEvent;
import org.jawata.mcp.learn.PlanLedger;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeArtifactStore;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * One seat hires another: the front door onto {@link HiredRun} (Sprint 28g S16, C16).
 *
 * <p>This is the production caller the capability had been missing. {@code HiredRun.issue} had
 * seven callers and every one was a test — filed as S16-5 — so the hire path could be driven and
 * nothing in the live process could reach it. C16's first clause is what wires it: <i>the PM seat
 * hires the cataloguer, named hirer, named hired, not a script, and that run goes end to end —
 * brief issued, gates run, result journalled.</i></p>
 *
 * <h2>Why a tool and not a scheduler entry</h2>
 *
 * <p>Because the hirer is an AGENT. {@code SeatScheduler} already fires a seat with nobody
 * present, which is the unattended case; this is the other one — a seat that has read a ledger,
 * formed a judgement and decided to delegate. The PM seat's own stance ends by emitting a brief
 * and says plainly <i>"if the right next step is an edit, you say which operation on which symbol
 * and hand it to an engineer or a seat"</i>. Handing it over is this call. Hiring is not editing,
 * which is why a seat forbidden to write code may still do it.</p>
 *
 * <h2>The hired seat's own file IS the hire</h2>
 *
 * <p>{@code seatFile} is required and nothing is discovered. The brain, the model, the tools the
 * brain may attach and every ceiling come from that file's frontmatter through
 * {@link SeatHire#read}, so changing which brain a seat uses is an edit to a settings file and
 * never a product edit — which is C16's own clause, met by construction rather than by assertion.
 * A resident guessing where a client keeps its seat files would be guessing at a layout that
 * differs per client, and the caller executing a seat already knows where its own came from.</p>
 *
 * <h2>What this call is NOT</h2>
 *
 * <p>It is SYNCHRONOUS, and bounded by the hired seat's own wall-clock ceiling rather than by
 * anything here. A seat declaring fifteen minutes will hold this call open for up to fifteen
 * minutes and a client may give up first; the run continues and its record still lands, because
 * the journal write is the hire's, not the caller's. An asynchronous start/status pair is the
 * shape {@code run_tests} uses for the same reason and is recorded as a finding rather than built
 * ahead of a brief that needs it.</p>
 */
public class HireTool extends AbstractTool {

    /*
     * THERE IS NO `binary` INPUT, AND THE REASON IS A DEFECT THIS TOOL SHIPPED FOR ONE HOUR.
     *
     * The first version published one, defaulted per brain, documented as "give an absolute path
     * when the CLI is installed somewhere else". It was INERT: HiredBrain.hire builds its command
     * as `new HostCommand(dialect.command(prompt, tools), base.workingDirectory(), ...)` and says
     * so in its own comment — "the dialect owns argv; the caller owns where and with what
     * environment" — so base.argv() is DISCARDED and the dialect's own binary is what runs.
     *
     * WHAT THAT COST, measured rather than imagined: a front-door probe pointed `binary` at a
     * harmless shell stand-in, the input was ignored, and the resident spawned the REAL claude in
     * the developer's own repository — 54 tool steps, $1.28, exit 1 — which by its own output
     * recorded job rows into the live knowledge store. The unit test could not have caught it: its
     * spy replaces the PROCESS, so it asserts the argv the dialect would ASK FOR and then runs a
     * stand-in whatever argv[0] said.
     *
     * So the input is GONE rather than fixed, for two reasons. Honouring it would mean `hire`
     * running an arbitrary executable named in a tool ARGUMENT with a brain's flags appended — an
     * execution surface held by any agent that can call this tool. And the override already has a
     * home that is a settings file rather than a call: every dialect takes its binary as a
     * constructor parameter, and SeatHire.dialect() is the one place that chooses it. A seat's
     * frontmatter is where "which executable" belongs, and a `binary:` field there would be read
     * by the same reader that already reads the brain and the model.
     *
     * A PROBE THAT MUST NOT SPEND A BRAIN SUBSTITUTES AT THE OS LEVEL instead — a directory on
     * PATH holding an executable of the brain's own name. That is where "which claude" is decided
     * for every other program on the machine, and it needs no product surface at all.
     */

    /** Reads a journal row's payload back into an object for the answer (S16-27). */
    private static final ObjectMapper JSON = new ObjectMapper();

    private final HostProcesses host;
    private final RuntimeArtifactStore artifacts;
    private final PlanLedger plans;
    private final Supplier<LearnerEventStore> journal;
    private final Supplier<ToolRegistry> registry;

    /**
     * The resident's own endpoint, asked per call and answering NULL when there is none — on stdio,
     * or before the transport is listening. A Claude hire given an endpoint starts with only this
     * resident as its MCP server; without one it keeps the user's own setup. See {@link SelfEndpoint}.
     */
    private final Supplier<SelfEndpoint> self;

    /** A hire with no endpoint of its own to hand the brain — the stdio resident's case. */
    public HireTool(Supplier<IJdtService> serviceSupplier, HostProcesses host,
            RuntimeArtifactStore artifacts, PlanLedger plans,
            Supplier<LearnerEventStore> journal, Supplier<ToolRegistry> registry) {
        this(serviceSupplier, host, artifacts, plans, journal, registry, () -> null);
    }

    /**
     * @param journal a SUPPLIER that may answer NULL, because the journal is conditional: it rides
     *     the experience store's H2 file, and a degraded resident has a non-H2 store and therefore
     *     no journal at all. The alternative was to register this tool only where the journal
     *     exists — which hides the degradation on the one channel a client cannot see, so the tool
     *     is always published and REFUSES with the reason instead.
     * @param registry a SUPPLIER because the cycle is real — this tool lives in the registry and
     *     its gates run through it. {@link ToolGateRunner} is built per call from whatever the
     *     supplier answers, so a tool registered after this one is still a reachable gate.
     * @param self the resident's own endpoint, a SUPPLIER that may answer null for the same reason
     *     the journal's may: whether this resident has one is known only once its transport listens.
     */
    public HireTool(Supplier<IJdtService> serviceSupplier, HostProcesses host,
            RuntimeArtifactStore artifacts, PlanLedger plans,
            Supplier<LearnerEventStore> journal, Supplier<ToolRegistry> registry,
            Supplier<SelfEndpoint> self) {
        super(serviceSupplier);
        if (host == null || artifacts == null || plans == null || journal == null
                || registry == null) {
            throw new IllegalArgumentException("a hire needs a host to spawn on, somewhere to put"
                + " the artifact, a plan to gate against, a journal to land in, and a registry to"
                + " run the gates through — a hire missing any of them would report a run nobody"
                + " can audit");
        }
        this.host = host;
        this.artifacts = artifacts;
        this.plans = plans;
        this.journal = journal;
        this.registry = registry;
        this.self = self == null ? () -> null : self;
    }

    @Override
    public String getName() {
        return "hire";
    }

    @Override
    public String getDescription() {
        return """
            One seat hires another to do a slice of work, and the run is recorded.

            USAGE: hire(hirer="pm", seat="cataloguer", seatFile="...", brief="...", steps=[...],
                        workingDirectory="...")

            WHICH EXECUTABLE a brain is cannot be set per call — only per seat, by its own file.
            This tool names no binary, because the dialect owns the whole command line.

            The HIRED SEAT'S OWN FILE is the hire: its frontmatter names the brain, the model, the
            tools that brain may attach and every ceiling. So changing which brain a seat uses is
            an edit to that file and never a call-site change here.

            THE STEPS ARE THE GATES, in order, each {tool, arguments}. They run against the real
            tool registry after the brain answers, and a gate that did not answer is reported as
            not having answered rather than as zero errors — the two are otherwise identical.

            SYNCHRONOUS, and bounded by the HIRED seat's wall-clock ceiling, not by this call. A
            seat declaring a long ceiling will hold the call open; the run's record lands either
            way, because the journal write belongs to the hire and not to the caller.

            A brain that cannot be told which tools to attach is REPORTED in the result rather
            than left out of it: `toolsSettableByTheHirer` is false and `toolAttachment` says what
            containment was applied instead.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("hirer", Map.of("type", "string",
            "description", "The seat issuing the brief. Named on the record, so a run can be"
                + " attributed to whoever asked for it."));
        properties.put("seat", Map.of("type", "string",
            "description", "The seat being hired. Named on the record beside the hirer."));
        properties.put("seatFile", Map.of("type", "string",
            "description", "Path to the HIRED seat's markdown. Its frontmatter is the hire — the"
                + " brain, the model, the attachable tools and the ceilings. Required and never"
                + " discovered: where a client keeps its seat files is not the resident's to"
                + " guess, and the caller executing a seat already knows."));
        properties.put("brief", Map.of("type", "string",
            "description", "The instruction the hired brain is given, verbatim."));
        properties.put("steps", Map.of("type", "array",
            "description", "The gates, in order. Each is {tool, arguments} — a jawata tool name"
                + " and the arguments it is called with. They run after the brain answers.",
            "items", Map.of("type", "object", "properties", Map.of(
                "tool", Map.of("type", "string"),
                "arguments", Map.of("type", "object")))));
        properties.put("workingDirectory", Map.of("type", "string",
            "description", "Where the brain runs — REQUIRED, never defaulted. It is the hire's"
                + " blast radius: the brain reads and may write here, so a caller states it rather"
                + " than inheriting the resident's own directory."));
        return Map.of(
            "type", "object",
            "properties", properties,
            "required",
            List.of("hirer", "seat", "seatFile", "brief", "steps", "workingDirectory"));
    }

    /**
     * The names of the inputs this tool reads — the keys of its own published schema, so the
     * refusal of an unread argument and the schema a client is shown cannot disagree.
     *
     * @return the property names of {@link #getInputSchema()}
     */
    @SuppressWarnings("unchecked")
    private Set<String> schemaProperties() {
        return ((Map<String, Object>) getInputSchema().get("properties")).keySet();
    }

    /**
     * FALSE, deliberately: a hire does not need a loaded project and its GATES answer for
     * themselves.
     *
     * <p>Returning true would refuse the whole hire with {@code PROJECT_NOT_LOADED} — the
     * registry's own pre-dispatch answer — and a reader would be told nothing about the brain, the
     * brief or the gates. With it false the brain runs, each gate reports whether it could answer,
     * and a gate that needed a project says so in its own row. That is the difference between one
     * refusal standing in for a run and a record of a run that happened.</p>
     */
    @Override
    protected boolean requiresLoadedProject() {
        return false;
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        LearnerEventStore landsIn = journal.get();
        if (landsIn == null) {
            // DEGRADATION ON THE RESPONSE CHANNEL, which is the only one a client can see. A hire
            // whose record cannot land is a run nobody can audit afterwards, and running it anyway
            // would spend a brain's time and a budget to produce exactly that.
            return ToolResponse.error("PRECONDITION_NOT_MET",
                "this resident has no journal, so a hire's record could not land anywhere a reader"
                    + " would find it",
                "the journal rides the experience store's H2 file; a degraded (non-H2) store has"
                    + " none, which the resident's own startup log states");
        }
        // S16-12: AN ARGUMENT THIS TOOL DOES NOT READ IS REFUSED, NEVER IGNORED. The first version
        // published a `binary` input and ignored it, and a probe that set it to a stand-in ran the
        // real claude in the developer's repository. Deleting the input from the schema left the
        // same hole for any caller still sending it, because an ignored argument IS an inert
        // input. Which executable a brain is comes from the seat file alone.
        if (arguments != null) {
            Set<String> read = schemaProperties();
            List<String> unread = new ArrayList<>();
            arguments.fieldNames().forEachRemaining(name -> {
                if (!read.contains(name)) {
                    unread.add(name);
                }
            });
            if (!unread.isEmpty()) {
                return ToolResponse.error("INVALID_PARAMETER",
                    "hire does not read " + unread + ", so passing it changes nothing a hire does",
                    "drop it; which brain and which executable a hire runs is the seat file's"
                        + " frontmatter, and the inputs hire reads are " + read);
            }
        }
        for (String required : List.of("hirer", "seat", "seatFile", "brief", "workingDirectory")) {
            ToolResponse missing = requireParam(arguments, required);
            if (missing != null) {
                return missing;
            }
        }
        String hirer = getStringParam(arguments, "hirer");
        String hired = getStringParam(arguments, "seat");
        String brief = getStringParam(arguments, "brief");

        SeatHire seat;
        try {
            seat = SeatHire.read(readSeatFile(getStringParam(arguments, "seatFile")));
        } catch (IOException | InvalidPathException e) {
            return ToolResponse.error("FILE_NOT_FOUND",
                "the hired seat's file could not be read: " + e.getMessage(),
                "seatFile must be a path to the seat's markdown, whose frontmatter is the hire");
        } catch (IllegalArgumentException e) {
            // SeatHire.read REFUSES an unknown brain rather than defaulting to one, which is the
            // whole reason this is a refusal and not a fallback: hiring the wrong brain would run
            // and report a result.
            return ToolResponse.error("INVALID_PARAMETER",
                "the seat file does not describe a hireable seat: " + e.getMessage(),
                "check its brain: field — an unknown brain is refused rather than defaulted");
        }

        if (!seat.enabled()) {
            // C11-6: the user switched this seat OFF in studio, which writes `enabled: false` into
            // the seat file. Refused before any brain starts — a hire is a paid run, and the switch
            // is the user's written word that this seat does not run.
            return ToolResponse.error("PRECONDITION_NOT_MET",
                "seat '" + seat.seat() + "' is switched off (its file says enabled: false)",
                "turn it on in studio's Seats view, or hire a seat that is on");
        }

        List<GatedStep> steps;
        try {
            steps = gatedSteps(arguments);
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("steps", e.getMessage());
        }
        if (steps.isEmpty()) {
            // PlanLedger.open refuses an empty step list, so this would fail one layer down with a
            // message about a plan rather than about the gates the caller did not declare.
            return ToolResponse.invalidParameter("steps",
                "a hire with no gates proves nothing was checked — declare at least one");
        }

        // THE ARGV HERE IS A PLACEHOLDER AND NOTHING READS IT. `HiredBrain.hire` takes only the
        // working directory, the environment and the clean-environment flag off this base; the
        // dialect builds the whole argv including its own binary. It carries the brain's LABEL
        // rather than a guess at its executable — `name()` answers the vendor ("claude-code"), not
        // the binary ("claude"), deliberately, so that a run pointed at a double is still
        // attributable to the brain whose dialect read it.
        // THE RESIDENT'S OWN ENDPOINT, so a Claude hire talks to this resident and nothing else.
        SelfEndpoint endpoint = self.get();
        if (endpoint == null && seat.brain() == SeatHire.Brain.CLAUDE_CODE) {
            // S16-36. Without an endpoint the claude CLI keeps the machine's own MCP servers,
            // settings and hooks — measured $2.26 for a one-word answer — and the containment that
            // actually removes its editors (--tools "") is sent only WITH one. A hire that cannot
            // be bounded is refused before anything is spawned, which is cheaper than reporting it
            // afterwards.
            return ToolResponse.error("PRECONDITION_NOT_MET",
                "a claude hire needs this resident's own endpoint, and this resident has none —"
                    + " it is serving stdio, so there is nothing to attach the brain to",
                "without an endpoint the brain would load every MCP server, setting and hook on"
                    + " this machine and keep its own editors. Hire from a resident serving HTTP,"
                    + " or give the seat another brain (cursor, codex or copilot)");
        }
        // REL44-6: the engine's code-changing tools the seat did not name are taken away from a
        // brain that attaches this resident, by the engine's own flag. --allowedTools only
        // PRE-APPROVES; the rest stayed offered (a read-only PM's init event listed all 44).
        ToolRegistry engineTools = registry.get();
        List<String> hiddenTools = engineTools.getToolNames().stream()
            .filter(name -> engineTools.getTool(name).map(Tool::changesCode).orElse(false))
            .filter(name -> !seat.tools().contains(name))
            .sorted()
            .toList();
        BrainDialect dialect = seat.dialect(endpoint, hiddenTools);
        WriteContainment containment = dialect.writeContainment();
        if (!containment.isContained() && !seat.acceptsUncontainedWrites()) {
            // THE ARCHITECT'S F1, option A. A brain whose writes are held only by an instruction to
            // the model (cursor: breached in 5 of 15 runs) or by nothing is refused BEFORE anything
            // spawns, unless the seat's own file says it accepts that. An unbounded hire reported
            // afterwards costs the run it could not stop; refusing costs a line in the seat.
            return ToolResponse.error("PRECONDITION_NOT_MET",
                "seat '" + seat.seat() + "' would run on " + dialect.name() + ", whose writes are"
                    + " not refused by a mechanism — " + containment.report(),
                "give the seat a brain that is contained (claude with this resident's endpoint,"
                    + " codex or copilot), or add accepts_uncontained_writes: true to the seat's"
                    + " frontmatter to say it may run on this brain as it is");
        }
        // REQUIRED, and that is the other half of the fix above. Defaulting it meant the brain ran
        // in the RESIDENT's own directory — which is how a real claude came to run in the
        // developer's repository with the store attached. A hire that does not say where the brain
        // works is a hire whose blast radius nobody stated. (A site has no argv: what the brain
        // runs is the dialect's alone.)
        HireSite base;
        try {
            base = HireSite.in(Path.of(getStringParam(arguments, "workingDirectory")));
        } catch (InvalidPathException e) {
            return ToolResponse.invalidParameter("workingDirectory", e.getMessage());
        }

        HiredRun run = new HiredRun(host, artifacts, plans, landsIn, endpoint);
        HiredRun.Proposal proposal;
        try {
            proposal = run.issue(seat, base, brief, steps, new ToolGateRunner(registry.get()),
                hiddenTools);
        } catch (IOException e) {
            return ToolResponse.error("INTERNAL_ERROR",
                "the brain could not be spawned: " + e.getMessage(),
                "the " + dialect.name() + " dialect runs its own executable — check that"
                    + " that CLI is installed and on the resident's PATH. Which executable a brain"
                    + " IS cannot be set per call; it is the dialect's, chosen by the seat's brain");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResponse.error("INTERNAL_ERROR",
                "the hire was interrupted while the brain was running",
                "the child process is reaped; nothing is left running");
        }

        Map<String, Object> data = new LinkedHashMap<>();
        // The two names C16 asks for, FIRST, because a record that cannot say who asked and who
        // answered is not an attribution.
        data.put("hirer", hirer);
        data.put("hired", hired);
        data.put("plan", proposal.plan().value());
        // The file a human reads, and the artifact it was stored as. The RUN writes both; this call
        // points at them rather than copying them into its own answer, so the record a reader opens
        // and the record the journal refers to cannot drift apart.
        data.put("artifactId", proposal.artifactId());
        data.put("record", proposal.record().toString());
        // S16-27: what the journal HOLDS for this run, read back by its plan id rather than
        // assumed from the write — `HiredRun` appends inside a catch that swallows, so a run whose
        // row was lost still returns a proposal.
        data.putAll(journalReport(landsIn.eventsOfSession(proposal.plan().value()),
            landsIn.failedWrites()));
        data.put("complete", proposal.complete());
        data.put("stepsPassed", proposal.stepsPassed());
        // The seat's own checks over its answer are steps of the plan too, ahead of the hirer's.
        data.put("stepsDeclared", steps.size() + seat.answerGates().size());
        if (!seat.answerGates().isEmpty()) {
            data.put("answerGates", seat.answerGates());
            data.put("answerGateRefusals", proposal.answerGateRefusals());
        }
        data.put("gates", gateRows(proposal));
        // EVERY CHANGED FILE ATTRIBUTED TO THE RUN — C16's clause, and the absence is an absence.
        // `changedFiles` is left OUT of the answer entirely when no census could be taken, rather
        // than sent as an empty list: a caller reading [] would be told the brain touched nothing
        // when in truth nobody looked, which is the one fact they most need right before deciding
        // whether to accept a proposal. `changedFilesBasis` is always present and says which.
        proposal.changed().changed().ifPresent(files -> {
            data.put("changedFiles", files);
            data.put("changedFileCount", files.size());
        });
        data.put("changedFilesBasis", proposal.changed().basis());
        // WHAT THE BRAIN DID, and the three absences are reported as absences. A brain that
        // published no dollar cost is not a brain that cost nothing — only one of the four does —
        // and `saidNothing` is what keeps "it produced nothing" apart from "it failed".
        data.put("cleanExit", proposal.outcome().clean());
        // WHY it was not clean: a `false` above told a reader nothing while the exit status and the
        // last of stderr sat in a record file the run's own scratch cleanup could remove. Measured
        // 2026-10-14: a test-manager hire ended mid-sentence with cleanExit false, stoppedBy
        // absent and timedOut false, and nothing in the answer said why. Absent exit status means
        // the child was reaped before it exited.
        data.put("exitCode", proposal.outcome().exitCode().isPresent()
            ? proposal.outcome().exitCode().getAsInt() : null);
        if (!proposal.outcome().stderrTail().isBlank()) {
            data.put("stderrTail", proposal.outcome().stderrTail());
        }
        data.put("toolSteps", proposal.outcome().toolSteps());
        // M11: WHICH calls, classified by the ENGINE's own flag. A count of calls says nothing
        // about whether any of them changed code; the names do, and the three lists that are not
        // "changed code" are reported beside the zero because a zero that hides a shell call, an
        // unclassifiable name or a call nobody could name is a clean bill for the runs the
        // measure understood least.
        ToolRegistry engine = registry.get();
        RunEffects.Classification effects = RunEffects.classify(
            proposal.outcome().toolNames(), proposal.outcome().unnamedToolSteps(),
            bare -> engine.getTool(bare).map(Tool::changesCode), proposal.outcome().toolsOffered());
        List<String> incomplete = incompleteReasons(effects, proposal.outcome(), dialect);
        data.put("toolsCalled", proposal.outcome().toolNames());
        // WHAT THE CLI SAID IT OFFERED, and which of the brain's calls named something else. A call
        // to an unoffered tool is an ATTEMPT the CLI refused, not a call that ran; it is listed here
        // and kept out of the four buckets below, so a refused Write cannot read as a code change.
        proposal.outcome().toolsOffered().ifPresent(offered -> data.put("toolsOffered", offered));
        data.put("attemptedButNotOffered", effects.notOffered());
        data.put("codeChangingCalls", effects.changesCode());
        data.put("shellCalls", effects.shell());
        data.put("unclassifiedCalls", effects.unknown());
        data.put("unnamedToolSteps", effects.unnamed());
        data.put("performedNoCodeChangingOperation", effects.performedNoCodeChangingOperation());
        data.put("noCodeChangeIsComplete", incomplete.isEmpty());
        data.put("noCodeChangeIncompleteBecause", incomplete);
        data.put("timedOut", proposal.outcome().timedOut());
        proposal.outcome().costUsd().ifPresentOrElse(
            cost -> data.put("costUsd", cost),
            () -> data.put("costUsd", "NOT PUBLISHED BY THIS BRAIN"));
        proposal.outcome().stoppedBy().ifPresent(why -> data.put("stoppedBy", why));
        data.put("answer", proposal.outcome().text());
        // C16's own clause: a brain that cannot be told which tools to attach is REPORTED here
        // rather than quietly left out of the count.
        data.put("toolAttachment", dialect.toolAttachment().report());
        // WHAT STOPS THIS BRAIN WRITING, and whether this run's own log bears it out.
        data.put("writeContainment", containment.report());
        data.put("writeContainmentBasis", containment.basis().name());
        data.put("writeContainmentWitnessed",
            HiredRun.writeWitness(containment, proposal.outcome().toolsOffered()));
        data.put("toolsSettableByTheHirer", dialect.toolAttachment().settableByTheHirer());
        seat.budgetReport().ifPresent(report -> data.put("budget", report));
        return ToolResponse.success(data);
    }

    /**
     * What the journal holds for a run, from its rows read back by the run's plan id (S16-27) — so
     * "journalled" is something the answer SHOWS rather than something a caller assumes.
     *
     * <p>THREE states, never two. {@code landed} carries the run's own row: which brain, and the
     * payload naming the artifact and how far the plan got. {@code not landed} means the journal
     * was read and holds no hired-run row for the plan; the store's own count of dropped writes is
     * named beside it, because a write the store gave up on is how that usually happens.
     * {@code unknown} means the journal could not be read at all. That says nothing about the row,
     * and reporting it as "not landed" would be a claim nobody established.</p>
     *
     * @param read          the journal's rows for the plan, or absent when it could not be read
     * @param droppedWrites the store's own count of writes it gave up on
     */
    static Map<String, Object> journalReport(Optional<List<LearnerEvent>> read, long droppedWrites) {
        Map<String, Object> report = new LinkedHashMap<>();
        if (read.isEmpty()) {
            report.put("journal", "unknown");
            report.put("journalBasis", "the journal could not be read back, so whether this run's row"
                + " landed is not established — which is not the same as its being absent");
            return report;
        }
        Optional<LearnerEvent> row = read.get().stream()
            .filter(event -> LearnerEvent.KIND_HIRED_RUN.equals(event.kind()))
            .reduce((earlier, later) -> later);
        if (row.isEmpty()) {
            report.put("journal", "not landed");
            report.put("journalBasis", "read back from the journal by this run's plan id: no"
                + " hired-run row is there. The journal has counted " + droppedWrites
                + " dropped write(s) since this resident started");
            return report;
        }
        Map<String, Object> shown = new LinkedHashMap<>();
        shown.put("kind", row.get().kind());
        shown.put("brain", row.get().tool());
        shown.put("detail", detailOf(row.get().detailJson()));
        report.put("journal", "landed");
        report.put("journalRow", shown);
        report.put("journalBasis", "read back from the journal by this run's plan id");
        return report;
    }

    /** A row's payload as an object, or as the text it was written as when it does not parse. */
    private static Object detailOf(String json) {
        if (json == null) {
            return null;
        }
        try {
            return JSON.readValue(json, Map.class);
        } catch (IOException e) {
            return json;
        }
    }

    /**
     * Why "this run changed no code" cannot be taken as complete — empty only when it can.
     *
     * <p>The call classification is not the whole question. A zero over a stream the dialect
     * only partly observes, over lines the reader could not parse, or over a run a ceiling cut
     * short (calls the brain made after the reader stopped are not in the list) is a clean bill
     * for exactly the runs the measure saw least. Every reason is named, so a reader sees WHICH
     * blind spot stands behind the zero rather than a bare false.</p>
     */
    private static List<String> incompleteReasons(RunEffects.Classification effects,
            HiredBrain.Outcome outcome, BrainDialect dialect) {
        List<String> reasons = new ArrayList<>(effects.incompleteBecause());
        if (!dialect.observesEveryCall()) {
            reasons.add(dialect.name() + " does not show every call it makes, by name");
        }
        if (outcome.unreadableLines() > 0) {
            reasons.add(outcome.unreadableLines() + " output line(s) could not be read");
        }
        if (!outcome.clean() || outcome.stoppedBy().isPresent()) {
            reasons.add("the run did not end on its own: calls after the reader stopped are unseen");
        }
        return List.copyOf(reasons);
    }

    /**
     * One row per declared gate, in order, each saying whether it ANSWERED as well as what it
     * counted.
     *
     * <p>Both fields, always, and never the count alone. A gate that did not answer reports zero
     * errors exactly as a clean one does — {@link ToolGateRunner} exists for that reason — so a row
     * carrying only {@code errorCount} would let a reader take four distinct failures for a pass.
     * What it cannot say is WHICH of those four, because {@code GateOutcome} has nowhere to put it;
     * that is filed as S16-10 and lives in the resident's log meanwhile.</p>
     */
    private static List<Map<String, Object>> gateRows(HiredRun.Proposal proposal) {
        List<Map<String, Object>> rows = new ArrayList<>();
        proposal.gates().forEach(gate -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("tool", gate.tool());
            row.put("errorCount", gate.errorCount());
            row.put("answered", gate.succeeded());
            // S16-10: WHICH of the four ways it failed to answer, so the hirer is sent to the
            // plan, the tool's complaint or the reader rather than to the log.
            if (gate.notAnsweredBecause() != null) {
                row.put("notAnsweredBecause", gate.notAnsweredBecause());
            }
            rows.add(row);
        });
        return rows;
    }

    /** Package-private so a test can hand over text without a file on disk. */
    String readSeatFile(String path) throws IOException {
        return Files.readString(Path.of(path));
    }

    /**
     * The declared gates, in the order given — and the INDEX is the position, not a caller input.
     *
     * <p>A step that named its own index could name one twice or skip one, and the plan ledger
     * advances by position; letting a caller choose it would make a plan that cannot be followed
     * expressible.</p>
     */
    private static List<GatedStep> gatedSteps(JsonNode arguments) {
        JsonNode declared = arguments.path("steps");
        if (!declared.isArray()) {
            throw new IllegalArgumentException("steps must be an array of {tool, arguments}");
        }
        List<GatedStep> steps = new ArrayList<>();
        for (int i = 0; i < declared.size(); i++) {
            JsonNode step = declared.get(i);
            String tool = step.path("tool").asText("");
            if (tool.isBlank()) {
                throw new IllegalArgumentException("step " + i + " names no tool");
            }
            JsonNode args = step.path("arguments");
            steps.add(new GatedStep(i, tool, args.isObject() ? args
                : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode()));
        }
        return steps;
    }
}
