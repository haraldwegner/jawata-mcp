package org.jawata.mcp.protocol;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

    /** The tools a chat's principal works with, which are given what is left of a total before the others (the bridge puts them first the same way). */
    private static final List<String> PRIORITY = List.of("hire", "hire_wait", "experience", "files", "lookup", "git", "plan_steps", "plan_board", "brains", "health_check");
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

    /** The member of a {@code tools/list} request's {@code _meta} that lists the names of the only tools wanted. */
    public static final String META_ONLY_KEY = "jawata/onlyTools";

    /** The names of the only tools a {@code tools/list} request wants, none when it names none. */
    public static Set<String> onlyOf(JsonNode params) {
        Set<String> names = new java.util.LinkedHashSet<>();
        JsonNode listed = params == null ? null : params.path("_meta").path(META_ONLY_KEY);
        if (listed != null && listed.isArray()) {
            listed.forEach(name -> names.add(name.asText()));
        }
        return names;
    }

    /**
     * The definitions as given when {@code maxChars} is 0, else each cut to fit it as far as the order above can. When {@code totalChars} is above 0
     * every definition is first made as small as it can be made, and what is left of the total is then spent, up to {@code maxChars} each, on the
     * tools of {@link #PRIORITY} first and the others after them: Antigravity (agy 1.5.15) offers its model the smallest definitions first until
     * their sum reaches about 62 000 characters, and the rest answer "tool X is not enabled for server Y". A parameter is never left out: that
     * brain checks a call against the schema it was given and refuses an argument the schema does not name (measured: "additional properties 'lane'
     * not allowed"), so a list whose smallest forms are together longer than the total is given as those forms and is not shortened further.
     *
     * @param totalChars the most characters all the definitions may take together, 0 for no such limit
     * @param only       the names of the only tools wanted, none for all of them: a brain refuses every tool its seat may not call, so offering it only
     *                   those leaves each of them room for a whole definition
     */
    public static List<Object> fitAll(List<Map<String, Object>> definitions, int maxChars, int totalChars, Set<String> only) {
        List<Map<String, Object>> wanted = only.isEmpty() ? definitions
            : definitions.stream().filter(definition -> only.contains(String.valueOf(definition.get("name")))).toList();
        if (maxChars <= 0) {
            return new ArrayList<>(wanted);
        }
        List<ObjectNode> nodes = new ArrayList<>();
        for (Map<String, Object> definition : wanted) {
            nodes.add(MAPPER.valueToTree(definition));
        }
        List<Object> fitted = new ArrayList<>();
        if (totalChars <= 0) {
            nodes.forEach(node -> fitted.add(fit(node, maxChars)));
            return fitted;
        }
        List<ObjectNode> smallest = new ArrayList<>();
        int spare = totalChars;
        for (ObjectNode node : nodes) {
            ObjectNode small = fit(node, 1);
            smallest.add(small);
            spare -= size(small);
        }
        ObjectNode[] result = smallest.toArray(new ObjectNode[0]);
        List<Integer> order = new ArrayList<>();
        for (int i = 0; i < nodes.size(); i++) {
            order.add(i);
        }
        order.sort(Comparator.comparingInt(i -> {
            int at = PRIORITY.indexOf(nodes.get(i).path("name").asText());
            return at < 0 ? PRIORITY.size() : at;
        }));
        for (int i : order) {
            if (spare <= 0) {
                break;
            }
            int smallSize = size(smallest.get(i));
            ObjectNode grown = fit(nodes.get(i), Math.min(maxChars, smallSize + spare));
            spare -= Math.max(0, size(grown) - smallSize);
            result[i] = grown;
        }
        fitted.addAll(List.of(result));
        return fitted;
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
        return tool;
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
