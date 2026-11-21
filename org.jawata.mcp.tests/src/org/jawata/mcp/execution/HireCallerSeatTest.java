package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, Stage 27, row (z25): the engine works out which seat is calling a hire and does not take the caller's word. A hired brain's
 * tool calls carry its own plan id as the session (the hire path gives it {@code forSession(plan)}), and a principal brain presents the key
 * the registry was told speaks for a chat; so the seat of those two is known, and a {@code hirer} argument that names another seat is
 * refused. A caller the registry knows nothing about is not guessed at: it keeps what it wrote.
 */
class HireCallerSeatTest {

    private static final Hirer HIRER = new Hirer(SeatTree.standard(), Map.of(), Set.of());

    @Test
    @DisplayName("the seat of a caller is the seat of the running hire whose plan id it presents, main for a principal brain's key, and nothing for a caller nobody knows")
    void theRegistryKnowsTheSeatOfItsCallers() {
        HireRegistry hires = HireRegistry.shared();
        try (HireRegistry.Registration running = hires.begin("developer", "z25-brief", "")) {
            hires.planOpened("plan-z25-developer");
            hires.speaksFor("z25-principal-key", "z25-chat");

            assertAll(
                () -> assertEquals(Optional.of("developer"), hires.seatOfCaller("plan-z25-developer"), "a hire's own plan id did not name its seat"),
                () -> assertEquals(Optional.of("main"), hires.seatOfCaller("z25-principal-key"), "a principal brain's key did not name main"),
                () -> assertEquals(Optional.empty(), hires.seatOfCaller("someone-nobody-registered"), "CONTROL: a caller nobody registered was given a seat"),
                () -> assertEquals(Optional.empty(), hires.seatOfCaller(null), "CONTROL: no caller was given a seat"));
        }
        assertEquals(Optional.empty(), HireRegistry.shared().seatOfCaller("plan-z25-developer"), "CONTROL: a hire that ended still names a seat");
    }

    @Test
    @DisplayName("a hirer that names a seat other than the caller's own is refused, naming both; the seat the caller really is passes, and an unknown caller keeps what it wrote")
    void aClaimThatDisagreesWithTheCallerIsRefused() {
        Optional<String> refused = HIRER.refuseClaim("chief-engineer", Optional.of("developer"));

        assertAll(
            () -> assertTrue(refused.isPresent(), "a developer was let say it is the chief engineer"),
            () -> assertTrue(refused.orElse("").contains("chief-engineer") && refused.orElse("").contains("developer"), "the refusal names neither seat: " + refused),
            () -> assertEquals(Optional.empty(), HIRER.refuseClaim("developer", Optional.of("developer")), "CONTROL: the caller's own seat was refused"),
            () -> assertEquals(Optional.empty(), HIRER.refuseClaim("Main", Optional.of("main")), "CONTROL: a spelling that differs only in case was refused"),
            () -> assertEquals(Optional.empty(), HIRER.refuseClaim("chief-engineer", Optional.empty()), "CONTROL: a caller the registry does not know was refused"));
    }

    @Test
    @DisplayName("28h z27: a full test run is refused for a known seat that is not the test manager, and for the test manager with no reason; the test manager with a reason and a caller nobody knows pass")
    void aFullRunIsTheTestManagersDecision() {
        Optional<String> byDeveloper = HIRER.refuseFullRun(Optional.of("developer"), "all of it");
        Optional<String> noReason = HIRER.refuseFullRun(Optional.of("test-manager"), "  ");

        assertAll(
            () -> assertTrue(byDeveloper.orElse("").contains("developer") && byDeveloper.orElse("").contains("test-manager"),
                "a developer was let order a full run, or the refusal names neither seat: " + byDeveloper),
            () -> assertTrue(noReason.orElse("").contains("reason"), "the test manager ordered a full run with no reason: " + noReason),
            () -> assertEquals(Optional.empty(), HIRER.refuseFullRun(Optional.of("test-manager"), "the checkpoint C27 gate"), "CONTROL: the test manager's reasoned full run was refused"),
            () -> assertEquals(Optional.empty(), HIRER.refuseFullRun(Optional.empty(), null), "CONTROL: a caller nobody knows, such as the person's own run, was refused"));
    }
}
