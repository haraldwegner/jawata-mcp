package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindDuplicateCodeTool;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28f Stage 8 D5 — the re-derived job, and the control that gives it its meaning.
 *
 * <p>This detector's claim is not "these methods are alike". It is the sharper one that a
 * TOKEN comparison cannot see them — because they were derived independently rather than
 * copied, so there is no shared token sequence to find. A test that only showed the
 * detector firing would leave that claim unmeasured, and the honest reading of a green run
 * would be "it found something", not "it found something nothing else could".</p>
 *
 * <p>So the control is the point of this file: {@code find_duplicate_code}, the shipped
 * token-based finder, is run over the SAME tree and must name none of these methods — at a
 * {@code minTokens} low enough that being small cannot be the reason it stays quiet. Both
 * halves are needed. Without the low threshold the control passes because the fixture
 * methods are short, which is the vacuous-assertion shape this sprint's record has had to
 * repair at every checkpoint.</p>
 *
 * <p>The fixture is a project of its OWN. That is the control's doing rather than
 * tidiness: {@code simple-maven} already carries clone groups, so "the token detector
 * names none of this" could never be asserted there about this fixture alone — and this
 * sprint's record says that project's counted populations have been moved four times by
 * fixtures written for something else.</p>
 */
class ReDerivedJobDetectorTest {

    /** Population one: the parse helper, the shape jawata's own tree carries ~34 times. */
    private static final List<String> PARSE_HELPERS = List.of(
        "com.example.AlphaTool#parse",
        "com.example.BetaTool#parse",
        "com.example.GammaTool#parse");

    /** Population two: a lookup, the shape the five type lookups had before they merged. */
    private static final List<String> LOOKUPS = List.of(
        "com.example.FirstDesk#find",
        "com.example.SecondDesk#find",
        "com.example.ThirdDesk#find",
        "com.example.FourthDesk#find");

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private FindDuplicateCodeTool tokenFinder;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("re-derived-job");
        tool = new FindQualityIssueTool(() -> service);
        tokenFinder = new FindDuplicateCodeTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "re_derived_job");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "re_derived_job must dispatch — refused with: "
            + (r.getError() != null
                ? r.getError().getCode() + " / " + r.getError().getMessage()
                : "(no error info)"));
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findings() {
        return (List<Map<String, Object>>) data().get("findings");
    }

    /**
     * What a failing assertion here has to say, and the reason it is not just the findings.
     *
     * <p>An empty findings list has two completely different causes — the detector looked
     * at the tree and found nothing, or it looked at nothing — and they are the same
     * printed value. {@code methodsExamined} is what separates them, and a reader chasing a
     * red run needs that before anything else.</p>
     */
    private String scan() {
        Map<String, Object> data = data();
        return "[filesScanned=" + data.get("filesScanned")
            + ", methodsExamined=" + data.get("methodsExamined")
            + ", bindingsUnresolved=" + data.get("bindingsUnresolved")
            + ", unreadable=" + data.get("unreadable")
            + ", groupsTooLarge=" + data.get("groupsTooLarge")
            + ", findings=" + data.get("findings") + "]";
    }

    private Set<String> symbols() {
        return findings().stream().map(f -> String.valueOf(f.get("symbol")))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<Object> groupsOf(List<String> population) {
        return findings().stream()
            .filter(f -> population.contains(String.valueOf(f.get("symbol"))))
            .map(f -> f.get("group"))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Test
    @DisplayName("PROOF OF LIFE: the parse helper population is named, as ONE group")
    void theParseHelperPopulationIsNamed() {
        Set<String> hits = symbols();
        for (String member : PARSE_HELPERS) {
            assertTrue(hits.contains(member),
                member + " derives the same job as its two siblings from the same two"
                    + " collaborators. If this is silent the detector is dead, and a dead"
                    + " detector and a clean codebase give the same answer — which is why"
                    + " the scan is printed beside the hits: " + hits + " " + scan());
        }
        assertEquals(1, groupsOf(PARSE_HELPERS).size(),
            "and they are ONE job derived three times, not three separate coincidences —"
                + " reporting them as unrelated pairs is what makes a real population"
                + " unreadable: " + findings());
    }

    @Test
    @DisplayName("the lookup population is named too, as a group of its own")
    void theLookupPopulationIsNamed() {
        Set<String> hits = symbols();
        for (String member : LOOKUPS) {
            assertTrue(hits.contains(member),
                member + " walks a catalogue for a name, as its three siblings do: "
                    + hits + " " + scan());
        }
        assertEquals(1, groupsOf(LOOKUPS).size(),
            "one job, four derivations, one group: " + findings());
        Set<Object> both = new LinkedHashSet<>(groupsOf(PARSE_HELPERS));
        both.addAll(groupsOf(LOOKUPS));
        assertEquals(2, both.size(),
            "and the two populations are DIFFERENT groups. This compares the UNION, because"
                + " a detector that merged every finding into one group would report one"
                + " group for each population separately and satisfy both of the size"
                + " assertions above: " + findings());
    }

    @Test
    @DisplayName("an IMPOSED signature is not a re-derivation, however alike the two are")
    void anImposedSignatureIsNotAReDerivation() {
        Set<String> hits = symbols();
        assertFalse(hits.contains("com.example.FlatPricing#quote"),
            "Pricing DECLARES quote(Order), so the shape is the type system's doing and"
                + " not two people arriving at it — this is what an interface is for: "
                + hits);
        assertFalse(hits.contains("com.example.TieredPricing#quote"),
            "and the same from the other side: " + hits);
    }

    /**
     * THE ONE THAT DEFINES THIS DETECTOR, and the only assertion that can tell the shipped
     * rule from the one the plan first wrote.
     *
     * <p>The plan's clause was "no common supertype". Measured against the population this
     * deliverable exists to find, that clause is wrong: jawata's ~34 {@code parse} helpers
     * sit very largely on classes sharing {@code AbstractApplyingRefactoringTool}, so
     * excluding pairs that share a supertype silences the flagship case completely. What
     * actually makes a match meaningless is the signature being DICTATED — the method
     * overrides something.</p>
     *
     * <p>No other assertion in this file separates the two rules, because every other
     * population here extends nothing. This pair does, and is reported anyway.</p>
     */
    @Test
    @DisplayName("sharing a parent is not having your signature dictated by one")
    void sharingAParentIsNotBeingImposedOn() {
        Set<String> hits = symbols();
        assertTrue(hits.contains("com.example.LeftBranch#label"),
            "LeftBranch and RightBranch share SharedBase, which declares nothing resembling"
                + " label(Catalogue) — so nothing dictated this shape and two people wrote"
                + " the same helper. A rule that excluded them for sharing a parent would"
                + " also silence the real parse population, which is the whole reason the"
                + " shipped rule asks about overriding instead: " + hits + " " + scan());
        assertTrue(hits.contains("com.example.RightBranch#label"),
            "and from the other side: " + hits);
    }

    @Test
    @DisplayName("a wrapper is not a second implementation")
    void aWrapperIsNotASecondImplementation() {
        Set<String> hits = symbols();
        assertFalse(hits.contains("com.example.Wrapper#render"),
            "Wrapper calls Owner, so it is the first implementation with something around"
                + " it rather than a rival to it: " + hits);
        assertFalse(hits.contains("com.example.Owner#render"), hits.toString());
    }

    @Test
    @DisplayName("one shared collaborator is not enough")
    void oneSharedCollaboratorIsNotEnough() {
        Set<String> hits = symbols();
        assertFalse(hits.contains("com.example.SoloA#tag"),
            "they share Item and nothing else. Pairing on one shared collaborator is what"
                + " would report the workspace against itself: " + hits);
        assertFalse(hits.contains("com.example.SoloB#tag"), hits.toString());
    }

    /**
     * THE CONTROL, and the reason this detector exists rather than another adapter over the
     * clone finder.
     *
     * <p>{@code minTokens} is dropped to 5 deliberately. At the shipped default of 50 the
     * finder would be silent here because the fixture's methods are SHORT, and a control
     * that passes for the wrong reason is worse than none — it reads as evidence while
     * measuring nothing.</p>
     *
     * <p>The trivial {@code build} and {@code locate} forwarders in the fixture ARE
     * byte-identical to each other, so the finder may well group those. That is correct
     * behaviour and it is left alone: it is the proof that the finder is awake on this
     * tree. What it must not do is name a member of either population.</p>
     */
    @Test
    @DisplayName("CONTROL: the token detector names none of them, and not for want of looking")
    @SuppressWarnings("unchecked")
    void theTokenDetectorNamesNoneOfThem() {
        ObjectNode args = mapper.createObjectNode();
        args.put("minTokens", 5);
        ToolResponse r = tokenFinder.execute(args);
        assertTrue(r.isSuccess(), "the token finder must run: "
            + (r.getError() != null ? r.getError().getMessage() : ""));
        Map<String, Object> data = (Map<String, Object>) r.getData();

        Object examined = data.get("methodsExamined");
        assertTrue(examined instanceof Number && ((Number) examined).intValue() > 0,
            "PROOF OF LIFE for the control itself: a finder that examined nothing names"
                + " nothing, and would pass every assertion below having looked at no"
                + " code. Got methodsExamined=" + examined + " from " + data.keySet());

        Set<String> cloned = new LinkedHashSet<>();
        if (data.get("groups") instanceof List<?> groups) {
            for (Object g : groups) {
                if (g instanceof Map<?, ?> group
                        && group.get("instances") instanceof List<?> instances) {
                    for (Object i : instances) {
                        if (i instanceof Map<?, ?> inst) {
                            cloned.add(String.valueOf(inst.get("methodName")));
                        }
                    }
                }
            }
        }
        assertFalse(cloned.contains("parse"),
            "a re-derived job is precisely the duplicate whose TOKENS do not match. If the"
                + " token finder can see the parse population, this fixture is three copies"
                + " of one body and proves nothing about this detector. It named: " + cloned);
        assertFalse(cloned.contains("find"),
            "and the same for the lookup population: " + cloned);
    }

    @Test
    @DisplayName("the kind is published, so a caller can actually ask for it")
    @SuppressWarnings("unchecked")
    void theKindIsPublished() {
        Map<String, Object> schema = tool.getInputSchema();
        Map<String, Object> properties = (Map<String, Object>) schema.get("properties");
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        List<String> published = (List<String>) kind.get("enum");
        assertTrue(published.contains("re_derived_job"),
            "a detector registered but not published is reachable by nobody, which is this"
                + " codebase's own recorded wired-but-unreachable defect: " + published);
    }
}
