package org.jawata.mcp.tools.shared;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 23 (D9, C13 decision B — Harald 2026-07-13) — per-row field
 * projection for the list-heavy tools. The C13 evidence matrix measured
 * ~2.5–3× redundancy in list rows (qualifiedName restating package+name,
 * per-row file paths, repeated JSON keys); a caller that only needs
 * coordinates asks {@code fields=["filePath","line"]} and each row carries
 * exactly those keys. Field projection captures ~75% of a pipe-DSL's saving
 * at none of its complexity — the decided shape.
 *
 * <p>Contract: no {@code fields} param = full rows (back-compatible).
 * Projection is top-level row keys only. Keys a row does not carry are
 * skipped silently — rows are heterogeneous by design (e.g. {@code context}
 * appears only where a source line exists), so absence is normal, and a
 * misspelled key simply never appears rather than failing the query.</p>
 */
public final class FieldsProjection {

    private FieldsProjection() {
    }

    /** The shared schema property declaring the {@code fields} param. */
    public static Map<String, Object> schemaProperty(String rowsName) {
        return Map.of(
            "type", "array",
            "items", Map.of("type", "string"),
            "description", "Optional per-row projection: keep ONLY these keys in each "
                + rowsName + " row (token economy on broad queries). Omit for full rows. "
                + "Keys a row does not carry are skipped silently.");
    }

    /**
     * @return the requested field names, or {@code null} when the param is absent
     * @throws IllegalArgumentException when present but not a non-empty array of
     *         non-blank strings — the caller maps this to an invalid-parameter response
     */
    public static List<String> parse(JsonNode arguments) {
        JsonNode node = arguments == null ? null : arguments.get("fields");
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isArray() || node.isEmpty()) {
            throw new IllegalArgumentException(
                "fields must be a non-empty array of row-key names, e.g. [\"filePath\", \"line\"]");
        }
        List<String> fields = new ArrayList<>(node.size());
        for (JsonNode f : node) {
            if (!f.isTextual() || f.asText().isBlank()) {
                throw new IllegalArgumentException(
                    "fields entries must be non-blank strings; got: " + f);
            }
            fields.add(f.asText());
        }
        return fields;
    }

    /**
     * Project each row down to the requested keys, in the caller's order.
     * {@code fields == null} returns the rows unchanged.
     */
    public static List<Map<String, Object>> project(List<Map<String, Object>> rows,
                                                    List<String> fields) {
        if (fields == null) {
            return rows;
        }
        List<Map<String, Object>> projected = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows) {
            Map<String, Object> kept = new LinkedHashMap<>();
            for (String field : fields) {
                if (row.containsKey(field)) {
                    kept.put(field, row.get(field));
                }
            }
            projected.add(kept);
        }
        return projected;
    }
}
