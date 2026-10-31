package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.jawata.mcp.protocol.JsonRpcMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, S11 (D25): the main seat's text leaves only after its review. The agent is the one emitter, so the review sits in front of
 * it: the draft is held, the reviewer answers, and the person reads what the reviewer returned, once.
 */
class ReviewBeforeSendTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** A connection whose brain says one thing, with the given reviewer in front of the person; collects what the host receives. */
    private static final class Connection {
        final List<JsonNode> said = new ArrayList<>();
        final AcpServer server;
        final String session;

        Connection(String draft, ReviewBeforeSend.Reviewer reviewer) {
            server = new AcpServer((prompt, chunks) -> chunks.accept(draft), List::of, message -> said.add(JSON.valueToTree(message)),
                TurnBoundary.open(), AgentRecorder.off(), null, reviewer);
            server.receive(JsonRpcMessage.request(1, "session/new", JSON.createObjectNode().put("cwd", "/w")));
            session = said.stream().filter(m -> m.path("result").has("sessionId")).findFirst().orElseThrow().path("result").path("sessionId").asText();
        }

        void prompt(int id) {
            ObjectNode params = JSON.createObjectNode().put("sessionId", session);
            params.putArray("prompt").addObject().put("type", "text").put("text", "hello");
            server.receive(JsonRpcMessage.request(id, "session/prompt", params));
        }

        /** Everything of the main seat's text the host has received so far, in order. */
        String textSoFar() {
            StringBuilder text = new StringBuilder();
            for (JsonNode message : said) {
                JsonNode update = message.path("params").path("update");
                if ("agent_message_chunk".equals(update.path("sessionUpdate").asText())) {
                    text.append(update.path("content").path("text").asText());
                }
            }
            return text.toString();
        }
    }

    @Test
    @DisplayName("D25: nothing of the draft reaches the host before the reviewer has answered, and what reaches it is the reviewed text, once")
    void textLeavesOnlyAfterReview() {
        String[] seenWhenReviewed = {null};
        Connection[] connection = new Connection[1];
        connection[0] = new Connection("the DRAFT", draft -> {
            seenWhenReviewed[0] = connection[0].textSoFar();
            return Optional.of("the REVIEWED message");
        });

        connection[0].prompt(2);

        assertEquals("", seenWhenReviewed[0], "the host had already received text when the reviewer was asked");
        assertEquals("the REVIEWED message", connection[0].textSoFar(), "the person did not get the reviewed text, once");
        assertFalse(connection[0].textSoFar().contains("DRAFT"), "the draft reached the person");
    }

    @Test
    @DisplayName("D25: a review that does not answer holds the message: the person gets the sentence saying so and never the draft")
    void aSilentReviewHoldsTheMessage() {
        Connection connection = new Connection("the DRAFT", draft -> Optional.empty());

        connection.prompt(2);

        assertEquals(ReviewBeforeSend.HELD, connection.textSoFar());
        assertFalse(connection.textSoFar().contains("DRAFT"), "an unreviewed draft escaped because its review was unavailable");
    }

    @Test
    @DisplayName("D25, the control: with no reviewer configured the text streams to the host as before")
    void withoutAReviewerTheTextStreamsAsBefore() {
        Connection connection = new Connection("the DRAFT", null);

        connection.prompt(2);

        assertEquals("the DRAFT", connection.textSoFar());
    }

    @Test
    @DisplayName("D25: each turn is reviewed on its own, so one turn's draft is not read again with the next")
    void eachTurnIsReviewedOnItsOwn() {
        List<String> reviewed = new ArrayList<>();
        Connection connection = new Connection("the DRAFT", draft -> {
            reviewed.add(draft);
            return Optional.of(draft);
        });

        connection.prompt(2);
        connection.prompt(3);

        assertEquals(List.of("the DRAFT", "the DRAFT"), reviewed, "a draft was reviewed together with the previous turn's");
    }

    @Test
    @DisplayName("D25: the reviewer's answer is read: send as it is, send revised, and an answer in neither shape is no review")
    void theReviewersAnswerIsRead() {
        assertEquals(Optional.of("the draft"), BrainReviewer.reviewed("REVIEW: SEND", "the draft"));
        assertEquals(Optional.of("the better message"), BrainReviewer.reviewed("REVIEW: REVISED\nthe better message", "the draft"));
        assertEquals(Optional.empty(), BrainReviewer.reviewed("REVIEW: REVISED\n   ", "the draft"));
        assertEquals(Optional.empty(), BrainReviewer.reviewed("looks fine to me", "the draft"));
        assertEquals(Optional.empty(), BrainReviewer.reviewed(null, "the draft"));
        assertTrue(BrainReviewer.rulesFrom(Optional.empty()).contains("decision"), "no rules file must leave the default rules");
    }
}
