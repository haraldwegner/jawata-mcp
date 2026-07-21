package org.jawata.mcp.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 27 C1 — the tokenizer parity gate.
 *
 * <p>The goldens were produced by the REFERENCE implementation
 * (sentence-transformers) and committed at C0; this test asserts our owned
 * tokenizer reproduces them exactly. Exactness, not closeness: one differing
 * word piece yields a different vector, so this gate is what the whole
 * C2 cosine-parity claim stands on.</p>
 *
 * <p>The cases deliberately include the shapes that break naive
 * re-implementations — accented Latin, CJK, emoji, an empty string, a word
 * longer than the reference's 100-character limit, and one input that exceeds
 * the 256-token window so truncation is actually exercised.</p>
 */
class WordPieceTokenizerGoldenTest {

    private static final String GOLDENS =
        "/test-resources/embed-goldens/golden-tokenizations.json";

    private static JsonNode goldens() throws Exception {
        try (InputStream in = WordPieceTokenizerGoldenTest.class.getResourceAsStream(GOLDENS)) {
            assertNotNull(in, "golden tokenizations must ship with the test fragment: " + GOLDENS);
            return new ObjectMapper().readTree(in);
        }
    }

    @Test
    void the_bundled_vocabulary_is_the_reference_vocabulary() {
        WordPieceTokenizer t = WordPieceTokenizer.bundled();
        assertEquals(30522, t.vocabSize(),
            "all-MiniLM-L6-v2 ships a 30522-entry WordPiece vocabulary");
    }

    @Test
    void every_golden_case_tokenizes_exactly_as_the_reference() throws Exception {
        JsonNode g = goldens();
        int maxLength = g.get("max_length").asInt();
        WordPieceTokenizer tok = WordPieceTokenizer.bundled();

        List<String> failures = new ArrayList<>();
        int cases = 0;
        for (JsonNode c : g.get("cases")) {
            cases++;
            String text = c.get("text").asText();
            int[] expected = new int[c.get("input_ids").size()];
            for (int i = 0; i < expected.length; i++) {
                expected[i] = c.get("input_ids").get(i).asInt();
            }
            int[] actual = tok.encode(text, maxLength);
            if (!java.util.Arrays.equals(expected, actual)) {
                failures.add("  case " + cases + " " + preview(text)
                    + "\n    expected " + java.util.Arrays.toString(trim(expected))
                    + "\n    actual   " + java.util.Arrays.toString(trim(actual)));
            }
        }
        assertTrue(cases >= 20, "the golden set must be broad, got " + cases + " cases");
        assertTrue(failures.isEmpty(),
            failures.size() + " of " + cases + " golden cases diverge:\n"
            + String.join("\n", failures));
    }

    /**
     * The truncation path specifically: a golden case longer than the window
     * must land at EXACTLY max_length and still end with [SEP]. Without a case
     * that genuinely overflows, a wrongly-truncating tokenizer passes the gate
     * (the C0 audit caught exactly that - the 2000-character case does not
     * truncate, because WordPiece maps any >100-char word to a single [UNK]).
     */
    @Test
    void the_over_long_case_truncates_to_the_window_and_keeps_its_terminator() throws Exception {
        JsonNode g = goldens();
        int maxLength = g.get("max_length").asInt();
        WordPieceTokenizer tok = WordPieceTokenizer.bundled();

        int truncatedCases = 0;
        for (JsonNode c : g.get("cases")) {
            if (!c.get("truncated").asBoolean()) {
                continue;
            }
            truncatedCases++;
            int[] ids = tok.encode(c.get("text").asText(), maxLength);
            assertEquals(maxLength, ids.length, "a truncated case fills the window exactly");
            assertEquals(c.get("input_ids").get(maxLength - 1).asInt(), ids[maxLength - 1],
                "the closing [SEP] survives truncation");
        }
        assertTrue(truncatedCases >= 1,
            "at least one golden case must exceed max_length, or truncation is untested");
    }

    @Test
    void an_empty_cue_still_produces_the_special_tokens() {
        WordPieceTokenizer tok = WordPieceTokenizer.bundled();
        assertEquals(2, tok.encode("", 256).length, "[CLS] + [SEP] and nothing between");
        assertTrue(tok.tokenize("").isEmpty());
    }

    @Test
    void a_word_beyond_the_reference_limit_becomes_one_unknown() {
        WordPieceTokenizer tok = WordPieceTokenizer.bundled();
        List<String> pieces = tok.tokenize("x".repeat(WordPieceTokenizer.MAX_INPUT_CHARS_PER_WORD + 1));
        assertEquals(List.of("[UNK]"), pieces,
            "over-long words are not decomposed, they are unknown");
    }

    @Test
    void normalization_strips_marks_but_keeps_characters_that_have_none() {
        // Pinned because it is the subtle half of strip_accents: the umlaut is a
        // combining mark and goes; the eszett has no decomposition and stays.
        String n = WordPieceTokenizer.normalize("München ß");
        assertEquals("munchen ß", n);
    }

    private static int[] trim(int[] ids) {
        return ids.length <= 24 ? ids : java.util.Arrays.copyOf(ids, 24);
    }

    private static String preview(String text) {
        String one = text.replace("\n", " ");
        return "\"" + (one.length() <= 48 ? one : one.substring(0, 48) + "…") + "\"";
    }
}
