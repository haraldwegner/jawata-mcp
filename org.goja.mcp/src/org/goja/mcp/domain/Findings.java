package org.goja.mcp.domain;

import org.goja.mcp.models.ResponseMeta;
import org.goja.mcp.models.ToolResponse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 16b/D — renders a {@code List<Finding>} into the MCP {@link ToolResponse}
 * wire shape. The path a Finding-producing detector (Sprint 17/20) takes to the
 * tool surface: compute findings → {@code Findings.toResponse(findings)}.
 */
public final class Findings {

    private Findings() {
    }

    /** {@code { count, findings: [ {kind, filePath, line, column, severity, message, symbol} ] } } */
    public static ToolResponse toResponse(List<Finding> findings) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Finding f : findings) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("kind", f.kind());
            if (f.filePath() != null) row.put("filePath", f.filePath());
            if (f.line() >= 0) row.put("line", f.line());
            if (f.column() >= 0) row.put("column", f.column());
            if (f.severity() != null) row.put("severity", f.severity());
            row.put("message", f.message());
            if (f.symbol() != null) row.put("symbol", f.symbol());
            rows.add(row);
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("count", rows.size());
        data.put("findings", rows);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(rows.size())
            .returnedCount(rows.size())
            .build());
    }
}
