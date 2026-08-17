package org.jawata.mcp.field;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28b D1, the trust gate: a corpus of would-be leaks — paths, error
 * messages, fully-qualified names, repo identity, secret-shaped strings — is
 * pushed through EVERY input of the recording path (tool name, kind argument,
 * error code, error message), and the serialized pile must contain none of
 * them. This is the spec measure verbatim: shapes, never content.
 */
class FieldLeakCorpusTest {

    private static final String CORPUS = "/test-resources/field/leak-corpus.txt";

    private static List<String> corpus() throws Exception {
        try (InputStream in = FieldLeakCorpusTest.class.getResourceAsStream(CORPUS)) {
            assertNotNull(in, "leak corpus must ship with the test fragment: " + CORPUS);
            List<String> lines = new ArrayList<>();
            for (String line : new String(in.readAllBytes(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.isBlank() && !line.startsWith("#")) {
                    lines.add(line);
                }
            }
            assertTrue(lines.size() >= 15, "the corpus is supposed to be substantial");
            return lines;
        }
    }

    @Test
    void no_corpus_string_survives_into_the_stored_pile(@TempDir Path dir) throws Exception {
        List<String> corpus = corpus();
        FieldPile pile = new FieldPile(dir);
        ClientDirectory clients = new ClientDirectory();
        FieldRecorder recorder = new FieldRecorder(pile, clients, "3.11.0");
        ObjectMapper mapper = new ObjectMapper();

        for (String leak : corpus) {
            // The leak arrives on every channel an event's inputs can arrive on.
            clients.record("s", leak);
            recorder.onCall("s", leak,
                mapper.createObjectNode().put("kind", leak),
                ToolResponse.error(leak, leak, leak), 42);
            recorder.onCall("s", leak,
                mapper.createObjectNode().put("action", leak),
                ToolResponse.internalError(new IllegalStateException(leak)), 42);
        }

        String stored = Files.readString(pile.file());
        for (String leak : corpus) {
            assertFalse(stored.contains(leak),
                "corpus string leaked into the pile: " + leak);
            // C1 audit F2: a content-preserving transform (punctuation →
            // underscores) must not smuggle the leak past the substring check.
            String transformed = leak.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            assertFalse(stored.contains(transformed),
                "corpus string leaked modulo punctuation: " + transformed);
        }
        // And the pile is not merely empty — every event was recorded, as shapes.
        assertTrue(pile.fold().size() == corpus.size() * 2,
            "sanitizing must coerce, not drop: " + pile.fold().size()
                + " events for " + corpus.size() + " corpus lines x2");
    }

    @Test
    void a_token_cannot_be_constructed_from_free_text() {
        assertTrue(Token.of("/home/harald/secret.java") == Token.UNKNOWN);
        assertTrue(Token.of("com.acme.Billing#run") == Token.UNKNOWN);
        assertTrue(Token.of("Cannot resolve symbol 'X'") == Token.UNKNOWN);
        assertTrue(Token.of(null) == Token.UNKNOWN);
        assertFalse(Token.of("search_symbols") == Token.UNKNOWN);
        assertFalse(Token.of("PROJECT_NOT_LOADED") == Token.UNKNOWN);
    }
}
