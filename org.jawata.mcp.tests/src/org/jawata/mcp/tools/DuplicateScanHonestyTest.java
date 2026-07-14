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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The bug this pins: {@code find_duplicate_code} used to swallow a failed project scan at
 * DEBUG level and answer {@code []} — "no duplicate code".
 *
 * <p>But an empty result from a scan that never ran is not an absence, it is a <b>failure to
 * look</b>, and the two must never be served as the same fact. It surfaced as a one-off in a
 * serial suite run ("clone group not found in: []") and would have been dismissed as a flake;
 * it is in truth the tool telling a confident lie whenever the Java model is mid-rebuild.</p>
 */
class DuplicateScanHonestyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper om;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        om = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    @Test
    @DisplayName("a scan ALWAYS says what it examined — so a zero can never pass as a clean bill")
    void everyAnswerReportsWhatWasActuallyLookedAt() {
        FindDuplicateCodeTool tool = new FindDuplicateCodeTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("minTokens", 10);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        Map<String, Object> result = data(r);

        // WITHOUT THESE, `groupCount: 0` is unreadable — it could mean "no duplicates" or
        // "we examined nothing", and only one of those is an answer.
        assertNotNull(result.get("methodsExamined"), "got: " + result);
        assertNotNull(result.get("projectsScanned"));
        assertTrue(((Number) result.get("methodsExamined")).intValue() > 0,
            "the fixture has methods; if we examined none, saying 'no duplicates' would be a "
                + "lie: " + result);
        assertEquals(1, ((Number) result.get("projectsScanned")).intValue());

        // A complete scan does not claim to be incomplete.
        assertFalse(result.containsKey("scanIncomplete"), "got: " + result);
    }

    @Test
    @DisplayName("a FAILED scan that finds nothing is REFUSED — it is not 'no duplicate code'")
    void anEmptyResultFromAFailedScanIsRefused() {
        // The guard, directly: this is the decision the tool makes, and it is the whole fix.
        FindDuplicateCodeTool.ScanReport failed = new FindDuplicateCodeTool.ScanReport();
        failed.failures.add(Map.of("project", "orb", "error",
            "JavaModelException: project is being rebuilt"));

        Optional<ToolResponse> refusal =
            FindDuplicateCodeTool.guardAgainstAnEmptyLie(List.of(), failed);

        assertTrue(refusal.isPresent(),
            "an empty result from a broken scan MUST NOT be served as an answer");
        assertEquals("SCAN_INCOMPLETE", refusal.get().getError().getCode());
        assertTrue(refusal.get().getError().getMessage().contains("could not"),
            "it must say we could not look, not that there is nothing there: "
                + refusal.get().getError().getMessage());
        assertTrue(refusal.get().getError().getMessage().contains("NOT 'no duplicate code'"),
            "in as many words: " + refusal.get().getError().getMessage());
    }

    @Test
    @DisplayName("a PARTIAL scan that still found something reports the findings AND the failure")
    void aPartialScanIsFlaggedRatherThanDiscarded() {
        FindDuplicateCodeTool.ScanReport partial = new FindDuplicateCodeTool.ScanReport();
        partial.methodsExamined = 40;
        partial.projectsScanned = 1;
        partial.failures.add(Map.of("project", "other", "error", "JavaModelException: broken"));

        // It found something: those findings are real and worth having — but the caller must
        // know the picture is incomplete rather than believe it is the whole truth.
        Optional<ToolResponse> refusal = FindDuplicateCodeTool.guardAgainstAnEmptyLie(
            List.of(Map.of("groupId", "abc")), partial);
        assertTrue(refusal.isEmpty(), "real findings are not thrown away");

        Map<String, Object> described = partial.describe();
        assertEquals(Boolean.TRUE, described.get("scanIncomplete"), "got: " + described);
        assertNotNull(described.get("failures"), "and it names what failed: " + described);
    }

    @Test
    @DisplayName("a COMPLETE scan finding nothing is a real absence, and says so")
    void aCleanEmptyScanIsAnHonestAnswer() {
        FindDuplicateCodeTool.ScanReport clean = new FindDuplicateCodeTool.ScanReport();
        clean.methodsExamined = 120;
        clean.projectsScanned = 1;

        assertTrue(FindDuplicateCodeTool.guardAgainstAnEmptyLie(List.of(), clean).isEmpty(),
            "when we DID look and found nothing, that is an answer — serve it");
        assertFalse(clean.incomplete());
        assertFalse(clean.describe().containsKey("scanIncomplete"));
    }
}
