package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/**
 * Sprint 27a Stage 1 — the analogy policy, tested against the Stage-0 DERIVED
 * cases (dossier-27a "Stage 0 evidence"; raw numbers in
 * {@code test-resources/embed-goldens/stage0-27a-profiles.tsv}).
 *
 * <p>The cases below replay measured profiles: a real cue's best answer stands
 * clear of that cue's own background, a nonsense cue's does not. The policy is
 * PURE — it decides from the profile alone, so these tests need no store, no
 * embedder and no rendering.</p>
 */
class AnalogyPolicyTest {

    /** Build a profile whose median is 0 and whose leaders sit at the given offsets. */
    private static Map<String, Double> profile(double... leaders) {
        Map<String, Double> m = new LinkedHashMap<>();
        for (int i = 0; i < leaders.length; i++) {
            m.put("lead-" + i, leaders[i]);
        }
        // 200 background entries at 0.0 dominate the median, as a real corpus does
        for (int i = 0; i < 200; i++) {
            m.put("bg-" + i, 0.0);
        }
        return m;
    }

    // --- the derived cases, replayed -------------------------------------------------

    @Test
    void cue06_peaked_profile_speaks_three() {
        // measured: top1/2/3 minus median = 0.387 / 0.318 / 0.311
        assertEquals(List.of("lead-0", "lead-1", "lead-2"),
            AnalogyPolicy.speak(profile(0.387, 0.318, 0.311)));
    }

    @Test
    void cue02_only_the_leader_stands_out() {
        // measured: 0.294 / 0.268 / 0.257 — only the first clears 0.283
        assertEquals(List.of("lead-0"), AnalogyPolicy.speak(profile(0.294, 0.268, 0.257)));
    }

    @Test
    void cue01_two_stand_out() {
        // measured: 0.308 / 0.284 / 0.270
        assertEquals(List.of("lead-0", "lead-1"),
            AnalogyPolicy.speak(profile(0.308, 0.284, 0.270)));
    }

    @Test
    void cue07_the_accepted_miss_abstains() {
        // measured: 0.215 / 0.210 / 0.207 — the one calibration cue the frozen
        // bar (>=11 of 12) permits us to lose.
        assertEquals(List.of(), AnalogyPolicy.speak(profile(0.215, 0.210, 0.207)));
    }

    @Test
    void nonsense_control_abstains() {
        // ctl-non-1 "purple elephant quantum sandwich protocol": 0.235 / 0.207 / 0.181
        assertEquals(List.of(), AnalogyPolicy.speak(profile(0.235, 0.207, 0.181)));
    }

    @Test
    void plausible_but_absent_abstains() {
        // ctl-abs-1 (a Kubernetes question this corpus cannot answer): 0.2707 top1
        assertEquals(List.of(), AnalogyPolicy.speak(profile(0.2707, 0.26, 0.25)));
    }

    // --- structural guarantees --------------------------------------------------------

    @Test
    void never_speaks_more_than_three_however_many_stand_out() {
        assertEquals(3, AnalogyPolicy.speak(profile(0.9, 0.8, 0.7, 0.6, 0.5)).size());
    }

    @Test
    void an_empty_profile_abstains_rather_than_failing() {
        assertEquals(List.of(), AnalogyPolicy.speak(Map.of()));
        assertEquals(List.of(), AnalogyPolicy.speak(null));
    }

    @Test
    void a_flat_profile_abstains_however_high_the_absolute_scores() {
        // THE point of the design: absolute score is not evidence. Everything at
        // 0.8 means nothing stands out, so nothing is said.
        Map<String, Double> flat = new LinkedHashMap<>();
        for (int i = 0; i < 50; i++) {
            flat.put("e" + i, 0.8);
        }
        assertEquals(List.of(), AnalogyPolicy.speak(flat));
    }

    @Test
    void ties_are_broken_deterministically_by_id() {
        Map<String, Double> m = new LinkedHashMap<>();
        m.put("zebra", 0.5);
        m.put("alpha", 0.5);
        for (int i = 0; i < 50; i++) {
            m.put("bg" + i, 0.0);
        }
        assertEquals(List.of("alpha", "zebra"), AnalogyPolicy.speak(m));
        // and repeatedly — no iteration-order dependence
        for (int i = 0; i < 20; i++) {
            assertEquals(List.of("alpha", "zebra"), AnalogyPolicy.speak(m));
        }
    }

    @Test
    void the_margin_is_the_derived_one_and_says_where_it_came_from() {
        assertEquals(0.283, AnalogyPolicy.STANDOUT_MARGIN, 1e-9);
        assertEquals(3, AnalogyPolicy.MAX_SPOKEN);
    }

    @Test
    void the_background_is_the_cue_s_own_median_not_a_global_constant() {
        // Same absolute leader, different backgrounds: the high-background cue
        // must abstain even though its top score is identical.
        Map<String, Double> lowBg = new LinkedHashMap<>();
        Map<String, Double> highBg = new LinkedHashMap<>();
        lowBg.put("top", 0.40);
        highBg.put("top", 0.40);
        for (int i = 0; i < 100; i++) {
            lowBg.put("b" + i, 0.05);
            highBg.put("b" + i, 0.30);
        }
        assertEquals(List.of("top"), AnalogyPolicy.speak(lowBg));
        assertEquals(List.of(), AnalogyPolicy.speak(highBg));
    }

    @Test
    void the_policy_is_pure_no_store_or_rendering_dependencies() {
        for (var f : AnalogyPolicy.class.getDeclaredFields()) {
            assertTrue(java.lang.reflect.Modifier.isStatic(f.getModifiers()),
                "AnalogyPolicy must hold no instance state: " + f.getName());
        }
        for (var ctor : AnalogyPolicy.class.getDeclaredConstructors()) {
            assertEquals(0, ctor.getParameterCount(),
                "AnalogyPolicy must not be constructed with collaborators");
        }
    }
}
