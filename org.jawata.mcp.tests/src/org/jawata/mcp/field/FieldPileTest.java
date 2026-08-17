package org.jawata.mcp.field;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Sprint 28b D1: the append-only pile — versioned header, fold-at-read,
 *  shape counting, and a parser that only accepts what the emitter writes. */
class FieldPileTest {

    private static FieldEvent event(String tool, String kind, boolean ok, String code) {
        return new FieldEvent(1L, Token.of(tool), Token.of(kind), ok,
            ok ? Token.UNKNOWN : new Token(code), 2, Token.of("claude_code"),
            Token.of("3_11_0"));
    }

    @Test
    void first_line_is_the_versioned_header(@TempDir Path dir) throws Exception {
        FieldPile pile = new FieldPile(dir);
        pile.append(event("search_symbols", "unknown", true, null));
        List<String> lines = Files.readAllLines(pile.file());
        assertEquals("{\"pileFormat\":" + FieldPile.FORMAT_VERSION
            + ",\"contract\":" + FieldContract.VERSION + "}", lines.get(0),
            "studio refuses newer formats instead of misreading them — the"
                + " version must be the first thing in the file");
        assertEquals(2, lines.size());
    }

    @Test
    void fold_round_trips_what_append_wrote(@TempDir Path dir) {
        FieldPile pile = new FieldPile(dir);
        pile.append(event("run_tests", "run", false, "TIMEOUT"));
        pile.append(event("inspect", "source", true, null));
        List<FieldEvent> folded = pile.fold();
        assertEquals(2, folded.size());
        assertEquals("run_tests/run/TIMEOUT", folded.get(0).shapeKey());
        assertTrue(folded.get(1).ok());
        assertEquals(0, pile.failedWrites());
    }

    @Test
    void error_shapes_are_counted_successes_are_not(@TempDir Path dir) {
        FieldPile pile = new FieldPile(dir);
        pile.append(event("run_tests", "run", false, "TIMEOUT"));
        pile.append(event("run_tests", "run", false, "TIMEOUT"));
        pile.append(event("run_tests", "run", true, null));
        Map<String, Long> shapes = pile.countErrorShapes();
        assertEquals(Map.of("run_tests/run/TIMEOUT", 2L), shapes);
    }

    @Test
    void the_parser_accepts_only_the_emitted_shape() {
        assertNull(FieldPile.parse("{\"pileFormat\":1,\"contract\":1}"), "header is not an event");
        assertNull(FieldPile.parse("free text someone appended"));
        assertNull(FieldPile.parse(null));
        FieldEvent roundTrip = FieldPile.parse(event("a_tool", "k", false, "CODE").toJsonLine());
        assertEquals("a_tool/k/CODE", roundTrip.shapeKey());
    }

    @Test
    void latency_buckets_are_log_scale() {
        assertEquals(0, FieldEvent.bucket(5));
        assertEquals(1, FieldEvent.bucket(50));
        assertEquals(2, FieldEvent.bucket(500));
        assertEquals(3, FieldEvent.bucket(5_000));
        assertEquals(4, FieldEvent.bucket(50_000));
        assertEquals(5, FieldEvent.bucket(500_000));
        assertEquals(6, FieldEvent.bucket(5_000_000));
    }
}
