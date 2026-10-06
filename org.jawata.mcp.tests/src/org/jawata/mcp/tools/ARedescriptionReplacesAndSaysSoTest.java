package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.StoredEntry;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28g S14 / D30 — through the DOOR a describing agent actually knocks on: a second
 * description of a member replaces the first, the answer names what it replaced, and an
 * anchor that already carries several is refused rather than collapsed.
 *
 * <p><b>Why the door and not the store.</b> {@code OneJobPerMemberTest} pins the store's
 * rewrite. Nothing there proves the record verb ROUTES through it — and that gap is the
 * shape this sprint has shipped repeatedly: a capability built, correct, and reached by
 * nothing. The defect being closed was measured at the door, by an agent following an
 * "undescribed members" list, so the door is where it has to be shown closed.</p>
 *
 * <p><b>The refusal is deliberately not a collapse.</b> jawata-mcp#85 counts four groups
 * that share one bare key and are NOT duplicates — overloads and constructors. Picking a
 * survivor among several descriptions is a judgement about which is right, and doing it as
 * a side effect of an unrelated write would destroy the loser with no backup. So the write
 * declines and says both ways out.</p>
 */
class ARedescriptionReplacesAndSaysSoTest {

    private static final String MEMBER = "com.example.d30.Dial#angle";

    private final ObjectMapper mapper = new ObjectMapper();

    private ToolResponse describe(ExperienceStore store, String summary, String symbol) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", "record");
        a.put("type", "job");
        a.put("summary", summary);
        a.put("symbol", symbol);
        return new ExperienceTool(() -> null, store).execute(a);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (Map<String, Object>) r.getData();
    }

    private static List<StoredEntry> jobsFor(ExperienceStore store, String member) {
        return store.listEntries("job", null, member, null, 50).stream()
            .filter(e -> member.equals(e.symbolFqn()))
            .toList();
    }

    @Test
    @DisplayName("D30: a re-description through the record verb replaces, and the answer NAMES the row it replaced")
    void aSecondDescriptionReplacesTheFirstAndIsReported(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            String first = "Answers the needle's angle.";
            String second = "Answers the needle's angle in degrees clockwise from rest, so a "
                + "caller rendering the dial needs no second call to find the origin.";

            Map<String, Object> firstWrite = data(describe(store, first, MEMBER));
            Map<String, Object> secondWrite = data(describe(store, second, MEMBER));

            @SuppressWarnings("unchecked")
            Map<String, Object> replaced = (Map<String, Object>) secondWrite.get("replaced");

            assertAll(
                () -> assertFalse(firstWrite.containsKey("replaced"),
                    () -> "THE CONTROL: the FIRST description replaced nothing, so the key "
                        + "must be absent — otherwise the assertion below is satisfied by a "
                        + "verb that reports a replacement on every write: " + firstWrite),
                () -> assertNotNull(replaced,
                    () -> "the second write must say what it replaced. Reported only as "
                        + "stored, a replacement is indistinguishable from a fresh row, "
                        + "which is how a caller never learns it overwrote somebody's "
                        + "wording: " + secondWrite),
                () -> assertEquals(firstWrite.get("id"), replaced.get("id"),
                    "and it names the FIRST row by id, not some other one"),
                () -> assertEquals(first, replaced.get("summary"),
                    "carrying the text that is now gone, which is the only place a reader "
                        + "can still see what was overwritten"),
                // THE TWO BELOW ARE WHAT CARRY THIS CASE, and a mutation is what showed it.
                // Making the gate DETECT the existing row and then write anyway leaves every
                // `replaced` assertion above GREEN — the report was still correct, about an
                // action that had not happened. A report can be right about nothing. These
                // two read the store instead of the response, so only they can tell.
                () -> assertEquals(firstWrite.get("id"), secondWrite.get("id"),
                    "the row identity is kept — links point at it and the review ledger "
                        + "names it"),
                () -> assertEquals(1, jobsFor(store, MEMBER).size(),
                    () -> "ONE row, which is the whole deliverable: " + jobsFor(store, MEMBER)),
                () -> assertEquals(second, jobsFor(store, MEMBER).get(0).summary(),
                    "and it carries the newer description"));
        }
    }

    /**
     * The ambiguous case. Built by writing two rows STRAIGHT TO THE STORE, because the door
     * is what now prevents a second one — so the only way to reach this branch is to
     * reproduce the state the store is already in for 73 members.
     */
    @Test
    @DisplayName("D30: an anchor already carrying several descriptions is refused, not collapsed")
    void anAmbiguousAnchorIsRefusedAndBothWaysOutAreNamed(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            store.put(org.jawata.mcp.knowledge.ExperienceEntry.of(
                org.jawata.mcp.knowledge.SymbolFact.of("job", "the older wording",
                    org.jawata.mcp.knowledge.Confidence.MEDIUM).symbol(MEMBER).build()).build());
            store.put(org.jawata.mcp.knowledge.ExperienceEntry.of(
                org.jawata.mcp.knowledge.SymbolFact.of("job", "the other older wording",
                    org.jawata.mcp.knowledge.Confidence.MEDIUM).symbol(MEMBER).build()).build());

            // THE SUMMARY HAS TO BE A REAL CLAIM, and the first version of this test is why
            // that is said here. "a third attempt" was refused by the ADMISSION gate — too
            // short to be a claim — so the write never reached D30's branch and this case
            // was measuring the wrong refusal. It failed only because the assertions below
            // read the refusal's WORDING; a bare isSuccess check would have passed while
            // proving nothing. Sixth instance in this sprint of a refusal shadowed by an
            // earlier one.
            ToolResponse refused = describe(store,
                "Answers the needle's angle in degrees clockwise from rest.", MEMBER);
            String message = String.valueOf(refused.getError());

            assertAll(
                () -> assertFalse(refused.isSuccess(),
                    () -> "the write is REFUSED: there is no single description to replace, "
                        + "and adding a third is the defect. Got: " + refused.getData()),
                () -> assertTrue(message.contains("2 descriptions"),
                    () -> "the refusal says how many it found, so a reader knows whether "
                        + "this is a pair or a pile: " + message),
                () -> assertTrue(message.contains("backup"),
                    () -> "and names why it will not choose for them — collapsing is "
                        + "destructive and needs a backup first: " + message),
                () -> assertTrue(message.contains("parameter types"),
                    () -> "and the OTHER way out, which is the case jawata-mcp#85 counts "
                        + "four of: these may be overloads sharing a bare key, and naming "
                        + "the types gives each its own row: " + message),
                () -> assertEquals(2, jobsFor(store, MEMBER).size(),
                    () -> "and nothing was written or destroyed — a refusal that quietly "
                        + "collapsed them would be worse than the duplicate: "
                        + jobsFor(store, MEMBER)));
        }
    }

    /**
     * The scope that is NOT a member. An {@code area} is anchored to a package and one
     * package legitimately carries one, so the member rule must not reach it — otherwise
     * re-describing a package would start replacing rows keyed on a different column.
     */
    @Test
    @DisplayName("D30: the rule is scoped to a member's description, not to every code-lane row")
    void anAreaIsNotTreatedAsAMember(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.open(dir)) {
            ObjectNode a = mapper.createObjectNode();
            a.put("kind", "record");
            a.put("type", "area");
            a.put("summary", "What this package is for, in a sentence a reader can act on.");
            a.putArray("packages").add("com.example.d30");

            Map<String, Object> written = data(new ExperienceTool(() -> null, store).execute(a));

            assertFalse(written.containsKey("replaced"),
                () -> "an area write reports no replacement: it is keyed on a package, and "
                    + "the one-per-member rule is about members. KnowledgeLane.CODE_TYPES "
                    + "holds both, which is exactly why the gate reads `job` and not that "
                    + "set: " + written);
        }
    }
}
