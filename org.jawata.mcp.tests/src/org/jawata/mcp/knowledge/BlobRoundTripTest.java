package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28f D2 — the store travels as a blob, on a machine with no files.
 *
 * <p><b>The case this exists for is the ordinary one, not the exotic one.</b>
 * D1 names it: a machine whose substrate root is null and whose disk carries no
 * story files — <i>"which is every client"</i>. The backup-and-restore path
 * needs a file store; export and import must work where there is none, or the
 * only machine that can move its knowledge is the one that happens to have a
 * folder of markdown on it.</p>
 *
 * <p><b>Counts are not the check, and that is the point of this class.</b> The
 * exporter omits null fields, so two stores can agree on row count and disagree
 * about everything in the rows. This compares the CONTENT that came back, and
 * the union of keys across every row rather than the first row's — one row's
 * populated columns are not the schema.</p>
 */
class BlobRoundTripTest {

    private static String put(H2ExperienceStore store, String summary, String symbol) {
        return store.put(SymbolFact.of("domain_fact", summary, Confidence.MEDIUM)
            .symbol(symbol)
            .details("how it is done here, and what it is not")
            .build());
    }

    /** The union of keys across every row — never the first row's keys. */
    private static Set<String> keysAcross(List<Map<String, Object>> rows) {
        return rows.stream().flatMap(r -> r.keySet().stream())
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    void a_blob_moves_the_store_to_a_machine_with_no_files(@TempDir Path source,
            @TempDir Path destination) throws Exception {
        List<Map<String, Object>> blob;
        Set<String> exportedKeys;
        try (H2ExperienceStore from = H2ExperienceStore.openAt(source)) {
            put(from, "the receiver decides when a cancel is done", "com.example.Broker");
            put(from, "a timestamp of zero dates an event to 1970", "com.example.Feed");
            put(from, "an amend quantity is the total, not the remainder", "com.example.Order");
            blob = from.exportEntries(null, null);
            assertEquals(3, blob.size(), "everything is in the blob");
            exportedKeys = keysAcross(blob);
            // The anchor column is `symbol_fqn`, which is what the exporter
            // emits — not `symbol`, which is what the builder takes. A first
            // version of this assertion guessed the builder's name and went red,
            // which is the test doing its job: the schema is what the store
            // writes, never what the caller called it.
            assertTrue(exportedKeys.contains("summary") && exportedKeys.contains("symbol_fqn")
                    && exportedKeys.contains("body"),
                "the blob carries the content, not just identifiers: " + exportedKeys);
        }

        // THE DESTINATION HAS NOTHING. No story files, no substrate to rebuild
        // from — this is the production case, and the one a file-based recovery
        // cannot serve.
        assertTrue(Files.list(destination).findAny().isEmpty(),
            "the destination must genuinely start empty for this to prove anything");

        try (H2ExperienceStore to = H2ExperienceStore.openAt(destination)) {
            assertEquals(0L, to.count(), "the control: it really was empty");
            Map<String, Object> report = to.importEntries(blob);
            assertNotNull(report);
            assertEquals(3L, to.count(), "every row arrived");

            List<Map<String, Object>> back = to.exportEntries(null, null);
            assertEquals(keysAcross(blob), keysAcross(back),
                "the same COLUMNS came back. A row count would agree here even if the"
                    + " content had been dropped, because the exporter omits null fields");

            Set<String> before = blob.stream()
                .map(r -> String.valueOf(r.get("summary"))).collect(Collectors.toCollection(TreeSet::new));
            Set<String> after = back.stream()
                .map(r -> String.valueOf(r.get("summary"))).collect(Collectors.toCollection(TreeSet::new));
            assertEquals(before, after, "and the rows say the same things");
        }
    }

    /**
     * The same trip with no file store at either end.
     *
     * <p>An in-memory resident cannot be backed up — there is no file to copy,
     * and {@code StoreBackups} says so rather than writing an empty archive. The
     * blob is what it has instead, so it must work there or that resident has no
     * way to move its knowledge at all.</p>
     */
    @Test
    void the_blob_works_where_no_backup_can_be_taken() {
        List<Map<String, Object>> blob;
        try (H2ExperienceStore from = H2ExperienceStore.open(null)) {
            put(from, "market data carries no round trip you can read", "com.example.Tape");
            org.junit.jupiter.api.Assertions.assertNull(
                new StoreBackups(() -> from).before("wipe"),
                "an in-memory store has no file to copy, so the answer is an honest absence"
                    + " — which is exactly why the blob has to work here");
            blob = from.exportEntries(null, null);
        }
        try (H2ExperienceStore to = H2ExperienceStore.open(null)) {
            to.importEntries(blob);
            assertEquals(1L, to.count());
            assertEquals("market data carries no round trip you can read",
                to.exportEntries(null, null).get(0).get("summary"));
        }
    }
}
