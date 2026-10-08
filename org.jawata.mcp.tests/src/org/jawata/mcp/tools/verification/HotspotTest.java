package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.jawata.mcp.tools.ProfileTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D11, C16) — profiles that name symbols. A deliberately hot method
 * ranks #1, as a compiler-verified symbol, in a paginated summary; an on-demand
 * dump works mid-run; and a target without Flight Recorder gets an honest
 * capability-absent report, never a silently empty one.
 */
class HotspotTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool debug;
    private ProfileTool profile;
    private ObjectMapper om;
    private Path targetClasses;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        debug = new DebugTool(() -> service, sessions);
        profile = new ProfileTool(() -> service, sessions);
        om = new ObjectMapper();

        targetClasses = Files.createTempDirectory("jawata-hotspot-target-");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example/debug");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", targetClasses.toString(),
            pkg.resolve("DebugTarget.java").toString(),
            pkg.resolve("HotLoopTarget.java").toString(),
            pkg.resolve("WallTimeTarget.java").toString(),
            pkg.resolve("LatencySeamTarget.java").toString(),
            pkg.resolve("DomainEventTarget.java").toString());
        assertEquals(0, rc, "the hotspot fixtures must compile");
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode debugAction(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private ObjectNode profileAction(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private String launchAndResume(String mainClass) {
        ObjectNode launch = debugAction("launch");
        launch.put("mainClass", mainClass);
        launch.put("classpath", targetClasses.toString());
        ToolResponse launched = debug.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        String sessionId = (String) data(launched).get("sessionId");

        ObjectNode resume = debugAction("resume");
        resume.put("sessionId", sessionId);
        assertTrue(debug.execute(resume).isSuccess());
        return sessionId;
    }

    // ========================================================== sample + hotspots

    @Test
    @DisplayName("the deliberately hot method is hotspot #1, symbol-named, paginated, with call counts")
    void hotMethodIsTopHotspot() throws Exception {
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 3);
        ToolResponse sampled = profile.execute(sample);
        assertTrue(sampled.isSuccess(), "got: " + sampled.getError());
        String artifactId = (String) data(sampled).get("artifactId");
        assertNotNull(artifactId);
        assertTrue(((Number) data(sampled).get("bytes")).longValue() > 0,
            "a real JFR file was written");

        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", artifactId);
        hotspots.put("dimension", "cpu");
        hotspots.put("limit", 5);
        ToolResponse r = profile.execute(hotspots);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");
        assertFalse(rows.isEmpty(), "at least one CPU sample must have been captured");

        Map<String, Object> top = rows.get(0);
        assertTrue(((Number) top.get("samples")).longValue() > 0,
            "call counts (sample-based) must be present: " + top);
        assertTrue(((Number) d.get("totalSamples")).longValue() > 0);

        // Sprint 28 (v3.6.3): same precondition as the wall test. HotLoopTarget burns CPU
        // continuously, so it is a far better sampling subject than the ~97%-blocked
        // WallTimeTarget and this has never been observed to thin out — but the assertion
        // below is the identical shape (a rank over sample counts), so it gets the identical
        // guard rather than waiting to be the next release's surprise.
        assumeRankable(rows, "HotLoopTarget#burnCpu");
        assertEquals(1, ((Number) top.get("rank")).intValue());
        assertEquals("com.example.debug.HotLoopTarget#burnCpu", top.get("symbol"),
            "the deliberately hot method, AS A SYMBOL, ranked #1: " + rows);
    }

    @Test
    @DisplayName("hotspots pagination is honest: capped rows, TRUE totals reported alongside")
    void hotspotsPaginationIsHonest() throws Exception {
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 3);
        String artifactId = (String) data(profile.execute(sample)).get("artifactId");

        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", artifactId);
        hotspots.put("dimension", "cpu");
        hotspots.put("limit", 1);
        ToolResponse r = profile.execute(hotspots);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");
        assertEquals(1, rows.size(), "limit must actually cap the returned rows");
        assertNotNull(d.get("totalMethods"), "the TRUE method count must be reported regardless of the cap");
        assertNotNull(d.get("totalSamples"), "the TRUE sample count must be reported regardless of the cap");
    }

    @Test
    @DisplayName("gc dimension: a pause summary, not a fabricated per-method ranking")
    void gcDimensionReportsSummaryNotFakeMethods() throws Exception {
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 2);
        String artifactId = (String) data(profile.execute(sample)).get("artifactId");

        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", artifactId);
        hotspots.put("dimension", "gc");
        ToolResponse r = profile.execute(hotspots);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        assertNotNull(d.get("pauseCount"), "got: " + d);
        assertNotNull(d.get("totalPauseMillis"), "got: " + d);
        assertNotNull(d.get("maxPauseMillis"), "got: " + d);
        assertFalse(d.containsKey("rows"), "GC has no per-method ranking to fabricate: " + d);
    }

    @Test
    @DisplayName("wall dimension: a method that BLOCKS ranks above one that only burns CPU")
    void wallDimensionRanksBlockingWallTimeNotJustCpu() throws Exception {
        // Sprint-24 audit T2.1: the spec lists wall time as a ranking dimension beside
        // cpu/alloc/lock/gc; v2.13.0 shipped the enum WITHOUT it, so dimension=wall was an
        // "unknown dimension" error. WallTimeTarget spends most of its ELAPSED time blocked
        // on a contended monitor in waitOnLock() and only a little CPU in burnCpu(). A CPU
        // profile points at burnCpu; a genuine WALL profile must point at waitOnLock — that
        // is where the wall clock goes, and it is invisible to CPU sampling (a blocked thread
        // is not on the CPU).
        String sessionId = launchAndResume("com.example.debug.WallTimeTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 5);
        String artifactId = (String) data(profile.execute(sample)).get("artifactId");

        ObjectNode wall = profileAction("hotspots");
        wall.put("artifactId", artifactId);
        wall.put("dimension", "wall");
        wall.put("limit", 20);
        ToolResponse r = profile.execute(wall);
        assertTrue(r.isSuccess(), "dimension=wall must be a real ranking, not 'unknown': " + r.getError());
        Map<String, Object> d = data(r);

        assertEquals("wall", d.get("dimension"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> wallRows = (List<Map<String, Object>>) d.get("rows");
        assertFalse(wallRows.isEmpty(), "a running program has wall-time hotspots: " + d);

        // Every row is ranked by ELAPSED milliseconds, not a sample count.
        for (Map<String, Object> row : wallRows) {
            assertNotNull(row.get("wallMillis"), "wall rows are ranked by milliseconds: " + row);
            assertTrue(row.get("symbol").toString().contains("#"), "symbol-named: " + row);
        }
        assertNotNull(d.get("totalWallMillis"));
        assertNotNull(d.get("samplingIntervalMillis"), "the measured on-CPU interval is disclosed");
        assertTrue(((Number) d.get("blockingEvents")).longValue() > 0,
            "the blocking wall time was actually captured, not just CPU samples: " + d);

        // THE PROOF that wall != CPU — RESTATED at Sprint 28g S15, because the previous form
        // of it was passing BECAUSE OF THE DEFECT M13 repairs, and that is worth more than the
        // assertion was.
        //
        // It used to require that the #1 wall row be a blocking one, matching "wait"/"park"/
        // "Lock". It passed — and the row it was matching was a JDK PRIMITIVE, `Object#wait0`,
        // into which every blocked thread in the process collapsed. That collapse is the N x T
        // defect: it summed many threads' waits into one frame and so stood above burnCpu's
        // genuine CPU time. Remove the collapse and the two legitimately TIE — measured on this
        // fixture at burnCpu 4832 ms against waitOnLock 4484 ms over a 5 s recording, 8% apart,
        // because one thread burns CPU continuously while another blocks continuously. A rank
        // asserted across an 8% gap is a coin flip, which is the hazard this very method
        // already names for its CPU half ("asserting a rank on that reports a conclusion the
        // data cannot support, in either direction") and never applied here.
        //
        // TWO CLAIMS MADE HERE WERE WIDER THAN THE EVIDENCE, and the C15 audit and the
        // architect watch caught them independently. Both are corrected rather than annotated:
        //
        // 1. "STRICTLY STRONGER" WAS FALSE. Two of the three replacement clauses are strictly
        //    stronger — requiring `waitOnLock` by its own program symbol, which the old test
        //    COULD NOT require since `Object#wait0` satisfied it, and forbidding any JVM
        //    primitive row. The third is REVERSED, not strengthened: the old test FORBADE
        //    burnCpu at rank 1, the new set merely requires it present. The rank was dropped
        //    and nothing ordinal replaced it, so a magnitude regression — a wrong time unit,
        //    or waitOnLock falling to rank 5 with 1 ms — passes all three new clauses and
        //    would have failed the old one.
        // 2. "PASSING BECAUSE OF THE DEFECT" was also too wide. `waitOnLock` contains both
        //    "wait" and "Lock", so it satisfies the old clauses too: the old test is still
        //    SATISFIABLE after the repair. It went red because the 8% tie flipped, not because
        //    the clause encoded the defect. The honest reason is the narrower one — it asserted
        //    a rank the data cannot support.
        //
        // SO A RANK-FREE RELATION IS ADDED BELOW, which was available and not taken. Dropping
        // the rank at an 8% gap is still right; carrying NO relation between rows was not.
        List<String> wallSymbols = wallRows.stream()
            .map(row -> row.get("symbol").toString())
            .toList();
        assertTrue(wallSymbols.contains("com.example.debug.WallTimeTarget#waitOnLock"),
            "wall must name the BLOCKING method by its own symbol — before M13 this time was"
                + " attributed to Object#wait0, which tells a reader nothing about where: "
                + wallRows);
        assertTrue(wallSymbols.contains("com.example.debug.WallTimeTarget#burnCpu"),
            "and wall counts on-CPU elapsed time too, so the CPU-burning method is here as"
                + " well — wall is not lock-time renamed: " + wallRows);
        assertTrue(wallSymbols.stream().noneMatch(
                symbol -> symbol.startsWith("jdk.internal.misc.Unsafe")
                    || symbol.startsWith("java.lang.Object#wait")),
            "and NO row is a bare JVM blocking primitive, which is the frame every blocked"
                + " thread shares and the row this assertion used to be satisfied by: "
                + wallRows);

        // THE RESTORED MAGNITUDE RELATION. Not a rank — a rank across the measured 8% gap is a
        // coin flip. A FLOOR on the blocking method's share is not: this fixture is "~97%
        // blocked BY DESIGN" (see the CPU caveat below), and measured it holds 4484 ms of a
        // 9316 ms ranked total, about 48%. A 10% floor therefore has nearly 5x of headroom
        // while still failing every regression the dropped rank would have caught — a wrong
        // time unit, or waitOnLock collapsing to a handful of milliseconds at rank 5.
        //
        // The architect watch proposed a sharper form: waitOnLock's share in WALL exceeding its
        // share in CPU, which expresses wall-is-not-cpu as a relation between the dimensions
        // rather than as a floor. It is better and it is NOT what this is. The cpu dimension
        // publishes no share, so it would have to be computed from a CAPPED page's sample sum,
        // which compares a whole-answer share against a page share — unlike things. It is owed
        // once the cpu dimension carries a share of its own, and that is carried as a named
        // deferral in the 28g plan's C15 carried-items list rather than left as a note here:
        // a comment saying "recorded" with no destination is a finding nobody will ever pick up,
        // which the round-2 audit called out as class 3.
        Map<String, Object> blockingRow = wallRows.stream()
            .filter(row -> "com.example.debug.WallTimeTarget#waitOnLock".equals(row.get("symbol")))
            .findFirst().orElseThrow();
        double blockingShare = ((Number) blockingRow.get("sharePercent")).doubleValue();
        assertTrue(blockingShare >= 10.0,
            "the blocking method must hold a REAL share of the wall clock, not merely appear in"
                + " the list — this is the magnitude dimension the dropped rank used to carry,"
                + " and the fixture blocks by design, so 10% is a floor with ~5x headroom. Got "
                + blockingShare + "% from " + blockingRow);

        // THE COUNTER SPLIT IS FALSIFIABLE HERE AND NOWHERE ELSE — C15 blocker 2, which the
        // round-2 audit found had shipped with no control that could fail.
        //
        // A wall row's milliseconds can come from blocking events or from on-CPU samples, so
        // the row publishes `blockingEvents` and `cpuSamples` separately and `samples` as their
        // sum. The parser-level test asserts that split on a recording it makes itself — and
        // that recording enables only the two blocking events, so every row's `cpuSamples` is
        // structurally ZERO there, the sum identity reads `b == b + 0`, and reverting the two
        // maps to one conflated counter left all of it green.
        //
        // This case profiles a LIVE JVM with execution samples, so both halves have something
        // to count, and the fixture puts them in DIFFERENT rows by construction: burnCpu burns
        // CPU and waitOnLock blocks. Requiring a floor on each in its own row is what no
        // single-map mutation can satisfy — fold the two counters together and whichever map
        // is left empty reads 0 for one of these two keys.
        Map<String, Object> cpuRow = wallRows.stream()
            .filter(row -> "com.example.debug.WallTimeTarget#burnCpu".equals(row.get("symbol")))
            .findFirst().orElseThrow();
        assertTrue(((Number) cpuRow.get("cpuSamples")).longValue() > 0,
            "the CPU-burning method's wall row must count on-CPU SAMPLES — this is the half the"
                + " parser-level test cannot reach, because its own recording has none: "
                + cpuRow);
        assertTrue(((Number) blockingRow.get("blockingEvents")).longValue() > 0,
            "and the blocking method's row must count BLOCKING EVENTS, so the two counters are"
                + " shown to be separate quantities rather than one wearing two names: "
                + blockingRow);

        // The CORROBORATION, and it is sampling-dependent, so it goes LAST and behind a
        // precondition. WallTimeTarget is ~97% blocked BY DESIGN — that is the whole point of
        // the fixture — which makes it a deliberately poor subject for a CPU sampler. On a
        // contended machine the 5 s recording can catch it once, and then every row ties at
        // one sample and "rank" is arbitrary tie order. Asserting a rank on that reports a
        // conclusion the data cannot support, in either direction.
        ObjectNode cpu = profileAction("hotspots");
        cpu.put("artifactId", artifactId);
        cpu.put("dimension", "cpu");
        cpu.put("limit", 20);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cpuRows =
            (List<Map<String, Object>>) data(profile.execute(cpu)).get("rows");

        assumeRankable(cpuRows, "WallTimeTarget#burnCpu");
        int burnCpuRankInCpu = rankOf(cpuRows, "com.example.debug.WallTimeTarget#burnCpu");
        assertTrue(burnCpuRankInCpu > 0 && burnCpuRankInCpu <= 3,
            "burnCpu is a top CPU hotspot (that is where the CPU goes): " + cpuRows);
    }

    /**
     * Sprint 28g C15 — <b>the hot PATH, which a top-of-stack ranking structurally cannot
     * name.</b>
     *
     * <p>A hotspot ranking credits each sample to the method executing at that instant, so a
     * method whose time goes entirely into what it CALLS scores zero and never appears. The
     * ranking names where the CPU was; it never says what the work was FOR. On this very
     * fixture the ranking names {@code burnCpu} — correctly — and cannot tell you that
     * {@code main} holds that same time, because {@code main} is never on top of the stack.</p>
     *
     * <p>The two halves are ordered deliberately, which is this class's own discipline: the
     * DETERMINISTIC claims first (an unrun entry point, and a missing one), then the
     * sampling-dependent one behind its own assumption. A rank asserted on a recording that
     * never caught the thread reports a conclusion the data cannot support.</p>
     */
    @Test
    @DisplayName("call_tree: the hot PATH from a named entry point, not only the hot leaf")
    void callTreeNamesThePathAndNotOnlyTheLeaf() throws Exception {
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 5);
        String artifactId = (String) data(profile.execute(sample)).get("artifactId");

        // DETERMINISTIC 1 — a method the recording never ran is an ABSENCE OF SAMPLES, and must
        // not read as "this method is fast". That distinction is the whole reason the note
        // exists, and it depends on no sampling luck at all.
        ObjectNode unrun = profileAction("call_tree");
        unrun.put("artifactId", artifactId);
        unrun.put("entryPoint", "com.example.debug.NoSuchTargetAtAll#neverRan");
        ToolResponse absentResponse = profile.execute(unrun);
        Map<String, Object> absent = data(absentResponse);
        String absentSteering = absentResponse.getMeta().getSteering();

        // DETERMINISTIC 2 — no entry point at all is REFUSED rather than rooted at Thread#run,
        // which would name nothing while looking like an answer.
        ObjectNode rootless = profileAction("call_tree");
        rootless.put("artifactId", artifactId);
        ToolResponse refused = profile.execute(rootless);

        // THE SAMPLING-DEPENDENT HALF.
        ObjectNode tree = profileAction("call_tree");
        tree.put("artifactId", artifactId);
        tree.put("entryPoint", "com.example.debug.HotLoopTarget#main");
        ToolResponse r = profile.execute(tree);
        assertTrue(r.isSuccess(), () -> "call_tree must be a real action: " + r.getError());
        Map<String, Object> d = data(r);

        assertAll(
            () -> assertEquals(0L,
                ((Number) absent.get("samplesThroughEntryPoint")).longValue(),
                () -> "a method that never ran has no samples through it: " + absent),
            () -> assertTrue(String.valueOf(absent.get("note")).contains("absence of samples"),
                () -> "and it SAYS that, rather than letting an empty tree read as a"
                    + " measurement that the method is cheap: " + absent),
            // C15 blocker 5 HAD NO CONTROL until the round-2 audit said so. The note was
            // widened from two causes to three, and the only assertion on it was the
            // substring above — which the OLD two-cause note satisfied just as well, so
            // reverting the widening reddened nothing. The third cause is the one that
            // matters: the method ran and was OFF-CPU throughout, which an execution-sample
            // tree cannot see at all. Reporting that as "it did not run" is this note's own
            // worst failure, and it is the only cause whose remedy is a different tool.
            () -> assertTrue(String.valueOf(absent.get("note")).contains("OFF-CPU"),
                () -> "and it names the THIRD cause — ran, but off-CPU throughout, which this"
                    + " tree is built from execution samples and cannot see — rather than"
                    + " offering only did-not-run and name-does-not-match: " + absent),
            // C15 ROUND 4's F1 — THE DISCRIMINATING STEERING HAD NO NEEDLE, which is round 2's
            // A2 recurring one layer out: new published text, nothing holding it. Nothing in
            // this suite read `ResponseMeta` for this tool, so folding the two branches back
            // into one message would have reddened nothing.
            //
            // This case is the deterministic way in. `HotLoopTarget` burns CPU for the whole
            // recording while this entry point names a class that does not exist, so
            // `executionSamplesInRecording > 0` and `samplesThroughEntryPoint == 0` both hold
            // by construction — exactly the branch that says the sampler WAS working and this
            // entry point is what it never caught. The needle is wording only that branch
            // emits; the other branch is unreachable from a test, because no fixture here can
            // produce a recording with zero samples on demand (the plan records 0 of ~80 with
            // the cause unestablished), and asserting it would be a flake.
            () -> assertTrue(absentSteering.contains("the sampler WAS working"),
                () -> "the steering must say WHICH absence this is — samples in the recording"
                    + " but none through the entry point — rather than one message for both"
                    + " cases, which sent a reader to the wrong half of the time: "
                    + absentSteering),
            () -> assertTrue(absentSteering.contains("hotspots(dimension=wall)"),
                () -> "and it must name the tool that CAN see an off-CPU method, since this"
                    + " tree is built from execution samples and structurally cannot: "
                    + absentSteering),
            () -> assertTrue(((List<?>) absent.get("hotPath")).isEmpty(),
                () -> "with no path invented for it: " + absent),
            () -> assertFalse(refused.isSuccess(),
                () -> "a tree with no named root must be refused: " + data(refused)));

        // C15 AUDIT BLOCKER 7 — the bare-Type entry form is advertised in the published schema
        // and in the absence note, and BOTH existing cases passed a Type#method. So the
        // capability was correct and unproven: making its clause return false reddened nothing.
        //
        // IT SITS HERE, ABOVE THE RANKABILITY ASSUMPTION, and the round-3 audit is why. It was
        // below it, and that assumption reads `samplesThroughEntryPoint` — which is an OUTPUT of
        // the matching. So a `matchesEntry`-always-false defect aborted the case before reaching
        // the one clause in the suite that would have caught it: measured, the whole suite
        // reported 2 aborts and 0 failures under that mutation. A claim about MATCHING must not
        // sit behind a gate the matching's own failure trips.
        //
        // Its own precondition is the recording's thickness instead, which no matching defect
        // can suppress — so a bare Type that stops entering at any of its methods now FAILS.
        ObjectNode bare = profileAction("call_tree");
        bare.put("artifactId", artifactId);
        bare.put("entryPoint", "com.example.debug.HotLoopTarget");
        Map<String, Object> byType = data(profile.execute(bare));

        Assumptions.assumeTrue(
            ((Number) byType.get("executionSamplesInRecording")).longValue() > 0,
            () -> "the sampler captured nothing in this window, so no entry form can be"
                + " exercised against it — a skip, not a pass: " + byType);
        assertTrue(((Number) byType.get("samplesThroughEntryPoint")).longValue() > 0,
            "a bare Type enters at any of its methods, which the schema promises and nothing"
                + " exercised: " + byType);

        long through = ((Number) d.get("samplesThroughEntryPoint")).longValue();
        Assumptions.assumeTrue(through >= MIN_SAMPLES_TO_RANK,
            () -> "recording too thin for a PATH: only " + through + " sample(s) passed through"
                + " the entry point, under the " + MIN_SAMPLES_TO_RANK + " needed for the"
                + " heaviest-child ordering to mean anything. Not a failure of the tree — the"
                + " recording never caught the thread often enough. Answer: " + d);

        @SuppressWarnings("unchecked")
        List<String> hotPath = (List<String>) d.get("hotPath");
        @SuppressWarnings("unchecked")
        Map<String, Object> root = (Map<String, Object>) d.get("tree");

        assertAll(
            () -> assertFalse(hotPath.isEmpty(), () -> "a sampled entry point has a path: " + d),
            () -> assertTrue(hotPath.get(0).startsWith("com.example.debug.HotLoopTarget#main"),
                () -> "the path starts at the entry point the caller NAMED, not at Thread#run:"
                    + " " + hotPath),
            () -> assertTrue(hotPath.stream().anyMatch(step -> step.contains("burnCpu")),
                () -> "and it REACHES the hot leaf — which is the point of the clause: the"
                    + " ranking already names burnCpu and can never tell you that main holds"
                    + " that time, because main is never on top of the stack. Path: " + hotPath),
            () -> assertTrue(hotPath.size() >= 2,
                () -> "a path of one is a leaf wearing a path's name: " + hotPath),
            // INCLUSIVE, which is what credits a caller at all.
            () -> assertTrue(((Number) root.get("inclusiveMillis")).longValue() > 0,
                () -> "the root carries its whole subtree's time, so the caller is credited"
                    + " with what it caused: " + root),
            // AND THE COUNT SITS BESIDE THE TIME, so a reader can tell a 3-sample node from a
            // 300-sample one — which is exactly what this class's own assumeRankable exists to
            // check and what a time without a count hides.
            () -> assertNotNull(root.get("samples"),
                () -> "every node reports the samples behind its number: " + root));

        // C15 AUDIT BLOCKER 4 — the truncation marker is NOT tested here, and the reason is a
        // measurement rather than a choice. `HotLoopTarget`'s sampled tree is exactly two deep
        // (main -> burnCpu, and burnCpu is the innermost frame of every sample), while the tool
        // clamps `maxDepth` to at least 1 — so the shallowest request this door accepts already
        // reaches the end of this fixture's chain and there is nothing to cut. A first version
        // asserted the marker here and failed for exactly that reason; loosening the clamp to
        // make it pass would have been changing the product to suit a test. The clause lives in
        // `WallTimeNamesWorkNotWaitingTest` instead, over a deliberately deep chain and against
        // the parser, where no clamp applies.

    }

    @Test
    @DisplayName("call_counts: REAL per-method invocation counts, not relabeled sample counts")
    void callCountsCountsActualCalls() throws Exception {
        // Sprint-24 audit T2.2: D11 asks for call counts; v2.13.0 relabeled a hotspot's
        // top-of-stack SAMPLE count as if it were one. LatencySeamTarget calls a cheap seam()
        // ~200×/s — so over 3s the REAL call count is in the hundreds, while a CPU sample
        // count of that same cheap, mostly-sleeping method would be a handful. This proves
        // call_counts counts CALLS, not stack-top samples.
        String sessionId = launchAndResume("com.example.debug.LatencySeamTarget");

        ObjectNode args = profileAction("call_counts");
        args.put("sessionId", sessionId);
        args.put("className", "com.example.debug.LatencySeamTarget");
        args.put("durationSeconds", 3);
        ToolResponse r = profile.execute(args);
        assertTrue(r.isSuccess(), "call_counts must be a real action, not unknown: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) d.get("rows");
        assertFalse(rows.isEmpty(), "a running program calls methods: " + d);

        long seamCalls = rows.stream()
            .filter(row -> "com.example.debug.LatencySeamTarget#seam".equals(row.get("symbol")))
            .mapToLong(row -> ((Number) row.get("calls")).longValue())
            .findFirst().orElse(0);
        assertTrue(seamCalls > 50,
            "seam() is called ~200x/s — a REAL call count over 3s is in the hundreds, where a "
                + "CPU-sample count of this cheap method would be a handful: got " + seamCalls
                + " from " + rows);

        for (Map<String, Object> row : rows) {
            assertTrue(row.get("symbol").toString().startsWith("com.example.debug.LatencySeamTarget#"),
                "every row is a symbol-named method of the class: " + row);
            assertNotNull(row.get("calls"), "…ranked by real call count: " + row);
        }
        assertNotNull(d.get("totalCalls"));
    }

    @Test
    @DisplayName("domain_events: the TARGET's own JFR events are surfaced, with their fields")
    void domainEventsSurfacesTheApplicationsOwnEvents() throws Exception {
        // Sprint-24 audit T2.3: D12 asks for "the target's own domain events where it emits
        // them"; v2.13.0 surfaced only the JVM's built-in jdk.* events. DomainEventTarget
        // commits its own OrderPlaced JFR event ~50x/s. sample() records into whatever JFR
        // recording is running, so those events land in the same recording jawata profiles,
        // and domain_events must read them back out.
        String sessionId = launchAndResume("com.example.debug.DomainEventTarget");

        ObjectNode sample = profileAction("sample");
        sample.put("sessionId", sessionId);
        sample.put("durationSeconds", 3);
        ToolResponse sampled = profile.execute(sample);
        assertTrue(sampled.isSuccess(), "got: " + sampled.getError());
        String artifactId = (String) data(sampled).get("artifactId");

        ObjectNode domain = profileAction("domain_events");
        domain.put("artifactId", artifactId);
        ToolResponse r = profile.execute(domain);
        assertTrue(r.isSuccess(), "domain_events must be a real action: " + r.getError());
        Map<String, Object> d = data(r);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> types = (List<Map<String, Object>>) d.get("domainEventTypes");
        assertFalse(types.isEmpty(), "the target's own events must be surfaced: " + d);

        Map<String, Object> order = types.stream()
            .filter(t -> "com.example.debug.OrderPlaced".equals(t.get("eventType")))
            .findFirst().orElse(null);
        assertNotNull(order, "the application's OrderPlaced event, by its own name: " + types);
        assertTrue(((Number) order.get("count")).longValue() > 0, "with a real count: " + order);

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> recent = (List<Map<String, Object>>) order.get("recent");
        assertFalse(recent.isEmpty(), "and a sample of the events themselves: " + order);
        Map<String, Object> one = recent.get(0);
        assertTrue(one.containsKey("symbol") && one.containsKey("quantity"),
            "carrying the event's OWN declared fields — the target's domain: " + one);
    }

    /**
     * The smallest top-of-ranking sample count for a CPU ranking to carry information.
     *
     * <p>Below this every row is in the noise: with all counts tied at one, "rank" is
     * arbitrary tie order, so an assertion on it can fail on a correct build and pass on a
     * broken one. Three is the smallest count that shows any separation.</p>
     */
    private static final long MIN_SAMPLES_TO_RANK = 3;

    /**
     * Require a CPU profile thick enough to rank, and ABORT VISIBLY when it is not.
     *
     * <p>Sprint 28 (v3.6.3): the CI runner returned a five-row CPU ranking in which every
     * row had exactly one sample — the profiled thread had been caught once in five seconds
     * on a contended machine — and the rank assertion failed a correct build.</p>
     *
     * <p>This aborts rather than passes, deliberately. A silent pass on a starved profile is
     * a test that quietly stops testing, which is the same shape as returning a failed
     * lookup as an ordinary empty result: the summary would read "succeeded" for a claim
     * nothing verified. An abort shows up in the suite line as its own count, and the
     * message carries the number that caused it.</p>
     */
    private static void assumeRankable(List<Map<String, Object>> rows, String what) {
        long topSamples = rows.stream()
            .map(row -> row.get("samples"))
            .filter(Number.class::isInstance)
            .mapToLong(value -> ((Number) value).longValue())
            .max()
            .orElse(0);
        Assumptions.assumeTrue(topSamples >= MIN_SAMPLES_TO_RANK,
            () -> "CPU profile too thin to rank " + what + ": the top row carries "
                + topSamples + " sample(s), under the " + MIN_SAMPLES_TO_RANK
                + " needed for the ordering to mean anything. Not a failure of the ranking "
                + "— the recording never caught the thread often enough to rank. Rows: "
                + rows);
    }

    private static int rankOf(List<Map<String, Object>> rows, String symbol) {
        for (Map<String, Object> row : rows) {
            if (symbol.equals(row.get("symbol"))) {
                return ((Number) row.get("rank")).intValue();
            }
        }
        return -1;
    }

    // ========================================================== jfr_dump (on-demand, mid-run)

    @Test
    @DisplayName("jfr_dump: the continuous recording is dumped ON DEMAND, mid-run")
    void jfrDumpMidRunSucceeds() throws Exception {
        // The dev/sim preset starts a CONTINUOUS recording ("jawata") on every launch —
        // no action=sample needed first.
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");
        Thread.sleep(1000); // let it accumulate something worth dumping

        ObjectNode dump = profileAction("jfr_dump");
        dump.put("sessionId", sessionId);
        ToolResponse r = profile.execute(dump);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> d = data(r);

        String artifactId = (String) d.get("artifactId");
        assertNotNull(artifactId);
        assertTrue(((Number) d.get("bytes")).longValue() > 0, "a real JFR file was dumped");

        // And it is USABLE — rank it, same as a targeted sample.
        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", artifactId);
        ToolResponse ranked = profile.execute(hotspots);
        assertTrue(ranked.isSuccess(), "a mid-run dump must be a valid JFR file: " + ranked.getError());
    }

    // ========================================================== capability honesty

    @Test
    @DisplayName("sample: honest capability-absent on a target with Flight Recorder disabled — never empty data")
    void sampleReportsFlightRecorderAbsentHonestly() throws Exception {
        Process foreign = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-XX:-FlightRecorder",
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
            "-cp", targetClasses.toString(), "com.example.debug.DebugTarget")
            .redirectErrorStream(true)
            .start();
        try {
            Thread.sleep(1500);
            assertTrue(foreign.isAlive());

            ObjectNode attach = debugAction("attach");
            attach.put("pid", foreign.pid());
            ToolResponse attached = debug.execute(attach);
            assertTrue(attached.isSuccess(), "got: " + attached.getError());
            String sessionId = (String) data(attached).get("sessionId");

            ObjectNode sample = profileAction("sample");
            sample.put("sessionId", sessionId);
            sample.put("durationSeconds", 1);
            ToolResponse r = profile.execute(sample);
            assertTrue(r.isSuccess(), "capability-absent is a SUCCESSFUL, honest answer: "
                + r.getError());
            Map<String, Object> d = data(r);
            assertEquals(Boolean.FALSE, d.get("enabled"), "got: " + d);
            assertNotNull(d.get("why"), "the absence must be EXPLAINED: " + d);
            assertTrue(String.valueOf(d.get("why")).contains("FlightRecorder"),
                "names the actual cause: " + d.get("why"));
        } finally {
            foreign.descendants().forEach(ProcessHandle::destroyForcibly);
            foreign.destroyForcibly();
            foreign.waitFor(10, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("jfr_dump: honest capability-absent when no continuous recording exists (not preset-launched)")
    void jfrDumpAbsentWhenNoContinuousRecording() throws Exception {
        // A foreign JVM with JDWP but no -XX:StartFlightRecording — same shape as the
        // NMT-absent proof in ProfileFloorTest.
        Process foreign = new ProcessBuilder(
            Path.of(System.getProperty("java.home"), "bin", "java").toString(),
            "-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=127.0.0.1:0",
            "-cp", targetClasses.toString(), "com.example.debug.DebugTarget")
            .redirectErrorStream(true)
            .start();
        try {
            Thread.sleep(1500);
            ObjectNode attach = debugAction("attach");
            attach.put("pid", foreign.pid());
            String sessionId = (String) data(debug.execute(attach)).get("sessionId");

            ObjectNode dump = profileAction("jfr_dump");
            dump.put("sessionId", sessionId);
            ToolResponse r = profile.execute(dump);
            assertTrue(r.isSuccess(), "capability-absent is a SUCCESSFUL, honest answer: "
                + r.getError());
            Map<String, Object> d = data(r);
            assertEquals(Boolean.FALSE, d.get("enabled"), "got: " + d);
            assertNotNull(d.get("why"), "got: " + d);
        } finally {
            foreign.descendants().forEach(ProcessHandle::destroyForcibly);
            foreign.destroyForcibly();
            foreign.waitFor(10, TimeUnit.SECONDS);
        }
    }

    // ========================================================== error handling

    @Test
    @DisplayName("hotspots on a non-JFR artifact is refused by name, not misread")
    void hotspotsRefusesWrongArtifactKind() throws Exception {
        String sessionId = launchAndResume("com.example.debug.HotLoopTarget");
        ToolResponse dumped = profile.execute(profileAction("heap_dump")
            .put("sessionId", sessionId));
        String heapArtifactId = (String) data(dumped).get("artifactId");

        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", heapArtifactId);
        ToolResponse r = profile.execute(hotspots);
        assertFalse(r.isSuccess());
        assertEquals("NOT_A_JFR_ARTIFACT", r.getError().getCode());
    }

    @Test
    @DisplayName("hotspots on an unknown artifactId is an honest miss")
    void hotspotsUnknownArtifactIsHonestMiss() {
        ObjectNode hotspots = profileAction("hotspots");
        hotspots.put("artifactId", "jfr-sample-nope");
        ToolResponse r = profile.execute(hotspots);
        assertFalse(r.isSuccess());
    }
}
