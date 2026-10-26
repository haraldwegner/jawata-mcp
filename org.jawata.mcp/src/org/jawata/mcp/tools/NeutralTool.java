package org.jawata.mcp.tools;

import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The base of every tool, whatever language it answers for (Sprint 28k, Stage 9): what reading a call's arguments needs, and nothing
 * about a language's model. The tools that name no language extend this directly; {@link AbstractTool} extends it for the tools that
 * work over Java's service.
 */
public abstract class NeutralTool implements Tool {

	/**
	 * Get a required string parameter.
	 *
	 * @param arguments The arguments node
	 * @param name The parameter name
	 * @return The parameter value, or null if missing
	 */
	protected String getStringParam(JsonNode arguments, String name) {
	    if (arguments == null || !arguments.has(name)) {
	        return null;
	    }
	    return arguments.get(name).asText();
	}

	/**
	 * Get an optional string parameter with default.
	 */
	protected String getStringParam(JsonNode arguments, String name, String defaultValue) {
	    String value = getStringParam(arguments, name);
	    return value != null ? value : defaultValue;
	}

	/**
	 * Get an optional int parameter with default.
	 */
	protected int getIntParam(JsonNode arguments, String name, int defaultValue) {
	    if (arguments == null || !arguments.has(name)) {
	        return defaultValue;
	    }
	    return arguments.get(name).asInt(defaultValue);
	}

	/**
	 * Get an optional boolean parameter with default.
	 */
	protected boolean getBooleanParam(JsonNode arguments, String name, boolean defaultValue) {
	    if (arguments == null || !arguments.has(name)) {
	        return defaultValue;
	    }
	    return arguments.get(name).asBoolean(defaultValue);
	}

	/**
	 * Check if a required parameter is present.
	 */
	protected ToolResponse requireParam(JsonNode arguments, String name) {
	    if (arguments == null || !arguments.has(name)) {
	        return ToolResponse.invalidParameter(name, "Required parameter missing");
	    }
	    return null; // No error
	}
}
