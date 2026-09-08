package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#57 — <b>a row that declared no language stops claiming to be Java.</b>
 *
 * <p>The three store write sites defaulted a null language to {@code "java"}, so
 * {@code by_language} in {@code stats} counted every unclassified row as Java: a corpus about
 * hardware, brokers and process reported as 100% {@code java}. That is the issue's THIRD harm
 * clause, and the one an earlier disposition of mine missed while refuting the other two.</p>
 *
 * <p><b>The gate does not move, and that is the whole safety argument.</b>
 * {@code StoredEntry.isJavaResolvable()} reads {@code language == null || isBlank() ||
 * "java"}, so null and {@code "java"} are indistinguishable to it at every call site — the
 * JDT maintenance exemption, the anchor backfill and the analogy lane included. Only what the
 * row CLAIMS about itself changes.</p>
 *
 * <p><b>Why the earlier attempt was different and was reverted</b> ({@code faafe689} →
 * {@code 9d43005d}): it made prose rows {@code markdown}, i.e. NON-Java, which does move the
 * gate — through the backfill, whose job is to discover anchors in prose and which then
 * skipped exactly its own candidates. The suite caught it as {@code {checked=0, anchored=0}}.
 * Storing null keeps every gate where it was, which is why this one is safe.</p>
 */
class UnclassifiedLanguageStaysNullTest {

    private ObjectMapper mapper;
    private ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void record(String summary, String language) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "record");
        args.put("type", "lesson");
        args.put("summary", summary);
        args.put("situation", "when a stored row declares no language of its own");
        args.put("verdict", "worked");
        args.put("status", "accepted");
        if (language != null) {
            args.put("language", language);
        }
        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess(), "the record must land: " + resp);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> byLanguage() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "stats");
        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess(), "stats answers: " + resp);
        Map<String, Object> data = (Map<String, Object>) resp.getData();
        Object bl = data.get("by_language");
        assertNotNull(bl, "stats carries by_language: " + data);
        return (Map<String, Object>) bl;
    }

    private static long count(Map<String, Object> byLanguage, String key) {
        Object v = byLanguage.get(key);
        return v instanceof Number ? ((Number) v).longValue() : 0L;
    }

    /**
     * Both directions in one run. The declared-language arm is what stops this being a blanket
     * change: a row that DOES say what it is about must still be stored as what it said, and
     * an assertion that only checked the unclassified row would pass against a write path that
     * had stopped storing the column at all.
     */
    @Test
    @DisplayName("an unclassified row is not counted as java, and a declared language still is")
    void unclassifiedIsNotJavaAndDeclaredSurvives() {
        record("A row that says nothing about what language it concerns.", null);
        record("A row that declares rust, and must be stored as rust.", "rust");

        Map<String, Object> counts = byLanguage();

        assertAll(
            () -> assertEquals(0L, count(counts, "java"),
                "no row here declared java, so nothing may be counted under it: " + counts),
            () -> assertEquals(1L, count(counts, "rust"),
                "a declared language passes through untouched: " + counts));
    }

    /**
     * The safety claim, asserted rather than argued: the unclassified row is STILL the thing
     * JDT maintenance may judge, exactly as it was when the column said "java". Without this,
     * the change above could be read as having exempted those rows from maintenance — which is
     * what the reverted attempt actually did.
     */
    @Test
    @DisplayName("an unclassified row stays JDT-resolvable — null and \"java\" gate alike")
    void theMaintenanceGateDoesNotMove() {
        record("A row that says nothing about what language it concerns.", null);

        StoredEntry stored = store.all().stream()
            .filter(e -> e.summary().startsWith("A row that says nothing"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the recorded row is in the store"));

        assertAll(
            () -> assertEquals(null, stored.language(),
                "the row stores null rather than claiming java"),
            () -> assertTrue(stored.isJavaResolvable(),
                "and null still gates identically to \"java\" — no maintenance exemption was"
                    + " created, which is the difference from the reverted cure"));
    }
}
