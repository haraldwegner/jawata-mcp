package org.jawata.boot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, z67: the stdio MCP server a brain without HTTP servers is given. Measured against agy 1.5.15 on 2026-11-24 (the brain, not this
 * program): a line of 30 000 characters is read and one of 70 000 is not, and a tool whose definition is about 3 100 characters is not offered
 * to its model. This test pins what the bridge itself promises: the list is asked of the engine with the limit named, is served in pages the
 * brain can read, with the tools a principal needs first, an answer too long for a line is cut with a sentence that says so, and a request the
 * engine could not answer ends in an answer of its own.
 */
class McpStdioBridgeTest {

    private static final String BIG_TEXT = "x".repeat(200_000);

    private record Engine(HttpServer server, List<String> requests) {
        String address() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp";
        }
    }

    /** A stand-in resident: 39 tools of 4 000 characters and hire last, and a tool call that answers 200 000 characters. */
    private static Engine engine() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> requests = new CopyOnWriteArrayList<>();
        server.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(body);
            if (!body.contains("\"id\"")) {
                exchange.sendResponseHeaders(202, -1);
                exchange.close();
                return;
            }
            String answer;
            if (body.contains("tools/list")) {
                List<String> tools = new ArrayList<>();
                for (int i = 0; i < 39; i++) {
                    tools.add("{\"name\":\"tool" + i + "\",\"description\":\"" + "d".repeat(4_000) + "\",\"inputSchema\":{\"type\":\"object\"}}");
                }
                tools.add("{\"name\":\"hire\",\"description\":\"hires\",\"inputSchema\":{\"type\":\"object\"}}");
                answer = "{\"jsonrpc\":\"2.0\",\"id\":3,\"result\":{\"tools\":[" + String.join(",", tools) + "]}}";
            } else {
                answer = "{\"jsonrpc\":\"2.0\",\"id\":4,\"result\":{\"content\":[{\"type\":\"text\",\"text\":\"" + BIG_TEXT + "\"}],\"isError\":false}}";
            }
            byte[] bytes = answer.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        return new Engine(server, requests);
    }

    private static List<String> lines(Engine engine, String... requests) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int status = McpStdioBridge.run(new String[] {"--url", engine.address()}, new ByteArrayInputStream(String.join("\n", requests).getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        assertEquals(0, status);
        List<String> lines = new ArrayList<>();
        for (String line : out.toString(StandardCharsets.UTF_8).split("\n")) {
            if (!line.isBlank()) {
                lines.add(line);
            }
        }
        return lines;
    }

    @Test
    void theToolListIsAskedWithTheLimitAndServedInReadablePagesWithTheToolsAPrincipalNeedsFirst() throws Exception {
        Engine engine = engine();
        try {
            String first = "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/list\",\"params\":{}}";
            List<String> answers = lines(engine, first);
            assertTrue(engine.requests().get(0).contains("\"jawata/maxToolChars\":" + McpStdioBridge.TOOL_CHARS), "the engine is asked for short definitions: " + engine.requests().get(0));
            assertTrue(engine.requests().get(0).contains("\"jawata/maxToolsChars\":" + McpStdioBridge.TOOLS_CHARS), "and for all of them together: " + engine.requests().get(0));
            assertEquals(1, answers.size());
            String page = answers.get(0);
            assertTrue(page.length() <= McpStdioBridge.PAGE_CHARS + 4_200, "a page is a line the brain reads: " + page.length());
            assertTrue(page.contains("\"nextCursor\""), "more follow: the list is paged");
            assertTrue(page.indexOf("\"name\":\"hire\"") > 0 && page.indexOf("\"name\":\"hire\"") < page.indexOf("\"name\":\"tool0\""), "hire is served before the rest");

            // the pages, asked for one after another from the same session, give every tool
            List<String> requests = new ArrayList<>(List.of(first));
            for (int cursor = 1; cursor <= 12; cursor++) {
                requests.add("{\"jsonrpc\":\"2.0\",\"id\":" + (10 + cursor) + ",\"method\":\"tools/list\",\"params\":{\"cursor\":\"" + (cursor * 3) + "\"}}");
            }
            int named = 0;
            for (String each : lines(engine, requests.toArray(new String[0]))) {
                assertTrue(each.length() <= McpStdioBridge.PAGE_CHARS + 4_200, "every page is readable: " + each.length());
                named += each.split("\"name\":\"", -1).length - 1;
            }
            assertTrue(named >= 40, "the pages carry the tools: " + named);
        } finally {
            engine.server().stop(0);
        }
    }

    @Test
    void anAnswerTooLongForALineIsCutWithASentenceThatSaysSo() throws Exception {
        Engine engine = engine();
        try {
            List<String> answers = lines(engine, "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\",\"params\":{\"name\":\"big\",\"arguments\":{}}}");
            assertEquals(1, answers.size());
            assertTrue(answers.get(0).length() <= McpStdioBridge.LINE_CHARS, "the line is one the brain reads: " + answers.get(0).length());
            assertTrue(answers.get(0).contains("[cut here: the answer was"), "the cut says so");
            assertTrue(answers.get(0).startsWith("{\"jsonrpc\":\"2.0\",\"id\":4,\"result\""), "and is still an answer to that call");
        } finally {
            engine.server().stop(0);
        }
    }

    @Test
    void aNotificationGetsNoAnswerAndARequestTheEngineCannotReachGetsAnErrorOfItsOwn() throws Exception {
        Engine engine = engine();
        try {
            assertEquals(List.of(), lines(engine, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\",\"params\":{}}"), "a notification is not answered");
        } finally {
            engine.server().stop(0);
        }
        int port;
        try (ServerSocket taken = new ServerSocket(0)) {
            port = taken.getLocalPort();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        McpStdioBridge.run(new String[] {"--url", "http://127.0.0.1:" + port + "/mcp"},
            new ByteArrayInputStream("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"tools/list\",\"params\":{}}\n".getBytes(StandardCharsets.UTF_8)),
            new PrintStream(out, true, StandardCharsets.UTF_8), new PrintStream(new ByteArrayOutputStream()));
        String answered = out.toString(StandardCharsets.UTF_8);
        assertTrue(answered.contains("\"id\":9") && answered.contains("\"error\"") && answered.contains("could not be reached"), "the brain's call ends: " + answered);
    }
}
