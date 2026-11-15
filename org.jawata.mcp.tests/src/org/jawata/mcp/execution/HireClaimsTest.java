package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Stage 24, M13 (D55): each running hire may claim the packages it works in. An overlapping claim of another hire is refused with who holds it,
 * a claim an agent above holds admits its hires, and a claim is released when its hire ends.
 */
class HireClaimsTest {

    /** A hire the registry holds as running on a thread of its own, with what its claim got; it ends when told to. */
    private static final class Running {
        final Thread thread;
        final CountDownLatch end = new CountDownLatch(1);
        final AtomicReference<Optional<String>> claimed = new AtomicReference<>();

        Running(Thread thread) {
            this.thread = thread;
        }

        void ends() throws InterruptedException {
            end.countDown();
            thread.join(5_000);
        }
    }

    private final List<Running> started = new ArrayList<>();

    @AfterEach
    void endWhatIsLeft() throws InterruptedException {
        for (Running running : started) {
            running.ends();
        }
    }

    /** Registers a hire on its own thread, claims these packages as the hire tool does before anything starts, and then opens its plan. */
    private Running aHire(String seat, String brief, String issuedBy, String planId, String... packages) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        Running[] holder = new Running[1];
        Thread thread = new Thread(() -> {
            try (HireRegistry.Registration registration = HireRegistry.shared().begin(seat, brief, issuedBy)) {
                holder[0].claimed.set(registration.claim(List.of(packages)));
                HireRegistry.shared().planOpened(planId);
                registered.countDown();
                holder[0].end.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        Running running = new Running(thread);
        holder[0] = running;
        started.add(running);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
        return running;
    }

    @Test
    @DisplayName("T5: an overlapping claim is refused naming the agent that holds the package, a claim that does not overlap is made, and a package under a held one overlaps it")
    void anOverlapIsRefusedWithTheHolder() throws Exception {
        Running first = aHire("developer", "m13 first", "m13-chat", "m13-plan-first", "org.m13.alpha.beta");
        Running same = aHire("developer", "m13 same", "m13-chat", "m13-plan-same", "org.m13.alpha.beta");
        Running above = aHire("developer", "m13 above", "m13-chat", "m13-plan-above", "org.m13.alpha");
        Running apart = aHire("developer", "m13 apart", "m13-chat", "m13-plan-apart", "org.m13.gamma");

        assertAll(
            () -> assertTrue(first.claimed.get().isEmpty(), "the first claim was refused: " + first.claimed.get()),
            () -> assertTrue(same.claimed.get().isPresent(), "the same package was claimed twice"),
            () -> assertTrue(same.claimed.get().orElse("").contains("developer") && same.claimed.get().orElse("").contains("m13-plan-first"),
                "the refusal does not name who holds the package: " + same.claimed.get()),
            () -> assertTrue(above.claimed.get().isPresent(), "a package above a held one was claimed"),
            () -> assertTrue(apart.claimed.get().isEmpty(), "the control: a package that overlaps nothing was refused: " + apart.claimed.get()));
    }

    @Test
    @DisplayName("T5: a claim an agent above holds admits the hires it issued, and a stranger is still refused")
    void anAncestorsClaimAdmitsItsHires() throws Exception {
        Running lead = aHire("pm", "m13 the sprint", "m13-chat", "m13-plan-lead", "org.m13.delta");
        Running child = aHire("chief-engineer", "m13 build", "m13-plan-lead", "m13-plan-child", "org.m13.delta.inner");
        Running stranger = aHire("developer", "m13 stranger", "m13-chat", "m13-plan-stranger", "org.m13.delta.inner");

        assertAll(
            () -> assertTrue(lead.claimed.get().isEmpty(), "the lead's own claim was refused: " + lead.claimed.get()),
            () -> assertTrue(child.claimed.get().isEmpty(), "the lead's hire was refused a package under the lead's claim: " + child.claimed.get()),
            () -> assertTrue(stranger.claimed.get().isPresent(), "an agent that is nobody's hire was admitted under the lead's claim"));
    }

    @Test
    @DisplayName("T5: a claim is released when its hire ends, so the package can be claimed again")
    void aClaimIsReleasedWhenTheHireEnds() throws Exception {
        Running holder = aHire("developer", "m13 holder", "m13-chat", "m13-plan-holder", "org.m13.epsilon");
        Running refused = aHire("developer", "m13 refused", "m13-chat", "m13-plan-refused", "org.m13.epsilon");
        holder.ends();
        Running later = aHire("developer", "m13 later", "m13-chat", "m13-plan-later", "org.m13.epsilon");

        assertAll(
            () -> assertTrue(refused.claimed.get().isPresent(), "the control: the package was free while its holder ran"),
            () -> assertTrue(later.claimed.get().isEmpty(), "the claim outlived the hire that made it: " + later.claimed.get()));
    }
}
