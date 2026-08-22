package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the FROZEN acceptance fixture for anchor-independent experience — the
 * instrument this sprint is judged by.
 *
 * <p>The fixture was committed before any of the retrieval code it measures
 * existed, and was measured against the abandoned build at that moment: five
 * anchorless records stored, <b>zero of five</b> retrievable from their
 * situation. That 0/5 is the number the work has to move, and it means
 * something only while the questions stay exactly as frozen.</p>
 *
 * <p><b>What makes this fixture able to fail.</b> Every positive question is
 * worded from its record's SITUATION and shares no content word with that
 * record's PRINCIPLE. The store indexes the principle, so a question that
 * borrowed even one principle word could be answered by the BROKEN store and
 * would prove nothing. That property is not a convention to be remembered — it
 * is asserted here, per question, so an edit that quietly relaxes it goes red
 * instead of silently disarming the gate.</p>
 *
 * <p><b>On the digest.</b> Changing any expected result changes it and this
 * test goes red. That is the point: an instrument adjusted after seeing the
 * reading measures nothing. Re-pinning is a deliberate re-freeze and needs the
 * owner's word, never a green-tests reflex.</p>
 */
class AcceptanceFixtureTest {

    /**
     * SHA-256 over the canonical projection built by {@link #canonical()}.
     *
     * <p>Frozen 2026-08-22, before any rescue retrieval code existed.</p>
     */
    private static final String FROZEN_DIGEST =
        "39f7dea282485dc74eee12fec11deb276d2a37816ac8bdb63c0c0971dee34d2a";

    /** Words too common to say anything about topical overlap either way. */
    private static final Set<String> STOP = Set.of(
        "the", "and", "for", "with", "that", "this", "our", "was", "were", "are",
        "its", "into", "from", "have", "has", "had", "not", "but", "can", "when",
        "where", "which", "who", "what", "how", "does", "did", "will", "would",
        "should", "must", "may", "there", "here", "than", "then", "out", "off",
        "over", "under", "such", "been", "being", "any", "all", "one", "two");

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * Finds a committed fixture by walking up from the working directory.
     *
     * <p>Fails loudly rather than skipping when it cannot be found. A gate that
     * skips when its own input is missing reports green while measuring
     * nothing; this project already lost two sprints of a headline gate to
     * exactly that, so an absent fixture is a failure here, never a skip.</p>
     */
    private static Path fixture(String name) {
        List<String> tried = new ArrayList<>();
        Path dir = Paths.get("").toAbsolutePath();
        for (int up = 0; up < 6 && dir != null; up++, dir = dir.getParent()) {
            Path candidate = dir.resolve("build").resolve("acceptance").resolve(name);
            tried.add(candidate.toString());
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        fail("the frozen acceptance fixture '" + name + "' was not found; looked in: " + tried);
        throw new IllegalStateException("unreachable");
    }

    private static JsonNode read(String name) throws Exception {
        return JSON.readTree(Files.readString(fixture(name), StandardCharsets.UTF_8));
    }

    /** Content stems of a phrase: lowercase, 5-char stems, stopwords and short words dropped. */
    private static Set<String> stems(String text) {
        Set<String> out = new HashSet<>();
        Matcher m = Pattern.compile("[a-z0-9]+").matcher(text.toLowerCase(Locale.ROOT));
        while (m.find()) {
            String w = m.group();
            if (w.length() > 2 && !STOP.contains(w)) {
                out.add(w.length() > 5 ? w.substring(0, 5) : w);
            }
        }
        return out;
    }

    @Test
    void the_fixture_has_exactly_the_frozen_shape() throws Exception {
        JsonNode a = read("anchorless-retrieval.json");
        assertEquals(5, a.get("records").size(), "five synthetic anchorless records");
        assertEquals(5, a.get("positive_questions").size(), "five positive questions");
        assertEquals(7, a.get("unrelated_questions").size(), "seven unrelated questions");
        assertEquals(5, read("catalogue-questions.json").get("questions").size(),
            "five catalogue questions");
    }

    @Test
    void no_fixture_record_carries_a_code_anchor() throws Exception {
        Set<String> banned = Set.of("symbol", "symbols", "package", "packages",
            "operation", "snippet", "snippets", "cut_type", "cut_method",
            "external_system", "source_ref");
        for (JsonNode r : read("anchorless-retrieval.json").get("records")) {
            r.fieldNames().forEachRemaining(f -> assertFalse(banned.contains(f),
                "record " + r.get("id").asText() + " carries the code-anchor field '" + f
                    + "' — the sprint fails by definition if an expected entry is made"
                    + " reachable by a symbol, package, operation or snippet"));
        }
    }

    @Test
    void every_positive_question_is_answerable_only_from_the_situation() throws Exception {
        JsonNode a = read("anchorless-retrieval.json");
        for (JsonNode q : a.get("positive_questions")) {
            String id = q.get("expect_id").asText();
            JsonNode rec = null;
            for (JsonNode r : a.get("records")) {
                if (id.equals(r.get("id").asText())) {
                    rec = r;
                }
            }
            assertTrue(rec != null, "positive question expects unknown record " + id);

            Set<String> question = stems(q.get("question").asText());
            Set<String> situation = new HashSet<>(stems(rec.get("situation").asText()));
            Set<String> principle = new HashSet<>(stems(rec.get("summary").asText()));
            situation.retainAll(question);
            principle.retainAll(question);

            assertTrue(situation.size() >= 3,
                "question for " + id + " shares only " + situation.size()
                    + " content stems with its SITUATION " + situation
                    + " — too few for the question to be about that situation at all");
            assertTrue(principle.isEmpty(),
                "question for " + id + " LEAKS into its PRINCIPLE via " + principle
                    + " — the store indexes the principle, so this question could be"
                    + " answered by the broken store and would prove nothing");
        }
    }

    @Test
    void no_catalogue_question_names_the_pattern_it_must_return() throws Exception {
        for (JsonNode q : read("catalogue-questions.json").get("questions")) {
            String slug = q.get("expect_slug").asText();
            String question = q.get("question").asText().toLowerCase(Locale.ROOT);
            for (String part : slug.split("-")) {
                assertFalse(question.contains(part),
                    "the question for '" + slug + "' contains '" + part + "' — a question"
                        + " naming its own pattern is answerable by string matching");
            }
        }
    }

    @Test
    void the_frozen_expectations_are_unchanged() throws Exception {
        MessageDigest sha = MessageDigest.getInstance("SHA-256");
        byte[] digest = sha.digest(canonical().getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : digest) {
            hex.append(String.format("%02x", b));
        }
        assertEquals(FROZEN_DIGEST, hex.toString(),
            "an expected result in the acceptance fixture CHANGED. This test is doing its"
                + " job: the fixture is the instrument the sprint is measured by, and an"
                + " instrument adjusted after seeing the reading measures nothing."
                + " Re-pinning the digest is a deliberate re-freeze that needs the owner's"
                + " word — never a reflex to make the suite green.");
    }

    /** The exact text the digest covers: every id, question and expected result, in file order. */
    private static String canonical() throws Exception {
        JsonNode a = read("anchorless-retrieval.json");
        List<String> lines = new ArrayList<>();
        for (JsonNode r : a.get("records")) {
            lines.add("R|" + r.get("id").asText() + "|" + r.get("situation").asText()
                + "|" + r.get("summary").asText() + "|" + r.get("verdict").asText());
        }
        for (JsonNode q : a.get("positive_questions")) {
            lines.add("P|" + q.get("question").asText() + "|" + q.get("expect_id").asText());
        }
        for (JsonNode q : a.get("unrelated_questions")) {
            lines.add("N|" + q.asText());
        }
        for (JsonNode q : read("catalogue-questions.json").get("questions")) {
            lines.add("C|" + q.get("question").asText() + "|" + q.get("expect_slug").asText());
        }
        return String.join("\n", lines);
    }
}
