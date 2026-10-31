package org.jawata.mcp.spike;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindReferencesTool;
import org.jawata.mcp.tools.GetDocumentSymbolsTool;
import org.jawata.mcp.tools.GetHoverInfoTool;
import org.jawata.mcp.tools.GoToDefinitionTool;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h S1, route A spike: the smallest language server (the Language Server Protocol, LSP, an editor's standard way to ask a server
 * about code) that answers from the engine's OWN JDT, with no Red Hat code. It speaks the protocol's framing (a Content-Length header,
 * then JSON), and answers {@code initialize}, document open/change/close with published diagnostics, hover, definition, references and
 * document symbols.
 *
 * <p>Where the engine already has an answer the server CALLS THE EXISTING TOOL in-process (hover, definition, references, document
 * symbols); where the tools have none it uses the JDT working copy directly (diagnostics for text that is not saved), which is the
 * very mechanism the spike is sizing. It is evidence, not product: it lives in test sources and writes no production member.</p>
 */
final class LspRouteASpikeServer {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final Supplier<IJdtService> service;
    private final GetHoverInfoTool hover;
    private final GoToDefinitionTool definition;
    private final FindReferencesTool references;
    private final GetDocumentSymbolsTool symbols;
    private final Map<String, ICompilationUnit> workingCopies = new HashMap<>();
    private OutputStream out;
    private boolean exited;

    LspRouteASpikeServer(Supplier<IJdtService> service) {
        this.service = service;
        this.hover = new GetHoverInfoTool(service);
        this.definition = new GoToDefinitionTool(service);
        this.references = new FindReferencesTool(service);
        this.symbols = new GetDocumentSymbolsTool(service);
    }

    /** Reads framed messages from {@code in} until {@code exit}, answering on {@code out}. */
    void serve(InputStream in, OutputStream out) throws IOException {
        this.out = out;
        while (!exited) {
            JsonNode message = read(in);
            if (message == null) {
                return;
            }
            handle(message);
        }
    }

    private static JsonNode read(InputStream in) throws IOException {
        int length = -1;
        while (true) {
            StringBuilder line = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n') {
                if (c != '\r') {
                    line.append((char) c);
                }
            }
            if (c == -1) {
                return null;
            }
            if (line.length() == 0) {
                break;
            }
            if (line.toString().toLowerCase().startsWith("content-length:")) {
                length = Integer.parseInt(line.substring("content-length:".length()).trim());
            }
        }
        byte[] body = in.readNBytes(length);
        return JSON.readTree(new String(body, StandardCharsets.UTF_8));
    }

    private synchronized void send(JsonNode message) throws IOException {
        byte[] body = JSON.writeValueAsBytes(message);
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        frame.write(("Content-Length: " + body.length + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        frame.write(body);
        out.write(frame.toByteArray());
        out.flush();
    }

    private void handle(JsonNode message) throws IOException {
        String method = message.path("method").asText("");
        JsonNode params = message.path("params");
        JsonNode id = message.get("id");
        Object result = null;
        switch (method) {
            case "initialize" -> result = initializeResult();
            case "shutdown" -> result = null;
            case "exit" -> {
                exited = true;
                return;
            }
            case "textDocument/didOpen" -> {
                JsonNode document = params.path("textDocument");
                change(document.path("uri").asText(), document.path("text").asText());
                return;
            }
            case "textDocument/didChange" -> {
                change(params.path("textDocument").path("uri").asText(), params.path("contentChanges").path(0).path("text").asText());
                return;
            }
            case "textDocument/didClose" -> {
                close(params.path("textDocument").path("uri").asText());
                return;
            }
            case "textDocument/hover" -> result = hoverResult(params);
            case "textDocument/definition" -> result = definitionResult(params);
            case "textDocument/references" -> result = referencesResult(params);
            case "textDocument/documentSymbol" -> result = symbolResult(params);
            default -> {
                if (id != null) {
                    respondError(id, -32601, "method not found: " + method);
                }
                return;
            }
        }
        if (id != null) {
            ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
            response.set("id", id);
            response.set("result", result == null ? JSON.nullNode() : JSON.valueToTree(result));
            send(response);
        }
    }

    private void respondError(JsonNode id, int code, String text) throws IOException {
        ObjectNode response = JSON.createObjectNode().put("jsonrpc", "2.0");
        response.set("id", id);
        response.set("error", JSON.createObjectNode().put("code", code).put("message", text));
        send(response);
    }

    private static Map<String, Object> initializeResult() {
        Map<String, Object> capabilities = new HashMap<>();
        capabilities.put("textDocumentSync", Map.of("openClose", true, "change", 1));
        capabilities.put("hoverProvider", true);
        capabilities.put("definitionProvider", true);
        capabilities.put("referencesProvider", true);
        capabilities.put("documentSymbolProvider", true);
        return Map.of("capabilities", capabilities, "serverInfo", Map.of("name", "jawata-route-a-spike"));
    }

    // ---- documents and diagnostics -------------------------------------------------------------------------------------------------

    private static Path pathOf(String uri) {
        return Path.of(URI.create(uri));
    }

    private void change(String uri, String text) throws IOException {
        ICompilationUnit original = service.get().getCompilationUnit(pathOf(uri));
        if (original == null) {
            publish(uri, JSON.createArrayNode());
            return;
        }
        try {
            ICompilationUnit copy = workingCopies.get(uri);
            if (copy == null) {
                copy = original.getWorkingCopy(null);
                workingCopies.put(uri, copy);
            }
            copy.getBuffer().setContents(text);
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(copy);
            parser.setResolveBindings(true);
            parser.setStatementsRecovery(true);
            parser.setBindingsRecovery(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            ArrayNode diagnostics = JSON.createArrayNode();
            if (ast != null) {
                for (IProblem problem : ast.getProblems()) {
                    if (!problem.isError() && !problem.isWarning()) {
                        continue;
                    }
                    ObjectNode row = JSON.createObjectNode();
                    row.set("range", rangeOf(ast, problem.getSourceStart(), problem.getSourceEnd() + 1));
                    row.put("severity", problem.isError() ? 1 : 2);
                    row.put("source", "jawata-jdt");
                    row.put("message", problem.getMessage());
                    diagnostics.add(row);
                }
            }
            publish(uri, diagnostics);
        } catch (Exception e) {
            throw new IOException("diagnostics failed for " + uri + ": " + e, e);
        }
    }

    private void close(String uri) throws IOException {
        ICompilationUnit copy = workingCopies.remove(uri);
        if (copy != null) {
            try {
                copy.discardWorkingCopy();
            } catch (Exception e) {
                throw new IOException(e);
            }
        }
        publish(uri, JSON.createArrayNode());
    }

    private void publish(String uri, ArrayNode diagnostics) throws IOException {
        ObjectNode params = JSON.createObjectNode().put("uri", uri);
        params.set("diagnostics", diagnostics);
        ObjectNode notification = JSON.createObjectNode().put("jsonrpc", "2.0").put("method", "textDocument/publishDiagnostics");
        notification.set("params", params);
        send(notification);
    }

    private static ObjectNode rangeOf(CompilationUnit ast, int start, int end) {
        ObjectNode range = JSON.createObjectNode();
        range.set("start", position(ast, start));
        range.set("end", position(ast, end));
        return range;
    }

    /** The protocol counts lines from zero and characters in UTF-16 units, which is how JDT counts its offsets: no conversion is needed. */
    private static ObjectNode position(CompilationUnit ast, int offset) {
        return JSON.createObjectNode().put("line", ast.getLineNumber(offset) - 1).put("character", ast.getColumnNumber(offset));
    }

    // ---- requests the engine's tools already answer ------------------------------------------------------------------------------

    private static ObjectNode args(JsonNode params) {
        JsonNode at = params.path("position");
        return JSON.createObjectNode().put("filePath", pathOf(params.path("textDocument").path("uri").asText()).toString())
            .put("line", at.path("line").asInt()).put("column", at.path("character").asInt());
    }

    private Object hoverResult(JsonNode params) {
        ToolResponse response = hover.execute(args(params));
        if (!response.isSuccess()) {
            return null;
        }
        JsonNode data = JSON.valueToTree(response.getData());
        String text = "```java\n" + data.path("signature").asText(data.path("name").asText()) + "\n```" + docOf(data);
        return Map.of("contents", Map.of("kind", "markdown", "value", text));
    }

    private static String docOf(JsonNode data) {
        String doc = data.path("docComment").asText("");
        return doc.isEmpty() ? "" : "\n\n" + doc;
    }

    private Object definitionResult(JsonNode params) {
        ToolResponse response = definition.execute(args(params));
        if (!response.isSuccess()) {
            return null;
        }
        JsonNode location = JSON.valueToTree(response.getData()).path("location");
        return location.isMissingNode() ? null : locationOf(location.path("filePath").asText(), location.path("line").asInt(), location.path("column").asInt(), 0);
    }

    private Object referencesResult(JsonNode params) {
        ToolResponse response = references.execute(args(params));
        if (!response.isSuccess()) {
            return List.of();
        }
        JsonNode data = JSON.valueToTree(response.getData());
        String symbol = data.path("symbol").asText("");
        List<Object> found = new ArrayList<>();
        for (JsonNode reference : data.path("references")) {
            found.add(locationOf(reference.path("filePath").asText(), reference.path("line").asInt(), reference.path("column").asInt(), symbol.length()));
        }
        return found;
    }

    /** The tools answer a path relative to the project and a start position only; the protocol wants a file address and a range. */
    private Object locationOf(String relative, int line, int column, int length) {
        Path file = service.get().resolvePath(relative);
        Map<String, Object> start = Map.of("line", line, "character", column);
        Map<String, Object> end = Map.of("line", line, "character", column + length);
        return Map.of("uri", file.toUri().toString(), "range", Map.of("start", start, "end", end));
    }

    private Object symbolResult(JsonNode params) {
        ObjectNode arguments = JSON.createObjectNode().put("filePath", pathOf(params.path("textDocument").path("uri").asText()).toString());
        ToolResponse response = symbols.execute(arguments);
        if (!response.isSuccess()) {
            return List.of();
        }
        List<Object> roots = new ArrayList<>();
        for (JsonNode symbol : JSON.valueToTree(response.getData()).path("symbols")) {
            roots.add(symbolOf(symbol));
        }
        return roots;
    }

    private static Map<String, Object> symbolOf(JsonNode symbol) {
        int line = symbol.path("line").asInt();
        Map<String, Object> at = Map.of("start", Map.of("line", line, "character", 0), "end", Map.of("line", line, "character", 0));
        Map<String, Object> out = new HashMap<>();
        out.put("name", symbol.path("name").asText());
        out.put("kind", kindOf(symbol.path("kind").asText()));
        out.put("range", at);
        out.put("selectionRange", at);
        List<Object> children = new ArrayList<>();
        for (JsonNode child : symbol.path("children")) {
            children.add(symbolOf(child));
        }
        out.put("children", children);
        return out;
    }

    private static int kindOf(String kind) {
        return switch (kind) {
            case "Class" -> 5;
            case "Method" -> 6;
            case "Constructor" -> 9;
            case "Field" -> 8;
            case "Interface" -> 11;
            case "Enum" -> 10;
            default -> 13;
        };
    }
}
