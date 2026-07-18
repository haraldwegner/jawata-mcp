package org.jawata.mcp.learn;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 26 Stage 3: the learner core — the no-text-features PIN, per-event
 * convergence, the rolling gate with its demotion guard, state persistence,
 * the serve budget contract, and the seven-learner service surface.
 */
class LearnerCoreTest {

    private H2ExperienceStore store;
    private LearnerEventStore events;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.openMemory();
        events = new LearnerEventStore(store);
    }

    @AfterEach
    void tearDown() throws Exception {
        store.close();
    }

    // ---------- the binding modeling constraint ----------

    @Test
    @DisplayName("PIN: identifier and literal renames yield IDENTICAL feature vectors (no text features)")
    void features_are_structural_never_textual() {
        String beforeA = "class A { int f(int x) { return x + 1; } }";
        String afterA = "class A { int f(int x) { log(x); return x + 1; } void log(int v) {} }";
        String beforeB = "class Zebra { int quux(int howdy) { return howdy + 42; } }";
        String afterB = "class Zebra { int quux(int howdy) { trace(howdy); return howdy + 42; } void trace(int z) {} }";
        assertArrayEquals(FeatureVector.extract(beforeA, afterA),
            FeatureVector.extract(beforeB, afterB),
            "same structure, different names/literals => identical vectors");
    }

    @Test
    void features_distinguish_structural_from_trivial() {
        String base = "class A { int f(int x) { return x + 1; } }";
        double[] commentOnly = FeatureVector.extract(base,
            "class A { /* explains f */ int f(int x) { return x + 1; } }");
        double[] signatureChange = FeatureVector.extract(base,
            "class A { int f(int x, int y) { return x + y; } }");
        assertFalse(java.util.Arrays.equals(commentOnly, signatureChange),
            "a signature change and a comment tweak must not look alike");
    }

    // ---------- per-event convergence ----------

    @Test
    void logreg_converges_on_a_separable_stream() {
        OnlineLogreg m = new OnlineLogreg(2, 0.5);
        Random rnd = new Random(7);
        for (int i = 0; i < 500; i++) {
            boolean positive = rnd.nextBoolean();
            double[] x = positive ? new double[] {1, 0} : new double[] {0, 1};
            m.update(x, positive ? 1 : 0);
        }
        assertTrue(m.predict(new double[] {1, 0}) > 0.9);
        assertTrue(m.predict(new double[] {0, 1}) < 0.1);
        assertEquals(500, m.updates());
    }

    @Test
    void tree_learns_a_simple_rule_on_rebuild() {
        HandTree tree = new HandTree();
        List<HandTree.Sample> samples = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            boolean pos = i % 2 == 0;
            samples.add(new HandTree.Sample(new double[] {pos ? 1 : 0, 0.3}, pos ? 1 : 0));
        }
        tree.rebuild(samples);
        assertTrue(tree.predict(new double[] {1, 0.3}) > 0.9);
        assertTrue(tree.predict(new double[] {0, 0.3}) < 0.1);
    }

    // ---------- the rolling gate + demotion guard ----------

    @Test
    void gate_promotes_only_when_beating_the_rule_on_BOTH_kinds_and_demotes_on_decay() {
        RollingRecord r = new RollingRecord();
        // Model beats the rule on both kinds at volume.
        for (int i = 0; i < 50; i++) {
            boolean actual = i % 2 == 0;
            r.add(new RollingRecord.Entry(!actual, actual, actual)); // rule always wrong, model right
        }
        assertTrue(r.modelDecides(), "strictly better on both kinds at volume");
        // A tie is not a promotion (fresh window, both perfect).
        RollingRecord tie = new RollingRecord();
        for (int i = 0; i < 50; i++) {
            boolean actual = i % 2 == 0;
            tie.add(new RollingRecord.Entry(actual, actual, actual));
        }
        assertFalse(tie.modelDecides(), "a tie never promotes");
        // Decay: the distribution flips, the model goes wrong => DEMOTED.
        for (int i = 0; i < RollingRecord.WINDOW; i++) {
            boolean actual = i % 2 == 0;
            r.add(new RollingRecord.Entry(actual, !actual, actual)); // rule right, model wrong
        }
        assertFalse(r.modelDecides(), "decay demotes back to shadow");
    }

    // ---------- learner: shadow-first serving + persistence ----------

    @Test
    void shadow_learner_answers_with_the_rule_and_says_so() {
        Learner l = new Learner("t", 2, events);
        Learner.Advice a = l.serve(new double[] {1, 0}, true);
        assertTrue(a.positive(), "shadow => the rule's answer stands");
        assertFalse(a.modelDecided());
        assertTrue(a.note().contains("shadow"));
    }

    @Test
    void learner_state_survives_a_restart() {
        Learner l = new Learner("persist-me", 2, events);
        for (int i = 0; i < 40; i++) {
            boolean pos = i % 2 == 0;
            l.observe(new double[] {pos ? 1 : 0, pos ? 0 : 1}, pos, !pos);
        }
        long seen = l.eventsSeen();
        Learner reloaded = new Learner("persist-me", 2, events);
        assertEquals(seen, reloaded.eventsSeen(), "events-seen survives");
        assertEquals(l.decides(), reloaded.decides(), "gate state survives");
    }

    // ---------- the service surface ----------

    @Test
    void the_service_reports_all_seven_learners_and_a_forced_pass_runs() {
        LearnerService service = new LearnerService(events);
        Map<String, Object> status = service.status();
        assertEquals(7, ((List<?>) status.get("learners")).size(), "seven learners");
        assertEquals(0L, status.get("failedWrites"));
        Map<String, Object> trained = service.train();
        assertEquals(7, ((List<?>) trained.get("learners")).size());
    }

    @Test
    void the_experience_kinds_train_and_learner_status_respond() throws Exception {
        org.jawata.mcp.tools.ExperienceTool tool =
            new org.jawata.mcp.tools.ExperienceTool(() -> null, store);
        com.fasterxml.jackson.databind.ObjectMapper mapper =
            new com.fasterxml.jackson.databind.ObjectMapper();
        // Unwired: honest refusal, never a fake-empty success.
        var unwired = tool.execute(mapper.readTree("{\"kind\":\"train\"}"));
        assertFalse(unwired.isSuccess());
        // Wired: both kinds answer with the seven-learner report.
        tool.setLearnerService(new LearnerService(events));
        var trained = tool.execute(mapper.readTree("{\"kind\":\"train\"}"));
        assertTrue(trained.isSuccess());
        assertTrue(String.valueOf(trained.getData()).contains("edit-switch"));
        var status = tool.execute(mapper.readTree("{\"kind\":\"learner_status\"}"));
        assertTrue(status.isSuccess());
        assertTrue(String.valueOf(status.getData()).contains("SHADOW"));
    }

    @Test
    void the_edit_switch_serving_seam_learns_and_can_earn_decides() {
        LearnerService service = new LearnerService(events);
        String trivialBefore = "class A { int f() { return 1; } }";
        String trivialAfter = "class A { /* doc */ int f() { return 1; } }";
        String structuralBefore = "class B { int g() { return 1; } }";
        String structuralAfter = "class B { int g(int n) { return n; } int h() { return 2; } }";
        // The rule is WRONG on purpose (inverted) so the model can beat it.
        for (int i = 0; i < 60; i++) {
            service.observeEdit(trivialBefore, trivialAfter, false, true);
            service.observeEdit(structuralBefore, structuralAfter, true, false);
        }
        Learner.Advice a = service.adviseEdit(structuralBefore, structuralAfter, false);
        assertTrue(a.modelDecided(), "beating a broken rule on both kinds earns DECIDES: "
            + a.note());
        assertTrue(a.positive(), "the promoted model calls the structural edit structural");
    }
}
