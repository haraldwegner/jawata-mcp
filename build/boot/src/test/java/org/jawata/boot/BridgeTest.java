package org.jawata.boot;

import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, D57 (e): the chat starts the agent's bridge as its agent program, so when the engine does not answer, what the bridge answers
 * is what the person is told. Measured 2026-11-17 before this test: the bridge printed a Java stack trace and ended, and the chat's request was
 * never answered, so the chat could not say why it was not connected.
 */
class BridgeTest {

    @Test
    void anEngineThatDoesNotAnswerIsSaidInTheAnswerToTheFirstRequest() throws Exception {
        int port;
        try (ServerSocket taken = new ServerSocket(0)) {
            port = taken.getLocalPort(); // closed again at once: nothing listens there
        }
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        int status = Bridge.runAcp(new String[] {"--url", "http://127.0.0.1:" + port}, new ByteArrayInputStream(initialize.getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(err, true, StandardCharsets.UTF_8));

        String answered = out.toString(StandardCharsets.UTF_8);
        assertNotEquals(0, status, "the bridge ends refusing");
        assertTrue(answered.contains("\"id\":1") && answered.contains("\"error\""), "the request is answered with an error: " + answered);
        assertTrue(answered.contains("does not answer") && answered.contains("127.0.0.1:" + port) && answered.contains("start the jawata studio"),
            "the answer says why, where, and what to do, in one sentence: " + answered);
        assertTrue(!err.toString(StandardCharsets.UTF_8).contains("\tat "), "no stack trace: " + err);
    }

    /**
     * The architect's watch of 28h C26: the engine can finish starting between the bridge's two connects, the event stream (refused) and the
     * first message (accepted). The bridge must then still reach the engine's answers, never run with a message line and no answer line, where
     * the chat would wait forever with nothing said.
     */
    @Test
    void anEngineThatComesUpWhileTheBridgeStartsIsReachedOnBothLines() throws Exception {
        int port;
        try (ServerSocket taken = new ServerSocket(0)) {
            port = taken.getLocalPort(); // nothing listens yet: the event stream's first connect is refused
        }
        String initialize = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":1}}\n";
        java.io.PipedOutputStream host = new java.io.PipedOutputStream();
        java.io.PipedInputStream toBridge = new java.io.PipedInputStream(host);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        PrintStream answers = new PrintStream(out, true, StandardCharsets.UTF_8);
        Thread bridge = new Thread(() -> {
            try {
                Bridge.runAcp(new String[] {"--url", "http://127.0.0.1:" + port}, toBridge, answers, new PrintStream(new ByteArrayOutputStream()));
            } catch (java.io.IOException ignored) {
                // the test reads what reached the host
            }
        });
        bridge.setDaemon(true);
        bridge.start();
        Thread.sleep(300); // the event stream has tried and been refused

        com.sun.net.httpserver.HttpServer engine = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", port), 0);
        engine.createContext("/acp/events", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            exchange.getResponseBody().write("data: {\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"protocolVersion\":1}}\n\n".getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
        });
        engine.createContext("/acp", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(202, -1);
            exchange.close();
        });
        engine.start();
        try {
            host.write(initialize.getBytes(StandardCharsets.UTF_8));
            host.flush();
            long until = System.currentTimeMillis() + 10_000;
            while (!out.toString(StandardCharsets.UTF_8).contains("\"result\"") && System.currentTimeMillis() < until) {
                Thread.sleep(50);
            }
            assertTrue(out.toString(StandardCharsets.UTF_8).contains("\"result\""),
                "the engine's answer reached the host over the event stream: " + out.toString(StandardCharsets.UTF_8));
        } finally {
            host.close();
            engine.stop(0);
        }
    }
}
