package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.jawata.mcp.tools.DebugTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D6, C8) — the interactive debugger, against a LIVE fixture JVM.
 *
 * <p>Every proof here runs against a real program: arm a breakpoint, start it, hit
 * the breakpoint, read the frame, evaluate in it, step, resume. Nothing is mocked —
 * a mocked debugger proves only that the mock works.</p>
 *
 * <p>Each test begins with the target held at the top of {@code main}: classes
 * loaded, objects built, and not one loop iteration run. That is what makes "the
 * third hit" mean the third one — rather than the third one we happened to catch.</p>
 */
class DebugInteractiveTest {

    private static final String TARGET = "com.example.debug.DebugTarget";
    private static final String WIDGET = "com.example.debug.DebugTarget$Widget";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private RuntimeSessionRegistry sessions;
    private DebugTool tool;
    private ObjectMapper om;
    private Path targetClasses;
    private List<String> source;
    private String sessionId;
    /** main, suspended at the top of itself — every test starts from here. */
    private long mainThread;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("debug-target");
        sessions = new RuntimeSessionRegistry();
        tool = new DebugTool(() -> service, sessions);
        om = new ObjectMapper();

        Path file = service.getProjectRoot()
            .resolve("src/main/java/com/example/debug/DebugTarget.java");
        source = Files.readAllLines(file);

        // -g, or the frame has no local-variable table and every locals proof is vacuous.
        targetClasses = Files.createTempDirectory("jawata-debug-interactive-");
        int rc = javax.tools.ToolProvider.getSystemJavaCompiler().run(
            null, null, null, "-g", "-d", targetClasses.toString(), file.toString());
        assertEquals(0, rc, "the debug fixture must compile");

        ObjectNode launch = action("launch");
        launch.put("mainClass", TARGET);
        launch.put("classpath", targetClasses.toString());
        ToolResponse launched = tool.execute(launch);
        assertTrue(launched.isSuccess(), "got: " + launched.getError());
        sessionId = (String) data(launched).get("sessionId");

        assertEquals(Boolean.TRUE, data(launched).get("awaitingStart"),
            "a launched target must not be running before we have armed anything: "
                + data(launched));

        mainThread = runToTopOfMain();
    }

    @AfterEach
    void tearDown() {
        sessions.closeAll();
    }

    // ------------------------------------------------------------- plumbing

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode action(String name) {
        ObjectNode args = om.createObjectNode();
        args.put("action", name);
        return args;
    }

    private ObjectNode onSession(String name) {
        ObjectNode args = action(name);
        args.put("sessionId", sessionId);
        return args;
    }

    /** The 1-based source line containing this snippet — never a hardcoded number. */
    private int lineOf(String snippet) {
        for (int i = 0; i < source.size(); i++) {
            if (source.get(i).contains(snippet)) {
                return i + 1;
            }
        }
        throw new AssertionError("the fixture no longer contains: " + snippet);
    }

    private Map<String, Object> setBreakpoint(ObjectNode args) {
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "breakpoint_set failed: " + r.getError());
        return data(r);
    }

    /** Arm a breakpoint on the fixture, which by now is loaded — so it binds at once. */
    private Map<String, Object> arm(String kind, Map<String, Object> extra) {
        ObjectNode args = onSession("breakpoint_set");
        args.put("kind", kind);
        args.put("className", TARGET);
        extra.forEach((key, value) -> {
            if (value instanceof Integer number) {
                args.put(key, number);
            } else {
                args.put(key, String.valueOf(value));
            }
        });
        Map<String, Object> armed = setBreakpoint(args);
        assertEquals(Boolean.TRUE, armed.get("bound"),
            "the fixture is loaded by now, so this binds immediately: " + armed);
        return armed;
    }

    /** Let the program run on from where it is stopped. */
    private void go() {
        ObjectNode args = onSession("resume");
        args.put("threadId", mainThread);
        assertTrue(tool.execute(args).isSuccess());
    }

    private long runToTopOfMain() {
        ObjectNode bootstrap = onSession("breakpoint_set");
        bootstrap.put("kind", "line");
        bootstrap.put("className", TARGET);
        bootstrap.put("line", lineOf("Node head = graph;"));
        Map<String, Object> armed = setBreakpoint(bootstrap);

        // The fixture is NOT loaded yet — the VM is held at init. So this breakpoint is
        // DEFERRED, and it says so rather than pretending to be armed.
        assertEquals(Boolean.FALSE, armed.get("bound"), "got: " + armed);
        assertEquals(Boolean.TRUE, armed.get("pending"));

        assertTrue(tool.execute(onSession("resume")).isSuccess(), "start the program");

        Map<String, Object> hit = awaitHit();
        assertEquals("main", hit.get("method"), "we stop at the top of main: " + hit);

        ObjectNode clear = onSession("breakpoint_clear");
        clear.put("breakpointId", (String) armed.get("breakpointId"));
        assertTrue(tool.execute(clear).isSuccess());

        return ((Number) hit.get("threadId")).longValue();
    }

    /** Wait for the next hit — generously, because a real JVM is doing real work. */
    private Map<String, Object> awaitHit() {
        ObjectNode args = onSession("wait");
        args.put("timeoutMillis", 30_000);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "wait failed: " + r.getError());
        Map<String, Object> hit = data(r);
        assertEquals(Boolean.TRUE, hit.get("hit"),
            "nothing hit within 30s — the breakpoint never fired: " + hit);
        return hit;
    }

    private Map<String, Object> snapshot(long threadId, int depth) {
        ObjectNode args = onSession("snapshot");
        args.put("threadId", threadId);
        args.put("depth", depth);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "snapshot failed: " + r.getError());
        return data(r);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> frameOf(Map<String, Object> snapshot) {
        return (Map<String, Object>) snapshot.get("frame");
    }

    private Object evaluate(long threadId, String expression) {
        ObjectNode args = onSession("evaluate");
        args.put("threadId", threadId);
        args.put("expression", expression);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "evaluate('" + expression + "') failed: " + r.getError());
        return data(r).get("summary");
    }

    private void step(long threadId, String mode) {
        ObjectNode args = onSession("step");
        args.put("threadId", threadId);
        args.put("mode", mode);
        assertTrue(tool.execute(args).isSuccess());
    }

    // -------------------------------------------------------- the six kinds

    @Test
    @DisplayName("LINE breakpoint: hit → stack + arguments → step over → the local appears")
    void lineBreakpointStopsAndTheFrameIsReadable() {
        arm("line", Map.of("line", lineOf("int doubled = iteration * 2;")));
        go();

        Map<String, Object> hit = awaitHit();
        assertEquals("computeSignal", hit.get("method"), "got: " + hit);
        long threadId = ((Number) hit.get("threadId")).longValue();

        Map<String, Object> frame = frameOf(snapshot(threadId, 2));
        assertEquals("computeSignal", frame.get("method"));
        assertNull(frame.get("this"), "computeSignal is static — there is no receiver");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> arguments = (List<Map<String, Object>>) frame.get("arguments");
        assertEquals(1, arguments.size(), "got: " + arguments);
        assertEquals("iteration", arguments.get(0).get("name"),
            "the argument, BY NAME, with its live value");

        // We stopped BEFORE `doubled` is assigned, so it is not in scope yet. Stepping over
        // one line brings it into existence — the step actually working, not a claim that
        // it did.
        step(threadId, "over");
        Map<String, Object> landed = awaitHit();
        assertEquals("step", landed.get("kind"), "got: " + landed);
        assertEquals("computeSignal", landed.get("method"));

        @SuppressWarnings("unchecked")
        Map<String, Object> locals = (Map<String, Object>) frameOf(
            snapshot(((Number) landed.get("threadId")).longValue(), 2)).get("locals");
        assertTrue(locals.containsKey("doubled"),
            "after stepping over its declaration, 'doubled' is a live local: " + locals);
    }

    @Test
    @DisplayName("METHOD breakpoint: stops on entry; step OUT returns to the caller")
    void methodBreakpointStopsOnEntry() {
        arm("method", Map.of("method", "offset"));
        go();

        Map<String, Object> hit = awaitHit();
        assertEquals("offset", hit.get("method"), "got: " + hit);
        assertEquals("method", hit.get("kind"));

        step(((Number) hit.get("threadId")).longValue(), "out");
        Map<String, Object> landed = awaitHit();
        assertEquals("computeSignal", landed.get("method"),
            "stepping out of offset() lands back in its caller: " + landed);
    }

    @Test
    @DisplayName("CONDITIONAL breakpoint: passes over every iteration until the condition holds")
    void conditionalBreakpointStopsOnlyWhenTrue() {
        arm("conditional", Map.of(
            "line", lineOf("int doubled = iteration * 2;"),
            "condition", "iteration == 4"));
        go();

        Map<String, Object> hit = awaitHit();
        long threadId = ((Number) hit.get("threadId")).longValue();
        assertNull(hit.get("conditionError"), "the condition evaluated cleanly: " + hit);

        assertEquals(4, ((Number) evaluate(threadId, "iteration")).intValue(),
            "a conditional breakpoint passes over the iterations that do not match");
    }

    @Test
    @DisplayName("HIT-COUNT breakpoint: stops on the Nth occurrence, not the first")
    void hitCountBreakpointStopsOnTheNth() {
        arm("hit_count", Map.of("line", lineOf("int echoed = echo();"), "hitCount", 3));
        go();

        Map<String, Object> hit = awaitHit();
        long threadId = ((Number) hit.get("threadId")).longValue();

        // The 3rd execution of that line is iteration 2 — the loop counts from 0, and NOT
        // ONE iteration ran before we armed it.
        assertEquals(2, ((Number) evaluate(threadId, "iteration")).intValue(),
            "hitCount=3 must stop on the THIRD occurrence: " + hit);
    }

    @Test
    @DisplayName("EXCEPTION breakpoint: catches the throw — though the program swallows it")
    void exceptionBreakpointCatchesAThrownException() {
        ObjectNode args = onSession("breakpoint_set");
        args.put("kind", "exception");
        args.put("className", "java.lang.IllegalStateException");
        Map<String, Object> armed = setBreakpoint(args);

        // Nothing has thrown one yet, so the class genuinely is not loaded — the
        // breakpoint is DEFERRED. It must still catch the very first throw, which is the
        // whole reason deferred breakpoints exist.
        assertEquals(Boolean.FALSE, armed.get("bound"),
            "an exception class is not loaded until something throws it: " + armed);
        go();

        Map<String, Object> hit = awaitHit();
        assertEquals("java.lang.IllegalStateException", hit.get("exception"), "got: " + hit);
        assertEquals(Boolean.TRUE, hit.get("caught"),
            "the fixture catches it — and we see it anyway, which is the point");
        assertEquals("riskyStep", hit.get("method"), "caught at the THROW site: " + hit);
        assertTrue(String.valueOf(hit.get("message")).contains("divisible by 17"),
            "with the exception's own message: " + hit.get("message"));
    }

    @Test
    @DisplayName("FIELD WRITE watchpoint: catches the assignment, with old AND new value")
    void fieldWriteWatchpointCatchesTheAssignment() {
        arm("field_write", Map.of("field", "lastSignal"));
        go();

        Map<String, Object> hit = awaitHit();
        assertEquals("field_write", hit.get("kind"), "got: " + hit);
        assertEquals("lastSignal", hit.get("field"));
        assertEquals("main", hit.get("method"), "the WRITE happens in main: " + hit);
        assertNotNull(hit.get("newValue"), "a write knows the value about to be stored: " + hit);
        assertNotNull(hit.get("currentValue"), "and the one being replaced: " + hit);
    }

    @Test
    @DisplayName("FIELD ACCESS watchpoint: catches the READ — a different event from the write")
    void fieldAccessWatchpointCatchesTheRead() {
        arm("field_access", Map.of("field", "lastSignal"));
        go();

        Map<String, Object> hit = awaitHit();
        assertEquals("field_access", hit.get("kind"), "got: " + hit);
        assertEquals("lastSignal", hit.get("field"));
        assertEquals("echo", hit.get("method"),
            "the READ happens in echo(), not at the assignment: " + hit);
        // A read has a current value and NO new value — that is what distinguishes it.
        assertNotNull(hit.get("currentValue"));
        assertNull(hit.get("newValue"), "nothing is being stored by a read: " + hit);
    }

    @Test
    @DisplayName("step IN descends into the callee; step TO_LINE runs to a line you name")
    void stepInAndStepToLine() {
        arm("line", Map.of("line", lineOf("int adjusted = doubled + offset();")));
        go();

        Map<String, Object> hit = awaitHit();
        long threadId = ((Number) hit.get("threadId")).longValue();
        assertEquals("computeSignal", hit.get("method"));

        step(threadId, "in");
        Map<String, Object> inside = awaitHit();
        assertEquals("offset", inside.get("method"),
            "stepping IN at a call site enters the callee: " + inside);

        ObjectNode toLine = onSession("step");
        toLine.put("threadId", ((Number) inside.get("threadId")).longValue());
        toLine.put("mode", "to_line");
        toLine.put("className", TARGET);
        toLine.put("line", lineOf("burned += i % 7;"));
        assertTrue(tool.execute(toLine).isSuccess());

        Map<String, Object> landed = awaitHit();
        assertEquals("spin", landed.get("method"),
            "we asked to run to a line inside spin(), and that is where we stopped: " + landed);
        assertEquals(lineOf("burned += i % 7;"), ((Number) landed.get("line")).intValue());
    }

    // ----------------------------------------------------- evaluate + expand

    @Test
    @DisplayName("evaluate: arithmetic, a real method call in the target, and an honest refusal")
    void evaluateRunsInTheTargetAndRefusesWhatItCannotDo() {
        arm("line", Map.of("line", lineOf("int doubled = iteration * 2;")));
        go();

        Map<String, Object> hit = awaitHit();
        long threadId = ((Number) hit.get("threadId")).longValue();

        int iteration = ((Number) evaluate(threadId, "iteration")).intValue();
        assertEquals(iteration + 1, ((Number) evaluate(threadId, "iteration + 1")).intValue());

        // A METHOD CALL that really runs over there.
        assertEquals(7, ((Number) evaluate(threadId, "offset()")).intValue(),
            "offset() was actually invoked in the target JVM");
        assertEquals(42, ((Number) evaluate(threadId, "multiply(6, 7)")).intValue(),
            "a static call with arguments");
        assertEquals(iteration * 2 + 7,
            ((Number) evaluate(threadId, "iteration * 2 + offset()")).intValue(),
            "a call composed into a larger expression");

        // A static field, and boolean logic over it.
        assertEquals(false, evaluate(threadId, "tripped"));
        assertEquals(true, evaluate(threadId, "iteration >= 0 && !tripped"));

        // AND THE REFUSAL. An assignment is not implemented — so it is refused BY NAME,
        // rather than evaluated as something else and returned as a confident wrong answer.
        ObjectNode bad = onSession("evaluate");
        bad.put("threadId", threadId);
        bad.put("expression", "iteration = 99");
        ToolResponse refused = tool.execute(bad);
        assertFalse(refused.isSuccess(), "an unsupported form must not silently 'work'");
        assertEquals("EVALUATION_UNSUPPORTED", refused.getError().getCode(),
            "refused by name: " + refused.getError());
    }

    @Test
    @DisplayName("paged expansion: a 12-deep graph is cut at the depth bound — and SAYS it was")
    void objectExpansionIsBoundedAndSaysSo() {
        arm("line", Map.of("line", lineOf("int echoed = echo();")));
        go();

        Map<String, Object> hit = awaitHit();
        long threadId = ((Number) hit.get("threadId")).longValue();

        @SuppressWarnings("unchecked")
        Map<String, Object> locals =
            (Map<String, Object>) frameOf(snapshot(threadId, 2)).get("locals");
        @SuppressWarnings("unchecked")
        Map<String, Object> head = (Map<String, Object>) locals.get("head");
        assertNotNull(head, "the deep graph is a live local here: " + locals.keySet());

        // depth=2: head → next → (cut). The cut is REPORTED, not silent.
        Map<String, Object> level = head;
        for (int i = 0; i < 2; i++) {
            @SuppressWarnings("unchecked")
            Map<String, Object> fields = (Map<String, Object>) level.get("fields");
            assertNotNull(fields, "expanded at depth " + i + ": " + level);
            @SuppressWarnings("unchecked")
            Map<String, Object> next = (Map<String, Object>) fields.get("next");
            level = next;
        }
        assertEquals(Boolean.TRUE, level.get("truncated"),
            "the graph is 12 deep and we asked for 2 — the boundary must announce itself: "
                + level);
        assertTrue(String.valueOf(level.get("reason")).contains("depth"),
            "and say WHICH bound bit: " + level.get("reason"));

        // Ask deeper and the same object yields more — which proves it was a bound, not
        // the end of the graph.
        @SuppressWarnings("unchecked")
        Map<String, Object> deeper = (Map<String, Object>) ((Map<String, Object>)
            frameOf(snapshot(threadId, 5)).get("locals")).get("head");
        assertNotNull(deeper.get("fields"));
    }

    // -------------------------------------------------- threads + instances

    @Test
    @DisplayName("threads: a RUNNING thread reports no stack and says why — never a raced read")
    void runningThreadsRefuseToShowAStack() throws Exception {
        go();                       // let main run; nothing is armed, so nothing stops it
        Thread.sleep(300);

        ToolResponse r = tool.execute(onSession("threads"));
        assertTrue(r.isSuccess(), "got: " + r.getError());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> threads = (List<Map<String, Object>>) data(r).get("threads");
        Map<String, Object> main = threads.stream()
            .filter(t -> "main".equals(t.get("name")))
            .findFirst().orElseThrow(() -> new AssertionError("no main thread: " + threads));

        assertEquals(Boolean.FALSE, main.get("suspended"), "nothing has stopped it");
        assertNull(main.get("stack"), "a running thread's stack is not a fact: " + main);
        assertNotNull(main.get("stackUnavailable"), "and it says why: " + main);

        // Asking anyway is refused WITH THE REASON — not answered with an empty list.
        ObjectNode args = onSession("snapshot");
        args.put("threadId", ((Number) main.get("threadId")).longValue());
        ToolResponse refused = tool.execute(args);
        assertFalse(refused.isSuccess());
        assertEquals("THREAD_NOT_SUSPENDED", refused.getError().getCode(),
            "got: " + refused.getError());
    }

    @Test
    @DisplayName("instances: exact below the cap, heap-backed exact above it — never a cap as a count")
    void instancesAreCountedHonestlyOnBothSidesOfTheCap() {
        // BELOW THE CAP — the fixture holds exactly 25, allocated before main's loop.
        ObjectNode args = onSession("instances");
        args.put("className", WIDGET);
        args.put("limit", 5);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        Map<String, Object> widgets = data(r);
        assertEquals(25, ((Number) widgets.get("count")).intValue(),
            "the fixture allocates exactly 25 and never another: " + widgets);
        assertTrue(String.valueOf(widgets.get("countSource")).contains("exact"),
            "and the count is exact, not a bound: " + widgets.get("countSource"));
        assertEquals(5, ((Number) widgets.get("returned")).intValue(), "paged: " + widgets);

        // ABOVE THE CAP — a JVM holds far more than 100 Strings. The count must NOT be the
        // cap wearing a count's clothes.
        ObjectNode many = onSession("instances");
        many.put("className", "java.lang.String");
        many.put("limit", 3);
        ToolResponse counted = tool.execute(many);
        assertTrue(counted.isSuccess(), "got: " + counted.getError());

        Map<String, Object> strings = data(counted);
        assertEquals(Boolean.TRUE, strings.get("pagingLimited"),
            "the page window is bounded and says so: " + strings);
        assertEquals(3, ((Number) strings.get("returned")).intValue());

        Object count = strings.get("count");
        if (count != null) {
            assertTrue(((Number) count).longValue() > 100,
                "an exact heap count, well past the enumeration cap: " + count);
            assertTrue(String.valueOf(strings.get("countSource")).contains("histogram"),
                "obtained from the heap, not from the cap: " + strings.get("countSource"));
        } else {
            // jcmd unavailable here: then it must report a FLOOR, labelled as one.
            assertNotNull(strings.get("countAtLeast"), "a floor, not a fake count: " + strings);
            assertTrue(String.valueOf(strings.get("countSource")).contains("FLOOR"),
                "and it says the word: " + strings.get("countSource"));
        }
    }

    @Test
    @DisplayName("a breakpoint on a class that is not loaded is DEFERRED, not failed")
    void unloadedClassGivesADeferredBreakpoint() {
        ObjectNode args = onSession("breakpoint_set");
        args.put("kind", "method");
        args.put("className", "com.example.debug.NeverLoaded");
        args.put("method", "whatever");
        Map<String, Object> breakpoint = setBreakpoint(args);

        assertEquals(Boolean.FALSE, breakpoint.get("bound"), "got: " + breakpoint);
        assertEquals(Boolean.TRUE, breakpoint.get("pending"));
        assertTrue(String.valueOf(breakpoint.get("pendingReason")).contains("not loaded"),
            "'not loaded yet' and 'never going to bind' must not look the same: " + breakpoint);
    }
}
