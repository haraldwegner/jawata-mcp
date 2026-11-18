package org.jawata.mcp.execution;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.jawata.mcp.models.ToolResponse;

/**
 * The jawata calls of a run that failed or were refused (Stage 27 (o)). The stream a hire's brain sends records a call and never its result, so
 * the engine, which answers every call, keeps the ones that answered with an error, under the caller's id, which for a hire is its plan id. The
 * hire's answer takes them once, when it ends, and Agent Control shows them beside the shell calls on Java.
 *
 * <p>Only the latest {@link #PER_CALLER} failures of one caller are kept, so a run that fails without end cannot make the store grow with it.</p>
 */
public final class CallOutcomes {

    /** One call that failed or was refused: the tool and the error code it answered with. */
    public record Failed(String tool, String code) {
    }

    /** How many failures of one caller are kept; the latest ones. */
    static final int PER_CALLER = 100;

    private static final CallOutcomes SHARED = new CallOutcomes();

    private final Map<String, Deque<Failed>> byCaller = new ConcurrentHashMap<>();

    /** The store the registry records into and a hire's answer takes from. */
    public static CallOutcomes shared() {
        return SHARED;
    }

    /** Keeps this call under its caller when it failed or was refused; a call that succeeded is not kept. */
    public void record(String callerId, String tool, ToolResponse response) {
        if (response == null || response.isSuccess()) {
            return;
        }
        String code = response.getError() == null || response.getError().getCode() == null ? "ERROR" : response.getError().getCode();
        Deque<Failed> kept = byCaller.computeIfAbsent(callerId, id -> new ArrayDeque<>());
        synchronized (kept) {
            kept.addLast(new Failed(tool, code));
            while (kept.size() > PER_CALLER) {
                kept.removeFirst();
            }
        }
    }

    /** What this caller's calls failed or were refused with, oldest first, handed over once: a second take of the same caller is empty. */
    public List<Failed> take(String callerId) {
        Deque<Failed> kept = byCaller.remove(callerId);
        if (kept == null) {
            return List.of();
        }
        synchronized (kept) {
            return new ArrayList<>(kept);
        }
    }
}
