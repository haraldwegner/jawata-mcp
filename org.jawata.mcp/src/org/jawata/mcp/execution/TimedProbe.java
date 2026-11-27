package org.jawata.mcp.execution;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import org.jawata.core.host.HostProcesses;

/**
 * Times one prompt through a brain's own program, run after run (28h z93), so a lead can learn how fast a model answers without a shell: the
 * engine starts the program and asks it. Kept running, the program is started once before the first run and each run is timed from its
 * prompt; the conversation then carries the earlier runs, as a program kept running does. Otherwise each run starts a program of its own and
 * its start is part of its time. The brain is given no tools and may use none of its own.
 */
final class TimedProbe {

    private static final int MOST_RUNS = 20;
    private static final Duration LIMIT = Duration.ofSeconds(120);

    private TimedProbe() {
    }

    /**
     * One row per run, in order: {@code run}, {@code seconds}, {@code secondsToFirstText}, {@code answer}, {@code outputTokens},
     * {@code thinkingTokens} and {@code costUsd}, each of the last three null when the brain published no figure, and {@code error} when the
     * run did not answer.
     */
    static List<Map<String, Object>> run(AcpBrainSession.Program program, String model, String prompt, int repeat, boolean keepRunning) throws IOException {
        if (repeat < 1 || repeat > MOST_RUNS) {
            throw new IllegalArgumentException("repeat must be 1 to " + MOST_RUNS + ", not " + repeat);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        AcpBrainSession.Conversation kept = keepRunning ? open(program, model) : null;
        try {
            for (int run = 1; run <= repeat; run++) {
                long started = System.nanoTime();
                AcpBrainSession.Conversation session = kept != null ? kept : open(program, model);
                try {
                    rows.add(timed(session, prompt, run, started));
                } finally {
                    if (kept == null) {
                        session.close();
                    }
                }
            }
        } finally {
            if (kept != null) {
                kept.close();
            }
        }
        return rows;
    }

    private static AcpBrainSession.Conversation open(AcpBrainSession.Program program, String model) throws IOException {
        AcpBrainSession.Conversation session = AcpBrainSession.Conversation.start(HostProcesses.system(), program,
            HireSite.in(AcpBrainSession.listingFolder()), null, List.of(), Optional.empty(), HiredBrain.Ceilings.of(LIMIT),
            AcpBrainSession.OwnTools.WITHHELD);
        if (model != null && !model.isBlank() && !session.selectModel(model)) {
            session.close();
            throw new IllegalArgumentException("the brain offers no model \"" + model + "\"");
        }
        return session;
    }

    private static Map<String, Object> timed(AcpBrainSession.Conversation session, String prompt, int run, long started) throws IOException {
        AtomicLong firstText = new AtomicLong();
        HiredBrain.Outcome outcome = session.prompt(prompt, List.of(), HiredBrain.Ceilings.of(LIMIT),
            text -> firstText.compareAndSet(0, System.nanoTime()), update -> { }, update -> { });
        long ended = System.nanoTime();
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("run", run);
        row.put("seconds", (ended - started) / 1e9);
        row.put("secondsToFirstText", firstText.get() == 0 ? null : (firstText.get() - started) / 1e9);
        row.put("answer", outcome.text());
        row.put("outputTokens", outcome.tokens().map(TokenUsage::output).orElse(null));
        // A brain that reports no thinking part is not a brain that did no thinking: zero is not published as a figure.
        row.put("thinkingTokens", outcome.tokens().map(TokenUsage::reasoning).filter(tokens -> tokens > 0).orElse(null));
        row.put("costUsd", outcome.costUsd().isPresent() ? outcome.costUsd().getAsDouble() : null);
        if (outcome.stoppedBy().isPresent() || outcome.timedOut()) {
            row.put("error", outcome.stoppedBy().orElse("the run passed " + LIMIT.toSeconds() + " seconds"));
        }
        return row;
    }
}
