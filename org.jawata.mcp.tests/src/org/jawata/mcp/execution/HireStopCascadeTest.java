package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage 24, M14, ND3 (D55): stopping a lead stops everything under it, and the answer names each stopped agent. A hire of another conversation
 * is left alone.
 */
class HireStopCascadeTest {

    /** A hire running on its own thread, which notes whether it was interrupted, as a stopped hire is. */
    private static final class Hire {
        final Thread thread;
        final AtomicBoolean interrupted = new AtomicBoolean();
        final CountDownLatch end = new CountDownLatch(1);

        Hire(Thread thread) {
            this.thread = thread;
        }
    }

    private final List<Hire> started = new ArrayList<>();

    @AfterEach
    void endWhatIsLeft() throws InterruptedException {
        for (Hire hire : started) {
            hire.end.countDown();
            hire.thread.join(5_000);
        }
    }

    private Hire aHire(String seat, String brief, String issuedBy, String planId) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Hire[] holder = new Hire[1];
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin(seat, brief, issuedBy)) {
                HireRegistry.shared().planOpened(planId);
                registered.countDown();
                holder[0].end.await(20, TimeUnit.SECONDS);
            } catch (InterruptedException stopped) {
                holder[0].interrupted.set(true);
            }
        });
        thread.setDaemon(true);
        holder[0] = new Hire(thread);
        started.add(holder[0]);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
        return holder[0];
    }

    @Test
    @DisplayName("T8: stopping a lead stops the hires it issued and theirs, names each stopped agent, and leaves a hire of another conversation running")
    void stoppingALeadStopsEverythingUnderIt() throws Exception {
        Hire lead = aHire("pm", "m14 the sprint", "m14-chat", "m14-plan-lead");
        Hire engineer = aHire("chief-engineer", "m14 build", "m14-plan-lead", "m14-plan-engineer");
        Hire developer = aHire("developer", "m14 write", "m14-plan-engineer", "m14-plan-developer");
        Hire stranger = aHire("pm", "m14 another sprint", "m14-other-chat", "m14-plan-stranger");

        List<String> names = HireRegistry.shared().stopTree("m14-plan-lead");
        for (Hire hire : List.of(lead, engineer, developer)) {
            hire.thread.join(5_000);
        }

        assertAll(
            () -> assertEquals(3, names.size(), "the answer does not name the lead and everything under it: " + names),
            () -> assertTrue(names.stream().anyMatch(name -> name.endsWith("pm")) && names.stream().anyMatch(name -> name.endsWith("chief-engineer"))
                && names.stream().anyMatch(name -> name.endsWith("developer")), String.valueOf(names)),
            () -> assertTrue(lead.interrupted.get(), "the lead was not stopped"),
            () -> assertTrue(engineer.interrupted.get(), "a hire of the lead went on running after the lead was stopped"),
            () -> assertTrue(developer.interrupted.get(), "a hire of the lead's hire went on running after the lead was stopped"),
            () -> assertFalse(stranger.interrupted.get(), "a hire of another conversation was stopped"));
    }

    @Test
    @DisplayName("T8: stopping a hire that has nothing under it stops that one alone")
    void stoppingAHireWithNothingUnderItStopsOnlyIt() throws Exception {
        Hire lead = aHire("pm", "m14 lone sprint", "m14-chat-2", "m14-plan-lone-lead");
        Hire engineer = aHire("chief-engineer", "m14 lone build", "m14-plan-lone-lead", "m14-plan-lone-engineer");

        List<String> names = HireRegistry.shared().stopTree("m14-plan-lone-engineer");
        engineer.thread.join(5_000);

        assertAll(
            () -> assertEquals(1, names.size(), String.valueOf(names)),
            () -> assertTrue(engineer.interrupted.get()),
            () -> assertFalse(lead.interrupted.get(), "stopping a hire stopped the lead above it"));
    }
}
