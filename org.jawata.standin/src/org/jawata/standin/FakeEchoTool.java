package org.jawata.standin;

import java.util.LinkedHashMap;
import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.Tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The one tool the stand-in language adds (Sprint 28k, Stage 8): it answers with what it was given and names its language, so a
 * client that lists the tools sees a language beside Java and can call what it brought.
 */
public final class FakeEchoTool implements Tool {

    /** The tool's published name. */
    public static final String NAME = "fake_echo";

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public String getDescription() {
        return "Answers with the text it was given and the language it belongs to. The stand-in language's only tool: it exists to show "
            + "that a second language can plug into the engine and bring a tool of its own.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> text = new LinkedHashMap<>();
        text.put("type", "string");
        text.put("description", "What to echo back.");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("text", text);
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", properties);
        return schema;
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        Map<String, Object> answer = new LinkedHashMap<>();
        answer.put("echo", arguments == null ? "" : arguments.path("text").asText(""));
        answer.put("language", FakeLanguageProvider.LANGUAGE);
        return ToolResponse.success(answer);
    }
}
