package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Optional;

import org.jawata.mcp.execution.HireRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, Stage 27, row (z28): the first ceiling, the time a running hire may show no sign of life before it is told as stalled, is a
 * setting of the host, {@code jawata.hire.stallSeconds}. A number above zero is used; no setting, text that is not a number, and zero or less
 * leave the default.
 */
class HireQuietCeilingSettingTest {

    @Test
    @DisplayName("28h z28: the first ceiling is the number the host set; with no setting, a word, zero or a negative number it is the default of five minutes")
    void theFirstCeilingIsTheHostsSettingOrTheDefault() {
        assertAll(
            () -> assertEquals(90, HireTool.quietCeilingOf(Optional.of(" 90 ")), "the host's number was not used"),
            () -> assertEquals(HireRegistry.STALLED_AFTER_SECONDS, HireTool.quietCeilingOf(Optional.empty()), "CONTROL: no setting was not the default"),
            () -> assertEquals(HireRegistry.STALLED_AFTER_SECONDS, HireTool.quietCeilingOf(Optional.of("soon")), "CONTROL: a word was not the default"),
            () -> assertEquals(HireRegistry.STALLED_AFTER_SECONDS, HireTool.quietCeilingOf(Optional.of("0")), "CONTROL: zero was not the default"),
            () -> assertEquals(HireRegistry.STALLED_AFTER_SECONDS, HireTool.quietCeilingOf(Optional.of("-5")), "CONTROL: a negative number was not the default"));
    }
}
