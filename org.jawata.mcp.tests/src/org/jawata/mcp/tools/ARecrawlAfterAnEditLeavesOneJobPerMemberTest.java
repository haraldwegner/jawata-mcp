package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Map;

import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.DescribedUnits;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.StoredEntry;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g, C14 clause 2 — <i>a crawl over a file edited after its first description ends with
 * no member carrying two descriptions.</i>
 *
 * <h2>Why this class exists, and what it is NOT</h2>
 *
 * <p>A C14 audit found this clause covered by nothing. Both of its halves were tested and neither
 * reached it: {@code DescribedUnitsTest#an_edited_unit_re_queues} is ledger-level — a unit whose
 * text changed hashes differently and so becomes outstanding again — and the one-job-per-member
 * gate is pinned separately by {@code OneJobPerMemberTest}. <b>Nothing composed them</b>, and the
 * composition is the clause: the duplication risk does not live in either half, it lives in
 * re-describing a member that already has a job.</p>
 *
 * <p>It was also declared a shortfall on the ground that the gate is not live in the running
 * resident. That reason is true of the LIVE-store clause and false of this one: this is a
 * behavioural property over a fixture project, and the harness below needs no resident at all.
 * Which is why it is built rather than carried.</p>
 *
 * <p>The member anchors are not derived here. They are read out of the closing verb's own
 * {@code undescribedMembers}, so this test and the product agree on what a member IS by
 * construction rather than by two implementations that could drift.</p>
 */
class ARecrawlAfterAnEditLeavesOneJobPerMemberTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private H2ExperienceStore store;
    private ExperienceTool tool;
    private ObjectMapper json;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("compile-clean");
        store = H2ExperienceStore.open(null);
        tool = new ExperienceTool(() -> new org.jawata.mcp.java.JavaSourceUnits(service), store);
        json = new ObjectMapper();
    }

    @AfterEach
    void tearDown() {
        if (store != null) {
            store.close();
        }
    }

    @Test
    @DisplayName("describing, editing the file, then describing again leaves one job per member")
    void aRecrawlAfterAnEditDoesNotDuplicate() throws IOException {
        String unit = aSourceUnit();

        // The product's own answer for what this unit's members are.
        List<String> members = undescribedMembersOf(unit);
        assertFalse(members.isEmpty(),
            "PROOF OF LIFE: the unit must declare members with no job, or every assertion below"
                + " runs over an empty list and proves nothing");

        for (String member : members) {
            describe(member, "First pass: answers one question this fixture's type exists to"
                + " answer, so a later reader need not open it.");
        }
        assertEquals(1, rowsFor(members.get(0)),
            "PROOF OF LIFE: one pass leaves exactly one row, so a second row below would be the"
                + " duplication this clause forbids rather than a pre-existing condition");

        // THE EDIT the clause names. A comment keeps the member set identical, so what changes
        // is the unit's TEXT and nothing else — which is what re-offers it.
        String before = DescribedUnits.hash(Files.readString(Path.of(unit)));
        Files.writeString(Path.of(unit), "\n// edited after its first description\n",
            StandardOpenOption.APPEND);
        String after = DescribedUnits.hash(Files.readString(Path.of(unit)));
        assertNotEquals(before, after,
            "PROOF OF LIFE: the edit must change the unit's hash, or there is no re-crawl to"
                + " test — the queue re-offers a unit precisely because its text hashes anew");

        // THE RE-CRAWL: the same members described again, as a second pass would.
        for (String member : members) {
            describe(member, "Second pass after the edit: answers the same question, written"
                + " afresh the way a later crawl would write it.");
        }

        for (String member : members) {
            assertEquals(1, rowsFor(member),
                () -> "after the re-crawl " + member + " must carry exactly ONE description —"
                    + " a second is the invisible backlog this clause exists to prevent, because"
                    + " the older row describes code that has since changed");
        }
    }

    /** A real production source file of the loaded fixture, taken from the service. */
    private String aSourceUnit() {
        return service.getAllJavaFiles().stream()
            .map(Object::toString)
            .filter(p -> !p.contains("/test/"))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "PROOF OF LIFE: the fixture must hold a source unit to describe"));
    }

    /**
     * The unit's members that carry no job, read from the closing verb's own answer rather than
     * derived here — so there is one statement of what a member is, not two.
     */
    private List<String> undescribedMembersOf(String unit) throws IOException {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "describe");
        a.put("action", "done");
        a.put("unit", unit);
        a.put("contentHash", DescribedUnits.hash(Files.readString(Path.of(unit))));
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), "got " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> named = (List<String>) data.get("undescribedMembers");
        return named == null ? List.of() : named;
    }

    private void describe(String member, String summary) {
        ObjectNode a = json.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("symbol", member);
        a.put("summary", summary);
        ToolResponse r = tool.execute(a);
        assertTrue(r.isSuccess(), () -> "describing " + member + " failed: " + r.getError());
    }

    /**
     * How many job rows the store holds for exactly this member — through the KEYED read,
     * 2026-10-08.
     *
     * <p>It was a capped {@code listEntries} page plus a Java re-filter. <b>This class carries
     * C14's clause that a re-crawl leaves no member with two descriptions</b>, and it was asking
     * the question through the read whose truncation is what produced the second description —
     * so the clause was proved by an instrument that shared the defect's blind spot. The window
     * of a thousand was never reached by this fixture, which is why nothing failed and nothing
     * was shown.</p>
     */
    private long rowsFor(String member) {
        return store.anchoredAt("job",
                org.jawata.mcp.knowledge.ExperienceStore.Anchor.SYMBOL, List.of(member))
            .getOrDefault(member, List.of()).size();
    }
}
