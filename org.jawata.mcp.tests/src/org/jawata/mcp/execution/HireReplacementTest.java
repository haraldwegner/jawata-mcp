package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, change request D58 (c): a hire is replaced at its role's context limit. At the limit it is told, with its next tool answer, to
 * finish its step and report; a quarter past the limit the engine stops it. A lead with hires of its own running waits for them and is not
 * stopped, and a hire with no limit is neither told nor stopped.
 */
class HireReplacementTest {

    /** One hire on a thread of its own: it opens its plan, reports these context sizes, and records what the registry said after each. */
    private static final class Seen {
        final java.util.List<Optional<String>> notices = new java.util.concurrent.CopyOnWriteArrayList<>();
        final AtomicBoolean stopRan = new AtomicBoolean();
        final AtomicReference<OptionalLong> reached = new AtomicReference<>();
        final AtomicReference<OptionalLong> cut = new AtomicReference<>();
        final AtomicBoolean stopped = new AtomicBoolean();
    }

    private static Seen played(String plan, long limit, long... contexts) throws InterruptedException {
        Seen seen = new Seen();
        Thread hire = new Thread(() -> {
            HireRegistry hires = HireRegistry.shared();
            try (HireRegistry.Registration running = hires.begin("developer", "d58c " + plan, "")) {
                hires.planOpened(plan);
                running.replaceAt(limit);
                hires.onStop(() -> seen.stopRan.set(true));
                for (long context : contexts) {
                    hires.noteUsage(OptionalLong.of(context), OptionalDouble.empty());
                    seen.notices.add(hires.replacementNotice(plan));
                }
                seen.reached.set(running.limitReachedAt());
                seen.cut.set(running.cutAt());
                seen.stopped.set(running.wasStopped());
            }
        });
        hire.start();
        hire.join(10_000);
        return seen;
    }

    @Test
    @DisplayName("under the limit nothing is said; at it the hire is told to finish and report; a quarter past it the engine stops it")
    void aHireIsToldAtItsLimitAndStoppedPastIt() throws Exception {
        Seen seen = played("plan-d58c-limit", 1_000, 900, 1_000, 1_249, 1_250);

        assertAll(
            () -> assertEquals(Optional.empty(), seen.notices.get(0), "told below the limit"),
            () -> assertTrue(seen.notices.get(1).orElse("").contains("report"), "not told at the limit: " + seen.notices.get(1)),
            () -> assertTrue(seen.notices.get(2).isPresent(), "the notice does not stay"),
            () -> assertEquals(OptionalLong.of(1_000), seen.reached.get(), "the size at which it was told"),
            () -> assertEquals(OptionalLong.of(1_250), seen.cut.get(), "stopped at a quarter past the limit"),
            () -> assertTrue(seen.stopRan.get() && seen.stopped.get(), "the stop did not reach the brain"));
    }

    @Test
    @DisplayName("CONTROL: a hire with no limit is neither told nor stopped, and one that stays under a quarter past is not stopped")
    void aHireWithNoLimitIsLeftAlone() throws Exception {
        Seen none = played("plan-d58c-none", 0, 5_000_000);
        Seen near = played("plan-d58c-near", 1_000, 1_200);

        assertAll(
            () -> assertEquals(Optional.empty(), none.notices.get(0)),
            () -> assertEquals(OptionalLong.empty(), none.cut.get()),
            () -> assertTrue(!none.stopRan.get() && !near.stopRan.get(), "a hire was stopped that should run on"),
            () -> assertEquals(OptionalLong.empty(), near.cut.get()));
    }

    @Test
    @DisplayName("a tool answer to the hire at its limit carries the notice; another session's answer does not")
    void aHiresToolAnswerCarriesTheNotice() throws Exception {
        org.jawata.mcp.tools.ToolRegistry registry = new org.jawata.mcp.tools.ToolRegistry();
        registry.register(new org.jawata.mcp.tools.Tool() {
            @Override
            public String getName() {
                return "probe";
            }

            @Override
            public String getDescription() {
                return "probe";
            }

            @Override
            public java.util.Map<String, Object> getInputSchema() {
                return java.util.Map.of();
            }

            @Override
            public org.jawata.mcp.models.ToolResponse execute(com.fasterxml.jackson.databind.JsonNode arguments) {
                return org.jawata.mcp.models.ToolResponse.success(java.util.Map.of());
            }
        });
        com.fasterxml.jackson.databind.JsonNode none = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
        java.util.function.Function<String, String> answered = session -> {
            try {
                var response = registry.callTool("probe", none, org.jawata.mcp.models.CallerIdentity.supplied(session));
                return response.getMeta() == null ? "" : String.valueOf(response.getMeta().getSteering());
            } catch (Exception missing) {
                throw new AssertionError(missing);
            }
        };
        HireRegistry hires = HireRegistry.shared();
        try (HireRegistry.Registration running = hires.begin("developer", "d58c answer", "")) {
            hires.planOpened("plan-d58c-answer");
            running.replaceAt(1_000);
            String before = answered.apply("plan-d58c-answer");
            hires.noteUsage(OptionalLong.of(1_000), OptionalDouble.empty());
            String after = answered.apply("plan-d58c-answer");
            String other = answered.apply("someone-else");

            assertAll(
                () -> assertTrue(!before.contains("replaced at"), before),
                () -> assertTrue(after.contains("replaced at 1000"), after),
                () -> assertTrue(!other.contains("replaced at"), other));
        }
    }

    @Test
    @DisplayName("a lead with a hire of its own running is told to wait for it, and is not stopped however far past its limit")
    void aLeadWaitsForItsHires() throws Exception {
        HireRegistry hires = HireRegistry.shared();
        java.util.concurrent.CountDownLatch childUp = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
        Thread child = new Thread(() -> {
            try (HireRegistry.Registration ignored = hires.begin("developer", "d58c child", "plan-d58c-lead")) {
                hires.planOpened("plan-d58c-child");
                childUp.countDown();
                release.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        child.start();
        childUp.await(5, java.util.concurrent.TimeUnit.SECONDS);
        try {
            Seen lead = played("plan-d58c-lead", 1_000, 2_000);

            assertAll(
                () -> assertTrue(lead.notices.get(0).orElse("").contains("your own hires"), String.valueOf(lead.notices.get(0))),
                () -> assertEquals(OptionalLong.empty(), lead.cut.get(), "a lead with a hire running was stopped"));
        } finally {
            release.countDown();
            child.join(5_000);
        }
    }
}
