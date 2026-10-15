package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * REL44-21 — an import on a machine with no embedder says its rows cannot be found by meaning.
 *
 * <p>Found by running the embedder tests with {@code -Djawata.embed.disabled=true}:
 * {@code ImportIndexesItsOwnRowsTest} failed, because the import's answer carried no
 * {@code unembedded} key at all. That key is the caller's only way to know whether the rows it
 * just handed over are findable, and its absence reads exactly like a store with nothing to
 * report. The {@code vectorise} verb already answers this case honestly; the import now answers
 * it the same way.</p>
 *
 * <p>It lives in this package for {@link EmbeddingService#resetForTests}, the seam
 * {@code NominationHonestyTest} uses to switch the embedder off and back on in one JVM. JUnit
 * runs one test at a time per JVM in this suite — no parallel execution is configured — so the
 * process-wide switch cannot reach a test running beside this one.</p>
 */
class AnImportWithNoEmbedderSaysSoTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;
    private String previous;

    @BeforeEach
    void setUp() {
        previous = System.getProperty(EmbeddingService.DISABLE_PROPERTY);
        System.setProperty(EmbeddingService.DISABLE_PROPERTY, "true");
        EmbeddingService.resetForTests();
        mapper = new ObjectMapper();
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
        if (previous == null) {
            System.clearProperty(EmbeddingService.DISABLE_PROPERTY);
        } else {
            System.setProperty(EmbeddingService.DISABLE_PROPERTY, previous);
        }
        EmbeddingService.resetForTests();
    }

    @Test
    @DisplayName("REL44-21: with no embedder the import says its rows cannot be found by meaning")
    @SuppressWarnings("unchecked")
    void theAnswerSaysTheRowsCannotBeFoundByMeaning() {
        assertTrue(!EmbeddingService.shared().available(),
            "the control: the switch must actually have turned the embedder off");

        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "import");
        ArrayNode rows = args.putArray("entries");
        for (int i = 1; i <= 2; i++) {
            ObjectNode row = rows.addObject();
            row.put("id", "5e1f0a2b-noembed-4000-8000-00000000000" + i);
            row.put("type", "lesson");
            row.put("summary", "an imported lesson number " + i + " about a held cancel");
            row.put("status", "accepted");
        }
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "import failed: " + r.getError());
        Map<String, Object> answer = (Map<String, Object>) r.getData();

        assertEquals(2, ((Number) answer.get("imported")).intValue(),
            () -> "the control: the rows did land, so the answer is about rows that exist: "
                + answer);
        assertEquals("unavailable", answer.get("embedding"),
            () -> "the answer must say there is no meaning index, rather than say nothing — an"
                + " absent key reads as 'nothing to report': " + answer);
        assertEquals("unknown", answer.get("unembedded"),
            () -> "and the remainder could not be counted, which is not zero: " + answer);
        assertTrue(String.valueOf(answer.get("embeddingNote")).contains("keyword"),
            () -> "and what the caller gets instead — recall on the keyword path: " + answer);
    }
}
