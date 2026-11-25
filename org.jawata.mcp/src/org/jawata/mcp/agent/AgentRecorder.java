package org.jawata.mcp.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the agent keeps of its conversations (Sprint 28h, S9, D31, D4): each turn is written to the engine's transcript lane through
 * the {@code experience} tool, which is all the face contract allows, and a host that lists or reloads a session is answered from
 * the same lane. The agent holds no second copy: the lane is the record, so a session survives the connection that opened it.
 *
 * <p>How much is recorded is one setting, {@value #SETTING}: {@code all} (the default) records the principal and every hired
 * agent, {@code principal} only the principal's own conversation, {@code off} nothing. A lane that is switched off or not installed
 * records nothing and says so in its own answer; the agent does not retry or hide that.</p>
 */
public final class AgentRecorder {

    /** The host setting that says how much is recorded. */
    public static final String SETTING = "jawata.agent.recording";

    static final String CLIENT = "jawata-agent";

    private static final Logger log = LoggerFactory.getLogger(AgentRecorder.class);
    private static final ObjectMapper JSON = new ObjectMapper();

    /** How much is recorded. */
    public enum Depth {
        /** The principal and every hired agent. */
        ALL,
        /** Only the principal's conversation with the person. */
        PRINCIPAL,
        /** Nothing. */
        OFF;

        /** The depth a setting names; a missing or unreadable setting is the default, {@link #ALL}. */
        static Depth of(String setting) {
            if (setting == null || setting.isBlank()) {
                return ALL;
            }
            try {
                return valueOf(setting.strip().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                return ALL;
            }
        }
    }

    /** Whose conversation a turn belongs to. */
    public enum Level {
        /** The principal, talking to the person. */
        PRINCIPAL,
        /** A hired agent, talking to whoever hired it. */
        HIRED
    }

    /**
     * One session as a host lists it.
     *
     * @param sessionId the session
     * @param title     the first words typed in it
     * @param updatedAt when it last changed, ISO-8601
     * @param client    which client wrote it: {@code jawata-agent} for the chat's own, {@code claude-code} or {@code cursor} for the hook's
     */
    public record SessionInfo(String sessionId, String title, String updatedAt, String client) {
    }

    /**
     * One page of sessions, newest first.
     *
     * @param sessions   the page
     * @param nextCursor what to send to get the page after it; null when this is the last
     */
    public record Page(List<SessionInfo> sessions, String nextCursor) {
    }

    /**
     * One recorded turn, in full.
     *
     * @param his   what the person typed
     * @param agent what the agent said
     * @param tools one line per tool call
     */
    public record Turn(List<String> his, List<String> agent, List<String> tools) {
    }

    /** How long a turn waits for the lane to take it before the prompt is answered without it, in milliseconds. */
    static final long WAITS_MILLIS = 5_000;

    private final ToolCalls tools;
    private final Depth depth;
    private final long waitMillis;
    /** The one thread that writes turns to the lane, so they land in the order they were recorded whether or not a turn waited for its write. */
    private final java.util.concurrent.ThreadPoolExecutor writer = new java.util.concurrent.ThreadPoolExecutor(1, 1, 30, java.util.concurrent.TimeUnit.SECONDS,
        new java.util.concurrent.LinkedBlockingQueue<>(), task -> {
            Thread thread = new Thread(task, "jawata-agent-recorder");
            thread.setDaemon(true);
            return thread;
        });

    public AgentRecorder(ToolCalls tools, Depth depth) {
        this(tools, depth, WAITS_MILLIS);
    }

    AgentRecorder(ToolCalls tools, Depth depth, long waitMillis) {
        this.tools = tools;
        this.depth = depth;
        this.waitMillis = waitMillis;
        this.writer.allowCoreThreadTimeOut(true);
    }

    /** A recorder that records nothing and lists nothing, for a connection built without an engine behind it. */
    public static AgentRecorder off() {
        return new AgentRecorder(null, Depth.OFF);
    }

    /** Whether a turn of this level is written down at the depth set. */
    public boolean records(Level level) {
        return switch (depth) {
            case ALL -> true;
            case PRINCIPAL -> level == Level.PRINCIPAL;
            case OFF -> false;
        };
    }

    /**
     * Writes one turn. A turn the lane refuses or cannot take is logged and the conversation goes on: recording is never a reason
     * for the person's prompt to fail, and it is not a reason for it to wait either (28h S19-1): the write is made by the recorder's own
     * thread and this call waits for it only up to a bound, then returns and leaves the write to land when the lane takes it. A lane that is
     * ready has taken the turn when this returns, so a history read at once sees it.
     */
    public void record(Level level, PrincipalSession session, String turnKey, String his, String agent, List<String> toolLines) {
        record(level, session, turnKey, his, agent, toolLines, why -> { });
    }

    /**
     * Writes one turn as {@link #record(Level, PrincipalSession, String, String, String, List)} does, and tells {@code notStored} why when the
     * lane did not keep it (28h S19-13): the store answers success even for a write that failed, so nobody noticed that none of the chat's
     * conversations had been kept for days.
     */
    public void record(Level level, PrincipalSession session, String turnKey, String his, String agent, List<String> toolLines,
        java.util.function.Consumer<String> notStored) {
        if (!records(level) || tools == null) {
            return;
        }
        ObjectNode args = JSON.createObjectNode();
        args.put("kind", "transcript_append");
        args.put("session", session.id());
        args.put("key", turnKey);
        args.put("client", CLIENT);
        args.put("capture", "turn");
        args.put("his", his);
        args.put("agent", agent);
        toolLines.forEach(args.putArray("tools")::add);
        CallerIdentity identity = session.identity();
        java.util.concurrent.Future<?> written = writer.submit(() -> write(session.id(), args, identity, notStored));
        try {
            written.get(waitMillis, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException slow) {
            log.warn("the transcript lane has not taken a turn of session {} within {} ms: the prompt is answered without waiting for it", session.id(), waitMillis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (java.util.concurrent.ExecutionException failed) {
            log.warn("a turn of session {} could not be recorded", session.id(), failed.getCause());
        }
    }

    /** From how long a write to the lane counts as slow, in milliseconds. */
    static final long SLOW_MILLIS = 1_000;

    private final java.util.concurrent.atomic.AtomicInteger slowWrites = new java.util.concurrent.atomic.AtomicInteger();

    /** How many writes to the lane took {@value #SLOW_MILLIS} ms or more: the shared lane is also used by other residents (28h S19-1). */
    int slowWrites() {
        return slowWrites.get();
    }

    /** The write itself, on the recorder's thread; one that takes long is counted and logged with its time, so a stalled lane leaves a trace. */
    private void write(String sessionId, ObjectNode args, CallerIdentity identity, java.util.function.Consumer<String> notStored) {
        long started = System.nanoTime();
        try {
            ToolResponse response = tools.callTool("experience", args, identity);
            String why = whyNotStored(response);
            if (why != null) {
                log.warn("the transcript lane did not take a turn of session {}: {}", sessionId, why);
                notStored.accept(why);
            }
        } catch (Exception e) {
            log.warn("a turn of session {} could not be recorded", sessionId, e);
            notStored.accept(e.toString());
        } finally {
            long took = java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            if (took >= SLOW_MILLIS) {
                slowWrites.incrementAndGet();
                log.warn("the transcript lane took {} ms to take a turn of session {}", took, sessionId);
            }
        }
    }

    /** Why the lane's answer to a write means the turn was not kept; null when it was. A failed or switched off write is answered as success. */
    private static String whyNotStored(ToolResponse response) {
        if (response == null) {
            return "the store did not answer";
        }
        if (!response.isSuccess()) {
            return response.getError().getMessage();
        }
        JsonNode data = JSON.valueToTree(response.getData());
        return switch (data.path("appended").asText("")) {
            case "failed" -> "the store could not write it: " + data.path("reason").asText("no reason given");
            case "disabled" -> "keeping conversations is switched off in the engine's settings";
            default -> null;
        };
    }

    /**
     * One page of the person's conversations, from every client, most recently active first; empty when none is held or the lane cannot be
     * read (28h D57 (a), widened by his ruling 2026-11-18). The lane holds every session any client wrote, among them the hook's captures of
     * a brain the engine started; those are left out ({@link #isPersons}). The sessions older than {@code cursor} (the last-activity time a
     * page ended on) make the next page; null starts at the newest. Machine-made sessions may be newer, so the read widens, doubling, until
     * a page and one more are found, the lane has no more rows, or {@value #MOST_ROWS_READ} rows have been read.
     */
    public Page sessions(int size, String cursor) {
        int asked = Math.max(size + 1, Math.min(size * OTHERS_PER_LISTED, MOST_ROWS_READ));
        while (true) {
            JsonNode rows = ask(recall().put("sessions", asked)).path("sessions");
            List<SessionInfo> found = new ArrayList<>();
            for (JsonNode row : rows) {
                if (found.size() > size) {
                    break;
                }
                String title = row.path("title").asText("");
                String updatedAt = row.path("updatedAt").asText("");
                if (!isPersons(row.path("client").asText(""), title) || (cursor != null && !cursor.isBlank() && updatedAt.compareTo(cursor) >= 0)) {
                    continue;
                }
                found.add(new SessionInfo(row.path("sessionId").asText(), title, updatedAt, row.path("client").asText("")));
            }
            boolean more = found.size() > size;
            if (more || rows.size() < asked || asked >= MOST_ROWS_READ) {
                List<SessionInfo> page = List.copyOf(more ? found.subList(0, size) : found);
                return new Page(page, more && !page.isEmpty() ? page.get(page.size() - 1).updatedAt() : null);
            }
            asked = Math.min(asked * 2, MOST_ROWS_READ);
        }
    }

    /** The name a person knows a client by, for a row of the session list; a client this engine has no name for is named as it wrote itself. */
    static String nameOf(String client) {
        return switch (client) {
            case CLIENT -> "jawata";
            case "claude-code" -> "Claude Code";
            case "cursor" -> "Cursor";
            default -> client;
        };
    }

    /**
     * Whether a session of the lane is a conversation of the person's. The engine's own mark is the client name: a brain it started has the
     * studio's hook write its session under the client's name with {@code -machine} after it. Sessions written before the mark existed are
     * told by their first words, which are the principal's or a seat's own prompt.
     */
    static boolean isPersons(String client, String title) {
        if (client == null || client.isBlank() || client.endsWith("-machine")) {
            return false;
        }
        return !(title.startsWith("You are jawata") || title.matches("(?s)^You are the [A-Za-z][A-Za-z -]{0,40} seat\\b.*"));
    }

    /** How many rows of other clients are read for each conversation listed, and the most rows one listing reads. */
    private static final int OTHERS_PER_LISTED = 10;
    private static final int MOST_ROWS_READ = 2000;

    /** Every turn of a session in order, in full; empty when the lane holds none. */
    public List<Turn> turns(String sessionId) {
        JsonNode data = ask(recall().put("session", sessionId).put("full", true));
        List<Turn> found = new ArrayList<>();
        for (JsonNode row : data.path("turns")) {
            found.add(new Turn(strings(row.path("his")), strings(row.path("agent")), strings(row.path("tools"))));
        }
        return found;
    }

    private static ObjectNode recall() {
        ObjectNode args = JSON.createObjectNode();
        args.put("kind", "recall");
        args.putArray("lanes").add("transcript");
        return args;
    }

    private JsonNode ask(ObjectNode args) {
        if (tools == null) {
            return JSON.createObjectNode();
        }
        try {
            ToolResponse response = tools.callTool("experience", args, CallerIdentity.supplied(CLIENT));
            return response == null || !response.isSuccess() ? JSON.createObjectNode() : JSON.valueToTree(response.getData());
        } catch (Exception e) {
            log.warn("the transcript lane could not be read", e);
            return JSON.createObjectNode();
        }
    }

    private static List<String> strings(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(item -> out.add(item.asText()));
        return out;
    }
}
