package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, Stage 8 — the two smells that ADAPT a finder that already shipped.
 *
 * <p><b>THIS FILE EXISTS BECAUSE A C8 AUDIT FOUND ITS SUBJECT ENTIRELY UNTESTED.</b>
 * {@code loops} and {@code data_class} shipped as registered kinds with no firing test of
 * any kind — {@code grep -rn ModernizationSmells org.jawata.mcp.tests} returned ZERO. Four
 * of Stage 8's six detectors had a positive and a negative test; these two had neither, and
 * C8's criterion names exactly that state: <i>"each detector fires on its fixture BEFORE its
 * silence on clean code counts"</i>. A detector whose only evidence is that its name appears
 * in a schema enum passes every gate while detecting nothing.</p>
 *
 * <h2>Why "the finder is already tested" is not an answer</h2>
 *
 * <p>{@code FindModernizationToolTest} covers {@code loop_to_stream} and
 * {@code class_to_record}, and that was the reason this pair looked covered. It is not the
 * same claim. {@link ModernizationSmells#adapt} does work the finder never does, and every
 * piece of it is a way these two kinds can be broken while the finder stays green:</p>
 *
 * <ul>
 *   <li>it SUBSTITUTES the kind into a DEEP COPY, so the caller's node survives — the
 *       family sweep hands one arguments node to every detector in turn;</li>
 *   <li>it RESHAPES {@code candidates} into {@code findings}, which is what makes
 *       {@code summary=true}, paging and the baseline work for these kinds at all;</li>
 *   <li>it derives {@code symbol} from the FILE NAME, a claim no finder makes;</li>
 *   <li>it reports a full page as {@code truncated} rather than as a count.</li>
 * </ul>
 *
 * <h2>No fixture was added, deliberately</h2>
 *
 * <p>These tests reuse the two fixtures the shipped finders already fire on —
 * {@code ModernizationTargets} and {@code RecordSealedTargets}. This sprint has FOUR
 * recorded instances of a new fixture in {@code simple-maven} moving a population out from
 * under an unrelated test: a findings page, a clone-group page, a naming census and a search
 * ranking. Adding a fifth to test a detector would have been a poor trade.</p>
 */
class ModernizationSmellsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findings(String kind) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), kind + " must dispatch through the front door — refused with: "
            + (r.getError() != null ? r.getError().getCode() + " / " + r.getError().getMessage()
                                    : "(no error info)"));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertNotNull(data.get("findings"),
            "PROOF OF LIFE: the adapter must publish `findings`. A pass-through would leave"
                + " the sweep's own `candidates` key here, which find_quality_issue does not"
                + " merge — so summary, paging and the baseline would be silently inert for"
                + " this kind while every assertion below still read something. Got: " + data);
        return (List<Map<String, Object>>) data.get("findings");
    }

    private Map<String, Object> findingIn(String kind, String fileName) {
        List<Map<String, Object>> all = findings(kind);
        return all.stream()
            .filter(f -> String.valueOf(f.get("filePath")).endsWith(fileName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                kind + " reported nothing in " + fileName + ", which is the fixture the"
                    + " shipped finder is already pinned on. Either the adapter is not"
                    + " reaching the sweep or the sweep stopped firing. Got: " + all));
    }

    // ------------------------------------------------------------------
    // FIRES — the clause C8 names, one test per kind
    // ------------------------------------------------------------------

    @Test
    @DisplayName("loops fires on the accumulation for-each, as a smell rather than a modernization")
    void loopsFires() {
        Map<String, Object> hit = findingIn("loops", "ModernizationTargets.java");

        assertEquals("loops", hit.get("kind"),
            "the finding must carry the SMELL's name. The sweep underneath was asked for"
                + " `loop_to_stream`, and a reader who asked about loops must not be handed"
                + " the modernization vocabulary back: " + hit);
        assertNotNull(hit.get("line"), "a finding without a line cannot be acted on: " + hit);
        assertTrue(String.valueOf(hit.get("message")).contains("Replace Loop with Pipeline"),
            "the message must NAME the cure — that sentence is the whole difference between"
                + " reporting a smell and reporting a scan result: " + hit);
    }

    @Test
    @DisplayName("data_class fires on the fields-and-accessors class")
    void dataClassFires() {
        Map<String, Object> hit = findingIn("data_class", "RecordSealedTargets.java");

        assertEquals("data_class", hit.get("kind"), "the smell's name, not the sweep's: " + hit);
        assertNotNull(hit.get("line"), "a finding without a line cannot be acted on: " + hit);
        assertTrue(String.valueOf(hit.get("message")).contains("record"),
            "the message must offer the cure the smell points at: " + hit);
    }

    // ------------------------------------------------------------------
    // The reshaping, which is the only part the finder's own tests cannot see
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the symbol is the top-level type, derived from the file rather than guessed")
    void symbolIsDerivedFromTheFileName() {
        assertEquals("com.example.RecordSealedTargets", findingIn("data_class",
            "RecordSealedTargets.java").get("symbol"),
            "A candidate carries a file and a line and NO symbol, so the adapter derives one"
                + " from the file name — which Java guarantees matches the public top-level"
                + " type. It is deliberately the COARSER answer: the class actually reported"
                + " inside this file is PointData, and the line beside the symbol is what"
                + " narrows it. Asserting the enclosing name rather than PointData is"
                + " therefore the assertion that matches the shipped claim.");
    }

    @Test
    @DisplayName("the caller's arguments survive the kind substitution")
    void theSweepIsAskedOnACopy() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "loops");
        args.put("projectKey", "simple-maven");

        ToolResponse r = ModernizationSmells.loops().detect(service, args);
        assertTrue(r.isSuccess(), "the detector must answer: " + r.getError());

        assertEquals("loops", args.get("kind").asText(),
            "THE DEEP COPY IS LOAD-BEARING AND NOTHING ELSE WATCHES IT. The adapter must"
                + " rewrite `kind` to the sweep's name on a COPY: find_quality_issue's family"
                + " sweep hands ONE arguments node to every detector in turn, so mutating it"
                + " in place would hand the NEXT detector `loop_to_stream` — a kind it never"
                + " asked for and does not answer to. The damage lands in a different"
                + " detector's result, which is why no test of this one would report it.");
        assertEquals("simple-maven", args.get("projectKey").asText(),
            "and the caller's other parameters must survive with it");
    }

    @Test
    @DisplayName("a full page reports itself as a floor, not as a total")
    void aFullPageIsNotACount() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "loops");
        args.put("maxResults", 1);

        ToolResponse r = ModernizationSmells.loops().detect(service, args);
        assertTrue(r.isSuccess(), "the detector must answer: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();

        assertEquals(Boolean.TRUE, data.get("truncated"),
            "a page filled to the limit is a floor. This bit at the DEFAULT 200 on this"
                + " repository — the kind answered exactly 200 — and reporting that as a"
                + " count states a number the scan never established: " + data);
        assertNotNull(data.get("countIsAtLeast"),
            "and the honest number must be published under a name that says what it is: "
                + data);
        assertTrue(String.valueOf(data.get("note")).contains("not a total"),
            "the note is what a reader sees; without it `truncated` is a flag nobody"
                + " interprets: " + data);
    }

    @Test
    @DisplayName("a page that is not full carries no truncation claim")
    void aShortPageIsACount() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "data_class");
        args.put("maxResults", 5000);

        ToolResponse r = ModernizationSmells.dataClass().detect(service, args);
        assertTrue(r.isSuccess(), "the detector must answer: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();

        // THE CONTROL. Without it `truncated` could be set unconditionally and the test
        // above would still pass, which would make the flag decoration rather than a claim.
        assertFalse(data.containsKey("truncated"),
            "under a limit the scan did not reach, `count` IS the count and no truncation"
                + " claim may be made: " + data);
        assertNotNull(data.get("count"), "and the count must be reported: " + data);
    }
}
