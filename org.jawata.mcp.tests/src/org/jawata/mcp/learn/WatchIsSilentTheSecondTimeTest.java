package org.jawata.mcp.learn;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE BEHAVIOUR THE 4.0.3 DOGFOOD LOST: a pre-existing finding must be reported once
 * and never again.
 *
 * <p>The change reviewer stored its per-file baseline under {@code watch:} plus the
 * file's absolute path, in a column declared sixty characters wide. Every real path
 * overran it, so every write failed, the baseline was never there, and every
 * pre-existing finding in a touched file was reported as newly introduced on every
 * single call.</p>
 *
 * <h2>Why the tests that existed could not catch it, which is the point of this one</h2>
 *
 * <p>{@code WatchEngineTest} asserts exactly this silence and stayed green through the
 * whole defect, for two independent reasons — either alone is enough to make it blind:</p>
 *
 * <ol>
 *   <li>Its paths are {@code "A.java"}. The key was then twelve characters and fitted
 *       the column comfortably. The defect only exists at realistic path lengths.</li>
 *   <li>{@code WatchEngine} keeps an in-memory {@code recentlyEmitted} map with a
 *       half-hour cooldown, checked BEFORE the answer is emitted. Within one engine
 *       instance the second call is suppressed by that map whatever the persisted
 *       baseline says — so a same-instance assertion of silence tests the cooldown, not
 *       the baseline, and would stay green with persistence entirely broken.</li>
 * </ol>
 *
 * <p>So this test does the two things that make it a discriminator: a path LONGER than
 * the column, and a SECOND engine over the SAME store, which starts with an empty
 * cooldown map and can therefore only be silent if the baseline was really stored and
 * really read back.</p>
 */
class WatchIsSilentTheSecondTimeTest {

    /**
     * Longer than the sixty-character column the baseline key is stored in, and shaped
     * like a real path. Under the defect the key built from this was 109 characters.
     */
    private static final String LONG_PATH =
        "/home/someone/workspace/some-product/org.example.module/src/org/example/module/"
            + "internal/deeply/nested/ThingThatCarriesAFinding.java";

    private H2ExperienceStore store;
    private LearnerEventStore events;

    private static Map<String, Object> finding(String file, int line, String msg) {
        return Map.of("kind", "bugs", "filePath", file, "line", line, "message", msg);
    }

    /** A detector that always reports the same one pre-existing finding. */
    private WatchEngine.DetectorFn alwaysOneFinding() {
        return (kind, path) -> "bugs".equals(kind)
            ? ToolResponse.success(Map.of("findings",
                List.of(finding(path, 42, "== on strings"))))
            : ToolResponse.success(Map.of("findings", List.of()));
    }

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    @Test
    @DisplayName("a file's finding is reported once, and a FRESH reviewer over the same store stays silent")
    void secondPassOverTheSameStoreIsSilent() {
        WatchEngine first = new WatchEngine(alwaysOneFinding(), events);
        Optional<String> pass1 = first.watch("s1", List.of(LONG_PATH));

        // THE CONTROL: it speaks the first time. Without this, silence the second time
        // would be satisfied just as well by a reviewer that never says anything.
        assertTrue(pass1.isPresent(),
            "the control: a finding the reviewer has never seen must surface");
        assertTrue(pass1.get().contains("== on strings"), "and it must be THAT finding");

        // A SECOND engine, same store. Its cooldown map is empty, so nothing but the
        // persisted baseline can keep it quiet.
        WatchEngine second = new WatchEngine(alwaysOneFinding(), events);
        Optional<String> pass2 = second.watch("s1", List.of(LONG_PATH));

        assertTrue(pass2.isEmpty(),
            "a pre-existing finding must not be re-reported as newly introduced. It was"
                + " silent here only if the baseline survived the store round trip — the"
                + " in-memory cooldown cannot explain it, because this is a new engine."
                + " Got: " + pass2.orElse(""));
    }

    @Test
    @DisplayName("nothing was dropped on the way to the store")
    void theBaselineWriteActuallySucceeded() {
        long before = events.failedWrites();
        new WatchEngine(alwaysOneFinding(), events).watch("s1", List.of(LONG_PATH));

        // The silence above would ALSO be produced by a baseline that was written and a
        // baseline that was never needed. This says the write itself did not fail, which
        // is the half the length property cannot show: a key can be short enough and the
        // write can still be refused for another reason.
        assertEquals(before, events.failedWrites(),
            "the baseline write must not have failed. Failed writes went from " + before
                + " to " + events.failedWrites() + " for a path of " + LONG_PATH.length()
                + " characters.");
    }
}
