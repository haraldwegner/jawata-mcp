package org.jawata.mcp.knowledge;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 7, deliverable 2 — {@code recall} renders each anchor's LIVE file+line.
 *
 * <p>A job says what one MEMBER is for. Handing its reader the file of the enclosing type
 * and no line makes them go and find the member themselves, which is the work the row
 * exists to have already done; and a job whose member has been deleted while its type
 * stands must say so rather than report live code.</p>
 *
 * <h2>Why this is a separate class from {@link JobAnchorTest}</h2>
 *
 * <p>The plan names one test for both halves of deliverable 2. They are split because this
 * half needs a LOADED PROJECT and the other does not: folding it in would make every
 * store-only case in {@code JobAnchorTest} pay a project load, and this repository has
 * already recorded what a looping test that loads projects costs. {@code JobAnchorTest}
 * owns the move-on-rename half; this owns the location half. A DECLARED DEVIATION from the
 * plan's test naming, recorded rather than silent.</p>
 *
 * <h2>What each case can and cannot fail on</h2>
 *
 * <p>Every expected line is READ OUT OF THE FIXTURE rather than written as a literal, so
 * editing {@code Clean.java} moves the expectation with it. The load-bearing one is
 * {@link #the_type_and_its_member_are_different_lines}: without it, an implementation that
 * answered about the TYPE — which is exactly what this method did before Stage 7 — would
 * satisfy every other assertion here by supplying a line that happens to be in the right
 * file.</p>
 */
class JobLocationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String TYPE = "com.example.Clean";
    private static final String GREET = TYPE + "#greet";
    private static final String VANISHED = TYPE + "#renderMonthly";

    private JdtServiceImpl service;
    private Path source;

    @BeforeEach
    void setUp() throws Exception {
        // compile-clean is TWO files and exists to be a stable baseline. simple-maven is
        // the shared, monotonically growing fixture that has moved a counted population
        // four times in this sprint; a test that pins a LINE must not read it.
        service = helper.loadProjectCopy("compile-clean");
        source = service.getProjectRoot().resolve("src/main/java/com/example/Clean.java");
    }

    /** The 1-based line carrying {@code needle} — the same base the pointer renders. */
    private int lineOf(String needle) throws Exception {
        String[] lines = Files.readString(source).split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i + 1;
            }
        }
        throw new AssertionError("the fixture no longer contains: " + needle);
    }

    private static ExperienceEntry row(String type, String summary, String symbol) {
        return ExperienceEntry.of(
            SymbolFact.of(type, summary, Confidence.MEDIUM).symbol(symbol).build()).build();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> pointerFor(ExperienceStore store, String cue) {
        ExperienceRetrieval retrieval = ExperienceRetrieval.keywordOnly(store, () -> service);
        Map<String, Object> answer = retrieval.recall(new RecallQuery(cue, null, null, null, null));
        assertEquals(ExperienceRetrieval.RESULT_MATCH, answer.get("result"),
            "the cue is an exact FQN, which the keyword side answers deterministically;"
                + " got " + answer);
        List<Map<String, Object>> entries = (List<Map<String, Object>>) answer.get("entries");
        assertEquals(1, entries.size(), "one row was written for this cue; got " + entries);
        Map<String, Object> pointer = (Map<String, Object>) entries.get(0).get("resolved_pointer");
        assertTrue(pointer != null, "recall must render the anchor's location; entry was "
            + entries.get(0));
        return pointer;
    }

    @Test
    @DisplayName("a job's anchor renders the MEMBER's own file and line")
    void a_jobs_anchor_renders_the_members_own_file_and_line() throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.put(row("job", "Greets by name for the welcome banner.", GREET));

            Map<String, Object> p = pointerFor(store, GREET);

            assertEquals(true, p.get("resolved"), "the member is there: " + p);
            assertEquals("greet", p.get("member"),
                "the pointer names WHICH member it resolved, so a reader can tell a"
                    + " member-level answer from a type-level one: " + p);
            assertTrue(String.valueOf(p.get("file")).endsWith("Clean.java"),
                "the anchor's live file: " + p);
            assertEquals(lineOf("public String greet()"), p.get("line"),
                "the member's own declaration line, 1-based — read off the fixture rather"
                    + " than written here, so editing Clean.java moves the expectation");
        }
    }

    /**
     * THE DISCRIMINATOR. Before Stage 7 this method stripped {@code #member} and answered
     * about the type, so a member pointer carried the type's file and NO line at all.
     * Asserting the member's line alone would not catch a regression to that behaviour if
     * the type happened to be declared on the same line; asserting the two DIFFER does.
     */
    @Test
    @DisplayName("the type and its member resolve to DIFFERENT lines")
    void the_type_and_its_member_are_different_lines() throws Exception {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.put(row("job", "Greets by name for the welcome banner.", GREET));
            Map<String, Object> member = pointerFor(store, GREET);

            try (H2ExperienceStore typeStore = H2ExperienceStore.open(null)) {
                typeStore.put(row("job", "Holds the greeting behaviour for the banner.", TYPE));
                Map<String, Object> type = pointerFor(typeStore, TYPE);

                assertEquals(lineOf("public class Clean"), type.get("line"),
                    "a type-only anchor still gets a line — its own declaration: " + type);
                assertNull(type.get("member"),
                    "and no member key, because the anchor named none: " + type);
                assertNotEquals(type.get("line"), member.get("line"),
                    "THE POINT: the member's line is not the type's. An implementation that"
                        + " resolved the type and called it the member's location would"
                        + " satisfy every other assertion in this class");
            }
        }
    }

    /**
     * LOCATION LOST — the case the type-level answer could not see at all.
     *
     * <p>The type stands and the member is gone. Answering {@code resolved: true} here
     * reports a job as pointing at live code while its subject no longer exists, which is
     * the state {@code refresh} is meant to enqueue for re-cataloguing.</p>
     */
    @Test
    @DisplayName("a member that is gone is STALE, even though its type is still there")
    void a_member_that_is_gone_is_stale_even_though_its_type_stands() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.put(row("job", "Renders the month's figures for billing.", VANISHED));

            Map<String, Object> p = pointerFor(store, VANISHED);

            assertEquals(false, p.get("resolved"), "the member is not there: " + p);
            assertEquals(true, p.get("stale"),
                "and a job is a FACT, so the stale flag REACHES THE READER rather than"
                    + " being replaced by an experience's provenance note: " + p);
            assertTrue(String.valueOf(p.get("note")).contains("renderMonthly"),
                "the note names the member that went, not merely that something did: " + p);
            assertTrue(String.valueOf(p.get("file")).endsWith("Clean.java"),
                "the type's file is kept so a reader can go and look at what is left: " + p);
        }
    }

    /**
     * THE CONTROL for the case above, and it is what proves that case is about the JOB's
     * classification rather than about the pointer alone.
     *
     * <p>An EXPERIENCE anchored at the same vanished member gets its {@code stale} flag
     * STRIPPED and replaced — deliberately, because for experience the anchor is provenance
     * and the code being gone says nothing about whether the lesson still holds. Same
     * pointer, same workspace, opposite rendering, decided entirely by the row's type. Had
     * {@code job} been left out of {@code KnowledgeKind}'s address-bound set, the case above
     * would render like this one and the location-lost signal would read as history.</p>
     */
    @Test
    @DisplayName("an EXPERIENCE at the same gone member is provenance, not staleness")
    void an_experience_at_the_same_gone_member_is_provenance_not_staleness() {
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            store.put(row("lesson", "Renaming this broke the billing run twice.", VANISHED));

            Map<String, Object> p = pointerFor(store, VANISHED);

            assertFalse(p.containsKey("stale"),
                "an experience is never discounted for the code having moved on: " + p);
            assertTrue(String.valueOf(p.get("note")).contains("learned here"),
                "it says what the anchor now is — provenance: " + p);
        }
    }
}
