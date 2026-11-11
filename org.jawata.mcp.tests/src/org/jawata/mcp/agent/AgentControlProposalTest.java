package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S9, contract R7: a seat's proposal reaches the host as {@code session/request_permission}. When a hire has ended well and changed
 * files, the person is asked once whether to accept it; the answer is kept on the hire's entry and shown to the host, and the agent changes no
 * file on it. An answer that chose nothing, a hire that changed nothing and a hire that failed ask or record nothing.
 */
class AgentControlProposalTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** An agent whose brain runs one hire (planId t1, seat developer) that ends with the given answer, and the messages it sent the host. */
    private static final class Connection {
        final List<JsonNode> said = new ArrayList<>();
        final AcpServer server;

        Connection(String answerJson, String status) {
            this(answerJson, status, AgentRecorder.off(), "");
        }

        Connection(String answerJson, String status, AgentRecorder recorder, String workingDirectory) {
            AgentBrain brain = new AgentBrain() {
                @Override
                public void answer(String prompt, Consumer<String> chunks) {
                    answer(prompt, chunks, call -> { });
                }

                @Override
                public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                    ObjectNode started = JSON.createObjectNode().put("sessionUpdate", "tool_call").put("toolCallId", "t1").put("status", "pending")
                        .put("title", "mcp__jawata-javata-dev__hire");
                    ObjectNode input = started.putObject("rawInput").put("hirer", "chief-engineer").put("seat", "developer").put("brief", "build it");
                    if (!workingDirectory.isEmpty()) {
                        input.put("workingDirectory", workingDirectory);
                    }
                    toolCalls.accept(started);
                    ObjectNode ended = JSON.createObjectNode().put("sessionUpdate", "tool_call_update").put("toolCallId", "t1").put("status", status)
                        .put("title", "mcp__jawata-javata-dev__hire");
                    ended.put("rawOutput", answerJson);
                    toolCalls.accept(ended);
                    chunks.accept("ok");
                }
            };
            server = new AcpServer(brain, List::of, message -> said.add(JSON.valueToTree(message)), TurnBoundary.open(), recorder, null);
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            String session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", "go");
            server.receive(JsonRpcMessage.request(2, "session/prompt", params));
        }

        List<JsonNode> questions() {
            return said.stream().filter(m -> "session/request_permission".equals(m.path("method").asText())).toList();
        }

        void answer(JsonNode question, ObjectNode outcome, Channel from) {
            ObjectNode result = JSON.createObjectNode();
            result.set("outcome", outcome);
            server.receive(JsonRpcMessage.successResponse(question.path("id").asText(), result), from);
        }

        ObjectNode selected(String optionId) {
            return JSON.createObjectNode().put("outcome", "selected").put("optionId", optionId);
        }

        /** The hire entry on the last update of the hire's tool call, as the host last saw it. */
        JsonNode lastEntry() {
            JsonNode entry = null;
            for (JsonNode m : said) {
                JsonNode update = m.path("params").path("update");
                if ("t1".equals(update.path("toolCallId").asText()) && update.path("_meta").has("jawata/hire")) {
                    entry = update.path("_meta").path("jawata/hire");
                }
            }
            return entry;
        }
    }

    private static final String CHANGED = "{\"data\":{\"cleanExit\":true,\"answer\":\"done\",\"changedFiles\":[\"src/A.java\",\"src/B.java\"],"
        + "\"changes\":[{\"path\":\"src/A.java\",\"oldText\":null,\"newText\":\"a\\n\"},{\"path\":\"src/B.java\",\"oldText\":\"b\\n\",\"newText\":\"bb\\n\"}]}}";

    @Test
    @DisplayName("a hire that ended well and changed files is put to the person once, as a permission request listing the files, with accept and reject")
    void aFinishedProposalIsPutToThePersonOnce() {
        Connection connection = new Connection(CHANGED, "completed");

        assertEquals(1, connection.questions().size(), connection.said.toString());
        JsonNode params = connection.questions().get(0).path("params");
        assertEquals("t1", params.path("toolCall").path("toolCallId").asText());
        assertTrue(params.path("toolCall").path("title").asText().contains("developer"), params.toString());
        assertTrue(params.path("toolCall").toString().contains("src/A.java") && params.path("toolCall").toString().contains("src/B.java"), params.toString());
        assertEquals("accept", params.path("options").get(0).path("optionId").asText());
        assertEquals("reject", params.path("options").get(1).path("optionId").asText());
        assertEquals("developer", params.path("_meta").path("jawata/hire").path("seat").asText());
    }

    @Test
    @DisplayName("the request carries each file the hire's answer could show as a diff block, names the rest as text, and is not put at all when there is nothing to draw")
    void theRequestCarriesTheDiffWhenThereIsOne() {
        String withDiff = "{\"data\":{\"cleanExit\":true,\"answer\":\"done\",\"changedFiles\":[\"src/A.java\",\"src/Big.java\"],"
            + "\"changes\":[{\"path\":\"src/A.java\",\"oldText\":\"int one;\\n\",\"newText\":\"int two;\\n\"}],\"changesOmitted\":[\"src/Big.java\"]}}";
        Connection shown = new Connection(withDiff, "completed");
        Connection bare = new Connection("{\"data\":{\"cleanExit\":true,\"answer\":\"done\",\"changedFiles\":[\"src/A.java\"]}}", "completed");

        JsonNode shownContent = shown.questions().get(0).path("params").path("toolCall").path("content");

        // The chat puts a request to the person only when it carries a diff block, so the file that could be shown is one, with its old and new text.
        assertEquals(2, shownContent.size(), shownContent.toString());
        assertEquals("diff", shownContent.get(0).path("type").asText());
        assertEquals("src/A.java", shownContent.get(0).path("path").asText());
        assertEquals("int one;\n", shownContent.get(0).path("oldText").asText());
        assertEquals("int two;\n", shownContent.get(0).path("newText").asText());
        // The file too large to carry is still named, as text.
        assertEquals("src/Big.java", shownContent.get(1).path("content").path("text").asText());
        // A hire with nothing the chat could draw is not put to anyone: the chat would answer "allow once" itself and record a yes nobody gave.
        assertEquals(0, bare.questions().size(), bare.said.toString());
    }

    @Test
    @DisplayName("the person's yes is kept on the hire's entry and shown to the host as an update of the hire's call")
    void anAcceptIsKeptAndShown() {
        Connection connection = new Connection(CHANGED, "completed");

        connection.answer(connection.questions().get(0), connection.selected("accept"), Channel.HOST);

        assertEquals("accepted", connection.lastEntry().path("verdict").asText(), connection.said.toString());
    }

    @Test
    @DisplayName("the person's no is kept as rejected, and nothing is asked a second time")
    void aRejectIsKept() {
        Connection connection = new Connection(CHANGED, "completed");

        connection.answer(connection.questions().get(0), connection.selected("reject"), Channel.HOST);

        assertEquals("rejected", connection.lastEntry().path("verdict").asText());
        assertEquals(1, connection.questions().size());
    }

    /** A git working tree with these tracked files committed, so a hire's changes have a HEAD to be compared with. */
    private static java.nio.file.Path repoWith(java.nio.file.Path folder, java.util.Map<String, String> tracked) throws Exception {
        for (var file : tracked.entrySet()) {
            java.nio.file.Path path = folder.resolve(file.getKey());
            java.nio.file.Files.createDirectories(path.getParent());
            java.nio.file.Files.writeString(path, file.getValue());
        }
        // no line-ending conversion in this repository whatever the machine's own git settings say: the test claims exact bytes, CRLF included
        for (String[] command : new String[][] { { "git", "init", "-q" }, { "git", "config", "core.autocrlf", "false" }, { "git", "add", "-A" },
            { "git", "-c", "user.email=t@t", "-c", "user.name=t", "commit", "-q", "-m", "base" } }) {
            Process git = new ProcessBuilder(command).directory(folder.toFile()).redirectErrorStream(true).start();
            git.getInputStream().readAllBytes();
            assertEquals(0, git.waitFor(), String.join(" ", command));
        }
        return folder;
    }

    /** The hire's answer for these changes: path, text at HEAD (null for a new file), text now, and whether the file was clean before. */
    private static String answerChanging(Object[]... changes) throws Exception {
        ObjectNode data = JSON.createObjectNode().put("cleanExit", true).put("answer", "done");
        var files = data.putArray("changedFiles");
        var shown = data.putArray("changes");
        for (Object[] change : changes) {
            files.add((String) change[0]);
            ObjectNode one = shown.addObject().put("path", (String) change[0]);
            if (change[1] == null) {
                one.putNull("oldText");
            } else {
                one.put("oldText", (String) change[1]);
            }
            one.put("newText", (String) change[2]).put("cleanBefore", (Boolean) change[3]);
        }
        return JSON.createObjectNode().set("data", data).toString();
    }

    @Test
    @DisplayName("open point 8: a rejected proposal puts back the files the hire changed, exactly and only where nothing of the person's is lost, and the card says what it kept and why")
    void aRejectPutsBackWhatItCanAndSaysWhatItKept(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        repoWith(folder, java.util.Map.of("src/B.java", "b\r\n", "src/C.java", "c\n", "src/D.java", "d\n"));
        // the hire edits B (clean before), creates A, and edits C and D; the person had edited C before the hire ran, and edits D after it
        java.nio.file.Files.writeString(folder.resolve("src/B.java"), "bb\r\n");
        java.nio.file.Files.writeString(folder.resolve("src/A.java"), "a\n");
        java.nio.file.Files.writeString(folder.resolve("src/C.java"), "cc\n");
        java.nio.file.Files.writeString(folder.resolve("src/D.java"), "dd and then the person's own edit\n");
        Connection connection = new Connection(answerChanging(new Object[] { "src/A.java", null, "a\n", true },
            new Object[] { "src/B.java", "b\r\n", "bb\r\n", true }, new Object[] { "src/C.java", "c\n", "cc\n", false },
            new Object[] { "src/D.java", "d\n", "dd\n", true }), "completed", AgentRecorder.off(), folder.toString());

        connection.answer(connection.questions().get(0), connection.selected("reject"), Channel.HOST);

        assertFalse(java.nio.file.Files.exists(folder.resolve("src/A.java")), "a file the hire created was not deleted");
        assertEquals("b\r\n", java.nio.file.Files.readString(folder.resolve("src/B.java")), "a clean tracked file is back to its exact bytes, line endings included");
        assertEquals("cc\n", java.nio.file.Files.readString(folder.resolve("src/C.java")), "a file the person had edited before the hire was overwritten");
        assertEquals("dd and then the person's own edit\n", java.nio.file.Files.readString(folder.resolve("src/D.java")),
            "a file changed after the hire was overwritten");
        String said = connection.said.toString();
        assertTrue(said.contains("Put back: src/A.java, src/B.java"), said);
        assertTrue(said.contains("src/C.java (you had changes in it before the hire ran)"), said);
        assertTrue(said.contains("src/D.java (it has changed since the hire)"), said);
        assertEquals("rejected", connection.lastEntry().path("verdict").asText());
    }

    @Test
    @DisplayName("a rejected proposal of a hire that ran in a subfolder is put back from the repository's top folder, where git names its paths")
    void aRejectPutsBackFromTheRepositoryRootWhenTheHireRanInASubfolder(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        repoWith(folder, java.util.Map.of("src/B.java", "b\n"));
        java.nio.file.Files.writeString(folder.resolve("src/B.java"), "bb\n");
        java.nio.file.Files.writeString(folder.resolve("src/N.java"), "n\n");
        // the hire ran in src, so git named its two files from the top folder: src/B.java and src/N.java
        Connection connection = new Connection(answerChanging(new Object[] { "src/B.java", "b\n", "bb\n", true },
            new Object[] { "src/N.java", null, "n\n", true }), "completed", AgentRecorder.off(), folder.resolve("src").toString());

        connection.answer(connection.questions().get(0), connection.selected("reject"), Channel.HOST);

        assertEquals("b\n", java.nio.file.Files.readString(folder.resolve("src/B.java")), connection.said.toString());
        assertFalse(java.nio.file.Files.exists(folder.resolve("src/N.java")), "a file the hire created in a subfolder was not deleted: " + connection.said);
        assertTrue(connection.said.toString().contains("Put back: src/B.java, src/N.java"), connection.said.toString());
    }

    @Test
    @DisplayName("a yes changes no file, and a no with no known folder keeps every file and says so")
    void anAcceptTouchesNoFileAndAnUnknownFolderKeepsEveryFile(@org.junit.jupiter.api.io.TempDir java.nio.file.Path folder) throws Exception {
        repoWith(folder, java.util.Map.of("src/B.java", "b\n"));
        java.nio.file.Files.writeString(folder.resolve("src/B.java"), "bb\n");
        String answer = answerChanging(new Object[] { "src/B.java", "b\n", "bb\n", true });
        Connection yes = new Connection(answer, "completed", AgentRecorder.off(), folder.toString());
        Connection noFolder = new Connection(answer, "completed");

        yes.answer(yes.questions().get(0), yes.selected("accept"), Channel.HOST);
        noFolder.answer(noFolder.questions().get(0), noFolder.selected("reject"), Channel.HOST);

        assertEquals("bb\n", java.nio.file.Files.readString(folder.resolve("src/B.java")), "an accept touched a file");
        assertTrue(noFolder.said.toString().contains("the folder the hire ran in is not known"), noFolder.said.toString());
    }

    @Test
    @DisplayName("a no on a hire whose folder name is not a legal path throws nothing, puts nothing back and says the folder is not known")
    void aRejectWhoseFolderIsNotALegalPathKeepsEveryFileAndSaysSo() {
        // jawata-distinct: the folder is a string no machine reads as a path (a NUL character), unlike the sibling tests, whose folders are real or empty
        // TakeBack logs the folder name it could not read, NUL character included; left on the console that byte lands in the suite's shard log and
        // makes every text tool treat the log as binary, so the warning is caught here (and asserted) instead of being printed
        java.util.logging.Logger takeBackLog = java.util.logging.Logger.getLogger("org.jawata.mcp.agent.TakeBack");
        List<java.util.logging.LogRecord> logged = new ArrayList<>();
        java.util.logging.Handler capture = new java.util.logging.Handler() {
            @Override
            public void publish(java.util.logging.LogRecord record) {
                logged.add(record);
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        boolean toParent = takeBackLog.getUseParentHandlers();
        takeBackLog.addHandler(capture);
        takeBackLog.setUseParentHandlers(false);
        Connection connection;
        try {
            connection = new Connection(CHANGED, "completed", AgentRecorder.off(), "not a legal path \u0000 at all");
            connection.answer(connection.questions().get(0), connection.selected("reject"), Channel.HOST);
        } finally {
            takeBackLog.removeHandler(capture);
            takeBackLog.setUseParentHandlers(toParent);
        }

        assertEquals(1, logged.size(), logged.toString());
        assertEquals(java.util.logging.Level.WARNING, logged.get(0).getLevel());
        String said = connection.said.toString();
        assertTrue(said.contains("Nothing was put back."), said);
        assertTrue(said.contains("src/A.java (the folder the hire ran in is not known)"), said);
        assertTrue(said.contains("src/B.java (the folder the hire ran in is not known)"), said);
        assertEquals("rejected", connection.lastEntry().path("verdict").asText(), said);
    }

    @Test
    @DisplayName("an answer that chose nothing, or one that came from a hired agent, leaves no verdict")
    void noChoiceIsNoVerdict() {
        Connection cancelled = new Connection(CHANGED, "completed");
        cancelled.answer(cancelled.questions().get(0), JSON.createObjectNode().put("outcome", "cancelled"), Channel.HOST);
        Connection forged = new Connection(CHANGED, "completed");
        forged.answer(forged.questions().get(0), forged.selected("accept"), Channel.BRAIN);

        assertFalse(cancelled.lastEntry().has("verdict"), cancelled.lastEntry().toString());
        assertFalse(forged.lastEntry().has("verdict"), "a hired agent's message was taken for the person's yes: " + forged.lastEntry());
    }

    /** A recorder over a stand-in engine that keeps every call the agent makes to it, so a test reads what was written to the lane. */
    private static final class Lane {
        final List<ObjectNode> written = new ArrayList<>();
        final AgentRecorder recorder = new AgentRecorder(new ToolCalls() {
            @Override
            public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                written.add(arguments.deepCopy());
                return ToolResponse.success(java.util.Map.of());
            }

            @Override
            public List<java.util.Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        }, AgentRecorder.Depth.ALL);

        /** The turns written for the person's verdict on a proposal, whatever the key of the other turns. */
        List<ObjectNode> verdicts() {
            return written.stream().filter(args -> args.path("key").asText().startsWith("verdict_")).toList();
        }
    }

    @Test
    @DisplayName("D29: the person's yes or no on a proposal is recorded in the transcript lane under the hire's id, and a cancelled question records none")
    void theVerdictIsRecordedInTheLane() {
        Lane accepted = new Lane();
        Connection yes = new Connection(CHANGED, "completed", accepted.recorder, "");
        yes.answer(yes.questions().get(0), yes.selected("accept"), Channel.HOST);
        Lane rejected = new Lane();
        Connection no = new Connection(CHANGED, "completed", rejected.recorder, "");
        no.answer(no.questions().get(0), no.selected("reject"), Channel.HOST);
        Lane cancelled = new Lane();
        Connection nothing = new Connection(CHANGED, "completed", cancelled.recorder, "");
        nothing.answer(nothing.questions().get(0), JSON.createObjectNode().put("outcome", "cancelled"), Channel.HOST);

        assertEquals(1, accepted.verdicts().size(), accepted.written.toString());
        assertEquals("verdict_t1", accepted.verdicts().get(0).path("key").asText());
        assertTrue(accepted.verdicts().get(0).path("his").asText().contains("accepted"), accepted.verdicts().toString());
        assertTrue(rejected.verdicts().get(0).path("his").asText().contains("rejected"), rejected.verdicts().toString());
        assertEquals(0, cancelled.verdicts().size(), "a question that chose nothing was recorded as a verdict: " + cancelled.verdicts());
    }

    @Test
    @DisplayName("28h item (a): the person's verdict line in the lane carries the hire's updated card, so a session loaded later shows the verdict")
    void theVerdictLineCarriesTheUpdatedCard() {
        Lane lane = new Lane();
        Connection yes = new Connection(CHANGED, "completed", lane.recorder, "");
        yes.answer(yes.questions().get(0), yes.selected("accept"), Channel.HOST);

        JsonNode tools = lane.verdicts().get(0).path("tools");
        java.util.Optional<ObjectNode> card = tools.isArray() && tools.size() == 1 ? HireEntry.readCardLine(tools.get(0).asText()) : java.util.Optional.empty();

        assertTrue(card.isPresent(), "the verdict turn holds no card line: " + lane.verdicts());
        assertEquals("t1", card.get().path("planId").asText());
        assertEquals("accepted", card.get().path("verdict").asText(), "the card in the line is the one with the verdict: " + card.get());
    }

    @Test
    @DisplayName("a hire that changed nothing, a hire that failed, and an answer to a question never asked put nothing to the person")
    void nothingToAskIsNotAsked() {
        Connection none = new Connection("{\"data\":{\"cleanExit\":true,\"answer\":\"read only\",\"changedFiles\":[]}}", "completed");
        Connection failed = new Connection("{\"data\":{\"cleanExit\":false,\"timedOut\":true,\"changedFiles\":[\"src/A.java\"]}}", "completed");
        none.server.receive(JsonRpcMessage.successResponse("jawata-proposal-99", JSON.createObjectNode()), Channel.HOST);

        assertEquals(0, none.questions().size());
        assertEquals(0, failed.questions().size());
    }
}
