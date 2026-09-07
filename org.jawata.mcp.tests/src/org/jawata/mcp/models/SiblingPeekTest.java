package org.jawata.mcp.models;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#27 stage 1 — asking the other residents.
 *
 * <p>The stubs speak the envelope {@code McpProtocolHandler} actually builds — one
 * {@code content} entry whose {@code text} is the serialized tool response — read from the side
 * that produces it rather than from what a response is imagined to look like. <b>That is still
 * a stub written by the author of the parser</b>, so it cannot catch a wrong belief about the
 * real wire; the two-resident live probe is what settles that, and it is C6's exit clause.</p>
 */
class SiblingPeekTest {

    private final List<HttpServer> servers = new ArrayList<>();
    private final List<ServerSocket> sockets = new ArrayList<>();
    private final HttpClient client = HttpClient.newBuilder()
        .connectTimeout(Duration.ofMillis(500)).build();

    @AfterEach
    void stopAll() {
        servers.forEach(s -> s.stop(0));
        sockets.forEach(s -> {
            try {
                s.close();
            } catch (IOException ignored) {
                // closing a test socket that is already gone is not a failure
            }
        });
    }

    /** A resident that answers `tools/call` with the given tool-response JSON. */
    private SiblingRegistry.Sibling resident(String name, String toolJson,
                                             AtomicReference<String> sawPeekHeader)
            throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/mcp", exchange -> {
            if (sawPeekHeader != null) {
                sawPeekHeader.set(exchange.getRequestHeaders()
                    .getFirst(SiblingPeek.PEEK_HEADER));
            }
            String envelope = "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"content\":[{"
                + "\"type\":\"text\",\"text\":"
                + com.fasterxml.jackson.databind.node.TextNode.valueOf(toolJson) + "}]}}";
            byte[] out = envelope.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
        servers.add(server);
        return new SiblingRegistry.Sibling(name, server.getAddress().getPort(), "tok");
    }

    /**
     * A port that ACCEPTS and never answers.
     *
     * <p>Not a dead port, deliberately. A connection to a loopback port with no listener is
     * refused in microseconds on Linux and can burn the client's whole timeout on Windows — so
     * a timing test built on "a dead port fails fast" passes here and proves nothing about the
     * machine where the timeout actually matters. Accepting into the backlog and going silent
     * manufactures the slow sibling on every platform.</p>
     */
    private SiblingRegistry.Sibling wedged(String name) throws Exception {
        ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        sockets.add(socket);
        return new SiblingRegistry.Sibling(name, socket.getLocalPort(), "tok");
    }

    /**
     * A row for a resident that has been STOPPED — the exit criterion's own case.
     *
     * <p>The registry is written when a resident spawns and nothing removes the row when it dies,
     * so this is what the ordinary machine looks like after a workspace is stopped: a listed
     * sibling with nothing listening behind it.</p>
     */
    private SiblingRegistry.Sibling stopped(String name) throws Exception {
        ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
        int port = socket.getLocalPort();
        socket.close();
        return new SiblingRegistry.Sibling(name, port, "tok");
    }

    private static String holds(String projectKey) {
        return "{\"success\":true,\"data\":{\"typeName\":\"com.foo.Bar\",\"projectKey\":\""
            + projectKey + "\",\"origin\":\"workspace-source\"}}";
    }

    private static final String DOES_NOT_HOLD = "{\"success\":false,\"error\":{\"code\":\"X\"}}";

    @Test
    @DisplayName("mcp#27: the first sibling that HOLDS the type answers, and says who it is")
    void theHolderAnswers() throws Exception {
        SiblingRegistry.Sibling empty = resident("empty-one", DOES_NOT_HOLD, null);
        SiblingRegistry.Sibling holder = resident("orb-strategy", holds("com-jats2-model"), null);

        SiblingPeek.Sweep sweep =
            SiblingPeek.sweep("com.foo.Bar", List.of(empty, holder), client);

        assertTrue(sweep.found().isPresent(), "the holder must answer: " + sweep);
        assertAll(
            () -> assertEquals("orb-strategy", sweep.found().orElseThrow().workspaceName()),
            () -> assertEquals("com-jats2-model", sweep.found().orElseThrow().projectKey()),
            // An answer found on the second of two is not the same fact as one found on the
            // first, and a caller told only "yes" cannot tell a cheap answer from a swept one.
            () -> assertEquals(2, sweep.listed()),
            () -> assertEquals(2, sweep.answered(), "both were asked and both replied: " + sweep),
            () -> assertFalse(sweep.budgetSpent()),
            () -> assertTrue(sweep.describe().contains("orb-strategy"), sweep.describe()),
            () -> assertTrue(sweep.describe().contains("com-jats2-model"), sweep.describe()));
    }

    @Test
    @DisplayName("mcp#27: every peek carries the loop guard")
    void thePeekMarksItself() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        SiblingRegistry.Sibling one = resident("orb-strategy", holds("p"), seen);

        SiblingPeek.sweep("com.foo.Bar", List.of(one), client);

        // Two residents that each peek on a miss, pointed at each other, never terminate. The
        // serving side honours this later; sending it now is what makes that possible.
        assertEquals("1", seen.get(),
            "the request must carry " + SiblingPeek.PEEK_HEADER + " or the loop guard has"
                + " nothing to key on");
    }

    @Test
    @DisplayName("mcp#27: nobody holding it is an empty answer, not a failure — and it says so")
    void nobodyHoldsIt() throws Exception {
        SiblingRegistry.Sibling a = resident("a", DOES_NOT_HOLD, null);
        SiblingRegistry.Sibling b = resident("b", DOES_NOT_HOLD, null);

        SiblingPeek.Sweep sweep = SiblingPeek.sweep("com.foo.Bar", List.of(a, b), client);

        assertAll(
            () -> assertTrue(sweep.found().isEmpty(),
                "a search must not fail because no sibling had the answer"),
            // TWO answered and neither had it. That is a different fact from two rows nobody
            // could reach, and the counts are the only thing that distinguishes them.
            () -> assertEquals(2, sweep.listed()),
            () -> assertEquals(2, sweep.answered(), "both replied: " + sweep),
            () -> assertTrue(sweep.describe().contains("2 of 2"), sweep.describe()));
    }

    @Test
    @DisplayName("THE EXIT CLAUSE — with the sibling STOPPED, the search says it consulted zero")
    void aStoppedSiblingIsListedAndNotConsulted() throws Exception {
        // C6's exit clause verbatim: "the same search with the second stopped says it consulted
        // zero siblings". The row survives the stop, because the registry is written on spawn
        // and nothing removes it — so the honest answer is one LISTED and zero ANSWERED, and a
        // single count would have to lie about one of the two.
        SiblingRegistry.Sibling gone = stopped("orb-strategy");

        SiblingPeek.Sweep sweep = SiblingPeek.sweep("com.foo.Bar", List.of(gone), client);

        assertAll(
            () -> assertTrue(sweep.found().isEmpty()),
            () -> assertEquals(0, sweep.answered(),
                "a resident that never heard us was not consulted: " + sweep),
            () -> assertEquals(1, sweep.listed(),
                "the stale row is still a row we had to consider: " + sweep),
            () -> assertTrue(sweep.describe().contains("consulted 0 of 1"), sweep.describe()));
    }

    @Test
    @DisplayName("mcp#27: a SLOW sibling is skipped and the ones after it are still asked")
    void aSlowSiblingDoesNotSwallowTheWalk() throws Exception {
        SiblingRegistry.Sibling silent = wedged("wedged");
        SiblingRegistry.Sibling holder = resident("orb-strategy", holds("p"), null);

        long start = System.nanoTime();
        SiblingPeek.Sweep sweep =
            SiblingPeek.sweep("com.foo.Bar", List.of(silent, holder), client);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertAll(
            () -> assertTrue(sweep.found().isPresent(),
                "the live sibling after the wedged one must still be reached: " + sweep),
            () -> assertEquals("orb-strategy", sweep.found().orElseThrow().workspaceName()),
            () -> assertEquals(1, sweep.answered(), "only one of the two spoke: " + sweep),
            () -> assertEquals(2, sweep.listed()),
            () -> assertTrue(elapsed.compareTo(SiblingPeek.TOTAL_BUDGET) < 0,
                "the walk took " + elapsed.toMillis() + "ms, at or over the whole budget"));
    }

    @Test
    @DisplayName("mcp#27: the TOTAL budget stops the walk, and the answer admits it")
    void theTotalBudgetCutsTheWalkShort() throws Exception {
        // FOUR wedged siblings at 750ms each is 3000ms of per-sibling timeout against a 2000ms
        // total — so this is the only shape that reaches the total budget at all. With two, the
        // per-sibling timeout alone keeps the walk inside it, which is why the test above cannot
        // exercise this and a mutation on the deadline stays green there.
        List<SiblingRegistry.Sibling> wedgedFour = List.of(
            wedged("one"), wedged("two"), wedged("three"), wedged("four"));

        long start = System.nanoTime();
        SiblingPeek.Sweep sweep = SiblingPeek.sweep("com.foo.Bar", wedgedFour, client);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

        assertAll(
            () -> assertTrue(sweep.budgetSpent(),
                "the walk must say it ran out of time rather than reporting a full sweep: "
                    + sweep),
            () -> assertEquals(4, sweep.listed()),
            () -> assertEquals(0, sweep.answered(), "none of them spoke: " + sweep),
            // Without the deadline the walk pays all four timeouts.
            () -> assertTrue(elapsed.compareTo(SiblingPeek.PER_SIBLING.multipliedBy(4)) < 0,
                "the walk took " + elapsed.toMillis() + "ms — it asked every sibling, so the"
                    + " total budget did nothing"),
            () -> assertTrue(sweep.describe().contains("time budget ran out"), sweep.describe()));
    }

    @Test
    @DisplayName("mcp#27: a garbled response is a NO, not an exception")
    void aGarbledResponseIsSurvivable() {
        assertAll(
            () -> assertTrue(SiblingPeek.projectKeyOf("{ not json").isEmpty()),
            () -> assertTrue(SiblingPeek.projectKeyOf("{\"result\":{}}").isEmpty()),
            // success=false with a data block is the shape that would fool a parser reading
            // `data` without checking whether the call succeeded.
            () -> assertTrue(SiblingPeek.projectKeyOf(
                "{\"result\":{\"content\":[{\"type\":\"text\",\"text\":"
                    + "\"{\\\"success\\\":false,\\\"data\\\":{\\\"projectKey\\\":\\\"x\\\"}}\""
                    + "}]}}").isEmpty(),
                "a FAILED tool call must not be read as an answer"));
    }

    @Test
    @DisplayName("THE CONTROL — no siblings at all asks nobody and is not an error")
    void noSiblingsIsNotAnError() {
        // Without this, an implementation that treated "nothing to ask" as an error state would
        // turn the ordinary single-resident machine — no studio, no registry — into a failing
        // miss path. It must also be DISTINGUISHABLE from a machine whose siblings are all
        // stopped, which is what the zero-listed count says.
        SiblingPeek.Sweep sweep = SiblingPeek.sweep("com.foo.Bar", List.of(), client);

        assertAll(
            () -> assertTrue(sweep.found().isEmpty()),
            () -> assertEquals(0, sweep.listed(), "nothing was listed: " + sweep),
            () -> assertEquals(0, sweep.answered()),
            () -> assertFalse(sweep.budgetSpent()),
            () -> assertTrue(sweep.describe().contains("0 of 0"), sweep.describe()));
    }
}
