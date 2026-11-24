package org.jawata.boot;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Sprint 28h, z67: the MCP server a brain is given over stdio when it cannot attach an HTTP one ({@code java -jar jawata.jar --mcp}). Antigravity's
 * agent says {@code mcpCapabilities.http: false} when a session opens, so the engine's own endpoint cannot be handed to it, and it used the servers of
 * its own configuration, which left the engine's hires, their cards and their refusals unseen by the chat the person was typing in. Every line read from
 * stdin is one JSON-RPC message; it is POSTed to the resident's {@code /mcp} URL (which names the session, so the engine knows whose calls these
 * are) and the answer is written as one line. A notification, which the resident answers with 202, has no answer. Like the other bridges it holds no
 * engine logic and starts no framework.
 *
 * <p><b>The line limit.</b> Measured against agy 1.5.15 (2026-11-24): a stdio server's line of 30 000 characters is read and one of 70 000 is not, and
 * the engine's {@code tools/list} is 270 000, so agy enabled none of the engine's tools ("tool experience is not enabled for server jawata"). The list
 * is therefore sent in pages of a few tools each ({@code nextCursor}, which the protocol provides), and an answer to a call that would still be over the
 * limit is cut with a sentence saying so, because a line the brain cannot read is a call that never ends.</p>
 *
 * <p>The URL is {@code JAWATA_MCP_URL} (or {@code --url}) and the key {@code JAWATA_KEY} (or {@code --key}). A refused key ends the program with
 * status 3 and a line on stderr.</p>
 */
final class McpStdioBridge {

    static final int USAGE = 2;
    static final int REFUSED = 3;

    /** The most characters of tool definitions one page of the list carries. */
    static final int PAGE_CHARS = 30_000;
    /**
     * The most characters of one tool definition asked of the engine. A definition is first made as small as it can be without leaving a parameter
     * out, and what is left of {@link #TOOLS_CHARS} is spent on the tools a chat's principal works with first, up to this many characters each.
     */
    static final int TOOL_CHARS = 3_500;
    /**
     * The most characters of all the definitions together. Measured against agy 1.5.15 (28h z67): it offers its model the smallest definitions first
     * until their sum reaches about 62 000 characters (36 of the 51 at 2 000 each, 29 of 51 uncut), and answers "not enabled" for the rest, so
     * 55 000 keeps all of them under it.
     */
    static final int TOOLS_CHARS = 55_000;
    /** The longest line sent to the brain, below the 64 KB its reader takes. */
    static final int LINE_CHARS = 60_000;
    /** How much of a cut answer's text is kept. */
    private static final int KEPT_CHARS = 45_000;

    private McpStdioBridge() {
    }

    static int run(String[] args, InputStream in, PrintStream out, PrintStream err) throws IOException {
        String url = System.getenv("JAWATA_MCP_URL");
        String key = System.getenv("JAWATA_KEY");
        for (int i = 0; i + 1 < args.length; i++) {
            if ("--url".equals(args[i])) {
                url = args[i + 1];
            } else if ("--key".equals(args[i])) {
                key = args[i + 1];
            }
        }
        if (url == null || url.isBlank()) {
            err.println("jawata --mcp: no resident named; pass --url <the /mcp url> or set JAWATA_MCP_URL");
            return USAGE;
        }
        HttpClient http = HttpClient.newHttpClient();
        BufferedReader lines = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
        List<String> tools = List.of();
        for (String line = lines.readLine(); line != null; line = lines.readLine()) {
            if (line.isBlank()) {
                continue;
            }
            Map<String, String> message = Bridge.topLevelMembers(line);
            String id = message.get("id");
            trace("<- " + message.get("method") + " id=" + id + " " + line.length() + " chars" + (message.get("params") == null ? "" : " params=" + message.get("params").substring(0, Math.min(80, message.get("params").length()))));
            boolean listing = "\"tools/list\"".equals(message.get("method"));
            String cursor = listing ? cursorOf(message.get("params")) : null;
            if (listing && cursor != null && !tools.isEmpty()) {
                String next = page(id, tools, cursor);
                trace("-> page from " + cursor + " " + next.length() + " chars");
                out.println(next);
                out.flush();
                continue;
            }
            HttpResponse<String> answer;
            try {
                answer = post(http, url, key, listing
                    ? "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"tools/list\",\"params\":{\"_meta\":{\"jawata/maxToolChars\":" + TOOL_CHARS + ",\"jawata/maxToolsChars\":" + TOOLS_CHARS
                        + onlyTools() + "}}}"
                    : line);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return 0;
            } catch (IOException unreachable) {
                err.println("jawata --mcp: the resident could not be reached: " + unreachable.getMessage());
                answerWithError(out, id, "the resident could not be reached: " + unreachable.getMessage());
                continue;
            }
            int status = answer.statusCode();
            if (status == 401) {
                err.println("jawata --mcp: the resident refused the key");
                return REFUSED;
            }
            if (status == 202 || status == 204) {
                continue;
            }
            if (status != 200) {
                answerWithError(out, id, "the resident answered " + status);
                continue;
            }
            for (String body : bodies(answer)) {
                if (listing) {
                    List<String> listed = toolsIn(body);
                    if (!listed.isEmpty()) {
                        tools = putFirst(listed);
                        String first = page(id, tools, "0");
                        trace("-> first page " + first.length() + " chars of " + tools.size() + " tools");
                        out.println(first);
                        out.flush();
                        continue;
                    }
                }
                String fitted = fit(body, id);
                trace("-> answer " + fitted.length() + " chars: " + fitted.substring(0, Math.min(160, fitted.length())));
                out.println(fitted);
                out.flush();
            }
        }
        return 0;
    }

    /**
     * The member of a tool-list request that asks for the tools named in {@code JAWATA_MCP_TOOLS} alone, comma-separated by the engine that started
     * this program from the tools its brain's seat may call; empty when none are named. A name that is not a plain tool name is left out, so what is
     * written needs no escaping.
     */
    static String onlyTools() {
        String named = System.getenv("JAWATA_MCP_TOOLS");
        if (named == null || named.isBlank()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String name : named.split(",")) {
            if (name.matches("[a-z][a-z0-9_]*")) {
                names.add("\"" + name + "\"");
            }
        }
        return names.isEmpty() ? "" : ",\"jawata/onlyTools\":[" + String.join(",", names) + "]";
    }

    private static HttpResponse<String> post(HttpClient http, String url, String key, String line) throws IOException, InterruptedException {
        // longer than the longest a tool runs (run_tests and coverage_mutation: up to 600 seconds), so a resident that never answers ends the call and
        // does not hold the program for ever
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(url)).timeout(java.time.Duration.ofSeconds(720))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json, text/event-stream")
            .POST(HttpRequest.BodyPublishers.ofString(line));
        if (key != null && !key.isBlank()) {
            request.header("Authorization", "Bearer " + key);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    /** The messages in an answer: one, or one per event of an event stream. */
    private static List<String> bodies(HttpResponse<String> answer) {
        List<String> bodies = new ArrayList<>();
        if (answer.headers().firstValue("Content-Type").orElse("").startsWith("text/event-stream")) {
            for (String event : answer.body().split("\n")) {
                if (event.startsWith("data:") && !event.substring(5).isBlank()) {
                    bodies.add(event.substring(5).strip());
                }
            }
        } else if (!answer.body().isBlank()) {
            bodies.add(answer.body().strip().replace("\n", " "));
        }
        return bodies;
    }

    /** The cursor a list request names, or null for the first page. */
    private static String cursorOf(String params) {
        if (params == null) {
            return null;
        }
        String cursor = Bridge.topLevelMembers(params).get("cursor");
        return cursor == null || "null".equals(cursor) ? null : cursor.replace("\"", "");
    }

    /** The tool definitions of a {@code tools/list} answer, each as its own text; none when the answer carries none. */
    static List<String> toolsIn(String body) {
        String result = Bridge.topLevelMembers(body).get("result");
        String tools = result == null ? null : Bridge.topLevelMembers(result).get("tools");
        return tools == null ? List.of() : elementsOf(tools);
    }

    /** The tools a principal reaches the engine's hires and memory by, which the brain must be given on the first page of the list. */
    private static final List<String> PRINCIPAL_TOOLS = List.of("hire", "hire_wait", "experience", "files", "lookup", "git", "plan_steps", "plan_board", "brains");

    /** The tool definitions with those of {@link #PRINCIPAL_TOOLS} first, in that order, and the rest after them in the order given. */
    static List<String> putFirst(List<String> tools) {
        List<String> first = new ArrayList<>();
        for (String wanted : PRINCIPAL_TOOLS) {
            for (String tool : tools) {
                if (("\"" + wanted + "\"").equals(Bridge.topLevelMembers(tool).get("name"))) {
                    first.add(tool);
                }
            }
        }
        List<String> ordered = new ArrayList<>(first);
        for (String tool : tools) {
            if (!first.contains(tool)) {
                ordered.add(tool);
            }
        }
        return ordered;
    }

    /** The elements of a JSON array, each as its own text. */
    static List<String> elementsOf(String array) {
        List<String> elements = new ArrayList<>();
        int at = Bridge.skipSpace(array, 0);
        if (at >= array.length() || array.charAt(at) != '[') {
            return elements;
        }
        at++;
        while (true) {
            at = Bridge.skipSpace(array, at);
            if (at >= array.length() || array.charAt(at) == ']') {
                return elements;
            }
            if (array.charAt(at) == ',') {
                at++;
                continue;
            }
            int end = Bridge.endOfValue(array, at);
            if (end < 0) {
                return elements;
            }
            elements.add(array.substring(at, end));
            at = end;
        }
    }

    /** One page of the tool list from the position {@code cursor} names, with {@code nextCursor} when more follow. */
    static String page(String id, List<String> tools, String cursor) {
        int from;
        try {
            from = Math.max(0, Math.min(Integer.parseInt(cursor), tools.size()));
        } catch (NumberFormatException notANumber) {
            from = 0;
        }
        StringBuilder page = new StringBuilder("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"tools\":[");
        int at = from;
        int chars = 0;
        while (at < tools.size() && (at == from || chars + tools.get(at).length() <= PAGE_CHARS)) {
            page.append(at == from ? "" : ",").append(tools.get(at));
            chars += tools.get(at).length();
            at++;
        }
        page.append(']');
        if (at < tools.size()) {
            page.append(",\"nextCursor\":\"").append(at).append('"');
        }
        return page.append("}}").toString();
    }

    /** The answer as one line the brain can read: itself when short enough, else its first content text cut with a sentence that says so. */
    static String fit(String body, String id) {
        if (body.getBytes(StandardCharsets.UTF_8).length <= LINE_CHARS) {
            return body;
        }
        String result = Bridge.topLevelMembers(body).get("result");
        String content = result == null ? null : Bridge.topLevelMembers(result).get("content");
        List<String> parts = content == null ? List.of() : elementsOf(content);
        String text = parts.isEmpty() ? null : Bridge.topLevelMembers(parts.get(0)).get("text");
        if (text == null || !text.startsWith("\"")) {
            return errorLine(id, "the answer was " + body.length() + " characters and the brain reads at most 64 KB a line");
        }
        int keep = Math.min(text.length() - 1, KEPT_CHARS);
        String isError = Bridge.topLevelMembers(result).getOrDefault("isError", "false");
        while (true) {
            // never end inside an escape: an odd run of backslashes, or a \\u with fewer than four digits after it
            String kept = text.substring(0, keep);
            int slashes = 0;
            while (slashes < kept.length() && kept.charAt(kept.length() - 1 - slashes) == '\\') {
                slashes++;
            }
            if (slashes % 2 == 1) {
                kept = kept.substring(0, kept.length() - 1);
            }
            int u = kept.lastIndexOf("\\u");
            if (u >= 0 && kept.length() - u < 6) {
                kept = kept.substring(0, u);
            }
            String line = "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"result\":{\"content\":[{\"type\":\"text\",\"text\":" + kept
                + "\\n\\n[cut here: the answer was " + body.length() + " characters and the brain reads at most 64 KB a line]\"}],\"isError\":" + isError + "}}";
            // the brain's reader counts bytes, and a character outside ASCII is up to three of them
            if (line.getBytes(StandardCharsets.UTF_8).length <= LINE_CHARS || keep <= 1_000) {
                return line;
            }
            keep = keep * 9 / 10;
        }
    }

    /** One line to the file named by {@code JAWATA_MCP_TRACE}, when it is set: what the brain asked and how long each answer sent back was. */
    private static void trace(String line) {
        String file = System.getenv("JAWATA_MCP_TRACE");
        if (file == null || file.isBlank()) {
            return;
        }
        try {
            java.nio.file.Files.writeString(java.nio.file.Path.of(file), line + "\n", java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException unwritable) {
            // a trace is a diagnostic aid: a file that cannot be written is not a reason to stop carrying the brain's messages
        }
    }

    private static String errorLine(String id, String reason) {
        return "{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"error\":{\"code\":-32000,\"message\":\"" + reason.replace("\\", "\\\\").replace("\"", "'").replaceAll("\\p{Cntrl}", " ") + "\"}}";
    }

    /** A request that got no answer from the resident is answered here, so the brain's call ends and does not wait for ever; a notification is not. */
    private static void answerWithError(PrintStream out, String id, String reason) {
        if (id != null) {
            out.println(errorLine(id, reason));
            out.flush();
        }
    }
}
