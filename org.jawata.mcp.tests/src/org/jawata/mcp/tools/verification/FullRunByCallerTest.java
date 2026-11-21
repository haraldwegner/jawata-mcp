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
}
