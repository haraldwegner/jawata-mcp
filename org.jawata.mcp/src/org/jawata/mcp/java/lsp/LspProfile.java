package org.jawata.mcp.java.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.engine.ToolNotFoundException;
import org.jawata.mcp.java.JavaRanges;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What the editor's profile page asks of the engine (Sprint 28i, Stage 25, step A6; ARCHITECTURE-28i-s25.md): {@code jawata/profile} records the
 * running program for some seconds and answers its hottest methods, each already resolved to the file and line of its source, so the page needs no
 * second round trip. Recording and ranking are the engine's own {@code profile} tool, asked through the one gate as the editor's caller.
 */
final class LspProfile {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final int DEFAULT_SECONDS = 5;
    private static final int DEFAULT_ROWS = 20;

    private final ToolCalls tools;
    private final CallerIdentity caller;
    private final Supplier<IJdtService> service;

    LspProfile(ToolCalls tools, CallerIdentity caller, Supplier<IJdtService> service) {
        this.tools = tools;
        this.caller = caller;
        this.service = service;
    }

    /**
     * Records the program of an engine session and answers its hot methods.
     *
     * @param params {@code sessionId} of the running program, and optionally {@code seconds} to record (default 5), {@code dimension} (cpu, alloc, wall
     *               or lock; default cpu) and {@code limit} of rows (default 20)
     */
    Object profile(JsonNode params) throws ToolNotFoundException, JavaModelException {
        String session = params.path("sessionId").asText("");
        if (session.isBlank()) {
            throw new IllegalArgumentException("a profile needs the sessionId of the running program");
        }
        ObjectNode sample = JSON.createObjectNode().put("action", "sample").put("sessionId", session)
            .put("durationSeconds", params.path("seconds").asInt(DEFAULT_SECONDS));
        JsonNode recorded = answerOf("the program could not be recorded", tools.callTool("profile", sample, caller));
        String artifact = recorded.path("artifactId").asText("");
        if (artifact.isBlank()) {
            throw new IllegalStateException("the program could not be recorded: " + recorded.path("why").asText("the engine named no recording"));
        }
        String dimension = params.path("dimension").asText("cpu");
        ObjectNode rank = JSON.createObjectNode().put("action", "hotspots").put("artifactId", artifact).put("dimension", dimension)
            .put("limit", params.path("limit").asInt(DEFAULT_ROWS));
        JsonNode ranked = answerOf("the recording could not be ranked", tools.callTool("profile", rank, caller));
        List<Object> rows = new ArrayList<>();
        for (JsonNode row : ranked.path("rows")) {
            @SuppressWarnings("unchecked")
            Map<String, Object> shown = JSON.convertValue(row, LinkedHashMap.class);
            Map<String, Object> where = locate(row.path("symbol").asText(""));
            if (where != null) {
                shown.put("location", where);
            }
            rows.add(shown);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", session);
        result.put("artifactId", artifact);
        result.put("dimension", dimension);
        result.put("totalSamples", ranked.path("totalSamples").asLong());
        result.put("totalMethods", ranked.path("totalMethods").asInt());
        result.put("rows", rows);
        return result;
    }

    private static JsonNode answerOf(String what, ToolResponse answer) {
        if (!answer.isSuccess()) {
            throw new IllegalStateException(what + ": " + (answer.getError() == null ? "no reason given" : answer.getError().getMessage()));
        }
        return JSON.valueToTree(answer.getData());
    }

    /**
     * The source file and the range of the name of the method {@code Type#method} names, from the engine's Java model; null when the type is not in
     * source of a loaded project (a library or the JDK) or the method is not declared there, which leaves the row without a place to open.
     */
    private Map<String, Object> locate(String symbol) throws JavaModelException {
        int hash = symbol.indexOf('#');
        IJdtService engine = service.get();
        if (hash < 0 || engine == null) {
            return null;
        }
        String typeName = symbol.substring(0, hash).replace('$', '.');
        String methodName = symbol.substring(hash + 1);
        int parameters = methodName.indexOf('(');
        if (parameters >= 0) {
            methodName = methodName.substring(0, parameters);
        }
        for (LoadedProject project : engine.allProjects()) {
            IType type = project.javaProject().findType(typeName);
            ICompilationUnit unit = type == null ? null : type.getCompilationUnit();
            if (unit == null) {
                continue;
            }
            for (IMethod method : type.getMethods()) {
                IResource resource = unit.getResource();
                if (method.getElementName().equals(methodName) && method.getNameRange() != null && resource != null && resource.getLocationURI() != null) {
                    JavaRanges.Lines lines = JavaRanges.linesOf(unit);
                    return Map.of("uri", resource.getLocationURI().toString(),
                        "range", lines.span(method.getNameRange().getOffset(), method.getNameRange().getLength()).toProtocol());
                }
            }
        }
        return null;
    }
}
