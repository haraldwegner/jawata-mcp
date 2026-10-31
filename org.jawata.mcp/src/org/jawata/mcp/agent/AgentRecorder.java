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
     */
    public record SessionInfo(String sessionId, String title, String updatedAt) {
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

    private final ToolCalls tools;
    private final Depth depth;

    public AgentRecorder(ToolCalls tools, Depth depth) {
        this.tools = tools;
        this.depth = depth;
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
     * for the person's prompt to fail.
     */
    public void record(Level level, PrincipalSession session, String turnKey, String his, String agent, List<String> toolLines) {
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
        try {
            ToolResponse response = tools.callTool("experience", args, session.identity());
            if (response == null || !response.isSuccess()) {
                log.warn("the transcript lane did not take a turn of session {}: {}", session.id(), response == null ? "no answer" : response.getError());
            }
        } catch (Exception e) {
            log.warn("a turn of session {} could not be recorded", session.id(), e);
        }
    }

    /** The sessions the lane holds, most recently active first; empty when none is held or the lane cannot be read. */
    public List<SessionInfo> sessions(int limit) {
        JsonNode data = ask(recall().put("sessions", limit));
        List<SessionInfo> found = new ArrayList<>();
        for (JsonNode row : data.path("sessions")) {
            found.add(new SessionInfo(row.path("sessionId").asText(), row.path("title").asText(""), row.path("updatedAt").asText("")));
        }
        return found;
    }

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
