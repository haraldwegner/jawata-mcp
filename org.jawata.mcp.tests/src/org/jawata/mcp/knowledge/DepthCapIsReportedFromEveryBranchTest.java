package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#49 — <b>a crawl that stopped at the depth cap must say so, from every branch
 * that can stop there.</b>
 *
 * <p>The crawl follows a file's outgoing links from SIX places and only two of them reported
 * anything when the cap halted them. From the other four a crawl that stopped early was
 * indistinguishable from one that found nothing more — this sprint's recurring defect, in the
 * ingest path.</p>
 *
 * <p><b>The issue counted five branches with three silent; measured, it is six with four.</b>
 * The one it does not list is the TOMBSTONED branch, added after it was filed. That is the
 * argument for one shared decision rather than four repairs: a seventh branch added later
 * inherits the report instead of having to remember it — and the branch this test drives, the
 * index hub, is the one the issue calls the likeliest to hit the boundary, because a hub is
 * usually where a crawl starts.</p>
 */
class DepthCapIsReportedFromEveryBranchTest {

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ExperienceMaintenance maint() {
        return new ExperienceMaintenance(store, fqn -> null);
    }

    /**
     * An index hub pointing at a real story. {@code MEMORY.md} takes the contentless-hub
     * branch — one of the four that were silent — and its link is the one the cap stops.
     */
    private void anIndexPointingAtAStory(Path dir) throws IOException {
        Files.writeString(dir.resolve("MEMORY.md"),
            "# Memory index\n\n- [The linked story](linked.md) — a hook\n");
        Files.writeString(dir.resolve("linked.md"),
            "---\nname: the-linked-story\ndescription: a story reached only by following the"
                + " index's link\nmetadata:\n  type: domain_fact\n---\nIt has a body.\n");
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> skipped(Map<String, Object> report) {
        return (List<Map<String, Object>>) report.getOrDefault("skipped", List.of());
    }

    private static boolean reportsTheCap(Map<String, Object> report) {
        return skipped(report).stream()
            .anyMatch(row -> String.valueOf(row.get("reason")).contains("max-depth"));
    }

    @Test
    @DisplayName("mcp#49: an index hub stopped by the cap REPORTS its unfollowed links")
    void theHubBranchReportsWhatTheCapDropped(@TempDir Path dir) throws IOException {
        anIndexPointingAtAStory(dir);

        // maxDepth 0: the hub is visited at depth 0, so its links are already at the cap.
        Map<String, Object> report = maint().loadSources(List.of(dir), false, 0, 100, 1_000_000L);

        assertAll(
            () -> assertTrue(reportsTheCap(report),
                "the crawl stopped at the cap and must say so — otherwise 'the index led"
                    + " nowhere' and 'I did not follow the index' are the same report. got: "
                    + skipped(report)),
            () -> assertTrue(skipped(report).stream().anyMatch(r ->
                    String.valueOf(r.get("source")).endsWith("MEMORY.md")),
                "and it must name the file whose links were dropped; got: " + skipped(report)),
            () -> assertTrue(skipped(report).stream().anyMatch(r ->
                    String.valueOf(r.get("reason")).contains("1 link(s) not followed")),
                "and how many, so a reader knows the size of what they are not seeing; got: "
                    + skipped(report)));
    }

    @Test
    @DisplayName("mcp#49's control: below the cap the links are FOLLOWED, and nothing is reported")
    void belowTheCapTheLinksAreActuallyFollowed(@TempDir Path dir) throws IOException {
        anIndexPointingAtAStory(dir);

        // One level of headroom: the same corpus, the same hub branch, cap not reached.
        Map<String, Object> report = maint().loadSources(List.of(dir), false, 1, 100, 1_000_000L);

        assertAll(
            // Proof the link is real and reachable — without it, case one could be passing
            // over a corpus whose link never resolved, and would report the cap for a link
            // that was never followable in the first place.
            () -> assertEquals(1, report.get("loaded"),
                "the linked story is reached and ingested when the cap allows it; got: "
                    + report),
            () -> assertFalse(reportsTheCap(report),
                "and nothing is reported as capped — a report that appeared at every depth"
                    + " would be noise rather than a signal; got: " + skipped(report)));
    }
}
