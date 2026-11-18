package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h D57 (d): a hire answers at once and its result reaches the lead only as this report, so what the engine found about a worker's steps
 * against its instructions must be in the report, or the observer's correction never reaches the lead that issued the instructions.
 */
class HireReportTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static String reportOf(String answerJson) throws Exception {
        JsonNode answer = JSON.readTree(answerJson);
        return HireReport.of(new HireRegistry.Event("finished", "plan-1", "Dora", "developer", "brief", "plan-ce", answer), List.of());
    }

    @Test
    @DisplayName("D57 (d): the report of a worker's hire says how many instructions ran as written, and carries each correction with its instruction's number and each step nobody judged")
    void aWorkersReportCarriesItsInstructionScore() throws Exception {
        String report = reportOf("""
            {"cleanExit":true,"answer":"done","instructionScore":{"computable":true,"instructionsIssued":2,"ranAsWritten":1,
             "notAsWritten":["instruction 2: called rename_symbol, not with the arguments written"],
             "observer":{"flagged":[{"instruction":2,"why":"other arguments"}],
                         "corrections":[{"instruction":2,"text":"you renamed to the wrong name at call #1"}],
                         "unjudged":[]}}}
            """);

        assertAll(
            () -> assertTrue(report.contains("1 of 2 ran as written"), report),
            () -> assertTrue(report.contains("instruction 2: called rename_symbol, not with the arguments written"), report),
            () -> assertTrue(report.contains("Correction for instruction 2: you renamed to the wrong name at call #1"), report));
    }

    @Test
    @DisplayName("D57 (d): a deviating step the observer could not judge is named in the report as unjudged, never dropped")
    void anUnjudgedStepIsNamedInTheReport() throws Exception {
        String report = reportOf("""
            {"cleanExit":true,"answer":"done","instructionScore":{"computable":true,"instructionsIssued":1,"ranAsWritten":0,
             "notAsWritten":["instruction 1: not called"],
             "observer":{"flagged":[{"instruction":1,"why":"not called"}],"corrections":[],
                         "unjudged":[{"instruction":1,"why":"not called"}]}}}
            """);

        assertTrue(report.contains("Not judged, instruction 1: not called"), report);
    }

    @Test
    @DisplayName("D57 (d): a score that could not be computed says why in the report")
    void aScoreThatCouldNotBeComputedSaysWhy() throws Exception {
        String report = reportOf("""
            {"cleanExit":true,"answer":"done","instructionScore":{"computable":false,"because":"the issuer's answer carried no INSTRUCTION block"}}
            """);

        assertTrue(report.contains("not computable: the issuer's answer carried no INSTRUCTION block"), report);
    }

    @Test
    @DisplayName("28h-C26-11: a worker briefed in prose is reported as not checked, with the reason, so 'not checked' cannot read as 'nothing to report'")
    void aWorkerBriefedInProseIsReportedAsNotChecked() throws Exception {
        com.fasterxml.jackson.databind.node.ObjectNode answer = JSON.createObjectNode().put("cleanExit", true).put("answer", "done");
        answer.set("instructionScore", JSON.valueToTree(HireReport.notChecked()));
        String report = HireReport.of(new HireRegistry.Event("finished", "plan-1", "Dora", "developer", "brief", "plan-ce", answer), List.of());

        assertAll(
            () -> assertTrue(report.contains("Instructions: not computable: its brief carries no INSTRUCTION blocks"), report),
            () -> assertTrue(report.contains("not compared with anything"), report));
    }

    @Test
    @DisplayName("D57 (d), the control: a hire with no instruction score gets no instruction lines")
    void aHireWithNoScoreGetsNoInstructionLines() throws Exception {
        String report = reportOf("{\"cleanExit\":true,\"answer\":\"PROBE OK\"}");

        assertAll(
            () -> assertTrue(report.contains("Answer: PROBE OK"), report),
            () -> assertFalse(report.contains("Instructions:"), report));
    }
}
