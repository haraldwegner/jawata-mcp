package org.jawata.mcp.java.lsp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 28h S16b: a request that takes time is answered off the connection's thread, exactly once, and is not answered with a result when
 * the editor cancelled it or the text it was about has changed. The handler here blocks on a latch the test holds, so the order of
 * events is the test's.
 */
class LspRequestsTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String URI = "file:///x/Slow.java";

    private final List<JsonRpcMessage> sent = new CopyOnWriteArrayList<>();
    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);
    private final LspRequests requests = new LspRequests(sent::add);

    private JsonRpcMessage slowRequest(int id) throws Exception {
        requests.register("test/slow", "slowProvider", true, params -> {
            started.countDown();
            release.await(30, TimeUnit.SECONDS);
            return Map.of("answer", 42);
        });
        return JsonRpcMessage.request(id, "test/slow", JSON.readTree("{\"textDocument\":{\"uri\":\"" + URI + "\"}}"));
    }

    private JsonRpcMessage answerTo(int id) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) {
            for (JsonRpcMessage message : sent) {
                if (message.getMethod() == null && Integer.valueOf(id).equals(message.getId())) {
                    return message;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("no answer to request " + id + ": " + sent);
    }

    @Test
    @DisplayName("a request is answered with the handler's result, and the answer is the only one")
    void aRequestIsAnsweredOnce() throws Exception {
        requests.dispatch(slowRequest(1));
        assertTrue(started.await(30, TimeUnit.SECONDS));
        release.countDown();

        JsonRpcMessage answer = answerTo(1);

        assertEquals(42, JSON.valueToTree(answer.getResult()).path("answer").asInt());
        Thread.sleep(100);
        assertEquals(1, sent.size(), "exactly one answer");
    }

    @Test
    @DisplayName("a cancelled request is answered RequestCancelled at once, and the result found later is thrown away")
    void aCancelledRequestIsAnsweredCancelled() throws Exception {
        requests.dispatch(slowRequest(2));
        assertTrue(started.await(30, TimeUnit.SECONDS));

        requests.cancel(JSON.readTree("2"));
        JsonRpcMessage answer = answerTo(2);
        release.countDown();
        Thread.sleep(100);

        assertNotNull(answer.getError());
        assertEquals(LspRequests.REQUEST_CANCELLED, answer.getError().getCode());
        assertNull(answer.getResult());
        assertEquals(1, sent.size(), "the late result was not sent as a second answer: " + sent);
    }

    @Test
    @DisplayName("a request about a document whose text changed meanwhile is answered ContentModified, not with a result about text that is gone")
    void aRequestOnChangedTextIsAnsweredContentModified() throws Exception {
        requests.dispatch(slowRequest(3));
        assertTrue(started.await(30, TimeUnit.SECONDS));

        requests.textChanged(URI);
        release.countDown();
        JsonRpcMessage answer = answerTo(3);

        assertNotNull(answer.getError());
        assertEquals(LspRequests.CONTENT_MODIFIED, answer.getError().getCode());
    }

    @Test
    @DisplayName("a change to some other document does not spoil the answer")
    void aChangeElsewhereDoesNotSpoilTheAnswer() throws Exception {
        requests.dispatch(slowRequest(4));
        assertTrue(started.await(30, TimeUnit.SECONDS));

        requests.textChanged("file:///x/Other.java");
        release.countDown();
        JsonRpcMessage answer = answerTo(4);

        assertNull(answer.getError());
        JsonNode result = JSON.valueToTree(answer.getResult());
        assertEquals(42, result.path("answer").asInt());
    }
}
