package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import org.jawata.mcp.engine.ToolCalls;
import org.jawata.mcp.execution.HireRegistry;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.TranscriptStore;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.protocol.JsonRpcMessage;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Stage 24, M14, ND4 (D55): a hire whose lead has ended reports to the principal, with a line saying whose hire it was, and a hire that finished
 * while the chat was closed is reported when the chat is opened again.
 */
class HireEndingsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private H2ExperienceStore store;
    private final List<Thread> threads = new ArrayList<>();
    private final List<AcpServer> servers = new ArrayList<>();

    @BeforeEach
    void openTheLane() {
        store = H2ExperienceStore.openMemory();
    }

    @AfterEach
    void closeEverything() throws InterruptedException {
        for (Thread thread : threads) {
            thread.interrupt();
            thread.join(5_000);
        }
        servers.forEach(AcpServer::closed);
        store.close();
    }

    /** The engine's tool surface as the agent sees it: only {@code experience}, backed by the real transcript lane. */
    private ToolCalls tools() {
        ExperienceTool experience = new ExperienceTool(() -> null, store);
        experience.setTranscriptStore(new TranscriptStore(store));
        return new ToolCalls() {
            @Override public ToolResponse callTool(String name, JsonNode arguments, CallerIdentity caller) {
                return "experience".equals(name) ? experience.execute(arguments) : ToolResponse.error("NO_SUCH_TOOL", name, "none");
            }
            @Override public List<Map<String, Object>> getToolDefinitions() {
                return List.of();
            }
        };
    }

    /** A connection whose brain notes every prompt it is given. */
    private final class Connection {
        final List<JsonNode> said = new CopyOnWriteArrayList<>();
        final List<String> prompts = new CopyOnWriteArrayList<>();
        final AcpServer server;
        String session;

        Connection() {
            AgentBrain brain = new AgentBrain() {
                @Override
                public void answer(String prompt, Consumer<String> chunks) {
                    answer(prompt, chunks, call -> { });
                }

                @Override
                public void answer(String prompt, Consumer<String> chunks, Consumer<JsonNode> toolCalls) {
                    prompts.add(prompt);
                    chunks.accept("ok");
                }
            };
            server = new AcpServer(brain, List::of, m -> said.add(JSON.valueToTree(m)), TurnBoundary.open(),
                new AgentRecorder(tools(), AgentRecorder.Depth.ALL), null, null);
            servers.add(server);
        }

        void open() {
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        }

        void personSays(String words) {
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", words);
            server.receive(JsonRpcMessage.request(9, "session/prompt", params));
        }
    }

    /** A hire running on a thread of its own as the engine runs one, issued by this caller; it ends when its latch is released. */
    private CountDownLatch aHireIsOut(String seat, String brief, String issuedBy, String planId, boolean inTheBackground) throws InterruptedException {
        CountDownLatch registered = new CountDownLatch(1);
        CountDownLatch end = new CountDownLatch(1);
        Thread thread = new Thread(() -> {
            if (inTheBackground) {
                HireRegistry.shared().nextHireIsBackground();
            }
            try (HireRegistry.Registration ignored = HireRegistry.shared().begin(seat, brief, issuedBy)) {
                HireRegistry.shared().planOpened(planId);
                registered.countDown();
                end.await();
            } catch (InterruptedException ended) {
                Thread.currentThread().interrupt();
            }
        });
        thread.setDaemon(true);
        threads.add(thread);
        thread.start();
        assertTrue(registered.await(5, TimeUnit.SECONDS), "the hire did not register as running");
        return end;
    }

    private static ObjectNode answered(String planId, String name, String text) {
        return JSON.createObjectNode().put("plan", planId).put("name", name).put("cleanExit", true).put("answer", text);
    }

    private static boolean waitUntil(java.util.function.BooleanSupplier condition, int seconds) throws InterruptedException {
        for (int i = 0; i < seconds * 20 && !condition.getAsBoolean(); i++) {
            Thread.sleep(50);
        }
        return condition.getAsBoolean();
    }

    @Test
    @DisplayName("T8: a hire whose lead has ended reports to the principal, and the report says whose hire it was and that the lead has ended")
    void aDeadLeadsHireReportsToThePrincipal() throws Exception {
        Connection chat = new Connection();
        chat.open();
        CountDownLatch leadEnds = aHireIsOut("pm", "m14 the lead", chat.session, "m14-dead-lead", false);
        aHireIsOut("developer", "m14 the child", "m14-dead-lead", "m14-dead-child", true);

        // The lead's run is over while its hire is still out.
        leadEnds.countDown();
        Thread.sleep(300);
        HireRegistry.shared().record("developer", "m14 the child", answered("m14-dead-child", "Joe", "the code is written"));

        boolean reported = waitUntil(() -> !chat.prompts.isEmpty(), 10);
        assertAll(
            () -> assertTrue(reported, "the report of the dead lead's hire never reached the principal"),
            () -> assertTrue(chat.prompts.get(0).contains("which has ended"), "the report does not say the lead has ended: " + chat.prompts),
            () -> assertTrue(chat.prompts.get(0).contains("pm"), "the report does not say whose hire it was: " + chat.prompts),
            () -> assertTrue(chat.prompts.get(0).contains("Joe · developer"), "the report does not name the hire: " + chat.prompts));
    }

    @Test
    @DisplayName("ND4: a hire that finishes while the chat is closed is reported when the chat is opened again, once, with a line saying so")
    void aReportThatCameWhileTheChatWasClosedIsDeliveredWhenItIsOpenedAgain() throws Exception {
        Connection first = new Connection();
        first.open();
        first.personSays("start the work");
        String session = first.session;
        first.server.closed();

        aHireIsOut("developer", "m14 closed brief", session, "m14-closed-plan", true);
        HireRegistry.shared().record("developer", "m14 closed brief", answered("m14-closed-plan", "Joe", "done while you were away"));
        int waiting = HireRegistry.shared().undeliveredFor(session).size();

        Connection later = new Connection();
        later.server.receive(JsonRpcMessage.request(2, "session/load", JSON.createObjectNode().put("sessionId", session).put("cwd", "/w")));
        boolean told = waitUntil(() -> !later.prompts.isEmpty(), 10);
        Thread.sleep(300);

        assertAll(
            () -> assertEquals(1, waiting, "the report was not kept for the chat that was closed"),
            () -> assertTrue(told, "the chat opened again was never told what finished while it was closed"),
            () -> assertTrue(later.prompts.get(0).contains("arrived while the chat was closed"), String.valueOf(later.prompts)),
            () -> assertTrue(later.prompts.get(0).contains("done while you were away")),
            () -> assertEquals(1, later.prompts.size(), "the report was delivered more than once: " + later.prompts),
            () -> assertTrue(HireRegistry.shared().undeliveredFor(session).isEmpty(), "a delivered report is still kept for the next connection"));
    }
}
