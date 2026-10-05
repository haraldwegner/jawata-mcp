package org.jawata.mcp.learn;

import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What a gate call actually did, as the ledger needs it (Sprint 28g S9a M2).
 *
 * <p>A value rather than three loose parameters because the three are ONE fact —
 * the evidence one gate call produced. A mutator taking them separately can be
 * handed a tool name from one call and an error count from another and has no
 * way to object; here they arrive together or not at all.</p>
 *
 * @param tool       the gate tool that was called
 * @param arguments  the arguments it was called WITH, as received — not as a step
 *     declared them. Keeping those two apart is the point: the ledger compares
 *     the declaration against the call, so whoever reports the call must not
 *     also be the one supplying the declaration. Null is allowed, because a tool
 *     can be called with none
 * @param errorCount how many errors the gate reported. Zero is NOT on its own a
 *     pass — see {@code succeeded}
 * @param succeeded  whether the gate call itself ran and answered. This is NOT
 *     redundant with a zero error count, and conflating the two is the defect
 *     this component exists to close: an error count is read out of a
 *     response's data, and a REFUSED call carries none, so a gate that never ran
 *     answers ZERO ERRORS. {@code compile_workspace} refusing with no projects
 *     loaded, an invalid parameter, a tool that failed before looking at
 *     anything — every one of them reports zero. {@code EventTap} guards exactly
 *     this with an {@code isSuccess()} check before its own gate branch, and
 *     nothing here did until this component was added
 */
public record GateOutcome(String tool, JsonNode arguments, long errorCount, boolean succeeded) {

    public GateOutcome {
        Objects.requireNonNull(tool, "a gate outcome with no tool names no gate");
        if (tool.isBlank()) {
            throw new IllegalArgumentException("a blank tool names no gate");
        }
        if (errorCount < 0) {
            throw new IllegalArgumentException(
                "a negative error count is not a count: " + errorCount);
        }
    }
}
