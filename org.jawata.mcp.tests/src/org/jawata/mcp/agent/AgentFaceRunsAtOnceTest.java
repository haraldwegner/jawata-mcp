package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S9: what must act while a prompt is running. A hire runs inside the principal's prompt, so the panel's stop queued behind that
 * prompt would act only when the hire it was meant to end had ended; the tests of the stop call the server directly and cannot see this, so
 * the route's own rule is held here.
 */
class AgentFaceRunsAtOnceTest {

    @Test
    @DisplayName("the person's switch and cancel, the panel's stop, pick, transcript and brains list, and the host's answers (which have no method) act at once")
    void whatTheDoorMustNotQueue() {
        for (String method : new String[] {"session/set_config_option", "session/cancel", "_jawata/hire/stop", "_jawata/hire/brain",
            "_jawata/hire/transcript", "_jawata/hire/figures", "_jawata/brains"}) {
            assertTrue(AgentFace.runsAtOnce(method), method + " was queued behind the running prompt");
        }
        assertTrue(AgentFace.runsAtOnce(null), "the host's answer to a question was queued behind the running prompt");
    }

    private static final com.fasterxml.jackson.databind.ObjectMapper JSON = new com.fasterxml.jackson.databind.ObjectMapper();

    private static org.jawata.mcp.protocol.JsonRpcMessage request(int id, String method, String params) throws Exception {
        return JSON.readValue("{\"jsonrpc\":\"2.0\",\"id\":" + id + ",\"method\":\"" + method + "\",\"params\":" + params + "}", org.jawata.mcp.protocol.JsonRpcMessage.class);
    }

    @Test
    @DisplayName("D53 (b): what a side thread sends is worked beside the main turn, and the main conversation's prompt is not")
    void aSideThreadIsWorkedBesideTheTurn() throws Exception {
        java.util.List<org.jawata.mcp.protocol.JsonRpcMessage> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        AcpServer server = new AcpServer((prompt, chunks) -> { }, java.util.List::of, sent::add);

        org.jawata.mcp.protocol.JsonRpcMessage openSide = request(1, "session/new", "{\"cwd\":\"/w\",\"_meta\":{\"jawata/side\":true}}");
        org.jawata.mcp.protocol.JsonRpcMessage openMain = request(2, "session/new", "{\"cwd\":\"/w\"}");
        assertTrue(server.worksBeside(openSide), "opening the side thread waited behind the main turn");
        assertFalse(server.worksBeside(openMain), "opening the main conversation overtook its turn");
        server.receive(openSide);
        server.receive(openMain);
        String side = JSON.valueToTree(sent.get(0).getResult()).get("sessionId").asText();
        String main = JSON.valueToTree(sent.stream().filter(m -> Integer.valueOf(2).equals(m.getId())).findFirst().orElseThrow().getResult()).get("sessionId").asText();

        assertTrue(server.worksBeside(request(3, "session/prompt", "{\"sessionId\":\"" + side + "\",\"prompt\":[]}")), "a side question waited behind the main turn");
        assertFalse(server.worksBeside(request(4, "session/prompt", "{\"sessionId\":\"" + main + "\",\"prompt\":[]}")), "a prompt of the main conversation left its order");
    }

    @Test
    @DisplayName("D53 (f): the person's stop, sent over the wire, reaches the brain that is answering")
    void aStopOverTheWireReachesTheAnsweringBrain() throws Exception {
        java.util.concurrent.CountDownLatch answering = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.CountDownLatch told = new java.util.concurrent.CountDownLatch(1);
        AgentBrain brain = new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                answering.countDown();
                try {
                    told.await(20, java.util.concurrent.TimeUnit.SECONDS);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt(); // the interrupt alone ends this wait, which is why told is asserted and not the turn's end
                }
            }

            @Override
            public void cancel() {
                told.countDown();
            }
        };
        java.util.List<org.jawata.mcp.protocol.JsonRpcMessage> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        AcpServer server = new AcpServer(brain, java.util.List::of, sent::add);
        server.receive(request(1, "session/new", "{\"cwd\":\"/w\"}"));
        String session = JSON.valueToTree(sent.stream().filter(m -> Integer.valueOf(1).equals(m.getId())).findFirst().orElseThrow().getResult()).get("sessionId").asText();
        org.jawata.mcp.protocol.JsonRpcMessage prompt = request(2, "session/prompt", "{\"sessionId\":\"" + session + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"go\"}]}");
        Thread turn = new Thread(() -> server.receive(prompt));
        turn.start();
        assertTrue(answering.await(10, java.util.concurrent.TimeUnit.SECONDS), "the brain never began");

        server.receive(JSON.readValue("{\"jsonrpc\":\"2.0\",\"method\":\"session/cancel\",\"params\":{\"sessionId\":\"" + session + "\"}}", org.jawata.mcp.protocol.JsonRpcMessage.class));

        turn.join(15_000);
        assertFalse(turn.isAlive(), "the turn went on after the stop");
        assertTrue(told.getCount() == 0, "the brain was never told of the stop");
    }

    @Test
    @DisplayName("D53: each conversation's brain, the side discussion's too, is told the folder the chat opened before it starts")
    void eachBrainIsToldTheFolderBeforeItStarts() throws Exception {
        java.util.List<String> told = new java.util.concurrent.CopyOnWriteArrayList<>();
        AgentBrain main = new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
            }

            @Override
            public void workIn(String folder) {
                told.add("main:" + folder);
            }

            @Override
            public void prepare() {
                told.add("main:prepare");
            }
        };
        AgentBrain side = new AgentBrain() {
            @Override
            public void answer(String prompt, java.util.function.Consumer<String> chunks) {
                told.add("side:answer");
            }

            @Override
            public void workIn(String folder) {
                told.add("side:" + folder);
            }
        };
        java.util.List<org.jawata.mcp.protocol.JsonRpcMessage> sent = new java.util.concurrent.CopyOnWriteArrayList<>();
        AcpServer server = new AcpServer(main, java.util.List::of, sent::add, TurnBoundary.open(), AgentRecorder.off(), () -> side, null);

        server.receive(request(1, "session/new", "{\"cwd\":\"/opened\"}"));
        server.receive(request(2, "session/new", "{\"cwd\":\"/opened\",\"_meta\":{\"jawata/side\":true}}"));
        String sideId = JSON.valueToTree(sent.stream().filter(m -> Integer.valueOf(2).equals(m.getId())).findFirst().orElseThrow().getResult()).get("sessionId").asText();
        server.receive(request(3, "session/prompt", "{\"sessionId\":\"" + sideId + "\",\"prompt\":[{\"type\":\"text\",\"text\":\"x\"}]}"));

        org.junit.jupiter.api.Assertions.assertEquals(java.util.List.of("main:/opened", "main:prepare", "side:/opened", "side:answer"), told);
    }

    @Test
    @DisplayName("the control: a prompt and the other requests are worked in order on the connection's one thread")
    void whatIsWorkedInOrder() {
        for (String method : new String[] {"session/prompt", "session/new", "session/list", "session/load", "initialize"}) {
            assertFalse(AgentFace.runsAtOnce(method), method + " was run at once and may overtake the prompt before it");
        }
    }
}
