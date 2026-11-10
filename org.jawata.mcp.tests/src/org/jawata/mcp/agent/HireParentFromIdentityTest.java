package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.execution.HireRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stage 24, D55: a hire's parent is the lead that issued it, read from who asked, and not a guess from the hirer's seat name, which cannot tell
 * two project managers apart.
 */
class HireParentFromIdentityTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HireWatch watch = new HireWatch(() -> 1_000L);
    private final List<Thread> threads = new ArrayList<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void end() throws InterruptedException {
        release.countDown();
        for (Thread thread : threads) {
            thread.join(5_000);
        }
    }

    /** A hire the registry holds as running, issued by this caller and with this plan id of its own. */
    private void running(String seat, String brief, String callerId, String planId) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin(seat, brief, callerId)) {
                HireRegistry.shared().planOpened(planId);
                registered.countDown();
                release.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
    }

    /** The card the agent's stream shows for a hire: the call, with the arguments its issuer gave. */
    private void card(String id, String hirer, String seat, String brief) {
        ObjectNode update = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", id).put("status", "pending");
        update.put("title", "mcp__jawata-javata-dev__hire");
        update.putObject("rawInput").put("hirer", hirer).put("seat", seat).put("brief", brief);
        watch.observe(update);
    }

    @Test
    @DisplayName("two project managers each start two chief engineers: each chief engineer is under the project manager that issued it")
    void eachChiefEngineerIsUnderItsOwnProjectManager() throws Exception {
        running("pm", "sprint A", "principal-m6", "plan-pm-a");
        running("pm", "sprint B", "principal-m6", "plan-pm-b");
        running("chief-engineer", "A, first", "plan-pm-a", "plan-ce-a1");
        running("chief-engineer", "A, second", "plan-pm-a", "plan-ce-a2");
        running("chief-engineer", "B, first", "plan-pm-b", "plan-ce-b1");
        running("chief-engineer", "B, second", "plan-pm-b", "plan-ce-b2");

        // The cards come in the order the agent's stream shows them. Guessing from the seat name attaches every chief engineer to the project
        // manager that was seen last, which is what a person watching two project managers would be told.
        card("card-pm-a", "main", "pm", "sprint A");
        card("card-pm-b", "main", "pm", "sprint B");
        card("card-ce-a1", "pm", "chief-engineer", "A, first");
        card("card-ce-a2", "pm", "chief-engineer", "A, second");
        card("card-ce-b1", "pm", "chief-engineer", "B, first");
        card("card-ce-b2", "pm", "chief-engineer", "B, second");

        assertAll(
            () -> assertEquals(Optional.of("card-pm-a"), watch.entry("card-ce-a1").orElseThrow().parentPlanId()),
            () -> assertEquals(Optional.of("card-pm-a"), watch.entry("card-ce-a2").orElseThrow().parentPlanId()),
            () -> assertEquals(Optional.of("card-pm-b"), watch.entry("card-ce-b1").orElseThrow().parentPlanId()),
            () -> assertEquals(Optional.of("card-pm-b"), watch.entry("card-ce-b2").orElseThrow().parentPlanId()));
    }
}
