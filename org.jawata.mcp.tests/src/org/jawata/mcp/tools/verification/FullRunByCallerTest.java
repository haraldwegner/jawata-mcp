package org.jawata.mcp.tools.verification;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.coverage.CoverageService;
import org.jawata.mcp.coverage.CoverageStore;
import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.RunTestsTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, Stage 27, row (z27): a full test run is the test manager's decision. A developer runs the tests its change reaches; a run of the
 * whole scope costs the machine, so {@code run_tests} refuses {@code fullRun=true} from a session the engine knows is another seat, and from the
 * test manager when it gives no {@code reason}. A call the engine cannot place, such as the person's own, is not refused.
 */
class FullRunByCallerTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path dir;

    private ObjectNode fullRunOrNot(boolean full, String reason) {
        ObjectNode args = JSON.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", "package");
        scope.put("packageName", "com.example.z27");
        args.put("fullRun", full);
        if (reason != null) {
            args.put("reason", reason);
        }
        return args;
    }

    private ToolResponse asCaller(String callerId, ObjectNode args) {
        RunTestsTool tool = new RunTestsTool(() -> null, () -> null, new CoverageService(new CoverageStore(dir)));
        return CallerIdentity.supplied(callerId).speaking(() -> tool.execute(args));
    }

    /** Whether the answer is this rule's refusal and not whatever else a call with no project loaded is answered. */
    private static boolean refusedByTheRule(ToolResponse response) {
        return !response.isSuccess() && String.valueOf(response.getError().getMessage()).contains("full test run");
    }

    /** A hire of this seat the registry holds as running, on a thread of its own, with this plan id; it ends when the latch is released. */
    private static Thread holdRunning(String seat, String planId, CountDownLatch end) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration hire = HireRegistry.shared().begin(seat, "z27 " + seat, "")) {
                HireRegistry.shared().planOpened(planId);
                registered.countDown();
                end.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the " + seat + " hire did not register as running");
        return thread;
    }

    @Test
    @DisplayName("28h z27: a full run from a developer's session is refused naming the seat; the test manager with no reason is refused; the test manager with a reason, a run that is no full run, and a caller nobody knows are not")
    void aFullRunIsTheTestManagersDecision() throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        Thread developer = holdRunning("developer", "plan-z27-developer", end);
        Thread manager = holdRunning("test-manager", "plan-z27-manager", end);
        try {
            ToolResponse byDeveloper = asCaller("plan-z27-developer", fullRunOrNot(true, "everything"));
            ToolResponse noReason = asCaller("plan-z27-manager", fullRunOrNot(true, null));
            ToolResponse withReason = asCaller("plan-z27-manager", fullRunOrNot(true, "the checkpoint gate"));
            ToolResponse notFull = asCaller("plan-z27-developer", fullRunOrNot(false, null));
            ToolResponse stranger = asCaller("z27-nobody-registered", fullRunOrNot(true, null));

            assertAll(
                () -> assertTrue(refusedByTheRule(byDeveloper), "a developer's session was let order a full run: " + byDeveloper.getError()),
                () -> assertTrue(String.valueOf(byDeveloper.getError().getMessage()).contains("developer"), "the refusal names the calling seat"),
                () -> assertTrue(refusedByTheRule(noReason), "the test manager ordered a full run with no reason: " + noReason.getError()),
                () -> assertFalse(refusedByTheRule(withReason), "CONTROL: the test manager's reasoned full run was refused by this rule"),
                () -> assertFalse(refusedByTheRule(notFull), "CONTROL: a developer's run that is no full run was refused by this rule"),
                () -> assertFalse(refusedByTheRule(stranger), "CONTROL: a caller nobody registered was refused by this rule"));
        } finally {
            end.countDown();
            developer.join(5_000);
            manager.join(5_000);
        }
    }

    @Test
    @DisplayName("28h z27: the reason the test manager gave for a full run is repeated in the run's answer, so the pm that reads the answer sees what it was argued for; an answer with no reason, a refusal and a data-less answer are left as they were")
    void theReasonIsRepeatedInTheRunsAnswer() {
        ToolResponse run = ToolResponse.success(new java.util.LinkedHashMap<>(java.util.Map.of("passed", 3)));
        ToolResponse stamped = RunTestsTool.repeatingReason(run, "the checkpoint C27 gate");
        ToolResponse blank = RunTestsTool.repeatingReason(ToolResponse.success(new java.util.LinkedHashMap<>(java.util.Map.of("passed", 3))), "  ");
        ToolResponse refusal = ToolResponse.error("FULL_RUN_NOT_ALLOWED", "no", "hint");
        ToolResponse noData = ToolResponse.success("plain text");
        assertAll(
            () -> assertTrue(stamped.getData() instanceof java.util.Map<?, ?> m && "the checkpoint C27 gate".equals(m.get("fullRunReason")), "the reason is not in the answer: " + stamped.getData()),
            () -> assertTrue(stamped.getData() instanceof java.util.Map<?, ?> m && Integer.valueOf(3).equals(m.get("passed")), "CONTROL: the run's own figures were lost"),
            () -> assertFalse(blank.getData() instanceof java.util.Map<?, ?> m && m.containsKey("fullRunReason"), "CONTROL: a blank reason was written into the answer"),
            () -> assertFalse(RunTestsTool.repeatingReason(refusal, "any").isSuccess(), "CONTROL: a refusal was turned into something else"),
            () -> assertTrue("plain text".equals(RunTestsTool.repeatingReason(noData, "any").getData()), "CONTROL: an answer that is not a map was changed"));
    }

    @Test
    @DisplayName("28h z27: the reason the test manager gave for a full run is kept on that hire's record; a refused full run and a run that is no full run keep none")
    void theReasonIsKeptOnTheTestManagersHireRecord() throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        Thread manager = holdRunning("test-manager", "plan-z27-record-manager", end);
        Thread developer = holdRunning("developer", "plan-z27-record-developer", end);
        try {
            asCaller("plan-z27-record-manager", fullRunOrNot(true, "the checkpoint gate"));
            asCaller("plan-z27-record-manager", fullRunOrNot(false, "not a full run"));
            asCaller("plan-z27-record-developer", fullRunOrNot(true, "a developer's argument"));

            org.junit.jupiter.api.Assertions.assertAll(
                () -> org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("the checkpoint gate"),
                    HireRegistry.shared().fullRunReasonsOf("plan-z27-record-manager"), "the reason was not kept on the test manager's hire"),
                () -> org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of(),
                    HireRegistry.shared().fullRunReasonsOf("plan-z27-record-developer"), "CONTROL: a refused developer's argument was kept on its hire"));
        } finally {
            end.countDown();
            manager.join(5_000);
            developer.join(5_000);
        }
    }

    private ObjectNode scoped(String kind) {
        ObjectNode args = JSON.createObjectNode();
        ObjectNode scope = args.putObject("scope");
        scope.put("kind", kind);
        if ("package".equals(kind)) {
            scope.put("packageName", "com.example.z27");
        } else {
            scope.put("typeName", "com.example.z27.SomeTest");
        }
        return args;
    }

    private static boolean refusedAsAPackageRun(ToolResponse response) {
        return !response.isSuccess() && String.valueOf(response.getError().getMessage()).contains("package-wide test run");
    }

    @Test
    @DisplayName("28h z27: a package-wide run from a developer's, a chief engineer's or a pm's session is refused naming the seat; the test manager, the test writer, a class run by the developer and a caller nobody knows are not")
    void aPackageRunIsForTheTestersOnly() throws Exception {
        CountDownLatch end = new CountDownLatch(1);
        Thread developer = holdRunning("developer", "plan-z27p-developer", end);
        Thread chief = holdRunning("chief-engineer", "plan-z27p-chief", end);
        Thread pm = holdRunning("pm", "plan-z27p-pm", end);
        Thread manager = holdRunning("test-manager", "plan-z27p-manager", end);
        Thread writer = holdRunning("test-writer", "plan-z27p-writer", end);
        Thread tester = holdRunning("tester", "plan-z27p-tester", end);
        try {
            ToolResponse byDeveloper = asCaller("plan-z27p-developer", scoped("package"));
            ToolResponse byChief = asCaller("plan-z27p-chief", scoped("package"));
            ToolResponse byPm = asCaller("plan-z27p-pm", scoped("package"));
            ToolResponse byManager = asCaller("plan-z27p-manager", scoped("package"));
            ToolResponse byWriter = asCaller("plan-z27p-writer", scoped("package"));
            ToolResponse byTester = asCaller("plan-z27p-tester", scoped("package"));
            ToolResponse classByDeveloper = asCaller("plan-z27p-developer", scoped("class"));
            ToolResponse stranger = asCaller("z27p-nobody-registered", scoped("package"));

            assertAll(
                () -> assertTrue(refusedAsAPackageRun(byDeveloper), "a developer's session was let run a package: " + byDeveloper.getError()),
                () -> assertTrue(String.valueOf(byDeveloper.getError().getMessage()).contains("developer"), "the refusal names the calling seat"),
                () -> assertTrue(refusedAsAPackageRun(byChief), "a chief engineer's session was let run a package: " + byChief.getError()),
                () -> assertTrue(refusedAsAPackageRun(byPm), "a pm's session was let run a package: " + byPm.getError()),
                () -> assertFalse(refusedAsAPackageRun(byManager), "CONTROL: the test manager's package run was refused by this rule"),
                () -> assertFalse(refusedAsAPackageRun(byWriter), "CONTROL: the test writer's package run was refused by this rule"),
                () -> assertFalse(refusedAsAPackageRun(byTester), "28h z94: the tester's briefed package run was refused: " + byTester.getError()),
                () -> assertFalse(refusedAsAPackageRun(classByDeveloper), "CONTROL: a developer's class run was refused by this rule"),
                () -> assertFalse(refusedAsAPackageRun(stranger), "CONTROL: a caller nobody registered was refused by this rule"));
        } finally {
            end.countDown();
            for (Thread held : new Thread[] {developer, chief, pm, manager, writer, tester}) {
                held.join(5_000);
            }
        }
    }
}
