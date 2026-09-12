package org.jawata.mcp.knowledge;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A ROW RECORDS WHEN IT WAS REVIEWED, AND NOTHING DID BEFORE (Sprint 28f Stage 6, v21).
 *
 * <p><b>What was missing.</b> The {@code reviewed:} frontmatter stamp has existed since
 * 28c. It was parsed at ingest, used as the reseed's gate, and then DISCARDED — measured
 * over 1100 files, the only production readers are the parser and that gate. So no row
 * could answer "did anybody check this", and the story folder's export half could not
 * write the stamp back without inventing a date.</p>
 *
 * <p><b>Why the acceptance is the honest source.</b> Inventing the date at export time
 * would assert a review that may never have happened, which {@code docs/story-template.md}
 * names as forging the one field the reseed gate trusts. The transition to
 * {@code accepted} is a real review event in this product — the {@code /memorize} flow
 * puts a candidate in front of a cold reader and only what that reader passed reaches
 * the transition — so stamping THERE records something that occurred.</p>
 */
class ReviewStampTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
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

    /** Record one row and answer its id. */
    private String record(String summary) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "domain_fact");
        a.put("summary", summary);
        assertTrue(tool.execute(a).isSuccess());
        return store.all().stream()
            .filter(e -> summary.equals(e.summary()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("the row just recorded is not in the store"))
            .id();
    }

    private Instant stampOf(String id) {
        return store.all().stream()
            .filter(e -> id.equals(e.id()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no row " + id))
            .facets()
            .reviewedAt();
    }

    /**
     * THE CONTROL COMES FIRST, and it is what makes the rest mean anything: a row that
     * has not been accepted carries NO stamp. Without it, an implementation that stamped
     * every row at insert would satisfy every assertion below.
     */
    @Test
    void a_row_nobody_accepted_carries_no_stamp() {
        String id = record("a fact recorded and never put in front of a reader");
        assertNull(stampOf(id),
            "a candidate has not been reviewed, so it must carry no review date");
    }

    @Test
    void accepting_a_row_stamps_when_the_review_happened() {
        String id = record("a fact a reader passed");
        Instant before = Instant.now();

        assertTrue(store.setStatus(id, ExperienceEntry.ACCEPTED));

        Instant stamp = stampOf(id);
        assertNotNull(stamp, "the acceptance IS the review event, so it must be recorded");
        assertTrue(!stamp.isBefore(before.minusSeconds(5)),
            "the stamp must be the moment of acceptance, not an older or invented date"
                + " — got " + stamp + " against " + before);
    }

    /**
     * RE-ACCEPTING MUST NOT MOVE THE DATE, for the reason {@code retire} gives about its
     * own: the honest answer to "when was this checked" is the FIRST time, and silently
     * rewriting it would turn a fact into whatever the last no-op write happened to say.
     */
    @Test
    void re_accepting_keeps_the_review_that_really_happened() {
        String id = record("a fact accepted twice");
        assertTrue(store.setStatus(id, ExperienceEntry.ACCEPTED));
        Instant first = stampOf(id);
        assertNotNull(first);

        assertTrue(store.setStatus(id, ExperienceEntry.ACCEPTED));

        assertEquals(first, stampOf(id),
            "the second acceptance reviewed nothing, so it must not move the date");
    }

    /**
     * A DEMOTION KEEPS THE STAMP, AND THE NEXT ACCEPTANCE DOES NOT RE-WRITE IT. The
     * review genuinely happened; the row's status changing afterwards says nothing about
     * that. This is also the case that separates "stamp on accept" from "stamp on any
     * status write", which a single accept-path test cannot tell apart.
     */
    @Test
    void a_status_that_is_not_an_acceptance_never_writes_the_stamp() {
        String id = record("a fact accepted, demoted, and accepted again");
        assertTrue(store.setStatus(id, ExperienceEntry.ACCEPTED));
        Instant reviewed = stampOf(id);
        assertNotNull(reviewed);

        assertTrue(store.setStatus(id, "candidate"));
        assertEquals(reviewed, stampOf(id),
            "a demotion does not un-review a row, so the date stands");

        assertTrue(store.setStatus(id, ExperienceEntry.ACCEPTED));
        assertEquals(reviewed, stampOf(id),
            "and the re-acceptance still reports the review that really happened");
    }

    /** A status write on a row that was NEVER accepted stamps nothing at all. */
    @Test
    void another_status_on_an_unreviewed_row_leaves_it_unreviewed() {
        String id = record("a fact superseded without ever being accepted");
        assertTrue(store.setStatus(id, ExperienceEntry.SUPERSEDED));
        assertNull(stampOf(id),
            "superseding is not reviewing — the row still carries no review date");
    }
}
