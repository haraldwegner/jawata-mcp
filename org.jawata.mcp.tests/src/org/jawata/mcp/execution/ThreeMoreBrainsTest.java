package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * cursor-agent, copilot and codex as hired brains (Sprint 28g S16, D36).
 *
 * <p><b>Every event line asserted below was captured from a REAL RUN on 2026-10-08</b>, not composed
 * to match the parser. Each CLI was driven in an empty directory, read-only where it offered the
 * option, once with a prompt needing no tools and — for codex — once with a prompt that forced a
 * shell call, because the tool-call event shape is stated in no help text. Lines are pasted here
 * verbatim apart from the session ids, so a vendor changing its shape breaks these rather than
 * passing against a fixture that was always a guess.</p>
 *
 * <p>The reach assertions are the C16 clause: all four brains are told which tools to attach, and
 * the brain that cannot be told is REPORTED rather than left out of the count.</p>
 */
class ThreeMoreBrainsTest {

    // ---------------------------------------------------------------- the C16 clause, over all four

    @Test
    @DisplayName("all four brains answer the tool question, and each answers differently")
    void everyBrainAnswersTheToolQuestionWithItsMeasuredReach() {
        // THE COUNT IS THE CLAUSE. Four brains, four answers, none of them silence — and the one
        // that cannot be told is IN this list rather than missing from it, which is the whole
        // difference between a reported brain and a dropped one.
        List<BrainDialect> brains = List.of(new ClaudeCodeDialect(), new CursorDialect(),
            new CopilotDialect(), new CodexDialect());

        assertAll(
            () -> assertEquals(4, brains.size()),
            () -> assertEquals(
                List.of(ToolAttachment.Reach.PER_RUN, ToolAttachment.Reach.NONE,
                    ToolAttachment.Reach.PER_RUN, ToolAttachment.Reach.OUT_OF_BAND),
                brains.stream().map(b -> b.toolAttachment().reach()).toList(),
                "claude per run, cursor cannot be told, copilot per run, codex out of band"),
            // Every one of the four says something a reader can check, which is what stops a
            // "reported" brain from being reported with nothing in it.
            () -> assertTrue(brains.stream().allMatch(b -> !b.toolAttachment().report().isBlank())),
            // And exactly ONE of the four is the clause's subject. A test asserting only that
            // cursor is NONE would still pass if a second brain silently became unsettable.
            () -> assertEquals(1, brains.stream()
                .filter(b -> b.toolAttachment().reach() == ToolAttachment.Reach.NONE).count(),
                "one brain of four cannot be told, measured; if this moves, re-measure"),
            () -> assertEquals(2, brains.stream()
                .filter(b -> b.toolAttachment().settableByTheHirer()).count(),
                "and only two can be told BY THE HIRER, for THIS brief"));
    }

    @Test
    @DisplayName("three of the four publish no dollar cost, so their cost ceiling cannot fire")
    void onlyOneBrainPublishesADollarCost() {
        // Measured by running all four. claude emits total_cost_usd; cursor and codex emit token
        // counts; copilot emits usage.premiumRequests = 0.33, a fractional count of requests
        // against a subscription. Publishing any of those three through costUsd would be a unit
        // error wearing the contracted measure's name, so each terminal line below is driven and
        // each must report NO cost.
        String cursorResult = "{\"type\":\"result\",\"subtype\":\"success\",\"duration_ms\":4573,"
            + "\"is_error\":false,\"result\":\"ok\",\"usage\":{\"inputTokens\":20111,"
            + "\"outputTokens\":46,\"cacheReadTokens\":0,\"cacheWriteTokens\":0}}";
        String copilotResult = "{\"type\":\"result\",\"exitCode\":0,\"usage\":"
            + "{\"premiumRequests\":0.33,\"totalApiDurationMs\":1490,\"sessionDurationMs\":2920}}";
        String codexResult = "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":12851,"
            + "\"cached_input_tokens\":9984,\"output_tokens\":5,\"reasoning_output_tokens\":0}}";

        assertAll(
            () -> assertTrue(new CursorDialect().read(cursorResult).costUsd().isEmpty(),
                "token counts are not dollars"),
            () -> assertTrue(new CopilotDialect().read(copilotResult).costUsd().isEmpty(),
                "0.33 premium requests is not 0.33 dollars"),
            () -> assertTrue(new CodexDialect().read(codexResult).costUsd().isEmpty(),
                "token counts are not dollars"),
            // THE CONTROL. Without it this test would pass against a seam that lost every cost,
            // which is the opposite of the finding it records.
            () -> assertEquals(0.0421, new ClaudeCodeDialect()
                .read("{\"type\":\"result\",\"result\":\"ok\",\"total_cost_usd\":0.0421}")
                .costUsd().orElseThrow(), 1e-9, "and the one brain that DOES publish dollars"));
    }

    // ---------------------------------------------------------------------------------- cursor

    @Test
    @DisplayName("cursor is told read-only and reports THAT as its containment, not 'unsupported'")
    void cursorReportsTheContainmentItActuallyHas() {
        ToolAttachment readOnly = new CursorDialect().toolAttachment();
        ToolAttachment writable = new CursorDialect("cursor-agent", false, null).toolAttachment();

        assertAll(
            () -> assertEquals(ToolAttachment.Reach.NONE, readOnly.reach()),
            () -> assertTrue(readOnly.report().contains("--mode plan"),
                "the containment this hire actually passes: " + readOnly.report()),
            // The two differ, and that is the point: a run that was NOT given --mode plan had
            // write and shell access, and its record must not read like one that was.
            () -> assertFalse(readOnly.report().equals(writable.report()),
                "a writable hire reports differently from a read-only one"),
            () -> assertTrue(writable.report().contains("NOT passed"),
                "and it says plainly that the containment was not applied: " + writable.report()));
    }

    @Test
    @DisplayName("cursor's argv carries --trust, without which it refuses and says nothing")
    void cursorCarriesTheTrustFlagItRefusesWithout() {
        List<String> argv = new CursorDialect().command("the brief", List.of("Read"));

        assertAll(
            // Measured: without this the run exits 1 with "Workspace Trust Required" and emits no
            // events at all — an empty answer that is NOT the brain choosing to say nothing.
            () -> assertTrue(argv.contains("--trust"), argv.toString()),
            () -> assertTrue(argv.containsAll(List.of("--print", "--output-format", "stream-json")),
                argv.toString()),
            () -> assertEquals(List.of("--mode", "plan"),
                argv.subList(argv.indexOf("--mode"), argv.indexOf("--mode") + 2),
                "read-only by default, because a hired run proposes"),
            // The tools are DROPPED rather than encoded — this brain has nowhere to put them, and
            // inventing a flag would have a run report tools as attached that never were.
            () -> assertFalse(argv.contains("Read"), "the tool list is dropped: " + argv),
            () -> assertEquals("the brief", argv.get(argv.size() - 1),
                "the prompt travels last, as one argument"));
    }

    @Test
    @DisplayName("cursor's thinking deltas are not its answer")
    void cursorsThinkingIsNotItsAnswer() {
        CursorDialect cursor = new CursorDialect();
        // Verbatim from the captured run: four of these preceded a one-word reply.
        BrainLine thinking = cursor.read("{\"type\":\"thinking\",\"subtype\":\"delta\","
            + "\"text\":\"The user requested a\",\"timestamp_ms\":1791473599319}");
        BrainLine answer = cursor.read("{\"type\":\"assistant\",\"message\":{\"role\":"
            + "\"assistant\",\"content\":[{\"type\":\"text\",\"text\":\"ok\"}]}}");

        assertAll(
            () -> assertTrue(thinking.parsed(), "it is read, and understood, and not the answer"),
            () -> assertTrue(thinking.text().isEmpty(),
                "collecting deltas would put the brain's reasoning in the record as its answer,"
                    + " in fragments"),
            () -> assertEquals("ok", answer.text().orElseThrow(),
                "and the assistant event IS the answer — the same content-array shape as claude's"));
    }

    // --------------------------------------------------------------------------------- copilot

    @Test
    @DisplayName("copilot's text is a STRING and its tool calls are an array on the message")
    void copilotReadsItsOwnShapeRatherThanAContentArray() {
        // Verbatim from the captured run, trimmed of the opaque reasoning blob.
        BrainLine message = new CopilotDialect().read("{\"type\":\"assistant.message\",\"data\":"
            + "{\"messageId\":\"96d6f879\",\"model\":\"claude-haiku-4.5\",\"content\":\"ok\","
            + "\"toolRequests\":[{\"name\":\"shell\"},{\"name\":\"view\"}],\"turnId\":\"0\"}}");

        assertAll(
            () -> assertEquals("ok", message.text().orElseThrow(),
                "data.content is a plain string here, NOT a content array"),
            () -> assertEquals(2, message.toolSteps(),
                "and the tool calls are a named array on the message, not typed blocks in it"));
    }

    @Test
    @DisplayName("...and its streaming deltas are not counted a second time")
    void copilotDoesNotCountTheSameAnswerTwice() {
        // assistant.message_delta carries the SAME answer in fragments. Reading both would report
        // the text twice, which is the defect this case exists against.
        BrainLine delta = new CopilotDialect().read("{\"type\":\"assistant.message_delta\","
            + "\"data\":{\"content\":\"ok\"},\"ephemeral\":true}");

        assertAll(
            () -> assertTrue(delta.parsed()),
            () -> assertTrue(delta.text().isEmpty(), "the completed message is the one that counts"),
            () -> assertEquals(0, delta.toolSteps()));
    }

    @Test
    @DisplayName("the warning that stopped copilot's first run reaches the record, labelled")
    void copilotsBlockingWarningIsNotLost() {
        // THE MEASURED FAILURE, verbatim. On its first run this brain never reached the model: it
        // loaded three local MCP servers, emitted this, and exited 1 having said nothing.
        BrainLine warning = new CopilotDialect().read("{\"type\":\"session.warning\",\"data\":"
            + "{\"warningType\":\"compaction_static_context_blocked\",\"message\":\"Static context"
            + " is using 126% of available input tokens, leaving too little room for the"
            + " conversation.\"}}");

        String text = warning.text().orElseThrow();
        assertAll(
            () -> assertTrue(text.contains("126%"),
                "the number a reader needs in order to act: " + text),
            () -> assertTrue(text.contains("compaction_static_context_blocked"), text),
            // LABELLED, because it is the CLI's warning and not the model's answer — and a run
            // whose model was never called must not render as a brain that chose to say nothing.
            () -> assertTrue(text.startsWith("[copilot warning"),
                "a reader can tell this from the brain's own words: " + text));
    }

    @Test
    @DisplayName("copilot is told which tools are AVAILABLE, not merely which need no prompt")
    void copilotNamesTheFlagThatBoundsAvailability() {
        List<String> argv = new CopilotDialect("copilot", true, List.of("jawata-javata-dev"))
            .command("the brief", List.of("shell", "view"));
        int flag = argv.indexOf("--available-tools");

        assertAll(
            () -> assertTrue(flag >= 0, argv.toString()),
            () -> assertEquals(List.of("shell", "view"), argv.subList(flag + 1, argv.size()),
                "separate arguments — confirmed by a run, not inferred: " + argv),
            () -> assertTrue(argv.containsAll(List.of("--output-format", "json")), argv.toString()),
            // The levers that decided whether this brain could run AT ALL.
            () -> assertTrue(argv.contains("--disable-builtin-mcps"), argv.toString()),
            () -> assertEquals(List.of("--disable-mcp-server", "jawata-javata-dev"),
                argv.subList(argv.indexOf("--disable-mcp-server"),
                    argv.indexOf("--disable-mcp-server") + 2)),
            // --allow-tool only suppresses a permission prompt for a tool the model still HAS;
            // naming it in the report would overstate the containment.
            () -> assertTrue(new CopilotDialect().toolAttachment().mechanism()
                .contains("--available-tools"),
                new CopilotDialect().toolAttachment().mechanism()),
            () -> assertFalse(new CopilotDialect().toolAttachment().mechanism()
                .contains("--allow-tool"),
                "the report names what bounds availability, not what silences a prompt"));
    }

    // ----------------------------------------------------------------------------------- codex

    @Test
    @DisplayName("codex's tool call is counted once, though it arrives twice")
    void codexCountsAToolCallOnceNotTwice() {
        CodexDialect codex = new CodexDialect();
        // Verbatim from the tool-using run: ONE shell call produced BOTH of these, same item id.
        BrainLine started = codex.read("{\"type\":\"item.started\",\"item\":{\"id\":\"item_1\","
            + "\"type\":\"command_execution\",\"command\":\"/bin/bash -lc 'cat one.txt'\","
            + "\"aggregated_output\":\"\",\"exit_code\":null,\"status\":\"in_progress\"}}");
        BrainLine completed = codex.read("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_1\","
            + "\"type\":\"command_execution\",\"command\":\"/bin/bash -lc 'cat one.txt'\","
            + "\"aggregated_output\":\"alpha\\n\",\"exit_code\":0,\"status\":\"completed\"}}");

        assertAll(
            () -> assertEquals(0, started.toolSteps(),
                "counting the start as well would double every tool call and halve the ceiling"),
            () -> assertEquals(1, completed.toolSteps(), "the completed call is the one step"),
            () -> assertTrue(started.parsed() && completed.parsed()),
            () -> assertTrue(completed.text().isEmpty(),
                "a shell call's output is not the brain speaking"));
    }

    @Test
    @DisplayName("...and its answer is the JOIN of its message items, not the last of them")
    void codexsAnswerArrivesInPieces() {
        CodexDialect codex = new CodexDialect();
        // Verbatim: the brain spoke before the shell call and again after it.
        BrainLine before = codex.read("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_0\","
            + "\"type\":\"agent_message\",\"text\":\"I will read the requested file.\"}}");
        BrainLine after = codex.read("{\"type\":\"item.completed\",\"item\":{\"id\":\"item_2\","
            + "\"type\":\"agent_message\",\"text\":\"alpha\"}}");

        assertAll(
            () -> assertEquals("I will read the requested file.", before.text().orElseThrow()),
            () -> assertEquals("alpha", after.text().orElseThrow()),
            // Each line reports its own piece; the driver joins them. A dialect that tried to be
            // the whole answer would have had to pick one, and either choice loses half of it.
            () -> assertEquals(0, before.toolSteps() + after.toolSteps()));
    }

    @Test
    @DisplayName("an unmeasured codex item type is read and NOT counted as a step")
    void codexDoesNotInventAStepForAnItemItHasNotSeen() {
        // Only agent_message and command_execution were observed. A dialect that counted every
        // unknown completed item as a tool step would make the ceiling fire on events nobody has
        // seen — and the honest answer about an unmeasured shape is to report nothing about it.
        BrainLine unknown = new CodexDialect().read("{\"type\":\"item.completed\","
            + "\"item\":{\"id\":\"item_9\",\"type\":\"some_future_kind\",\"text\":\"x\"}}");

        assertAll(
            () -> assertTrue(unknown.parsed(), "read and understood"),
            () -> assertEquals(0, unknown.toolSteps()),
            () -> assertTrue(unknown.text().isEmpty()));
    }

    @Test
    @DisplayName("codex carries its trust flag and its sandbox, and reports both honestly")
    void codexCarriesTheFlagsItRefusesWithout() {
        List<String> argv = new CodexDialect().command("the brief", List.of("shell"));
        ToolAttachment attachment = new CodexDialect().toolAttachment();

        assertAll(
            () -> assertEquals("exec", argv.get(1), "the headless subcommand"),
            () -> assertTrue(argv.contains("--json"), argv.toString()),
            // Measured: without this it exits 1 with "Not inside a trusted directory" and emits
            // nothing at all.
            () -> assertTrue(argv.contains("--skip-git-repo-check"), argv.toString()),
            () -> assertEquals(List.of("--sandbox", "read-only"),
                argv.subList(argv.indexOf("--sandbox"), argv.indexOf("--sandbox") + 2),
                "read-only by default, because a hired run proposes"),
            () -> assertFalse(argv.contains("shell"), "the tool list is dropped: " + argv),
            // OUT_OF_BAND rather than NONE: the tools CAN be set, just not by the hirer and not
            // for one run. Reporting it as unsettable would be as wrong as reporting it settable.
            () -> assertEquals(ToolAttachment.Reach.OUT_OF_BAND, attachment.reach()),
            () -> assertFalse(attachment.settableByTheHirer()),
            () -> assertTrue(attachment.report().contains("reported, not chosen"),
                attachment.report()),
            () -> assertTrue(attachment.mechanism().contains("--sandbox read-only"),
                "and the containment it DOES take per run: " + attachment.mechanism()));
    }
}
