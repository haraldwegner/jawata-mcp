package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.spi.lang.LoadedWorkspace;
import org.jawata.spi.lang.SourceDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Get compilation diagnostics (errors and warnings) for a file or project.
 *
 * <p>Asks the language for the problems it finds in a source file without a build (Sprint 28k, Stage 9: {@link SourceDiagnostics}); for
 * Java that is JDT's reconcile.</p>
 */
public class GetDiagnosticsTool extends WorkspaceTool<LoadedWorkspace> {

    private static final Logger log = LoggerFactory.getLogger(GetDiagnosticsTool.class);

    /** The language's per-file check, read at call time because a language may arrive after the tool is built. */
    private final Supplier<SourceDiagnostics> diagnostics;

    /**
     * @param workspace   the language's loaded workspace
     * @param diagnostics the language's per-file problem check
     */
    public GetDiagnosticsTool(Supplier<? extends LoadedWorkspace> workspace, Supplier<SourceDiagnostics> diagnostics) {
        super(workspace);
        this.diagnostics = diagnostics;
    }

    @Override
    public String getName() {
        return "get_diagnostics";
    }

    /**
     * A GATE: this tool's answer is evidence a claim was verified (S9a M1).
     *
     * <p>Declared here rather than held as a name in {@code learn/}, which is where it
     * lived in three byte-identical sets at once. See {@link Tool#isGate()}.</p>
     */
    @Override
    public boolean isGate() {
        return true;
    }

    @Override
    public String getDescription() {
        return """
            Get compilation diagnostics (errors and warnings) for a file or project.

            USAGE: get_diagnostics() for all files, or get_diagnostics(filePath="...") for one file
            OUTPUT: List of compilation errors and warnings with locations

            Useful for finding syntax errors, type mismatches, missing imports, etc.
            PROJECTION: fields=["filePath","line","message"] keeps only those keys per row.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Optional path to source file. If omitted, checks all files."
            ),
            "severity", Map.of(
                "type", "string",
                "description", "Filter by severity: 'error', 'warning', or 'all' (default: 'all')"
            ),
            "maxResults", Map.of(
                "type", "integer",
                "description", "Max diagnostics to return (default 100)"
            ),
            "fields", org.jawata.mcp.tools.shared.FieldsProjection.schemaProperty("diagnostic")
        ));
        schema.put("required", List.of());
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithWorkspace(LoadedWorkspace workspace, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        String severity = getStringParam(arguments, "severity", "all");
        int maxResults = getIntParam(arguments, "maxResults", 100);

        // Sprint 23 (C13 decision B): per-row projection, validated up front.
        List<String> fields;
        try {
            fields = org.jawata.mcp.tools.shared.FieldsProjection.parse(arguments);
        } catch (IllegalArgumentException e) {
            return ToolResponse.invalidParameter("fields", e.getMessage());
        }

        SourceDiagnostics language = diagnostics.get();
        if (language == null) {
            return ToolResponse.invalidParameter("filePath",
                "No loaded language can report diagnostics for a source file.");
        }

        try {
            List<Map<String, Object>> collected = new ArrayList<>();
            Tally tally = new Tally();
            int filesChecked = 0;

            if (filePath != null && !filePath.isBlank()) {
                // Check single file
                Path path = Path.of(filePath);
                Optional<List<SourceDiagnostics.Row>> rows = language.inFile(workspace, path);
                if (rows.isEmpty()) {
                    return ToolResponse.fileNotFound(filePath);
                }
                filesChecked = 1;
                collect(workspace, path, rows.get(), severity, maxResults, collected, tally);
            } else {
                // Check all files in the project, until enough problems are found
                int[] checked = {0};
                language.inEveryFile(workspace, (path, rows) -> {
                    checked[0]++;
                    collect(workspace, path, rows, severity, maxResults - collected.size(), collected, tally);
                    return collected.size() < maxResults;
                });
                filesChecked = checked[0];
            }

            int errorCount = tally.errors;
            Map<String, Object> data = new LinkedHashMap<>();
            // jawata-mcp#4 (Sprint 27a Stage 8): a gate that could not look
            // must SAY so, never answer green. On an unbuildable project the
            // reconcile reports 0 problems because NOTHING was compiled — the
            // exact state that read as "compiles clean" on the M2 workspace.
            // Each health problem counts as an ERROR so no caller can gate on
            // a vacuous zero.
            List<LoadedWorkspace.ProblemInfo> unbuildable = workspace.problems();
            if (!unbuildable.isEmpty()) {
                List<Map<String, Object>> rows = new ArrayList<>();
                for (var p : unbuildable) {
                    rows.add(p.describe());
                }
                data.put("couldNotCompile", rows);
                data.put("couldNotCompileWarning", "0 diagnostics on the project(s) above"
                    + " means NOT COMPILED, not clean — they cannot be built, so this gate"
                    + " could not look. Fix each per its remedy, then refresh_workspace.");
                errorCount += unbuildable.size();
            }
            data.put("totalDiagnostics", collected.size());
            data.put("errorCount", errorCount);
            data.put("warningCount", tally.warnings);
            data.put("filesChecked", filesChecked);
            data.put("diagnostics",
                org.jawata.mcp.tools.shared.FieldsProjection.project(collected, fields));

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(collected.size())
                .returnedCount(collected.size())
                .truncated(collected.size() >= maxResults)
                .suggestedNextTools(List.of(
                    "go_to_definition to navigate to error location",
                    "inspect(kind=document_symbols) to see file structure"
                ))
                .build());

        } catch (Exception e) {
            log.error("Error getting diagnostics: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /** Adds one file's rows that pass the severity filter, up to {@code remaining}, counting what it adds. */
    private void collect(LoadedWorkspace workspace, Path path, List<SourceDiagnostics.Row> rows, String severity, int remaining,
                         List<Map<String, Object>> into, Tally tally) {
        int room = remaining;
        for (SourceDiagnostics.Row row : rows) {
            if (room <= 0) {
                break;
            }
            boolean isError = "error".equals(row.severity());
            if ("error".equals(severity) && !isError) {
                continue;
            }
            if ("warning".equals(severity) && isError) {
                continue;
            }
            if (isError) {
                tally.errors++;
            } else {
                tally.warnings++;
            }
            Map<String, Object> diag = new LinkedHashMap<>();
            diag.put("filePath", workspace.display(path));
            diag.put("line", row.line());
            if (row.startOffset() != null) {
                diag.put("startOffset", row.startOffset());
            }
            if (row.endOffset() != null) {
                diag.put("endOffset", row.endOffset());
            }
            diag.put("severity", row.severity());
            diag.put("message", row.message());
            if (row.problemId() != null) {
                diag.put("problemId", row.problemId());
            }
            if (row.category() != null) {
                diag.put("category", row.category());
            }
            into.add(diag);
            room--;
        }
    }

    /** The errors and warnings the call has collected. */
    private static final class Tally {
        int errors;
        int warnings;
    }
}
