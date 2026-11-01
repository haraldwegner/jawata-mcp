package org.jawata.mcp.java.lsp;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.jawata.core.IJdtService;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The language server's navigation requests (Sprint 28h, S16b): hover, definition, references, implementations, symbols, signature
 * help and highlights. Each is one question put to a published door of the engine through the one gate, as the editor's own caller,
 * and the answer translated into the protocol's shape. The doors read the unit the model hands out, which is the editor's working copy
 * for an open file, so every answer is about the text on the person's screen, saved or not.
 *
 * <p>The doors answer a start position and, since S16a, an end; where they give a {@code nameRange} the protocol range is the name,
 * which is what an editor underlines and jumps to.</p>
 */
final class LspNavigation {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final int HIGHLIGHT_TEXT = 1;

    /** The address of a library class: it names no file, and {@code java/classFileContents} answers its source. */
    private static final String LIBRARY_PREFIX = "jdt://contents/";
    private static final String LIBRARY_SUFFIX = ".java";

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final Supplier<IJdtService> service;

    LspNavigation(ToolCalls tools, CallerIdentity caller, Supplier<IJdtService> service) {
        this.tools = tools;
        this.caller = caller;
        this.service = service;
    }

    Object hover(JsonNode params) throws ToolNotFoundException {
        JsonNode data = ask("get_at_position", at(params).put("kind", "hover"));
        if (data == null) {
            return null;
        }
        String signature = data.path("signature").asText(data.path("name").asText());
        String doc = data.path("docComment").asText(data.path("documentation").asText(""));
        String text = "```java\n" + signature + "\n```" + (doc.isEmpty() ? "" : "\n\n" + doc);
        return Map.of("contents", Map.of("kind", "markdown", "value", text));
    }

    Object definition(JsonNode params) throws ToolNotFoundException {
        JsonNode data = ask("go_to_definition", at(params));
        JsonNode place = data == null ? null : data.path("location");
        if (place != null && !place.isMissingNode()) {
            return locationOf(place);
        }
        // No source of ours: a library type (or a member of one) is answered with the address its class is read at.
        String library = data == null ? "" : data.has("package") ? data.path("package").asText() + "." + data.path("symbol").asText()
            : data.path("containingType").asText("");
        return library.isBlank() || library.startsWith(".") ? null : libraryLocation(library);
    }

    Object references(JsonNode params) throws ToolNotFoundException {
        JsonNode data = ask("find_references", at(params).put("kind", "references"));
        return data == null ? List.of() : locationsOf(data.path("references"));
    }

    Object implementations(JsonNode params) throws ToolNotFoundException {
        JsonNode data = ask("find_references", at(params).put("kind", "implementations"));
        return data == null ? List.of() : locationsOf(data.path("implementations"));
    }

    Object documentHighlight(JsonNode params) throws ToolNotFoundException {
        JsonNode data = ask("find_references", at(params).put("kind", "references"));
        List<Object> highlights = new ArrayList<>();
        if (data != null) {
            Path here = pathOf(params.path("textDocument").path("uri").asText());
            for (JsonNode reference : data.path("references")) {
                if (here.equals(fileOf(reference.path("filePath").asText()))) {
                    highlights.add(Map.of("range", rangeOf(reference), "kind", HIGHLIGHT_TEXT));
                }
            }
            // the references do not include the declaration, which an editor marks with the uses
            JsonNode definition = ask("go_to_definition", at(params));
            JsonNode place = definition == null ? null : definition.path("location");
            if (place != null && place.isObject() && here.equals(fileOf(place.path("filePath").asText()))) {
                highlights.add(Map.of("range", rangeOf(place), "kind", HIGHLIGHT_TEXT));
            }
        }
        return highlights;
    }

    Object documentSymbol(JsonNode params) throws ToolNotFoundException {
        ObjectNode arguments = JSON.createObjectNode().put("kind", "document_symbols")
            .put("filePath", pathOf(params.path("textDocument").path("uri").asText()).toString());
        JsonNode data = ask("inspect", arguments);
        List<Object> roots = new ArrayList<>();
        if (data != null) {
            for (JsonNode symbol : data.path("symbols")) {
                roots.add(symbolOf(symbol));
            }
        }
        return roots;
    }

    Object workspaceSymbol(JsonNode params) throws ToolNotFoundException {
        String query = params.path("query").asText("");
        List<Object> found = new ArrayList<>();
        if (query.isBlank()) {
            return found;
        }
        JsonNode data = ask("search_symbols", JSON.createObjectNode().put("query", query).put("maxResults", 100));
        if (data != null) {
            for (JsonNode hit : data.path("results")) {
                if (hit.path("filePath").asText("").isEmpty()) {
                    continue; // a symbol of a library has no file of ours to jump to
                }
                Map<String, Object> symbol = new LinkedHashMap<>();
                symbol.put("name", hit.path("name").asText());
                symbol.put("kind", kindOf(hit.path("kind").asText()));
                symbol.put("location", locationOf(hit));
                found.add(symbol);
            }
        }
        return found;
    }

    /**
     * The editor asks with the cursor among the arguments, where the door finds the enclosing method, not the call. So the call is
     * found in the text first: the name before the parenthesis the cursor is inside, and how many commas lie between them.
     */
    Object signatureHelp(JsonNode params) throws Exception {
        Path file = pathOf(params.path("textDocument").path("uri").asText());
        var unit = service.get().getCompilationUnit(file);
        String text = unit == null ? null : unit.getSource();
        int[] call = text == null ? null
            : callAt(text, params.path("position").path("line").asInt(), params.path("position").path("character").asInt());
        if (call == null) {
            return null;
        }
        JsonNode data = ask("get_at_position", JSON.createObjectNode().put("kind", "signature").put("filePath", file.toString())
            .put("line", call[0]).put("column", call[1] + 1)); // one into the name, as the other doors are asked
        if (data == null) {
            return null;
        }
        // The door answers the enclosing method where it could not resolve the call (text JDT cannot recover); that is not the call's
        // signature, and showing it would be a wrong answer, so only signatures that carry the called name are kept.
        String called = text.substring(call[3], call[4]) + "(";
        List<Object> signatures = new ArrayList<>();
        for (JsonNode signature : data.path("signatures")) {
            if (signature.path("label").asText().startsWith(called)) {
                signatures.add(JSON.convertValue(signature, Map.class));
            }
        }
        if (signatures.isEmpty()) {
            return null;
        }
        Map<String, Object> help = new LinkedHashMap<>();
        help.put("signatures", signatures);
        help.put("activeSignature", 0);
        help.put("activeParameter", call[2]);
        return help;
    }

    /**
     * The call the cursor is inside: its name's line and column, how many arguments the cursor is past, and the name's offsets in the
     * text. Null where the cursor is in no call, which is where a statement or a block ends before an opening parenthesis is found.
     */
    static int[] callAt(String text, int line, int character) {
        int offset = 0;
        for (int i = 0; i < line; i++) {
            int next = text.indexOf('\n', offset);
            if (next < 0) {
                return null;
            }
            offset = next + 1;
        }
        offset = Math.min(offset + character, text.length());
        int depth = 0;
        int commas = 0;
        int open = -1;
        for (int i = offset - 1; i >= 0 && open < 0; i--) {
            char c = text.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    open = i;
                } else {
                    depth--;
                }
            } else if (depth == 0 && c == ',') {
                commas++;
            } else if (depth == 0 && (c == ';' || c == '{' || c == '}')) {
                return null;
            }
        }
        int end = open;
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        int start = end;
        while (start > 0 && Character.isJavaIdentifierPart(text.charAt(start - 1))) {
            start--;
        }
        if (open < 0 || start == end) {
            return null;
        }
        int nameLine = (int) text.substring(0, start).chars().filter(c -> c == '\n').count();
        return new int[] {nameLine, start - (text.lastIndexOf('\n', start - 1) + 1), commas, start, end};
    }

    // ---- type definition, hierarchies, commands, library classes ------------------------------------------------------------------------

    /** The declaration of the type the name under the cursor is declared to have; a library type is answered with the address its source is asked at. */
    Object typeDefinition(JsonNode params) throws Exception {
        var unit = service.get().getCompilationUnit(pathOf(params.path("textDocument").path("uri").asText()));
        var type = unit == null ? null : org.jawata.mcp.java.JavaTypeDefinitions.at(unit,
            params.path("position").path("line").asInt(), params.path("position").path("character").asInt());
        if (type == null) {
            return null;
        }
        var where = org.jawata.mcp.tools.shared.ElementLocations.of(type, service.get());
        return where == null ? libraryLocation(type.getFullyQualifiedName()) : locationOf(JSON.valueToTree(where));
    }

    Object prepareCallHierarchy(JsonNode params) throws ToolNotFoundException {
        JsonNode data = ask("go_to_definition", at(params));
        if (data == null || !"Method".equals(data.path("kind").asText()) || !data.path("location").isObject()) {
            return null;
        }
        return List.of(callItem(data.path("location"), data.path("symbol").asText(), data.path("containingType").asText("")));
    }

    Object incomingCalls(JsonNode params) throws ToolNotFoundException {
        JsonNode data = ask("get_call_hierarchy", callArguments("incoming", params.path("item")));
        return data == null ? List.of() : callsOf(data.path("callers"), "callerLocation", "callerMethod", "callerClass", "", "from");
    }

    Object outgoingCalls(JsonNode params) throws ToolNotFoundException {
        JsonNode data = ask("get_call_hierarchy", callArguments("outgoing", params.path("item")));
        return data == null ? List.of() : callsOf(data.path("callees"), "declaration", "method", "declaringClass", "callLocation", "to");
    }

    Object prepareTypeHierarchy(JsonNode params) throws ToolNotFoundException {
        JsonNode type = ask("get_at_position", at(params).put("kind", "type"));
        JsonNode hierarchy = type == null ? null : hierarchyOf(type.path("qualifiedName").asText(""));
        JsonNode row = hierarchy == null ? null : hierarchy.path("type");
        return row == null || !row.path("location").isObject() ? null : List.of(typeItem(row));
    }

    /** The door answers every supertype, the nearest first, not only the direct ones; the editor is shown them all. */
    Object supertypes(JsonNode params) throws ToolNotFoundException {
        JsonNode hierarchy = hierarchyOf(params.path("item").path("data").path("qualifiedName").asText(""));
        List<Object> items = new ArrayList<>();
        if (hierarchy != null) {
            addTypeItems(items, hierarchy.path("superclasses"));
            addTypeItems(items, hierarchy.path("interfaces"));
        }
        return items;
    }

    /** The door answers every subtype, not only the direct ones; the editor is shown them all. */
    Object subtypes(JsonNode params) throws ToolNotFoundException {
        JsonNode hierarchy = hierarchyOf(params.path("item").path("data").path("qualifiedName").asText(""));
        List<Object> items = new ArrayList<>();
        if (hierarchy != null) {
            addTypeItems(items, hierarchy.path("subtypes"));
        }
        return items;
    }

    /** The names of the tools the gate publishes: the commands an editor may run; none when the engine has no gate to ask. */
    List<String> commands() {
        List<String> names = new ArrayList<>();
        if (tools == null) {
            return names;
        }
        for (Map<String, Object> definition : tools.getToolDefinitions()) {
            names.add(String.valueOf(definition.get("name")));
        }
        return names;
    }

    /** A command is a tool of the gate, run as the editor's own caller; its first argument holds the tool's arguments. */
    Object executeCommand(JsonNode params) throws Exception {
        String command = params.path("command").asText();
        JsonNode argument = params.path("arguments").path(0);
        ObjectNode arguments = argument.isObject() ? ((ObjectNode) argument).deepCopy() : JSON.createObjectNode();
        ToolResponse response = tools.callTool(command, arguments, caller);
        if (!response.isSuccess()) {
            throw new IllegalStateException("The command " + command + " failed: " + response);
        }
        return response.getData();
    }

    /** The source of a library class, asked at the address a definition answered; the editor shows it read-only. */
    Object classFileContents(JsonNode params) throws ToolNotFoundException {
        String uri = params.path("uri").asText();
        if (!uri.startsWith(LIBRARY_PREFIX) || !uri.endsWith(LIBRARY_SUFFIX)) {
            return null;
        }
        JsonNode data = ask("inspect", JSON.createObjectNode().put("kind", "source")
            .put("typeName", uri.substring(LIBRARY_PREFIX.length(), uri.length() - LIBRARY_SUFFIX.length())));
        return data == null ? null : data.path("source").asText(null);
    }

    private JsonNode hierarchyOf(String typeName) throws ToolNotFoundException {
        // a nested type is named with '$' by the door that found it, and with '.' by the lookup the hierarchy door asks
        return typeName.isEmpty() ? null
            : ask("inspect", JSON.createObjectNode().put("kind", "type_hierarchy").put("typeName", typeName.replace('$', '.')));
    }

    private void addTypeItems(List<Object> items, JsonNode rows) {
        for (JsonNode row : rows) {
            if (row.path("location").isObject()) { // a library type has no file of ours to show
                items.add(typeItem(row));
            }
        }
    }

    private Map<String, Object> typeItem(JsonNode row) {
        JsonNode location = row.path("location");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", row.path("name").asText());
        item.put("kind", kindOf(row.path("kind").asText()));
        item.put("uri", locationOf(location).get("uri"));
        item.put("range", rangeOf(withoutName(location)));
        item.put("selectionRange", rangeOf(location));
        item.put("data", Map.of("qualifiedName", row.path("qualifiedName").asText()));
        return item;
    }

    /** An item of a call hierarchy; its data holds where the name is, so the next question can be put at it. */
    private Map<String, Object> callItem(JsonNode location, String name, String detail) {
        JsonNode span = location.path("nameRange").isObject() ? location.path("nameRange") : location;
        String uri = (String) locationOf(location).get("uri");
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("name", name);
        item.put("kind", kindOf("Method"));
        item.put("detail", detail);
        item.put("uri", uri);
        item.put("range", rangeOf(withoutName(location)));
        item.put("selectionRange", rangeOf(location));
        item.put("data", Map.of("uri", uri, "line", span.path("line").asInt(), "character", span.path("column").asInt()));
        return item;
    }

    private static ObjectNode callArguments(String direction, JsonNode item) {
        JsonNode data = item.path("data");
        return JSON.createObjectNode().put("direction", direction).put("filePath", pathOf(data.path("uri").asText()).toString())
            .put("line", data.path("line").asInt()).put("column", data.path("character").asInt());
    }

    /** The rows of a call hierarchy grouped by the method they name, each with every range it is called at. */
    private List<Object> callsOf(JsonNode rows, String locationField, String nameField, String detailField, String rangeField, String side) {
        Map<String, Object> items = new LinkedHashMap<>();
        Map<String, List<Object>> ranges = new LinkedHashMap<>();
        for (JsonNode row : rows) {
            JsonNode where = row.path(locationField);
            if (where.isObject()) { // a library method has no file of ours to show
                String key = where.toString();
                items.computeIfAbsent(key, k -> callItem(where, row.path(nameField).asText(), row.path(detailField).asText("")));
                ranges.computeIfAbsent(key, k -> new ArrayList<>()).add(rangeOf(rangeField.isEmpty() ? row : row.path(rangeField)));
            }
        }
        List<Object> calls = new ArrayList<>();
        items.forEach((key, item) -> calls.add(Map.of(side, item, "fromRanges", ranges.get(key))));
        return calls;
    }

    /** Where the source of a library class is asked: the address {@link #classFileContents} understands. */
    private static Map<String, Object> libraryLocation(String qualifiedName) {
        Map<String, Object> start = Map.of("line", 0, "character", 0);
        return Map.of("uri", LIBRARY_PREFIX + qualifiedName + LIBRARY_SUFFIX, "range", Map.of("start", start, "end", start));
    }

    // ---- asking the gate ----------------------------------------------------------------------------------------------------------------

    private JsonNode ask(String door, ObjectNode arguments) throws ToolNotFoundException {
        ToolResponse response = tools.callTool(door, arguments, caller);
        return response.isSuccess() ? JSON.valueToTree(response.getData()) : null;
    }

    private static ObjectNode at(JsonNode params) {
        JsonNode position = params.path("position");
        return JSON.createObjectNode().put("filePath", pathOf(params.path("textDocument").path("uri").asText()).toString())
            .put("line", position.path("line").asInt()).put("column", position.path("character").asInt());
    }

    private static Path pathOf(String uri) {
        return Path.of(URI.create(uri));
    }

    // ---- translating the answers --------------------------------------------------------------------------------------------------------

    /** The doors name a file by a path relative to its project; the protocol wants the file's address. */
    private Path fileOf(String path) {
        Path file = Path.of(path);
        return file.isAbsolute() ? file : service.get().resolvePath(path);
    }

    private List<Object> locationsOf(JsonNode places) {
        List<Object> locations = new ArrayList<>();
        for (JsonNode place : places) {
            if (!place.path("filePath").asText("").isEmpty()) {
                locations.add(locationOf(place));
            }
        }
        return locations;
    }

    private Map<String, Object> locationOf(JsonNode place) {
        Map<String, Object> location = new LinkedHashMap<>();
        location.put("uri", fileOf(place.path("filePath").asText()).toUri().toString());
        location.put("range", rangeOf(place));
        return location;
    }

    /** The name's range when the door gave one, else the range it gave, else the start alone. */
    private static Map<String, Object> rangeOf(JsonNode place) {
        JsonNode name = place.path("nameRange");
        JsonNode span = name.isObject() ? name : place;
        int line = span.path("line").asInt();
        int column = span.path("column").asInt();
        int endLine = span.path("endLine").asInt(line);
        int endColumn = span.path("endColumn").asInt(column);
        return Map.of("start", Map.of("line", line, "character", column), "end", Map.of("line", endLine, "character", endColumn));
    }

    private Map<String, Object> symbolOf(JsonNode symbol) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("name", symbol.path("name").asText());
        out.put("kind", kindOf(symbol.path("kind").asText()));
        Map<String, Object> whole = rangeOf(withoutName(symbol));
        out.put("range", whole);
        out.put("selectionRange", symbol.path("nameRange").isObject() ? rangeOf(symbol) : whole);
        List<Object> children = new ArrayList<>();
        for (JsonNode child : symbol.path("children")) {
            children.add(symbolOf(child));
        }
        out.put("children", children);
        return out;
    }

    private static JsonNode withoutName(JsonNode symbol) {
        ObjectNode copy = symbol.deepCopy();
        copy.remove("nameRange");
        return copy;
    }

    /** The protocol's numbering of what a symbol is. */
    static int kindOf(String kind) {
        return switch (kind) {
            case "Class", "Record" -> 5;
            case "Method" -> 6;
            case "Field" -> 8;
            case "Constructor" -> 9;
            case "Enum" -> 10;
            case "Interface", "Annotation" -> 11;
            default -> 13;
        };
    }
}
