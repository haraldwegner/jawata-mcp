package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c D10 — a reseed admits stamped stories only.
 *
 * <p>The check is deterministic on purpose. It asks whether a review HAPPENED,
 * never whether the story is any good: judgement belongs to the cold reader in
 * FRONT of ingest, because an ingest that judged content would be an
 * unreviewable intelligence in the one place nobody watches, handing down
 * verdicts nobody read.</p>
 *
 * <p><b>What this test does NOT cover, said out loud.</b> D10's measure has a
 * second half — that a story failing the cold reader is refused with the
 * reader's verdict recorded, and that one particular fluent-but-empty sentence
 * fails it. The cold reader is an AGENT. No unit test can assert its judgement,
 * and a fixture that pretended to would be asserting my imitation of a reader
 * rather than a reader. That half is a live check and is recorded as one.</p>
 */
class ReseedStampGateTest {

    private static Path story(Path dir, String name, boolean stamped) throws Exception {
        Path f = dir.resolve(name + ".md");
        Files.writeString(f, """
            ---
            name: %s
            description: "A quokka files nothing before the equinox, and the deadline moves."
            type: lesson
            verdict: worked
            situation: when the quokka filing window opens and the deadline is quoted from memory
            %s---

            **The punchline.** Something concrete enough to be wrong.
            """.formatted(name, stamped ? "reviewed: 2026-08-26\n" : ""));
        return f;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> skipped(Map<String, Object> report) {
        Object s = report.get("skipped");
        return s == null ? List.of() : (List<Map<String, Object>>) s;
    }

    /**
     * The pair in one test, because either half alone passes for the wrong
     * reason: a gate that refuses everything looks identical to a working one if
     * you only feed it the unstamped file.
     */
    @Test
    void a_reseed_takes_the_stamped_story_and_refuses_the_unstamped_one(
            @TempDir Path dir, @TempDir Path storeDir) throws Exception {
        story(dir, "reviewed-story", true);
        story(dir, "unreviewed-story", false);

        try (H2ExperienceStore store = H2ExperienceStore.open(storeDir)) {
            ExperienceMaintenance maintenance = new ExperienceMaintenance(store, fqn -> null);
            Map<String, Object> report = maintenance.load(dir, true, true);

            assertEquals(1L, store.count(),
                () -> "the reseed gate let through the wrong number of stories: " + report);

            List<Map<String, Object>> refusals = skipped(report);
            assertEquals(1, refusals.size(),
                () -> "exactly one refusal expected, got: " + refusals);
            String reason = String.valueOf(refusals.get(0).get("reason"));
            assertTrue(reason.startsWith("reviewed —"),
                () -> "the refusal must name the missing stamp: " + reason);
            assertTrue(reason.contains("kind=review"),
                () -> "the refusal must say how to earn the stamp, not just that it is"
                    + " missing — a gate that refuses without a route is a wall: " + reason);
            assertTrue(String.valueOf(refusals.get(0).get("source")).contains("unreviewed-story"),
                () -> "the refusal names the wrong file: " + refusals);
        }
    }

    /**
     * Sprint 28c D11 — the store answers WHERE a new story file belongs, and
     * refuses to guess when it cannot.
     *
     * <p>`/memorize` writes a file rather than a record, which leaves it one
     * question it cannot answer alone. A path an agent invents is the same
     * failure as a value invented to satisfy a rule, so the empty case must come
     * back null with a refusal — not with the most plausible directory, which is
     * indistinguishable from a correct answer at the call site.</p>
     */
    @Test
    void the_store_names_its_substrate_and_refuses_to_guess_one(
            @TempDir Path dir, @TempDir Path storeDir) throws Exception {
        story(dir, "reviewed-story", true);
        try (H2ExperienceStore store = H2ExperienceStore.open(storeDir)) {
            org.jawata.mcp.tools.ExperienceTool tool =
                new org.jawata.mcp.tools.ExperienceTool(() -> null, store);
            com.fasterxml.jackson.databind.ObjectMapper m =
                new com.fasterxml.jackson.databind.ObjectMapper();

            com.fasterxml.jackson.databind.node.ObjectNode a = m.createObjectNode();
            a.put("kind", "stats");
            @SuppressWarnings("unchecked")
            Map<String, Object> before = (Map<String, Object>) tool.execute(a).getData();
            @SuppressWarnings("unchecked")
            Map<String, Object> emptySub = (Map<String, Object>) before.get("substrate");
            assertNull(emptySub.get("root"),
                "an empty store offered a substrate directory — a plausible path here is"
                    + " indistinguishable from a correct one at the call site");

            new ExperienceMaintenance(store, fqn -> null).load(dir, true, true);
            @SuppressWarnings("unchecked")
            Map<String, Object> after = (Map<String, Object>) tool.execute(a).getData();
            @SuppressWarnings("unchecked")
            Map<String, Object> sub = (Map<String, Object>) after.get("substrate");
            assertEquals(dir.toString(), sub.get("root"),
                () -> "the substrate must be the directory the entries came from: " + sub);
        }
    }

    /**
     * An ordinary load makes no claim that anybody checked anything, so it takes
     * both. Loading is how a corpus of notes reaches the store; reseeding is how
     * the store is REBUILT, and only the second is a claim about review.
     *
     * <p>Without this, the stamp gate could be wired into every path and the test
     * above would still pass — and every existing caller of load() would have
     * broken silently.</p>
     */
    @Test
    void an_ordinary_load_still_takes_both(@TempDir Path dir, @TempDir Path storeDir)
            throws Exception {
        story(dir, "reviewed-story", true);
        story(dir, "unreviewed-story", false);

        try (H2ExperienceStore store = H2ExperienceStore.open(storeDir)) {
            ExperienceMaintenance maintenance = new ExperienceMaintenance(store, fqn -> null);
            Map<String, Object> report = maintenance.load(dir, true);
            assertEquals(2L, store.count(),
                () -> "load must not require the stamp — only reseed does: " + report);
        }
    }
}
