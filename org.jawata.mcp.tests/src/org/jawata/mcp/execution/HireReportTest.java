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
    @DisplayName("28h Stage 27 (z2): the report of a lead says what it wrote with its own editor outside the documents, with the observer's correction, and names a write nobody judged")
    void aLeadsOwnWritesAreInItsReport() throws Exception {
        String report = reportOf("""
            {"cleanExit":true,"answer":"done","ownWrites":{
             "flagged":[{"instruction":0,"why":"Edit on /w/Foo.java"},{"instruction":0,"why":"Write on /w/a.rs"}],
             "corrections":[{"instruction":0,"text":"you edited Foo.java yourself; hire the chief engineer"}],
             "unjudged":[{"instruction":0,"why":"Write on /w/a.rs"}]}}
            """);

        assertAll(
            () -> assertTrue(report.contains("Own write, correction: you edited Foo.java yourself; hire the chief engineer"), report),
            () -> assertTrue(report.contains("Own write, not judged: Write on /w/a.rs"), report));
    }

    @Test
    @DisplayName("28h Stage 27 (z2), the control: a lead that wrote no file of its own gets no own-write lines")
    void aLeadWithNoOwnWritesGetsNoOwnWriteLines() throws Exception {
        String report = reportOf("{\"cleanExit\":true,\"answer\":\"planned\"}");

        assertFalse(report.contains("Own write"), report);
    }

    @Test
    @DisplayName("D57 (d), the control: a hire with no instruction score gets no instruction lines")
    void aHireWithNoScoreGetsNoInstructionLines() throws Exception {
        String report = reportOf("{\"cleanExit\":true,\"answer\":\"REPORT: PROBE OK\"}");

        assertAll(
            () -> assertTrue(report.contains("Report: PROBE OK"), report),
            () -> assertFalse(report.contains("Instructions:"), report));
    }

    @Test
    @DisplayName("28h z52: a lead is told the worker's REPORT section and where the record is, never the rest of the answer")
    void aReportCarriesTheWorkersOwnSectionAndNotTheRestOfItsAnswer() throws Exception {
        String report = reportOf("""
            {"cleanExit":true,"record":"/tmp/records/a1/proposal.md",
             "answer":"I read A.java and rewrote the loop line by line, as follows\\n\\n## REPORT\\nRenamed total to sum. Open: nothing.",
             "changes":[{"path":"A.java","oldText":"int total;","newText":"int sum;"}]}
            """);

        assertAll(
            () -> assertTrue(report.contains("Report: Renamed total to sum. Open: nothing."), report),
            () -> assertTrue(report.contains("Record: /tmp/records/a1/proposal.md"), report),
            () -> assertFalse(report.contains("line by line"), "the working-out of the level below went up: " + report),
            () -> assertFalse(report.contains("int total;"), "the changed text went up: " + report));
    }

    @Test
    @DisplayName("28h z136: a lead's report says the one total of money, time and tokens under it, and how many brains published no figure")
    void aReportCarriesTheTotalUnderTheLead() throws Exception {
        String report = reportOf("""
            {"cleanExit":true,"answer":"REPORT: done","below":
             {"hires":3,"costUsd":0.75,"withoutCost":1,"elapsedMs":80000,"tokens":3000,"withoutTokens":0}}
            """);

        assertTrue(report.contains("Under it: 3 hires, 0.75 USD (1 with no money figure), 80 s, 3000 tokens"), report);
    }

    @Test
    @DisplayName("28h z136: the registry adds up what ended hires used under their lead, their own and what they carried from below")
    void theRegistryAddsUpWhatWasUsedUnderALead() throws Exception {
        HireRegistry hires = HireRegistry.shared();
        endedUnder(hires, "z136-sum-lead", "z136-sum-a", "{\"plan\":\"z136-sum-a\",\"costUsd\":0.25,\"tokens\":{\"spent\":100},"
            + "\"journalRow\":{\"detail\":{\"elapsedMs\":1000}}}");
        endedUnder(hires, "z136-sum-lead", "z136-sum-b", "{\"plan\":\"z136-sum-b\",\"costUsd\":\"NOT PUBLISHED BY THIS BRAIN\","
            + "\"below\":{\"hires\":1,\"costUsd\":0.5,\"withoutCost\":0,\"elapsedMs\":2000,\"tokens\":200,\"withoutTokens\":0}}");

        HireTotals total = hires.usedUnder("z136-sum-lead").orElse(HireTotals.NONE);

        assertAll(
            () -> assertTrue(total.hires() == 3, "hires: " + total),
            () -> assertTrue(total.costUsd() == 0.75, "money: " + total),
            () -> assertTrue(total.withoutCost() == 1, "no money figure: " + total),
            () -> assertTrue(total.elapsedMs() == 3000, "time: " + total),
            () -> assertTrue(total.tokens() == 300 && total.withoutTokens() == 1, "tokens: " + total),
            () -> assertTrue(hires.usedUnder("z136-sum-nobody").isEmpty(), "a lead with no ended hire was given a total"));
    }

    /** One background hire of this lead, begun, given its plan and recorded as ended with this answer. */
    private static void endedUnder(HireRegistry hires, String lead, String plan, String answer) throws Exception {
        hires.nextHireIsBackground();
        try (HireRegistry.Registration running = hires.begin("developer", plan, lead)) {
            hires.planOpened(plan);
        }
        hires.record("developer", plan, JSON.readTree(answer));
    }

    @Test
    @DisplayName("28h z52: a worker that wrote no REPORT section is reported as such, with its record, and its answer does not go up")
    void anAnswerWithNoReportSectionDoesNotGoUp() throws Exception {
        String report = reportOf("{\"cleanExit\":true,\"record\":\"/tmp/records/a2/proposal.md\",\"answer\":\"first I looked at the tree, then\"}");

        assertAll(
            () -> assertTrue(report.contains("Report: no REPORT section was written; the whole answer is in the record"), report),
            () -> assertTrue(report.contains("Record: /tmp/records/a2/proposal.md"), report),
            () -> assertFalse(report.contains("first I looked"), report));
    }

    @Test
    @DisplayName("28h z136: a report says the money, time, tokens and number of files, and 'no figure' where there is none, never zero")
    void aReportCarriesItsFigures() throws Exception {
        String told = reportOf("""
            {"cleanExit":true,"answer":"REPORT: done","costUsd":0.25,"tokens":{"spent":1200},"changedFileCount":3,
             "journalRow":{"detail":{"elapsedMs":42000}}}
            """);
        String untold = reportOf("{\"cleanExit\":true,\"answer\":\"REPORT: done\",\"costUsd\":\"NOT PUBLISHED BY THIS BRAIN\"}");

        assertAll(
            () -> assertTrue(told.contains("Money: 0.25 USD"), told),
            () -> assertTrue(told.contains("Time: 42 s"), told),
            () -> assertTrue(told.contains("Tokens: 1200"), told),
            () -> assertTrue(told.contains("Files changed: 3"), told),
            () -> assertTrue(untold.contains("Money: no figure"), untold),
            () -> assertTrue(untold.contains("Time: no figure"), untold),
            () -> assertTrue(untold.contains("Tokens: no figure"), untold),
            () -> assertTrue(untold.contains("Files changed: not measured"), untold));
    }

    @Test
    @DisplayName("28h z90 (a): a run that exited cleanly with every step passed but answered nothing is failed, not done, and the report says the answer was empty")
    void aCleanRunWithAnEmptyAnswerIsFailed() throws Exception {
        String report = reportOf("{\"cleanExit\":true,\"answer\":\"\",\"stepsPassed\":3,\"stepsDeclared\":3}");

        assertAll(
            () -> assertTrue(report.contains("State: failed"), report),
            () -> assertTrue(report.contains("Ended because: the answer was empty"), report),
            () -> assertTrue(report.contains("Report: (empty answer)"), report));
    }

    @Test
    @DisplayName("28h z90 (b): a run with fewer steps passed than declared is failed, says so as 'checks: 2 of 3 passed', and lists each gate with its outcome")
    void aRunWithFewerStepsPassedThanDeclaredIsFailedAndListsItsGates() throws Exception {
        String report = reportOf("""
            {"cleanExit":true,"answer":"I changed it","stepsPassed":2,"stepsDeclared":3,"gates":[
              {"tool":"compile_workspace","errorCount":0,"answered":true},
              {"tool":"get_diagnostics","errorCount":4,"answered":true},
              {"tool":"git","errorCount":0,"answered":false,"notAnsweredBecause":"the brain was stopped before it finished"}]}
            """);

        assertAll(
            () -> assertTrue(report.contains("State: failed"), report),
            () -> assertTrue(report.contains("Ended because: checks: 2 of 3 passed"), report),
            () -> assertTrue(report.contains("Checks: 2 of 3 passed"), report),
            () -> assertTrue(report.contains("Gate 1: compile_workspace: passed"), report),
            () -> assertTrue(report.contains("Gate 2: get_diagnostics: failed: 4 error(s)"), report),
            () -> assertTrue(report.contains("Gate 3: git: did not answer: the brain was stopped before it finished"), report));
    }

    @Test
    @DisplayName("28h z90 (c): a run stopped by a ceiling is failed, and the report names the ceiling first and the empty answer after it")
    void aRunStoppedByACeilingNamesTheCeilingFirst() throws Exception {
        String report = reportOf("""
            {"cleanExit":false,"stoppedBy":"tool steps: 61 over the ceiling of 60","exitCode":null,"answer":""}
            """);

        assertAll(
            () -> assertTrue(report.contains("State: failed"), report),
            () -> assertTrue(report.contains(
                "Ended because: stopped: tool steps: 61 over the ceiling of 60; the answer was empty"), report));
    }

    @Test
    @DisplayName("28h z90 (d): a brain that exited with a non-zero code without a clean exit is failed and the report gives the code")
    void aBrainThatExitedWithACodeIsFailedWithTheCode() throws Exception {
        String report = reportOf("{\"cleanExit\":false,\"exitCode\":1,\"answer\":\"partial\",\"stepsPassed\":3,\"stepsDeclared\":3}");

        assertAll(
            () -> assertTrue(report.contains("State: failed"), report),
            () -> assertTrue(report.contains("Ended because: brain exited with code 1"), report));
    }

    @Test
    @DisplayName("28h z90 (e), the control: a clean run with an answer and every step passed is done, says 'Checks: 3 of 3 passed' and has no 'Ended because' line")
    void aCleanRunWithAnAnswerAndAllStepsPassedIsDone() throws Exception {
        String report = reportOf("{\"cleanExit\":true,\"answer\":\"all good\",\"stepsPassed\":3,\"stepsDeclared\":3}");

        assertAll(
            () -> assertTrue(report.contains("State: done"), report),
            () -> assertTrue(report.contains("Checks: 3 of 3 passed"), report),
            () -> assertFalse(report.contains("Ended because"), report));
    }

    @Test
    @DisplayName("28h z90 (f): a run that timed out is failed even when its exit was clean, and the report says it timed out")
    void aRunThatTimedOutIsFailed() throws Exception {
        String report = reportOf("{\"cleanExit\":true,\"timedOut\":true,\"answer\":\"late\",\"stepsPassed\":3,\"stepsDeclared\":3}");

        assertAll(
            () -> assertTrue(report.contains("State: failed"), report),
            () -> assertTrue(report.contains("Ended because: timed out"), report));
    }

    @Test
    @DisplayName("28h z90 (g): an answer that carries no step counts and no gates is not failed for that, and says the checks were not recorded")
    void anAnswerWithoutStepCountsIsNotFailedForIt() throws Exception {
        String report = reportOf("{\"cleanExit\":true,\"answer\":\"fine\"}");

        assertAll(
            () -> assertTrue(report.contains("State: done"), report),
            () -> assertTrue(report.contains("Checks: not recorded in the answer"), report),
            () -> assertFalse(report.contains("Gate 1"), report));
    }

    @Test
    @DisplayName("28h z90 (h): every reason that holds is listed, in the order stopped, timed out, exit code, empty answer, checks")
    void everyReasonThatHoldsIsListedInOrder() throws Exception {
        String report = reportOf("{\"cleanExit\":false,\"timedOut\":true,\"exitCode\":143,\"answer\":\"\",\"stepsPassed\":1,\"stepsDeclared\":2}");

        assertTrue(report.contains(
            "Ended because: timed out; brain exited with code 143; the answer was empty; checks: 1 of 2 passed"), report);
    }
}
