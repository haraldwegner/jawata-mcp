package org.jawata.mcp.domain;

import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 16b/D — the Finding value type + its MCP renderer. */
class FindingsTest {

    @Test
    @DisplayName("renders findings; omits null symbol and negative coordinates")
    void renders_findings_to_response() {
        ToolResponse r = Findings.toResponse(List.of(
            new Finding("naming", "A.java", 12, 4, "warning", "bad name", "com.x.A"),
            Finding.warning("bugs", "B.java", 7, "== on String")));

        assertTrue(r.isSuccess());
        Map<?, ?> data = (Map<?, ?>) r.getData();
        assertEquals(2, data.get("count"));

        List<?> findings = (List<?>) data.get("findings");
        assertEquals(2, findings.size());

        Map<?, ?> first = (Map<?, ?>) findings.get(0);
        assertEquals("naming", first.get("kind"));
        assertEquals(12, first.get("line"));
        assertEquals(4, first.get("column"));
        assertEquals("com.x.A", first.get("symbol"));

        Map<?, ?> second = (Map<?, ?>) findings.get(1);
        assertEquals("bugs", second.get("kind"));
        assertEquals("== on String", second.get("message"));
        assertFalse(second.containsKey("symbol"), "null symbol omitted");
        assertFalse(second.containsKey("column"), "negative column omitted");
    }
}
