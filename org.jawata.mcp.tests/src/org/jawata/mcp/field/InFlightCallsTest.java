package org.jawata.mcp.field;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.AbstractTool;
import org.jawata.mcp.tools.FieldTool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#42 — <b>a call that hangs must leave a trace.</b>
 *
 * <p>{@link FieldRecorder} fires on the RESPONSE, so a call that never returns produced no
 * row, no shape and no count: the single worst thing jawata can do to a user was the one
 * failure {@code /report} structurally could not surface. Measured 2026-08-21 — two hung
 * {@code inspect(kind=landmarks)} calls, and a pile reporting three shapes over 1303 events
 * with no {@code inspect/landmarks} row anywhere in it.</p>
 */
class InFlightCallsTest {

    private static final ObjectMapper OM = new ObjectMapper();

    // ------------------------------------------------- the registry itself

    @Test
    @DisplayName("an outstanding call is reported; one that came back is not")
    void outstandingIsWhatDidNotComeBack() {
        InFlightCalls calls = new InFlightCalls();
        long stuck = calls.started("inspect", "landmarks");
        long returned = calls.started("search_symbols", "kind");
        calls.finished(returned);

        List<InFlightCalls.Call> out = calls.outstanding(0);

        assertAll(
            () -> assertEquals(1, out.size(), "only the one still running: " + out),
            () -> assertEquals("inspect/landmarks/NEVER_RETURNED", out.get(0).shape(),
                "the shape ranks in the same tool/kind/code vocabulary as a returned failure"),
            () -> assertEquals(1, calls.liveCount(), "and it is still live"));

        calls.finished(stuck);
        assertTrue(calls.outstanding(0).isEmpty(), "once it comes back, it is not a hang");
    }

    @Test
    @DisplayName("THE CONTROL — a call younger than the threshold is in progress, not stuck")
    void aYoungCallIsNotAHang() {
        // Every call is in flight for its own duration, INCLUDING the field call asking
        // the question. Without the threshold this mechanism would report the reader to
        // itself on every invocation, which is noise rather than news.
        InFlightCalls calls = new InFlightCalls();
        calls.started("inspect", "landmarks");

        assertTrue(calls.outstanding(InFlightCalls.DEFAULT_STUCK_MS).isEmpty(),
            "a call that started a moment ago is not a hang");
        assertEquals(1, calls.liveCount(),
            "but it IS live — the two questions are different and both are answerable");
    }

    @Test
    @DisplayName("the record holds shapes only — free text cannot be constructed into it")
    void itHoldsShapesOnly() {
        InFlightCalls calls = new InFlightCalls();
        calls.started("/home/someone/secret-project/src/Main.java", "hunter2");

        InFlightCalls.Call call = calls.outstanding(0).get(0);
        assertAll(
            () -> assertEquals("unknown", call.tool().value(),
                "a path is coerced, not stored: " + call.tool()),
            () -> assertEquals("unknown", call.kind().value(),
                "and so is anything else off the whitelist: " + call.kind()),
            () -> assertFalse(call.shape().contains("secret"),
                "the /report seat drafts a PUBLIC issue body from this: " + call.shape()));
    }

    // ------------------------------------------------- the wiring

    /** A tool that blocks until released — a hang, deterministically. */
    private static final class HangingTool extends AbstractTool {
        private final CountDownLatch release;
        private final CountDownLatch entered = new CountDownLatch(1);

        HangingTool(CountDownLatch release) {
            super(() -> null);
            this.release = release;
        }

        @Override
        protected boolean requiresLoadedProject() {
            return false;
        }

        @Override
        public String getName() {
            return "inspect";
        }

        @Override
        public String getDescription() {
            return "a tool that hangs";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of("type", "object", "properties", Map.of());
        }

        @Override
        protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
            entered.countDown();
            try {
                release.await(30, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return ToolResponse.success(Map.of("ok", true));
        }
    }

    @Test
    @DisplayName("mcp#42: callTool registers the call, and releases it however the call ends")
    void theChokeRegistersAndReleases() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        HangingTool hanging = new HangingTool(release);
        ToolRegistry registry = new ToolRegistry();
        registry.register(hanging);

        Thread caller = new Thread(() -> {
            try {
                registry.callTool("inspect", OM.createObjectNode().put("kind", "landmarks"));
            } catch (Exception ignored) {
                // the tool is not the subject; the registry's bookkeeping is
            }
        });
        caller.start();
        assertTrue(hanging.entered.await(10, TimeUnit.SECONDS), "the tool must have started");

        // MID-CALL: this is the state the recorder cannot see, because no response exists.
        List<InFlightCalls.Call> live = registry.inFlight().outstanding(0);
        assertAll(
            () -> assertEquals(1, live.size(), "the call in progress is registered: " + live),
            () -> assertEquals("inspect/landmarks/NEVER_RETURNED", live.get(0).shape(),
                "carrying the tool AND its discriminator: " + live.get(0)));

        release.countDown();
        caller.join(30_000);

        assertTrue(registry.inFlight().outstanding(0).isEmpty(),
            "and the finally releases it — a ticket left behind would be a FALSE hang, "
                + "which is the opposite defect");
    }

    // ------------------------------------------------- the report surface

    @Test
    @DisplayName("mcp#42: field(action=pile) reports the calls that never came back")
    void thePileReportsWhatNeverCameBack(@TempDir Path dir) {
        InFlightCalls calls = new InFlightCalls();
        // Genuinely old, rather than slept for: the point is the threshold, not the clock.
        calls.startedAt("inspect", "landmarks",
            System.currentTimeMillis() - InFlightCalls.DEFAULT_STUCK_MS - 5_000);

        FieldTool tool = new FieldTool(() -> null, () -> dir, () -> calls);
        ToolResponse response = tool.execute(OM.createObjectNode().put("action", "pile"));

        assertTrue(response.isSuccess(), "got: " + response.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stuck = (List<Map<String, Object>>) data.get("neverReturned");

        assertAll(
            () -> assertNotNull(stuck, "the key is always present: " + data.keySet()),
            () -> assertEquals(1, stuck.size(), "got: " + stuck),
            () -> assertEquals("inspect/landmarks/NEVER_RETURNED", stuck.get(0).get("shape"),
                "the shape the pile could not previously produce at all"),
            () -> assertTrue(((Number) stuck.get(0).get("outstandingMs")).longValue()
                    >= InFlightCalls.DEFAULT_STUCK_MS,
                "with how long it has been stuck, which is the number that matters: " + stuck),

            // THE CONTROL: the pile is otherwise unchanged and still says so honestly.
            // Without it, a fix that reported everything as stuck would pass above.
            () -> assertEquals(0, ((Number) data.get("events")).intValue(),
                "an empty pile still reports zero events rather than borrowing from this"),
            () -> assertEquals(0, ((Number) data.get("failures")).intValue(),
                "a hang is NOT counted as a returned failure — it is a different thing"));
    }

    @Test
    @DisplayName("THE CONTROL — nothing stuck reports an empty list, never an absent key")
    void nothingStuckIsStatedRatherThanOmitted() {
        // An absent key would make "nothing is stuck" and "this build cannot tell" the
        // same answer to a reader, which is the defect one level up from this one.
        FieldTool tool = new FieldTool(() -> null, () -> Path.of("/nonexistent-field-dir"),
            InFlightCalls::new);
        ToolResponse response = tool.execute(OM.createObjectNode().put("action", "pile"));

        assertTrue(response.isSuccess(), "got: " + response.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertTrue(data.containsKey("neverReturned"), "the key is present: " + data.keySet());
        assertTrue(((List<?>) data.get("neverReturned")).isEmpty(),
            "and empty: " + data.get("neverReturned"));
    }
}
