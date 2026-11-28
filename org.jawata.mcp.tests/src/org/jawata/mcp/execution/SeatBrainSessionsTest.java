package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** 28h z203: which earlier brain session a later hire of a seat continues. */
class SeatBrainSessionsTest {

    @Test
    @DisplayName("28h z203: a pm continues its session within the hour and starts fresh after an hour idle; another seat still continues")
    void aPmStartsFreshAfterAnHourIdle() {
        AtomicLong now = new AtomicLong();
        SeatBrainSessions sessions = new SeatBrainSessions(now::get);
        sessions.remember("z203-chat", "pm", "pm-session");
        sessions.remember("z203-chat", "developer", "dev-session");

        now.set(TimeUnit.MINUTES.toMillis(59));
        Optional<String> withinTheHour = sessions.recall("z203-chat", "pm");
        now.set(TimeUnit.MINUTES.toMillis(60));
        Optional<String> afterTheHour = sessions.recall("z203-chat", "pm");
        Optional<String> developer = sessions.recall("z203-chat", "developer");
        sessions.remember("z203-chat", "pm", "pm-second-session");
        now.set(TimeUnit.MINUTES.toMillis(90));
        Optional<String> rememberedAgain = sessions.recall("z203-chat", "pm");

        assertAll(
            () -> assertEquals(Optional.of("pm-session"), withinTheHour, "the pm did not continue its session within the hour"),
            () -> assertEquals(Optional.empty(), afterTheHour, "the pm continued a session idle for an hour"),
            () -> assertEquals(Optional.of("dev-session"), developer, "the control: another seat lost its session"),
            () -> assertEquals(Optional.of("pm-second-session"), rememberedAgain, "the hour is not counted from the last answer"));
    }
}
