package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 25 Stage 14a (the C0-F2 cure): async family sweeps. A family sweep
 * can outlive a client timeout; the finished result must stay RETRIEVABLE
 * (repeatedly), progress must be visible while running, and cancellation is
 * honest (partial marked partial, never a silent nothing).
 */
class FindQualityIssueAsyncTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private FindQualityIssueTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (Map<String, Object>) r.getData();
    }

    private Map<String, Object> awaitFinished(String sweepId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 120_000;
        while (System.currentTimeMillis() < deadline) {
            ObjectNode status = objectMapper.createObjectNode();
            status.put("action", "status");
            status.put("sweepId", sweepId);
            Map<String, Object> d = data(tool.execute(status));
            if (!"running".equals(d.get("state"))) {
                return d;
            }
            // Progress is visible while running.
            assertTrue((int) d.get("kindsTotal") > 0);
            Thread.sleep(100);
        }
        throw new AssertionError("sweep " + sweepId + " never finished");
    }

    @Test
    @DisplayName("start returns a sweepId; status reaches finished; the FULL result is retrievable REPEATEDLY")
    void asyncSweep_fullLifecycle() throws Exception {
        ObjectNode start = objectMapper.createObjectNode();
        start.put("action", "start");
        start.put("family", "quality");
        Map<String, Object> started = data(tool.execute(start));
        String sweepId = (String) started.get("sweepId");
        assertNotNull(sweepId, "start must hand out the sweep handle");
        assertTrue((int) started.get("kindsTotal") > 0);

        Map<String, Object> finished = awaitFinished(sweepId);
        assertEquals("finished", finished.get("state"));
        assertEquals("quality", finished.get("family"));
        assertNotNull(finished.get("findings"), "the FULL sweep result rides the status response");
        assertNotNull(finished.get("count"));

        // The point of the feature: a timed-out client RETRIEVES the completed
        // result — repeatedly, byte-for-byte the same set.
        Map<String, Object> again = awaitFinished(sweepId);
        assertEquals(finished.get("count"), again.get("count"));
        assertEquals("finished", again.get("state"));
    }

    /**
     * jawata-mcp#72 — A SINGLE SLOW KIND TAKES THE SAME WAY OUT A FAMILY ALREADY HAD.
     *
     * <p>Found by dogfooding the released v4.1.0: {@code kind=encapsulation} over 1056 files
     * timed out twice at the 30-second default, and returned 228 findings inside a family
     * sweep — so the work was always finishable and only the channel was missing. This branch
     * REQUIRED a {@code family}, which meant the escape hatch existed for the shape that
     * refuses in milliseconds and not for the one that burns the caller's whole timeout.</p>
     *
     * <p>A bare timeout cannot be told apart from a broken tool, and the two moves it leaves a
     * caller are to retry — identical result — or to abandon the tool. That is what this
     * closes; it does not try to predict WHICH kind is slow, because that is a property of the
     * project rather than of the detector.</p>
     */
    @Test
    @DisplayName("a single kind can be started asynchronously, and answers what the synchronous call would")
    void asyncSingleKind_isTheSameAnswerByAnotherRoute() throws Exception {
        ObjectNode start = objectMapper.createObjectNode();
        start.put("action", "start");
        start.put("kind", "long_method");
        Map<String, Object> started = data(tool.execute(start));

        String sweepId = (String) started.get("sweepId");
        assertNotNull(sweepId, "a single kind must hand out a handle just as a family does");
        assertEquals("long_method", started.get("kind"),
            "and the response must say WHAT it started: " + started);
        assertNull(started.get("family"),
            "a single-kind sweep has no family, and saying `family: null` would read as one "
                + "that lost it: " + started);
        assertEquals(1, started.get("kindsTotal"));

        Map<String, Object> finished = awaitFinished(sweepId);
        assertEquals("finished", finished.get("state"));
        assertEquals("long_method", finished.get("kind"),
            "a retrieved result must describe itself — the handle may be days old: " + finished);
        assertNotNull(finished.get("findings"), "the full result rides the status response");

        // THE CLAUSE THAT MAKES THIS A FIX RATHER THAN A SECOND CODE PATH. A caller who moved
        // here because their project is large must get the SAME answer, not a thinner one: the
        // async route keeps the cure join and the path filter the synchronous route applies.
        ObjectNode direct = objectMapper.createObjectNode();
        direct.put("kind", "long_method");
        Map<String, Object> synchronously = data(tool.execute(direct));
        assertEquals(synchronously.get("count"), finished.get("count"),
            "the async route must not answer with a different finding set than `run` does");
    }

    @Test
    @DisplayName("start with neither a family nor a kind refuses, and names both ways in")
    void startNeedsSomethingToSweep() {
        ObjectNode start = objectMapper.createObjectNode();
        start.put("action", "start");
        ToolResponse r = tool.execute(start);

        assertFalse(r.isSuccess(), "there is nothing to start");
        String message = String.valueOf(r.getError().getMessage());
        assertTrue(message.contains("family") && message.contains("kind"),
            "a refusal that names only one of the two ways in sends half its readers nowhere: "
                + message);
    }

    @Test
    @DisplayName("cancel is honest: state=cancelled + partial:true, kindsDone visible")
    void asyncSweep_cancelIsHonestPartial() throws Exception {
        ObjectNode start = objectMapper.createObjectNode();
        start.put("action", "start");
        start.put("family", "fowler"); // the slow family — cancel can land mid-run
        String sweepId = (String) data(tool.execute(start)).get("sweepId");

        ObjectNode cancel = objectMapper.createObjectNode();
        cancel.put("action", "cancel");
        cancel.put("sweepId", sweepId);
        Map<String, Object> cancelled = data(tool.execute(cancel));
        assertTrue(Map.of("cancel_requested", 1, "cancelled", 1, "finished", 1)
                .containsKey(cancelled.get("state")), "state: " + cancelled.get("state"));

        Map<String, Object> end = awaitFinished(sweepId);
        // Raced completion is legitimate; a cancelled run must say partial.
        if ("cancelled".equals(end.get("state"))) {
            assertEquals(Boolean.TRUE, end.get("partial"));
            assertNotNull(end.get("kindsDone"));
        } else {
            assertEquals("finished", end.get("state"));
        }
    }

    @Test
    @DisplayName("unknown sweepId + start without family are loud INVALID_PARAMETER")
    void asyncSweep_invalidInputsAreLoud() {
        ObjectNode status = objectMapper.createObjectNode();
        status.put("action", "status");
        status.put("sweepId", "sweep-never-existed");
        ToolResponse r = tool.execute(status);
        assertFalse(r.isSuccess());
        assertEquals("INVALID_PARAMETER", r.getError().getCode());

        ObjectNode start = objectMapper.createObjectNode();
        start.put("action", "start");
        ToolResponse r2 = tool.execute(start);
        assertFalse(r2.isSuccess());
        assertEquals("INVALID_PARAMETER", r2.getError().getCode());

        ObjectNode bad = objectMapper.createObjectNode();
        bad.put("action", "resume");
        ToolResponse r3 = tool.execute(bad);
        assertFalse(r3.isSuccess());
        assertEquals("INVALID_PARAMETER", r3.getError().getCode());
    }

    /**
     * This test used to assert that a synchronous family sweep worked and that
     * {@code action="run"} matched it. {@code jawata-mcp#10} removed the thing
     * it asserted: a family sweep timed out on any realistic project, so the
     * synchronous path is refused rather than advertised.
     *
     * <p>What survives is the invariant underneath it — the two spellings of
     * "synchronous" must behave IDENTICALLY. Omitting {@code action} and
     * passing {@code action="run"} are the same request, and one of them
     * quietly still running a sweep would be the divergence this file was
     * written to catch, wearing the opposite sign.</p>
     */
    @Test
    @DisplayName("both spellings of synchronous refuse identically, and name the async path")
    void theTwoSynchronousSpellingsAgree() {
        ObjectNode plain = objectMapper.createObjectNode();
        plain.put("family", "quality");
        ToolResponse a = tool.execute(plain);

        ObjectNode run = objectMapper.createObjectNode();
        run.put("action", "run");
        run.put("family", "quality");
        ToolResponse b = tool.execute(run);

        assertFalse(a.isSuccess(), "no action: a family sweep is async-only");
        assertFalse(b.isSuccess(), "action=run: the same request, the same answer");
        assertEquals(a.getError().getCode(), b.getError().getCode());
        assertEquals("SWEEP_REQUIRES_ASYNC", a.getError().getCode());
        assertTrue(a.getError().getMessage().contains("start"),
            "the refusal must point at the path this whole file tests: "
                + a.getError().getMessage());
    }

    /**
     * jawata-mcp#6 (Sprint 27a Stage 8): status must honor summary/limit/offset
     * exactly as run does — the M2 sweep returned 100 FULL findings (over the
     * client limit) while advising "use summary:true", because the worker froze
     * the START call's shaping into every retrieval.
     */
    @Test
    @DisplayName("status honors the RETRIEVING call's summary/limit/offset, not the start call's")
    void asyncSweep_statusHonorsTheRetrievingCallsShaping() throws Exception {
        ObjectNode start = objectMapper.createObjectNode();
        start.put("action", "start");
        start.put("family", "quality");
        String sweepId = (String) data(tool.execute(start)).get("sweepId");
        awaitFinished(sweepId);

        // summary:true on STATUS → counts only, NO findings array.
        ObjectNode summary = objectMapper.createObjectNode();
        summary.put("action", "status");
        summary.put("sweepId", sweepId);
        summary.put("summary", true);
        Map<String, Object> s = data(tool.execute(summary));
        assertEquals("finished", s.get("state"));
        assertNull(s.get("findings"), "summary:true must drop the findings array: " + s.keySet());
        assertNotNull(s.get("byKind"), "and carry the counts-by-kind block");

        // limit on STATUS → that page, with honest truncation bookkeeping.
        ObjectNode paged = objectMapper.createObjectNode();
        paged.put("action", "status");
        paged.put("sweepId", sweepId);
        paged.put("limit", 1);
        Map<String, Object> p = data(tool.execute(paged));
        assertEquals(1, ((java.util.List<?>) p.get("findings")).size(),
            "limit:1 must page to one finding");
        assertNotNull(p.get("count"), "the FULL total stays visible beside the page");
    }
}
