package org.jawata.mcp.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Sprint 28h, z67: a tool definition cut to a character limit a client names, for the brains that do not offer a tool to their model when its
 * definition is long. Measured against Antigravity (agy 1.5.15, 2026-11-24): a tool whose definition is 3 012 characters compact is offered and one of
 * 3 153 is not, and a call to one that is not offered ends with "tool X is not enabled for server Y" before it reaches the server. A definition that
 * already fits is left exactly as it is, and one that does not loses, in this order, the parts a model can do without: the tail of the tool's
 * description, the tail of each parameter's description (longest first), those descriptions altogether, most of the tool's description, and last
 * the declared type of the parameters that carry nothing else. The names of the parameters, and the values a parameter may take, are never cut,
 * because they are what a call is made of.
 */
public final class ToolDefinitionFit {

    /** The member of a {@code tools/list} request's {@code _meta} that names the limit. */
    public static final String META_KEY = "jawata/maxToolChars";

    /** The member of a {@code tools/list} request's {@code _meta} that names the most characters all the definitions may take together. */
    public static final String META_TOTAL_KEY = "jawata/maxToolsChars";

    private static final int MIN_EACH = 600;
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int TOOL_DESCRIPTION = 700;
    private static final int SHORT_TOOL_DESCRIPTION = 250;
    private static final int PARAMETER_DESCRIPTION = 60;

    private ToolDefinitionFit() {
    }

    /**
     * The limit a {@code tools/list} request asks for, or 0 when it asks for none.
     *
     * @param params the request's parameters
     * @param key    the member of their {@code _meta} that names the limit: {@link #META_KEY} for one definition, {@link #META_TOTAL_KEY} for all
     */
    public static int limitOf(JsonNode params, String key) {
        JsonNode limit = params == null ? null : params.path("_meta").path(key);
        return limit != null && limit.isInt() && limit.asInt() > 0 ? limit.asInt() : 0;
    }

    /**
     * The definitions as given when {@code maxChars} is 0, else each cut to fit it as far as the order above can. When {@code totalChars} is above 0
     * and the cut definitions together are longer, the limit of each is lowered by 100 characters at a time, to no less than 600, until they are not:
     * Antigravity (agy 1.5.15) offers its model the smallest definitions first until their sum reaches about 62 000 characters, and the rest answer
     * "tool X is not enabled for server Y".
     *
     * @param totalChars the most characters all the definitions may take together, 0 for no such limit
     */
    public static List<Object> fitAll(List<Map<String, Object>> definitions, int maxChars, int totalChars) {
        if (maxChars <= 0) {
            return new ArrayList<>(definitions);
        }
        int each = maxChars;
        while (true) {
            List<Object> fitted = new ArrayList<>();
            int total = 0;
            for (Map<String, Object> definition : definitions) {
                ObjectNode cut = fit(MAPPER.valueToTree(definition), each);
                total += size(cut);
                fitted.add(cut);
            }
            if (totalChars <= 0 || total <= totalChars || each <= MIN_EACH) {
                return fitted;
            }
            each -= 100;
        }
    }

    /** One definition cut to {@code maxChars} compact characters, or as near as the order above gets; the given node is not changed. */
    public static ObjectNode fit(ObjectNode definition, int maxChars) {
        ObjectNode tool = definition.deepCopy();
        if (size(tool) <= maxChars) {
            return tool;
        }
        cutDescription(tool, TOOL_DESCRIPTION);
        if (size(tool) <= maxChars) {
            return tool;
        }
        List<ObjectNode> described = new ArrayList<>();
        List<ObjectNode> parents = new ArrayList<>();
        List<String> plainNames = new ArrayList<>();
        collect(tool.path("inputSchema"), described, parents, plainNames);
        described.sort(Comparator.comparingInt((ObjectNode parameter) -> parameter.path("description").asText().length()).reversed());
        for (ObjectNode parameter : described) {
            cutDescription(parameter, PARAMETER_DESCRIPTION);
            if (size(tool) <= maxChars) {
                return tool;
            }
        }
        for (ObjectNode parameter : described) {
            parameter.remove("description");
            if (size(tool) <= maxChars) {
                return tool;
            }
        }
        cutDescription(tool, SHORT_TOOL_DESCRIPTION);
        for (int i = 0; i < parents.size() && size(tool) > maxChars; i++) {
            parents.get(i).set(plainNames.get(i), MAPPER.createObjectNode());
        }
        dropLastOptionalParameters(tool, maxChars);
        return tool;
    }

    /**
     * Last resort for a tool that stays too long however little is left of its text: the parameters declared last are left out of the schema until
     * it fits, except {@code kind}, {@code action} and the required ones. The tool still accepts a call that carries them, because the engine reads
     * the call's arguments and not this definition; the model is only no longer told of them.
     */
    private static void dropLastOptionalParameters(ObjectNode tool, int maxChars) {
        if (!(tool.path("inputSchema").path("properties") instanceof ObjectNode parameters)) {
            return;
        }
        List<String> required = new ArrayList<>();
        tool.path("inputSchema").path("required").forEach(name -> required.add(name.asText()));
        List<String> names = new ArrayList<>();
        parameters.fieldNames().forEachRemaining(names::add);
        for (int i = names.size() - 1; i >= 0 && size(tool) > maxChars; i--) {
            String name = names.get(i);
            if (!required.contains(name) && !"kind".equals(name) && !"action".equals(name)) {
                parameters.remove(name);
            }
        }
    }

    /** The length of the node written compactly, which is what a brain counts. */
    static int size(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node).length();
        } catch (JsonProcessingException unwritable) {
            throw new IllegalStateException("a tool definition could not be written: " + unwritable.getMessage(), unwritable);
        }
    }

    /** The description cut to about {@code keep} characters, at the end of a sentence or a line when one is past the half of it. */
    private static void cutDescription(ObjectNode node, int keep) {
        String description = node.path("description").asText(null);
        if (description == null || description.length() <= keep) {
            return;
        }
        String cut = description.substring(0, keep);
        int boundary = Math.max(cut.lastIndexOf(". "), cut.lastIndexOf('\n'));
        node.put("description", (boundary > keep / 2 ? cut.substring(0, boundary + 1) : cut).stripTrailing());
    }

    /** The parameters of the schema, nested ones too: those with a description, and, as parent and name, those that declare nothing but a type. */
    private static void collect(JsonNode schema, List<ObjectNode> described, List<ObjectNode> parents, List<String> plainNames) {
        if (!(schema instanceof ObjectNode)) {
            return;
        }
        if (schema.path("properties") instanceof ObjectNode parameters) {
            List<String> names = new ArrayList<>();
            parameters.fieldNames().forEachRemaining(names::add);
            for (String name : names) {
                if (parameters.get(name) instanceof ObjectNode parameter) {
                    if (parameter.has("description")) {
                        described.add(parameter);
                    }
                    if (parameter.size() == 1 && parameter.has("type")) {
                        parents.add(parameters);
                        plainNames.add(name);
                    }
                    collect(parameter, described, parents, plainNames);
                }
            }
        }
        collect(schema.path("items"), described, parents, plainNames);
    }
}
