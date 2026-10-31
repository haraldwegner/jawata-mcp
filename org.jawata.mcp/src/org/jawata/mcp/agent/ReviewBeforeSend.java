package org.jawata.mcp.agent;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * The review before sending (Sprint 28h, S11, D25): the main seat's text for a turn is HELD, a reviewer reads it, and only what the
 * reviewer returns reaches the person. The agent is the only emitter of that text, so there is no second road for an unreviewed message
 * to take, and the two defects of the old hook (a message that escaped unreviewed; the same message read twice, as draft and as
 * correction) cannot occur: a hook could only append to what had been sent, and this subtracts before anything is sent.
 *
 * <p>It fails CLOSED. A review that does not answer sends a sentence saying the message was held, and never the draft: a draft that
 * escaped because its reviewer was unavailable is exactly the defect this exists to end.</p>
 */
final class ReviewBeforeSend {

    /** The reviewer: the text to send for a draft, or empty when the review did not answer. */
    @FunctionalInterface
    interface Reviewer {
        Optional<String> review(String draft);
    }

    /** What the person gets when the review did not answer. */
    static final String HELD = "jawata held this message because its review did not answer; nothing unreviewed was sent.";

    private final Reviewer reviewer;
    private final Consumer<String> emit;
    private final StringBuilder draft = new StringBuilder();

    /**
     * @param reviewer the review every message passes
     * @param emit     the one road to the person; nothing is passed to it before {@link #release}
     */
    ReviewBeforeSend(Reviewer reviewer, Consumer<String> emit) {
        this.reviewer = reviewer;
        this.emit = emit;
    }

    /** Takes a piece of the draft. Nothing leaves. */
    void hold(String piece) {
        draft.append(piece);
    }

    /**
     * Reviews what was held and sends the reviewed text, or the sentence saying it was held; empty drafts send nothing.
     *
     * @return what the person was sent
     */
    String release() {
        String held = draft.toString();
        draft.setLength(0);
        if (held.isBlank()) {
            return "";
        }
        String sent = reviewer.review(held).filter(text -> !text.isBlank()).orElse(HELD);
        emit.accept(sent);
        return sent;
    }
}
