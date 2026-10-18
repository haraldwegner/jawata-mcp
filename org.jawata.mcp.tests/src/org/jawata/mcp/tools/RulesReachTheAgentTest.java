package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.SeatRules;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28g Stage 24 (D45) — what the rules lane promises once rules are INJECTED rather than
 * merely stored: an accepted rule reaches the next typed message with no code change, a candidate
 * never does, a seat's brief carries exactly the rules that bind it, and a rule whose label nobody
 * asks for is listed rather than silently dead.
 *
 * <p>R0: every case here was red before the engine knew the rules lane on a typed message. The
 * cases drive the PUBLISHED verbs ({@code promote_rule}, {@code promote}, {@code recall}) the way
 * the seat and the hook do, because a rule that is only correct when written through the store's
 * class is a rule the wire never delivers.</p>
 */
class RulesReachTheAgentTest {

    private ObjectMapper mapper;
    private H2ExperienceStore store;
    private ExperienceTool tool;

    @BeforeEach
    void setUp() {
        mapper = new ObjectMapper();
        store = H2ExperienceStore.openMemory();
        tool = new ExperienceTool(() -> null, store);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private ToolResponse exec(String kind, java.util.function.Consumer<ObjectNode> fill) {
        ObjectNode a = mapper.createObjectNode();
        a.put("kind", kind);
        fill.accept(a);
        return tool.execute(a);
    }

    @SuppressWarnings("unchecked")
    private String idOf(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> "expected success: " + r.getError());
        return (String) ((Map<String, Object>) r.getData()).get("id");
    }

    /** A source entry a rule can be drawn from. */
    private String source() {
        return idOf(exec("record", a -> {
            a.put("type", "lesson");
            a.put("summary", "a second permission question for a release he already ordered costs him a turn");
            a.put("situation", "when he has said release and I am about to ask whether to push");
            a.put("verdict", "failed_avoid");
        }));
    }

    /** A rule, written the way the seat writes one. A null status or operation sends none. */
    private ToolResponse rule(String summary, String situation, String status, String operation) {
        return exec("promote_rule", a -> {
            a.put("summary", summary);
            a.put("situation", situation);
            if (status != null) {
                a.put("status", status);
            }
            if (operation != null) {
                a.put("operation", operation);
            }
            ArrayNode ids = a.putArray("ids");
            ids.add(source());
        });
    }

    /** Whether the next typed message's recall carries this id. The id is the needle: a cue echoes. */
    private boolean typedMessageRecallReturns(String cue, String id) {
        ToolResponse r = exec("recall", a -> {
            a.put("symptom", cue);
            a.put("occasion", "typed_message");
        });
        assertTrue(r.isSuccess(), () -> "recall failed: " + r.getError());
        return String.valueOf(r.getData()).contains(id);
    }

    @Test
    void aRuleRecordedAfterTheBuildIsInTheNextTypedMessageRecall() {
        String id = idOf(rule("never ask a second time before the push of a release he ordered",
            "when the agent is about to ask whether to push or tag a release he already ordered",
            null, "release"));

        assertTrue(typedMessageRecallReturns("second time release push", id),
            "an accepted rule is in the rules lane, and a typed message asks that lane (D45)");
    }

    @Test
    void aCandidateIsNotInjectedAndAnAcceptedRuleIs() {
        String id = idOf(rule("do not repeat a question he has answered once",
            "when the agent is about to ask what he has answered",
            "candidate", "all"));

        assertFalse(typedMessageRecallReturns("repeat question answered", id),
            "a PROPOSAL is never injected: only his acceptance makes a rule live");

        assertTrue(exec("promote", a -> {
            a.put("id", id);
            a.put("status", "accepted");
        }).isSuccess());

        assertTrue(typedMessageRecallReturns("repeat question answered", id),
            "THE CONTROL: the same rule, once accepted, is injected — so the absence above was the "
                + "status and not a cue that never fit");
    }

    @Test
    void aProposalThatStatesNoBindingIsRefused() {
        ToolResponse unbound = rule("a rule that does not say who it binds",
            "when something happens", "candidate", null);
        assertFalse(unbound.isSuccess(),
            "a proposal must say which seats it binds: an operation label, or 'all' for every seat");

        assertTrue(rule("a rule that binds the project manager", "when a stage is judged done",
            "candidate", "seat:pm").isSuccess(), "THE CONTROL: the same proposal with its binding stated");
    }

    @Test
    void aSeatsBriefCarriesTheRulesThatBindItAndNoOthers() {
        idOf(rule("the project manager checks efficiency", "when a stage is judged done",
            "accepted", "seat:pm"));
        idOf(rule("do not ask a second time before a release push",
            "when he has ordered a release", "accepted", "release"));
        idOf(rule("keep messages short", "when reporting", "accepted", "all"));

        List<String> pm = SeatRules.bindingOn(store, "pm", Set.of("release"));
        List<String> worker = SeatRules.bindingOn(store, "worker", Set.of());

        assertEquals(3, pm.size(), "the PM is bound by its own rule, the release rule and the general one: " + pm);
        assertTrue(pm.stream().anyMatch(t -> t.contains("project manager checks efficiency")));
        assertTrue(pm.stream().anyMatch(t -> t.contains("second time before a release push")));
        assertEquals(1, worker.size(), "a worker answers for none of the PM's rules or a release: " + worker);
        assertTrue(worker.get(0).contains("keep messages short"), "but it is bound by the one bound to no seat");
    }

    @Test
    void withNoLimitSetEveryBindingRuleIsInjectedAndAsetLimitCapsThem() {
        for (int i = 0; i < 5; i++) {
            idOf(rule("general rule number " + i, "when reporting " + i, "accepted", "all"));
        }
        String before = System.getProperty("jawata.rules.limit");
        try {
            System.clearProperty("jawata.rules.limit");
            assertEquals(0, SeatRules.limit(), "unset is unlimited, the default");
            assertEquals(5, SeatRules.bindingOn(store, "worker", Set.of()).size(), "every rule, nothing dropped");

            System.setProperty("jawata.rules.limit", "2");
            assertEquals(2, SeatRules.bindingOn(store, "worker", Set.of()).size(), "a set limit caps the brief");

            System.setProperty("jawata.rules.limit", "not a number");
            assertEquals(5, SeatRules.bindingOn(store, "worker", Set.of()).size(),
                "a value that is no number leaves the limit off instead of dropping rules");
        } finally {
            if (before == null) {
                System.clearProperty("jawata.rules.limit");
            } else {
                System.setProperty("jawata.rules.limit", before);
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theReviewSweepListsTheRulesNobodyAsksForFromTheSeatFilesThemselves() throws Exception {
        java.nio.file.Path seats = java.nio.file.Files.createTempDirectory("jawata-seats-");
        java.nio.file.Files.writeString(seats.resolve("pm.md"),
            "---\nname: pm\nbrain: claude-code\nactivities: [release]\nttl_secs: 30\n---\n\nYou are the pm.\n");
        java.nio.file.Files.writeString(seats.resolve("worker.md"),
            "---\nname: worker\nbrain: claude-code\nttl_secs: 30\n---\n\nYou are the worker.\n");
        idOf(rule("typo", "when a stage is judged done", "accepted", "seat:pmm"));
        idOf(rule("fine", "when he has ordered a release", "accepted", "release"));

        Map<String, Object> asked = (Map<String, Object>) ((Map<String, Object>) exec("review_sweep",
            a -> a.put("seats", seats.toString())).getData()).get("rulesNobodyAsks");
        Map<String, Object> notGiven = (Map<String, Object>) ((Map<String, Object>) exec("review_sweep",
            a -> { }).getData()).get("rulesNobodyAsks");

        assertEquals(Boolean.TRUE, asked.get("checked"));
        assertEquals(2, asked.get("seatsRead"), "both seat files were read");
        assertEquals(Set.of("seat:pmm"), ((Map<String, Object>) asked.get("unasked")).keySet(),
            "pm.md declares release, so only the typo is unasked: " + asked);
        assertEquals(Boolean.FALSE, notGiven.get("checked"),
            "with no directory the sweep says it did not check, rather than listing every rule dead");
    }

    @Test
    void aRuleWhoseLabelNobodyAsksForIsListedNotSilentlyDead() {
        idOf(rule("the project manager checks efficiency", "when a stage is judged done",
            "accepted", "seat:pmm"));
        idOf(rule("do not ask a second time before a release push", "when he has ordered a release",
            "accepted", "release"));

        Map<String, List<String>> unasked = SeatRules.unaskedLabels(store, Set.of("seat:pm", "release"));

        assertEquals(Set.of("seat:pmm"), unasked.keySet(),
            "the typo is the only label no seat and no activity asks for: " + unasked);
    }
}
