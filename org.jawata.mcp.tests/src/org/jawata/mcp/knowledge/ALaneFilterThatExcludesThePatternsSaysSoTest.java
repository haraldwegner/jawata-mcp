package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28g S3 / D7h — a lane filter that cannot return a catalogue pattern SAYS SO.
 *
 * <p>The field report: eight lookups filtered {@code lane=domain} and came back with the
 * project's own domain facts and no patterns whatever. Nothing was broken — a catalogue
 * {@code reference} is a borrowed EXPERIENCE by {@link KnowledgeLane#of}'s own ruling — and
 * nothing said so, so <strong>the caller could not tell "no pattern fits" from "patterns were
 * never searched"</strong>. Those two answers want opposite next moves, and the store's own
 * steering tells a caller to match one of the rows it returned.</p>
 *
 * <p>Driven through the TOOL rather than the retrieval object, because the complaint is about
 * what a caller saw. A note the retrieval produced and the tool dropped would read identically
 * from inside.</p>
 */
class ALaneFilterThatExcludesThePatternsSaysSoTest {

    private static final String ANCHOR = "com.example.Thermostat#selectedTemperature";

    private H2ExperienceStore store;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        objectMapper = new ObjectMapper();

        // ONE of ours, DOMAIN by its type.
        store.put(ExperienceEntry.of(
                SymbolFact.of("domain_fact", "the setpoint is read from the dial, not the probe",
                        Confidence.MEDIUM)
                    .symbol(ANCHOR)
                    .build())
            .status(ExperienceEntry.ACCEPTED)
            .build());

        // ONE borrowed from the catalogue, on the SAME anchor. This row is what makes the note
        // truthful rather than decorative: without it the claim "patterns were excluded"
        // would describe behaviour nothing in the test exercises.
        store.put(ExperienceEntry.of(
                SymbolFact.of("reference", "Strategy: vary an algorithm behind one interface",
                        Confidence.MEDIUM)
                    .symbol(ANCHOR)
                    .build())
            .status(ExperienceEntry.ACCEPTED)
            .provenanceKind(CatalogueManifest.PROVENANCE)
            .build());
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    @Test
    @DisplayName("the exclusion is REAL: lane=domain returns our fact and never the pattern")
    void theExclusionTheNoteDescribesActuallyHappens() {
        String domainOnly = renderOf(recall(ANCHOR, "domain"));
        assertTrue(domainOnly.contains("setpoint is read from the dial"),
            () -> "our own domain fact must still answer: " + domainOnly);
        assertFalse(domainOnly.contains("Strategy: vary an algorithm"),
            () -> "and the catalogue pattern must be absent — that absence is the defect's "
                + "subject, and asserting the note without it would describe nothing: "
                + domainOnly);

        // THE CONTROL that makes the above mean something: the pattern IS in the store and IS
        // reachable, through the lane the note names.
        String patternsOnly = renderOf(recall(ANCHOR, "patterns"));
        assertTrue(patternsOnly.contains("Strategy: vary an algorithm"),
            () -> "the pattern must be reachable in its own lane, or the first assertion is "
                + "just an empty store: " + patternsOnly);
    }

    @Test
    @DisplayName("lane=domain says the patterns were not searched, and names the lane that reaches them")
    void aDomainFilterNamesTheExclusion() {
        Map<String, Object> data = recall(ANCHOR, "domain");
        assertEquals(Boolean.TRUE, data.get("catalogueExcluded"),
            () -> "the caller must be told the corpus was not consulted: " + data.keySet());

        String note = String.valueOf(data.get("catalogueExclusionNote"));
        assertTrue(note.contains("NOT SEARCHED"),
            () -> "the note's job is to separate 'none fits' from 'none was looked at': " + note);
        assertTrue(note.contains("lane=" + KnowledgeLane.catalogueLane()),
            () -> "and to name the lane that WOULD reach them, derived rather than spelled: "
                + note);
    }

    @Test
    @DisplayName("the lane that HOLDS the patterns says nothing — the note is not on every answer")
    void theCatalogueOwnLaneIsSilent() {
        assertNull(recall(ANCHOR, "patterns").get("catalogueExcluded"),
            "lane=patterns is where a catalogue pattern lives, so nothing is excluded and "
                + "nothing should be claimed");
    }

    @Test
    @DisplayName("naming every lane says nothing either")
    void noFilterIsSilent() {
        assertNull(recall(ANCHOR, "experience,patterns,domain,code,rules").get("catalogueExcluded"),
            "a recall that names every lane searches the patterns, so there is no exclusion to report");
    }

    @Test
    @DisplayName("an ABSENCE under lane=domain carries the note too — the case that misleads most")
    void anAbsenceStillCarriesTheNote() {
        Map<String, Object> data = recall("com.example.NothingAnchoredHere#atAll", "domain");

        // This is the clause the note exists for, and it is why the note is written beside the
        // echoed cue rather than beside the hits: an empty answer under a lane that excludes
        // the catalogue is EXACTLY the answer a caller reads as "no pattern fits".
        assertEquals(Boolean.TRUE, data.get("catalogueExcluded"),
            () -> "an answer with no rows at all is the one most easily misread, so it must "
                + "carry the exclusion: " + data.keySet());
        // THE STORE'S OWN WORD FOR IT, not an empty list. The first version asserted
        // `entriesOf(data).isEmpty()`, and `entriesOf` answers `List.of()` whenever the key
        // is absent or is not a List — so it passed for any reason at all, including a
        // response-shape change that stopped carrying `entries`. The C3 audit named it; the
        // fix is to read the field the store sets to say so, which is what the no-cue case
        // below was already doing.
        assertEquals("absence", data.get("result"),
            () -> "the fixture must genuinely match nothing, else this measures a hit: " + data);
    }

    @Test
    @DisplayName("an UNKNOWN lane name is not an exclusion — it was answered as one")
    void anUnknownLaneClaimsNothing() {
        // THE DEFECT THIS PINS SHIPPED IN D7h ITSELF, and the C3 audit found it.
        // `excludesCatalogue` answered TRUE for any string that was not the catalogue's lane,
        // so a MISSPELLED filter produced the note in full — "the patterns were NOT SEARCHED,
        // ask for lane=experience" — with complete authority, about a lane that does not
        // exist. The note's whole job is to separate "no pattern fits" from "patterns were
        // never searched"; a third state, "that is not a lane", was being answered as the
        // second.
        //
        // S22: a name that is not a lane is now REFUSED outright, which is the same promise kept
        // more strongly — the caller is told it is not a lane and shown the ones that are,
        // instead of being handed an answer that claims nothing.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "recall");
        args.put("symbol", ANCHOR);
        args.put("lane", "domian");
        ToolResponse refused = new ExperienceTool(() -> null, store).execute(args);
        assertFalse(refused.isSuccess(), "a misspelled lane must be refused, not answered");
        String said = String.valueOf(refused.getError());
        assertTrue(said.contains("'domian' is not a lane") && said.contains("domain"),
            () -> "and the refusal must name the lanes that exist: " + said);
    }

    @Test
    @DisplayName("and the NO-CUE absence carries it too — a second absence path, found by a mutation")
    void theNoCueAbsenceAlsoCarriesTheNote() {
        // THERE ARE TWO ABSENCE PATHS and the test above only reached one. The case above
        // supplies a real cue that matches nothing, which goes through the store; a query with
        // NO cue returns earlier, from its own branch. Mutation I moved the note past that
        // earlier branch and the whole class stayed green — a complete mutation that does not
        // fail has found something, and what it found was this gap.
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "recall");
        args.put("lane", "domain");
        // deliberately no symbol, package, operation or symptom

        Map<String, Object> data = dataOf(new ExperienceTool(() -> null, store).execute(args));
        assertEquals("absence", data.get("result"),
            () -> "the fixture must take the NO-CUE branch, or this measures the other path "
                + "again: " + data);
        assertEquals(Boolean.TRUE, data.get("catalogueExcluded"),
            () -> "a caller who filtered the patterns out AND gave no cue is told both, not "
                + "just the one the store noticed second: " + data.keySet());
    }

    @Test
    @DisplayName("a DESIGN question (nominate) under lane=domain carries it as well")
    void aNominationNamesTheExclusion() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "nominate");
        args.put("question", "how should one algorithm vary behind a single interface?");
        args.put("lane", "domain");

        Map<String, Object> data = dataOf(new ExperienceTool(() -> null, store).execute(args));
        assertEquals(Boolean.TRUE, data.get("catalogueExcluded"),
            () -> "nominate is the path the architect seat asks a design question on, which is "
                + "where a silently pattern-free answer costs most: " + data.keySet());
    }

    private Map<String, Object> recall(String symbol, String lane) {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("kind", "recall");
        args.put("symbol", symbol);
        args.put("lane", lane);
        return dataOf(new ExperienceTool(() -> null, store).execute(args));
    }

    /** The whole answer as text, so an assertion about a row's presence reads the real answer. */
    private static String renderOf(Map<String, Object> data) {
        return String.valueOf(data);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "the store must answer at all: " + r.getError());
        Object data = r.getData();
        assertNotNull(data, "a successful recall carries data");
        return (Map<String, Object>) data;
    }
}
