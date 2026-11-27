package org.jawata.mcp.execution;

import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * The hires that are running right now, so one of them can be stopped from the Agent Control panel (Sprint 28h, S9).
 *
 * <p>The agent knows a hire by the tool call that started it, and the hire tool knows it by its seat and brief; neither knows the other's
 * handle, so a running hire is found by the pair, seat and brief, which two hires only share when they are the same job.</p>
 *
 * <p>A stop does two things. It runs what the hire registered to be run on a stop, which is how the code that started the brain's process
 * kills it: a hire reading that process's output is blocked in a read that a thread interrupt cannot end, so killing the process is what ends
 * the read. Then it interrupts the thread, for a hire that is waiting rather than reading. The hire is marked as stopped by the person, so
 * its answer says so instead of reporting a failed or interrupted run.</p>
 */
public final class HireRegistry {

    /**
     * What a running hire's brain has reported so far, read off its stream as it arrives (S9, E3): the tool calls it has made, the size of
     * its context at its last usage report, and the cost it last reported. A figure the brain has not sent is absent, never zero.
     */
    public record Progress(int toolSteps, OptionalLong contextTokens, OptionalDouble costUsd, List<String> calls) {

        public Progress(int toolSteps, OptionalLong contextTokens, OptionalDouble costUsd) {
            this(toolSteps, contextTokens, costUsd, List.of());
        }
    }

    private static final class Running {
        final String seat;
        final String brief;
        /** The first name this hire carries, given when it was registered (Stage 24, D55). */
        final String name;
        /** The id of the caller that issued this hire: when that caller is a hired lead, the plan id of the lead's own hire (Stage 24, D55). */
        volatile String leadId;
        /** This hire's own plan id, known once its plan is open; empty before. */
        volatile String planId = "";
        /** Whether this hire runs on an engine thread started for it, so its result comes back as a new turn and not as the call's answer. */
        volatile boolean background;
        /** The packages this hire has claimed to work in (Stage 24, M13); empty when it claimed none, and emptied when the hire ends. */
        volatile java.util.Set<String> claims = java.util.Set.of();
        /** When the claims were made, in milliseconds. */
        volatile long claimedAtMs;
        /** When this hire last showed a sign of life (28h Stage 27, z28): a tool call, a usage report, a compaction, or its plan opening; the time it registered until then. */
        volatile long lastActivityMs = System.currentTimeMillis();
        /** How many seconds this hire had been quiet when the engine stopped it for that (28h Stage 27, z28, the second ceiling); negative while it was not. */
        volatile long stalledForSeconds = -1;
        /** The caller id of the hirer that stopped this hire (28h z136); {@code null} when no hirer did. */
        volatile String stoppedByHirer;
        /** The sign-of-life time at which the subscribers were told this hire had gone quiet, so one stretch of quiet is told once; 0 until told. */
        volatile long quietToldAtMs;
        final Thread thread;
        /** When this hire was registered, in milliseconds. */
        final long startedAtMs = System.currentTimeMillis();
        final List<Runnable> onStop = new CopyOnWriteArrayList<>();
        volatile Progress progress = new Progress(0, OptionalLong.empty(), OptionalDouble.empty());
        /** Where each call named by an id stands among the progress calls, and its title, so its line is written again once its input arrives; the hire's thread only. */
        final java.util.Map<String, java.util.Map.Entry<Integer, String>> lines = new java.util.HashMap<>();
        /** Whether the brain is compacting its own context right now, as its stream last said. */
        volatile boolean compacting;
        /**
         * The reports of this lead's hires that its run never put in front of its brain, held until this lead ends and then told again for its
         * chat (28h-C27-18). Read and written only under the registry's lock, which closing this hire holds too, so none is added after the drain.
         */
        final List<Event> handedUp = new java.util.ArrayList<>();
        /** What this hire's brain has written to its caller so far, from which its progress lines are read (28h z85); guarded by itself. */
        final StringBuilder written = new StringBuilder();

        /** The lines it wrote that start STEP:, without the mark, in the order written; a line still being written is included. */
        List<String> progressLines() {
            synchronized (written) {
                return written.toString().lines().map(String::strip).filter(line -> line.startsWith("STEP:"))
                    .map(line -> line.substring("STEP:".length()).strip()).filter(line -> !line.isEmpty()).toList();
            }
        }

        Running(String seat, String brief, String name, String leadId, Thread thread) {
            this.seat = seat;
            this.brief = brief;
            this.name = name;
            this.leadId = leadId;
            this.thread = thread;
        }
    }

    /** A hire registered as running on the current thread; closing it removes the hire. */
    public final class Registration implements AutoCloseable {
        private final Running hire;

        private Registration(Running hire) {
            this.hire = hire;
        }

        /** The first name this hire was given when it was registered. */
        public String name() {
            return hire.name;
        }

        /**
         * Claims these packages for this hire (Stage 24, M13). The answer is a refusal naming the holder when another running hire that is not an
         * ancestor of this one holds an overlapping package, and empty when the claim is made; a claim lasts until this hire ends.
         */
        public Optional<String> claim(java.util.Collection<String> packages) {
            return HireRegistry.this.claim(hire, packages);
        }

        /** The progress lines this hire has written for its lead (28h z85). */
        public List<String> progressLines() {
            return hire.progressLines();
        }

        /** Whether the person stopped this hire; true once, then the mark is gone. */
        public boolean wasStopped() {
            return stopped.remove(hire.thread);
        }

        @Override
        public void close() {
            List<Event> handed;
            synchronized (HireRegistry.this) {
                hire.claims = java.util.Set.of();
                running.remove(hire);
                stopped.remove(hire.thread);
                if (!hire.planId.isEmpty()) {
                    endedOn.put(hire.thread, new Ended(hire.planId, hire.name));
                }
                handed = List.copyOf(hire.handedUp);
                hire.handedUp.clear();
                // 28h z102: its lead was waiting for it, so the lead's quiet counts from now, when there is a report to read.
                for (Running lead : running) {
                    if (!lead.planId.isEmpty() && lead.planId.equals(hire.leadId)) {
                        lead.lastActivityMs = System.currentTimeMillis();
                    }
                }
            }
            // Told outside the lock, now that this hire no longer runs: the reports its brain never read go to its chat.
            handed.forEach(HireRegistry.this::retell);
        }

        /** How many seconds this hire had been quiet when the engine stopped it for that (28h Stage 27, z28); none when it was not stopped as a stall. */
        public OptionalLong stalledFor() {
            return hire.stalledForSeconds < 0 ? OptionalLong.empty() : OptionalLong.of(hire.stalledForSeconds);
        }

        /** The caller id of the hirer that stopped this hire (28h z136); none when no hirer did. */
        public Optional<String> stoppedByHirer() {
            return Optional.ofNullable(hire.stoppedByHirer);
        }
    }

    /** Which hire ended last on a thread: its plan id and first name. */
    public record Ended(String planId, String name) {
    }

    /**
     * The hire that last ended on each thread, until the caller that ran it reads it. A hire that ends in an error, a stop included, has no answer
     * to carry its plan id and name, so without this the end of it is announced under neither (28h S19-2: a stopped hire's card went on running).
     */
    private final java.util.Map<Thread, Ended> endedOn = new ConcurrentHashMap<>();

    /** The plan id and name of the hire that last ended on the current thread, once; none when it ended before its plan opened or none ended. */
    public Optional<Ended> takeEnded() {
        return Optional.ofNullable(endedOn.remove(Thread.currentThread()));
    }

    /**
     * How long a running hire may show no sign of life before it is called stalled (28h Stage 27, z28): the first ceiling, at which a client that
     * asks {@code hire_wait} is told and the card of the hire says so. The second, past which the engine stops the hire, is a host setting.
     */
    public static final long STALLED_AFTER_SECONDS = 300;

    private volatile long quietCeilingSeconds = STALLED_AFTER_SECONDS;

    /** How many seconds a running hire may show no sign of life before it is told as stalled (the first ceiling): the host's setting when it set one, else {@value #STALLED_AFTER_SECONDS}. */
    public long quietCeiling() {
        return quietCeilingSeconds;
    }

    /** Sets the first ceiling, which the hire tool reads from the host's settings on every hire; a value of zero or less puts back the default. */
    public void quietCeiling(long seconds) {
        quietCeilingSeconds = seconds > 0 ? seconds : STALLED_AFTER_SECONDS;
    }

    private static final HireRegistry SHARED = new HireRegistry();

    private final List<Running> running = new CopyOnWriteArrayList<>();
    private final Set<Thread> stopped = ConcurrentHashMap.newKeySet();

    /** The one registry of running hires in this process, which the hire tool fills and the agent face reads. */
    public static HireRegistry shared() {
        return SHARED;
    }

    /**
     * Registers a hire of this seat for this brief as running on the current thread, and gives it the next first name of this conversation,
     * the id of the caller that issued it, so two hires of one seat in one conversation can be told apart. A stop mark left on this thread by an
     * earlier hire is dropped: it would otherwise end this hire the moment its brain's process started. Registering, stopping and closing take
     * turns, so a stop cannot mark a thread whose hire has just closed.
     */
    public synchronized Registration begin(String seat, String brief, String conversationKey) {
        // The names are drawn for the whole chat the hire traces back to, not for the lead that issued it: two chief engineers under two project
        // managers of one chat were given the same name by a run with Cursor, and a row must tell them apart.
        Running hire = new Running(seat, normal(brief), HireNames.shared().assign(rootOf(conversationKey)), conversationKey == null ? "" : conversationKey,
            Thread.currentThread());
        stopped.remove(hire.thread);
        endedOn.remove(hire.thread); // an end nobody read belongs to an earlier hire of this thread
        hire.background = backgroundNext.remove(hire.thread);
        running.add(hire);
        begun.incrementAndGet();
        return new Registration(hire);
    }

    /** How many hires this engine has begun, which with the running list answers whether any hire worked since a moment. */
    private final java.util.concurrent.atomic.AtomicLong begun = new java.util.concurrent.atomic.AtomicLong();

    /** A moment to ask {@link #workedSince} about later (28h z114). */
    public record WorkMark(long begun, boolean anyRunning) {
    }

    /** This moment, for {@link #workedSince}. */
    public WorkMark workMark() {
        return new WorkMark(begun.get(), !running.isEmpty());
    }

    /** Whether any hire was working at the mark, began after it, or is working now. */
    public boolean workedSince(WorkMark mark) {
        return mark.anyRunning() || begun.get() != mark.begun() || !running.isEmpty();
    }

    /**
     * Registers what a stop does to the hire running on the current thread, such as killing the brain's process it has just started. When no
     * hire is registered on this thread, as in a run made outside the hire tool, there is nothing to stop and nothing is registered. A stop
     * that came before the process was started has already gone by, so the action is run at once: otherwise the process would start after
     * the stop and nothing would ever end it.
     */
    public void onStop(Runnable action) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                hire.onStop.add(action);
                if (stopped.contains(hire.thread)) {
                    action.run();
                }
            }
        }
    }

    /**
     * Counts one more tool call for the hire running on the current thread and keeps its title, so Agent Control can show the calls of a hire
     * that still runs (28h D57 (i)); a thread running no hire is ignored.
     *
     * @param title the call's title as the brain streamed it; a blank one is counted and not kept
     */
    public void noteToolCall(String title) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                Progress seen = hire.progress;
                List<String> calls = title == null || title.isBlank() ? seen.calls()
                    : java.util.stream.Stream.concat(seen.calls().stream(), java.util.stream.Stream.of(title)).toList();
                hire.progress = new Progress(seen.toolSteps() + 1, seen.contextTokens(), seen.costUsd(), calls);
                hire.lastActivityMs = System.currentTimeMillis();
            }
        }
    }

    /**
     * Keeps the files these calls of its own editor wrote as written by the plan of the hire running on the current thread (28h z120); a
     * thread running no hire, or a hire whose plan is not open yet, is ignored.
     */
    public void noteOwnWrites(List<ToolCall> made) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                WrittenFiles.shared().record(hire.planId, OwnWrites.pathsWritten(made));
            }
        }
    }

    /**
     * Records what the brain of the hire running on the current thread last reported about its usage. A figure the report did not carry keeps
     * the value the hire had before it: a report that names only a context size has not said the cost fell to nothing.
     */
    public void noteUsage(OptionalLong contextTokens, OptionalDouble costUsd) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                Progress seen = hire.progress;
                hire.progress = new Progress(seen.toolSteps(), contextTokens.isPresent() ? contextTokens : seen.contextTokens(),
                    costUsd.isPresent() ? costUsd : seen.costUsd(), seen.calls());
                hire.lastActivityMs = System.currentTimeMillis();
            }
        }
    }

    /** Records that the brain of the hire running on the current thread began or ended compacting its context; a thread running no hire is ignored. */
    public void noteCompaction(boolean compacting) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                hire.compacting = compacting;
                hire.lastActivityMs = System.currentTimeMillis();
            }
        }
    }

    /** Whether the running hire of this seat for this brief is compacting its context at this moment; false when no such hire is running. */
    public boolean compacting(String seat, String brief) {
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief))) {
                return hire.compacting;
            }
        }
        return false;
    }

    /** What the running hire of this seat for this brief has reported so far; none when no such hire is running. */
    public Optional<Progress> progress(String seat, String brief) {
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief))) {
                return Optional.of(hire.progress);
            }
        }
        return Optional.empty();
    }

    /** What a running hire has reported so far and for how many seconds it has run (28h z136). */
    public record SoFar(Progress progress, long seconds, List<String> progressLines) {
    }

    /** What the running hire of this plan has used so far; none when no such hire is running. */
    public Optional<SoFar> soFarOfPlan(String planId) {
        for (Running hire : running) {
            if (hire.planId.equals(planId)) {
                return Optional.of(new SoFar(hire.progress, (System.currentTimeMillis() - hire.startedAtMs) / 1000, hire.progressLines()));
            }
        }
        return Optional.empty();
    }

    /**
     * What the registry tells a subscriber about a hire (Stage 24, M8): {@code started} when its plan opens, {@code finished} when its answer is
     * kept. The lead id is the id of the caller that issued the hire; the answer is set only on a finished event.
     */
    public record Event(String kind, String planId, String name, String seat, String brief, String leadId, com.fasterxml.jackson.databind.JsonNode answer, boolean background) {

        public Event(String kind, String planId, String name, String seat, String brief, String leadId, com.fasterxml.jackson.databind.JsonNode answer) {
            this(kind, planId, name, seat, brief, leadId, answer, false);
        }
    }

    private final List<java.util.function.Consumer<Event>> subscribers = new CopyOnWriteArrayList<>();

    /** The caller that issued each hire by plan id, kept after the hire ends so a late event can still be tied to the conversation it belongs to. */
    private final java.util.Map<String, String> issuedBy = new ConcurrentHashMap<>();

    /** Subscribes to the events of every hire in this process and answers what ends the subscription. */
    public Runnable subscribe(java.util.function.Consumer<Event> subscriber) {
        subscribers.add(subscriber);
        return () -> subscribers.remove(subscriber);
    }

    /**
     * What a hired lead's run waits on between its turns (Stage 24, M12): the reports of the hires its brain started in the background. It counts
     * those hires from the moment they start and not from the moment they end, so a lead is never told it has nothing out in the instant between a
     * hire's registration closing and its report being kept.
     *
     * <p>The inbox also owns what happens to a report its lead's brain never read (28h-C27-18). A report is taken in by {@link #offer} and counts
     * as read only when the lead says its brain answered the turn that showed it ({@link #delivered}). Closing the inbox, whatever way the lead's
     * run ended (its brain died, a ceiling stopped it, a report came after its last turn), hands every report not read up to the registry, which
     * holds it on the lead's own record and tells it again for the lead's chat when the lead ends. A closed inbox refuses new reports, so one
     * that arrives while it closes goes the same way.</p>
     */
    public final class Inbox implements AutoCloseable {
        private final java.util.concurrent.LinkedBlockingQueue<Event> reports = new java.util.concurrent.LinkedBlockingQueue<>();
        /** The plan ids of this lead's hires that are out. A set, so a report of a hire never counted takes nothing off. */
        private final java.util.Set<String> out = ConcurrentHashMap.newKeySet();
        /** The plan ids whose report has come, so a "started" event told after the report does not count the hire as out again; guarded by this inbox. */
        private final java.util.Set<String> reported = new java.util.HashSet<>();
        private final Runnable unsubscribe;
        /** The reports the lead's run has taken and not yet said were put in front of its brain; guarded by this inbox. */
        private final List<Event> taken = new java.util.ArrayList<>();
        /** Whether this inbox is closed, after which it takes no report and the registry hands each one up; guarded by this inbox. */
        private boolean closed;

        private final String leadId;

        private Inbox(String leadId) {
            this.leadId = leadId;
            inboxes.put(leadId, this);
            this.unsubscribe = subscribe(event -> {
                if (leadId.equals(event.leadId()) && event.background() && "started".equals(event.kind())) {
                    expect(event.planId());
                }
            });
        }

        /** Counts this hire as out, unless its report has already come. */
        private synchronized void expect(String planId) {
            if (!reported.contains(planId)) {
                out.add(planId);
            }
        }

        /**
         * Takes the report of one of this lead's hires, the one way a report enters an inbox; false once the inbox is closed, when the caller
         * hands the report up instead, so a report arriving while the inbox closes is neither queued for nobody nor told to nobody.
         */
        synchronized boolean offer(Event finished) {
            if (closed) {
                return false;
            }
            reported.add(finished.planId());
            out.remove(finished.planId());
            reports.add(finished);
            return true;
        }

        /** Says these reports were put in front of the lead's brain, which answered them: closing the inbox no longer hands them up. */
        public synchronized void delivered(List<Event> shown) {
            taken.removeAll(shown);
        }

        /** Whether a report has come that the lead's brain has not been shown and answered, taken or still waiting. */
        public synchronized boolean hasUndelivered() {
            return !taken.isEmpty() || !reports.isEmpty();
        }

        /** How many of this lead's background hires have started and not yet reported. */
        public int outstanding() {
            return out.size();
        }

        /** The reports that have come, waiting up to this long for the first when none has; empty when none came in that time. */
        public List<Event> await(long millis) throws InterruptedException {
            Event first = reports.poll(millis, java.util.concurrent.TimeUnit.MILLISECONDS);
            if (first == null) {
                return List.of();
            }
            List<Event> all = new java.util.ArrayList<>();
            all.add(first);
            reports.drainTo(all);
            synchronized (this) {
                taken.addAll(all);
            }
            return all;
        }

        /**
         * The reports that have come, waiting in slices of this length for as long as a hire of this lead is out and {@code running} says one still
         * runs. A lead's own ceiling is no bound on a hire below it, which has its own, so a single wait of the lead's length ended the lead while
         * a hire was still working. Empty only when nothing is out, or when the count says one is out and nothing runs, so a count that cannot
         * fall does not hold the lead.
         */
        public List<Event> awaitWhileOut(long sliceMillis, java.util.function.BooleanSupplier running) throws InterruptedException {
            while (outstanding() > 0 || !isEmpty()) {
                List<Event> got = await(sliceMillis);
                if (!got.isEmpty()) {
                    return got;
                }
                if (!running.getAsBoolean()) {
                    return got;
                }
            }
            return List.of();
        }

        /** Whether no report is waiting to be read. */
        public boolean isEmpty() {
            return reports.isEmpty();
        }

        /**
         * Closes this inbox and hands up every report the lead's brain was not shown and did not answer, whatever way the lead's run ended (its
         * brain died, a ceiling stopped it, a report came after its last turn): they are held until the lead ends and then go to its chat
         * (28h-C27-18). A report that arrives after this takes the same way, since a closed inbox refuses it.
         */
        @Override
        public void close() {
            List<Event> unread;
            synchronized (this) {
                closed = true;
                unread = new java.util.ArrayList<>(taken);
                taken.clear();
                reports.drainTo(unread);
            }
            inboxes.remove(leadId, this);
            unsubscribe.run();
            if (!heldForItsLead(leadId, unread)) {
                unread.forEach(HireRegistry.this::retell);
            }
        }
    }

    /** The inbox each lead's run reads its hires' reports from right now, by the lead's plan id. */
    private final java.util.Map<String, Inbox> inboxes = new ConcurrentHashMap<>();

    /** Opens the inbox of this lead, which must be opened before the lead's brain can start a hire, so that no report is missed. */
    public Inbox inboxFor(String leadId) {
        return new Inbox(leadId);
    }

    private void tell(Event event) {
        for (java.util.function.Consumer<Event> subscriber : subscribers) {
            subscriber.accept(event);
        }
    }

    /**
     * The id at the top of the chain of hires that led to this caller: when the caller is itself a hire's plan id the chain goes on to whoever
     * issued that hire, and it ends at an id no hire has, which for a hire that traces back to the person's chat is that chat's session id.
     */
    public String rootOf(String callerId) {
        String current = callerId == null ? "" : callerId;
        for (int steps = 0; steps < 16; steps++) {
            String above = issuedBy.get(current);
            if (above == null || above.isEmpty()) {
                break;
            }
            current = above;
        }
        return current;
    }

    /** The keys principal brains present to the engine as the caller of their tool calls, each of which speaks for one chat session. */
    private final Set<String> speakingKeys = ConcurrentHashMap.newKeySet();

    /**
     * Records that the key a principal brain presents on its tool calls speaks for this chat session, so a hire that brain starts traces back to
     * the chat whose person it works for. The brain's key is not the chat's own id, and the first live run with Cursor showed that a registry
     * which knew only the chat's id found no chat for any hire.
     *
     * @param callerKey the key the brain presents to the engine on every tool call
     * @param sessionId the chat session it speaks for
     */
    public void speaksFor(String callerKey, String sessionId) {
        if (callerKey != null && !callerKey.isEmpty() && sessionId != null && !sessionId.isEmpty()) {
            issuedBy.put(callerKey, sessionId);
            speakingKeys.add(callerKey);
        }
    }

    /**
     * Records that the hire with this plan id was started by the brain of this chat session, because the chat saw its own brain's hire call
     * come back with that plan id (D57 (l)). {@link #speaksFor} learns a chat's brain from the key the brain presents on its tool calls; a brain
     * whose calls reached the engine under another caller id left its hires traced to a caller no chat had registered, so neither their
     * reports nor the end of their cards reached the chat (seen live 2026-11-16, three hires in a row). Learning it from the call itself does
     * not depend on any header.
     *
     * @param planId    the plan id the brain's hire call answered with
     * @param sessionId the chat session whose brain made the call
     * @return the hire's finished event, addressed to this chat, when the hire had already ended and its report was held for the caller the
     *         engine saw: the chat takes it now, since nothing else will
     */
    public Optional<Event> startedFrom(String planId, String sessionId) {
        if (planId == null || planId.isEmpty() || sessionId == null || sessionId.isEmpty()) {
            return Optional.empty();
        }
        // Under the routing lock, so a report is either already kept when this looks, or is routed to this chat afterwards.
        synchronized (routing) {
            String before = issuedBy.put(planId, sessionId);
            if (before == null || before.equals(sessionId)) {
                return Optional.empty();
            }
            List<Event> held = waiting.get(rootOf(before));
            if (held == null) {
                return Optional.empty();
            }
            for (Event kept : held) {
                if (kept.planId().equals(planId) && held.remove(kept)) {
                    return Optional.of(new Event(kept.kind(), kept.planId(), kept.name(), kept.seat(), kept.brief(), sessionId, kept.answer(),
                        kept.background()));
                }
            }
            return Optional.empty();
        }
    }

    /**
     * Records that the hire with this plan id was started by the brain of the hired lead whose plan this is, because the lead saw its own brain's
     * hire call come back with that plan id (28h Stage 27, finding 28h-C27-1). A hired lead's brain does not reliably present the lead's plan id
     * as its caller, so the engine saw its hire calls under another id and the lead's inbox, which counts the hires issued under the lead's own
     * id, never knew a hire was out: the lead ended with it running. The hire is then told to the registry's subscribers as started under the
     * lead, or, when it had already ended, its held report is told as ended under the lead.
     *
     * @param planId the plan id the lead's brain's hire call answered with
     * @param leadId the plan id of the hired lead whose brain made the call
     */
    public void startedBy(String planId, String leadId) {
        if (planId == null || planId.isEmpty() || leadId == null || leadId.isEmpty()) {
            return;
        }
        Event toTell = null;
        // Under the same lock as the routing in record(), so a report is either already kept when this looks, or is routed to the lead afterwards.
        synchronized (routing) {
            String before = issuedBy.put(planId, leadId);
            if (leadId.equals(before)) {
                return;
            }
            for (Running hire : running) {
                if (planId.equals(hire.planId)) {
                    hire.leadId = leadId;
                    toTell = new Event("started", planId, hire.name, hire.seat, hire.brief, leadId, null, hire.background);
                }
            }
            List<Event> held = toTell != null || before == null ? null : waiting.get(rootOf(before));
            for (Event kept : held == null ? List.<Event>of() : held) {
                if (kept.planId().equals(planId) && held.remove(kept)) {
                    // Telling is not giving: only an offer puts a report in front of the lead's brain, and without one the report of a hire that
                    // ended before the lead learned it was its own was lost.
                    toTell = new Event(kept.kind(), kept.planId(), kept.name(), kept.seat(), kept.brief(), leadId, kept.answer(), kept.background());
                    route(inboxes.get(leadId), toTell);
                    break;
                }
            }
            // Closed and its report not yet kept: it is counted as out, so the lead waits for the report record() is about to bring.
            Inbox inbox = inboxes.get(leadId);
            if (toTell == null && inbox != null && backgroundPlans.contains(planId)) {
                inbox.expect(planId);
            }
        }
        if (toTell != null) {
            tell(toTell);
        }
    }

    /** Held while a report is routed and while a lead takes a hire over, so neither can miss the other. */
    private final Object routing = new Object();

    /**
     * Puts a background hire's report where it will be read: in front of its lead's brain when the lead's inbox takes it, else held on the
     * lead's own record while it runs, else kept for its chat.
     */
    private void route(Inbox inbox, Event ended) {
        if ((inbox == null || !inbox.offer(ended)) && !heldForItsLead(ended.leadId(), List.of(ended)) && !takenAbove(ended)) {
            keepForItsChat(ended);
        }
    }

    /**
     * The nearest hire that still runs at or above this caller in the chain of hires, by its plan id; none when no hire there runs, so only the
     * chat is above.
     */
    public Optional<String> runningLeadAtOrAbove(String callerId) {
        String current = callerId == null ? "" : callerId;
        for (int steps = 0; steps < 16 && !current.isEmpty(); steps++) {
            if (isRunningPlan(current)) {
                return Optional.of(current);
            }
            current = issuedBy.getOrDefault(current, "");
        }
        return Optional.empty();
    }

    /**
     * Gives a report whose lead has ended to the nearest lead above it that still runs (D55), with who hired it written on it: in front of that
     * lead's brain when its inbox takes it, else held on its record until it ends. False when no lead above runs, so it goes to the chat.
     */
    private boolean takenAbove(Event report) {
        Optional<String> above = runningLeadAtOrAbove(issuedBy.getOrDefault(report.leadId(), ""));
        if (above.isEmpty()) {
            return false;
        }
        com.fasterxml.jackson.databind.JsonNode answer = report.answer();
        if (answer instanceof com.fasterxml.jackson.databind.node.ObjectNode told && !told.has("orphanOf")) {
            answer = told.deepCopy().put("orphanOf", labelOf(report.leadId()));
        }
        Event passed = new Event(report.kind(), report.planId(), report.name(), report.seat(), report.brief(), above.get(), answer,
            report.background());
        Inbox inbox = inboxes.get(above.get());
        return inbox != null && inbox.offer(passed) || heldForItsLead(above.get(), List.of(passed));
    }

    /** Whether this plan is the account's own or was issued under it, directly or through other hires (28h z120). */
    public boolean isOfAccount(String planId, String accountPlan) {
        String at = planId;
        for (int depth = 0; at != null && !at.isEmpty() && depth < 32; depth++) {
            if (at.equals(accountPlan)) {
                return true;
            }
            at = issuedBy.get(at);
        }
        return false;
    }

    /** Whether this caller is a principal brain's own key, so a hire it issued has no lead above it but the chat. */
    public boolean isRoot(String callerId) {
        return callerId != null && speakingKeys.contains(callerId);
    }

    /**
     * Whether the report of a hire this caller starts comes back to it as a new turn (28h z62): when the caller is a principal brain of the jawata chat, or
     * a hire that traces back to one. Such a caller has nothing to wait for and ends its turn; any other caller is its own principal, nothing pushes the
     * report into its turn, and it collects it with {@code hire_wait}.
     */
    public boolean reportComesAsTurn(String callerId) {
        String current = callerId == null ? "" : callerId;
        for (int steps = 0; steps < 16 && !current.isEmpty(); steps++) {
            if (speakingKeys.contains(current)) {
                return true;
            }
            current = issuedBy.getOrDefault(current, "");
        }
        return false;
    }

    /** {@link #reportComesAsTurn} for the hire of this plan, by whoever issued it; false for a plan this engine knows no issuer of. */
    public boolean reportComesAsTurnForPlan(String planId) {
        return reportComesAsTurn(issuedBy.getOrDefault(planId, ""));
    }

    /** What the current thread asked to be told when the hire it is about to run opens its plan: the plan id and the hire's first name. */
    private final java.util.Map<Thread, java.util.function.BiConsumer<String, String>> planListeners = new ConcurrentHashMap<>();

    /**
     * Asks to be told, once, which plan id and first name the hire this thread runs next is given when its plan opens (Stage 24, M7): a call
     * that starts a hire on an engine thread answers the caller with that identity before the brain runs.
     */
    public void whenPlanOpened(java.util.function.BiConsumer<String, String> listener) {
        planListeners.put(Thread.currentThread(), listener);
    }

    /** Drops what the current thread asked in {@link #whenPlanOpened}, once its hire has ended. */
    public void forgetPlanListener() {
        planListeners.remove(Thread.currentThread());
    }

    /** Records the plan id of the hire running on the current thread, once its plan is open; a thread running no hire is ignored. */
    public void planOpened(String planId) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                hire.planId = planId;
                hire.lastActivityMs = System.currentTimeMillis();
                issuedBy.put(planId, hire.leadId);
                labels.put(planId, hire.name + " · " + hire.seat);
                java.util.function.BiConsumer<String, String> told = planListeners.get(hire.thread);
                if (told != null) {
                    told.accept(planId, hire.name);
                }
                if (hire.background) {
                    backgroundPlans.add(planId);
                }
                tell(new Event("started", planId, hire.name, hire.seat, hire.brief, hire.leadId, null, hire.background));
            }
        }
    }

    /** The plan id of the running hire of this seat for this brief; none when no such hire is running or its plan is not open yet. */
    public Optional<String> planOf(String seat, String brief) {
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief)) && !hire.planId.isEmpty()) {
                return Optional.of(hire.planId);
            }
        }
        return Optional.empty();
    }

    /**
     * The plan id of the running hire that issued the running hire of this seat for this brief: the caller of a hire is the hired lead whose own
     * session id is that lead's plan id, so the lead is the running hire whose plan id equals the caller's id. None when the issuer is no running
     * hire, as for a hire the principal itself issued.
     */
    public Optional<String> leadPlanOf(String seat, String brief) {
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief)) && !hire.leadId.isEmpty()) {
                for (Running lead : running) {
                    if (lead != hire && hire.leadId.equals(lead.planId)) {
                        return Optional.of(lead.planId);
                    }
                }
            }
        }
        return Optional.empty();
    }

    private synchronized Optional<String> claim(Running claimant, java.util.Collection<String> packages) {
        java.util.Set<String> wanted = new java.util.LinkedHashSet<>();
        for (String name : packages) {
            if (name != null && !name.isBlank()) {
                wanted.add(name.strip());
            }
        }
        for (Running holder : running) {
            if (holder == claimant || holder.claims.isEmpty() || isAncestorOf(holder, claimant)) {
                continue;
            }
            for (String held : holder.claims) {
                for (String asked : wanted) {
                    if (held.equals(asked) || held.startsWith(asked + ".") || asked.startsWith(held + ".")) {
                        return Optional.of("package " + asked + " overlaps " + held + ", which is held by " + holder.name + " · " + holder.seat
                            + (holder.planId.isEmpty() ? "" : " (hire " + holder.planId + ")") + " since "
                            + java.time.LocalTime.ofInstant(java.time.Instant.ofEpochMilli(holder.claimedAtMs), java.time.ZoneId.systemDefault())
                                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES)
                            // the two ids the admission compared, so a lead's hire that was not recognised as below its lead shows whether they differ
                            + "; this claim was issued by " + (claimant.leadId.isEmpty() ? "nobody (no issuer id)" : claimant.leadId)
                            + ", and the admission needs that to be the holder's plan id");
                    }
                }
            }
        }
        claimant.claims = java.util.Set.copyOf(wanted);
        claimant.claimedAtMs = System.currentTimeMillis();
        return Optional.empty();
    }

    /** Whether the hire {@code maybe} is above {@code hire} in the chain of hires that issued it: an ancestor's claim admits what its hires claim. */
    private boolean isAncestorOf(Running maybe, Running hire) {
        String above = hire.leadId;
        for (int steps = 0; steps < 16 && !above.isEmpty(); steps++) {
            if (above.equals(maybe.planId)) {
                return true;
            }
            above = issuedBy.getOrDefault(above, "");
        }
        return false;
    }

    /**
     * Stops the running hire with this plan id and every running hire below it in the chain of hires that issued them (Stage 24, M14, ND3): what
     * a person stopping a lead means is that nothing under it goes on spending. Answers each stopped hire as first name and seat, so the answer
     * can say who was stopped.
     * @param byHirer TODO
     */
    public synchronized List<String> stopTree(String planId, String byHirer) {
        List<String> stoppedNames = new java.util.ArrayList<>();
        for (Running hire : running) {
            boolean below = false;
            String above = hire.leadId;
            for (int steps = 0; steps < 16 && !above.isEmpty() && !below; steps++) {
                below = above.equals(planId);
                above = issuedBy.getOrDefault(above, "");
            }
            if (hire.planId.equals(planId) || below) {
                hire.stoppedByHirer = byHirer;
                stopped.add(hire.thread);
                hire.onStop.forEach(Runnable::run);
                hire.thread.interrupt();
                stoppedNames.add(hire.name + " · " + hire.seat);
            }
        }
        return stoppedNames;
    }

    /**
     * Whether this caller may stop the hire with this plan id (28h z136): it issued that hire, or issued a hire above it. A hired lead's caller
     * id is its own plan id, so a lead stops the hires under it and never another lead's.
     */
    public boolean mayBeStoppedBy(String planId, String callerId) {
        String above = issuedBy.get(planId);
        for (int steps = 0; steps < 16 && above != null; steps++) {
            if (above.equals(callerId)) {
                return true;
            }
            above = above.isEmpty() ? null : issuedBy.get(above);
        }
        return false;
    }

    /** What each plan was, as first name and seat, kept so a report can say whose hire it was after that hire has ended. */
    private final java.util.Map<String, String> labels = new ConcurrentHashMap<>();

    /** The finished background hires no connection has taken the report of, by the chat session they trace back to, until one does. */
    private final java.util.Map<String, List<Event>> waiting = new ConcurrentHashMap<>();

    /**
     * Holds reports no brain of their lead will read on that lead's own record while it runs (28h-C27-18); once it has ended each is told again
     * and kept for its chat, whose principal receives it as the report of a hire whose lead has ended. Holding and ending take the registry's
     * lock, so a report is never held for a lead that has just ended: the answer is false then, and the caller passes the report on itself.
     *
     * @param leadId  the plan id of the lead
     * @param reports the reports that came for it
     * @return whether the reports are held (nothing to hold counts as held); false when the lead does not run
     */
    private synchronized boolean heldForItsLead(String leadId, List<Event> reports) {
        if (reports.isEmpty()) {
            return true;
        }
        for (Running hire : running) {
            if (leadId != null && !leadId.isEmpty() && leadId.equals(hire.planId)) {
                hire.handedUp.addAll(reports);
                return true;
            }
        }
        return false;
    }

    /** Tells a report again for the chat above its ended lead, and keeps it until a connection takes it, as an orphaned report is kept. */
    private void retell(Event report) {
        if (takenAbove(report)) {
            return;
        }
        keepForItsChat(report);
        tell(report);
    }

    /** The first name and seat of the hire with this plan id, running or ended; the id itself when it is no hire's. */
    public String labelOf(String planId) {
        return labels.getOrDefault(planId, planId);
    }

    /** Records that a connection has taken this report, so it is not handed over again. */
    public void markDelivered(Event event) {
        List<Event> held = waiting.get(rootOf(event.leadId()));
        if (held != null) {
            held.removeIf(kept -> kept.planId().equals(event.planId()));
        }
    }

    /**
     * Marks the report of the hire with this plan id as read (28h Stage 27, z30), for whichever chat it was kept for, so the chat does not hand
     * over a report a client already took with {@code hire_wait}. Answers whether it was still waiting to be read.
     */
    public boolean takeReportOf(String planId) {
        boolean wasWaiting = false;
        for (List<Event> held : waiting.values()) {
            wasWaiting |= held.removeIf(kept -> kept.planId().equals(planId));
        }
        return wasWaiting;
    }

    /** The reports of finished background hires that trace back to this chat session and that no connection has taken, oldest first. */
    public List<Event> undeliveredFor(String sessionId) {
        return List.copyOf(waiting.getOrDefault(sessionId, new CopyOnWriteArrayList<>()));
    }

    /** How many hires are running now. */
    public int runningCount() {
        return running.size();
    }

    /** The running hires as first name and seat, in the order they began: who a refusal for too many running hires names. */
    public List<String> runningNames() {
        List<String> names = new java.util.ArrayList<>();
        for (Running hire : running) {
            names.add(hire.name + " · " + hire.seat);
        }
        return names;
    }

    /** The threads whose next hire is started in the background; read once, when that hire is registered. */
    private final Set<Thread> backgroundNext = ConcurrentHashMap.newKeySet();

    /** The plan ids of the running hires that were started in the background, until their answers are kept. */
    private final Set<String> backgroundPlans = ConcurrentHashMap.newKeySet();

    /** Marks the hire the current thread registers next as started in the background (Stage 24, M7): its result comes back to its lead as a new turn. */
    public void nextHireIsBackground() {
        backgroundNext.add(Thread.currentThread());
    }

    /** The running hires this caller issued, each as "first name · seat", in the order they began: what is still out for a lead that has just had one report. */
    public List<String> runningNamesFor(String callerId) {
        List<String> names = new java.util.ArrayList<>();
        for (Running hire : running) {
            if (hire.leadId.equals(callerId) && !hire.leadId.isEmpty()) {
                names.add(hire.name + " · " + hire.seat);
            }
        }
        return names;
    }

    /** The running hires this caller issued, each as "first name · seat (plan id)": what a lead that stopped hands to whoever hired it (28h z109). */
    public List<String> runningWithPlansFor(String callerId) {
        List<String> listed = new java.util.ArrayList<>();
        for (Running hire : running) {
            if (hire.leadId.equals(callerId) && !hire.leadId.isEmpty()) {
                listed.add(hire.name + " · " + hire.seat + " (" + hire.planId + ")");
            }
        }
        return listed;
    }

    /**
     * Whether this plan id is the plan of a hire that is running right now (Stage 24, M11): a step of such a plan is waiting because a hire is
     * working on it, which is the normal state while a lead has hires out, and not a step that has been left behind.
     */
    public boolean isRunningPlan(String planId) {
        for (Running hire : running) {
            if (!planId.isEmpty() && planId.equals(hire.planId)) {
                return true;
            }
        }
        return false;
    }

    /** The first name of the running hire of this seat for this brief; none when no such hire is running. */
    public Optional<String> nameOf(String seat, String brief) {
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief))) {
                return Optional.of(hire.name);
            }
        }
        return Optional.empty();
    }

    /** Stops every running hire of this seat for this brief and answers how many there were. */
    public synchronized int stop(String seat, String brief) {
        int stoppedCount = 0;
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief))) {
                stopped.add(hire.thread);
                hire.onStop.forEach(Runnable::run);
                hire.thread.interrupt();
                stoppedCount++;
            }
        }
        return stoppedCount;
    }

    /** How many finished answers are kept. */
    static final int KEPT = 200;

    /**
     * What the hire tool answered for each hire it ran (Sprint 28h, S17): kept so the agent face can show a hire's outcome, tokens, cost and
     * changed files even when the hiring brain's stream does not carry the answer back. Cursor's agent reports the end of a tool call with
     * {@code {"success": true}} and nothing else, so a face that only reads the brain's echo shows every hire it ran as done, including one
     * the hire tool refused. The engine runs the hire itself, so it is the first-hand source. Bounded: the newest {@value #KEPT} are kept.
     */
    private final java.util.Map<String, com.fasterxml.jackson.databind.JsonNode> finished = new java.util.LinkedHashMap<>();

    /** Keeps the answer of the hire of this seat for this brief, replacing an earlier one and dropping the oldest past the bound. */
    public void record(String seat, String brief, com.fasterxml.jackson.databind.JsonNode answer) {
        String key = seat + "\u0000" + normal(brief);
        synchronized (this) {
            finished.remove(key);
            finished.put(key, answer);
            while (finished.size() > KEPT) {
                finished.remove(finished.keySet().iterator().next());
            }
        }
        // Told outside the lock: a subscriber answers a host and takes locks of its own.
        String plan = answer.path("plan").asText("");
        Event ended;
        synchronized (routing) {
            ended = new Event("finished", plan, answer.path("name").asText(""), seat, normal(brief), issuedBy.getOrDefault(plan, ""), answer,
                backgroundPlans.remove(plan));
            // 28h z137: a hire that was itself an attempt at an earlier task is remembered with its number, so the next one counts on.
            if (answer.path("attempt").isNumber()) {
                attempts.put(plan, answer.path("attempt").asInt());
            }
            // 28h z136: what it used is added to its lead's one total, before any report of it is read.
            if (!ended.leadId().isEmpty()) {
                HireTotals used = HireTotals.of(answer);
                below.merge(ended.leadId(), used, HireTotals::plus);
                // A lead that has ended sent its total up without this one, so it counts under the nearest lead above that still runs too.
                if (!isRunningPlan(ended.leadId())) {
                    runningLeadAtOrAbove(issuedBy.getOrDefault(ended.leadId(), "")).ifPresent(above -> below.merge(above, used, HireTotals::plus));
                }
            }
            if (ended.background()) {
                route(inboxes.get(ended.leadId()), ended);
            }
        }
        // A lead's run that reads an inbox takes the report there. When none takes it, a lead that still runs (its brain's turns are over, or its
        // brain is not started over ACP) has the report held and told again the moment it ends, for its chat (28h-C27-18). A background hire whose
        // lead does not run, a chat or a hire that has ended, has its report kept until a connection takes it, so a chat closed while it worked is
        // told when it is opened again (ND4).
        // Told whoever holds the report: the hire has finished, so its card ends now, while its lead still runs too.
        tell(ended);
    }

    /** Keeps a finished hire's report for the chat its lead traces back to, until a connection takes it; the newest hundred are kept. */
    private void keepForItsChat(Event ended) {
        List<Event> held = waiting.computeIfAbsent(rootOf(ended.leadId()), root -> new CopyOnWriteArrayList<>());
        held.add(ended);
        while (held.size() > 100) {
            held.remove(0);
        }
    }

    /** The answer of the latest hire of this seat for this brief, or none when this engine ran no such hire. */
    public synchronized Optional<com.fasterxml.jackson.databind.JsonNode> answerOf(String seat, String brief) {
        return Optional.ofNullable(finished.get(seat + "\u0000" + normal(brief)));
    }

    /**
     * The kept answer of the hire whose plan this is (28h D57 (j)): what the commit tool reads the files a step produced from. None when this
     * engine kept no answer for that plan, which is also the answer for a plan older than the {@value #KEPT} kept.
     */
    public synchronized Optional<com.fasterxml.jackson.databind.JsonNode> answerOfPlan(String planId) {
        if (planId == null || planId.isBlank()) {
            return Optional.empty();
        }
        return finished.values().stream().filter(answer -> planId.equals(answer.path("plan").asText(""))).reduce((first, later) -> later);
    }

    private static String normal(String brief) {
        return brief == null ? "" : brief.strip();
    }

    /**
     * The seat this caller really is (28h Stage 27, z25), so a hire path does not take the caller's word for it: a hired brain's tool calls carry
     * its own plan id as the session, so the caller is the running hire with that plan id and its seat is that hire's; a principal brain's key is
     * main. None for a caller the registry knows nothing about, which is not guessed at.
     */
    public Optional<String> seatOfCaller(String callerId) {
        if (callerId == null || callerId.isEmpty()) {
            return Optional.empty();
        }
        for (Running hire : running) {
            if (callerId.equals(hire.planId)) {
                return Optional.of(hire.seat);
            }
        }
        return isRoot(callerId) ? Optional.of(SeatTree.TOP) : Optional.empty();
    }

    /**
     * The claim a pm takes for the sprint whose plan file is {@code boardFile} (28h Stage 27, z26): one pm runs a sprint, and a second is refused by
     * the same admission that refuses two hires in one package, naming the pm that holds it. The sprint is the plan file because no sprint id is
     * on the hire call; the key cannot overlap a package name, so it never clashes with a package claim.
     */
    public String sprintClaim(String boardFile) {
        return "sprint:" + java.nio.file.Path.of(boardFile).toAbsolutePath().normalize();
    }

    /**
     * How long the running hire with this plan id has shown no sign of life, in whole seconds (28h Stage 27, z28): a hire whose brain has made no
     * call, sent no usage report and begun no compaction since this long is quiet, and a principal that is told can look at it. None when no
     * hire with this plan id is running.
     */
    public java.util.OptionalLong idleSecondsOf(String planId) {
        for (Running hire : running) {
            if (planId != null && !planId.isEmpty() && planId.equals(hire.planId)) {
                return java.util.OptionalLong.of(Math.max(0, System.currentTimeMillis() - hire.lastActivityMs) / 1000);
            }
        }
        return java.util.OptionalLong.empty();
    }

    /** Keeps the reason a running hire gave for a full test run on that hire's record, for the pm that reads the hire (28h Stage 27, z27); a blank reason or a caller that is no running hire keeps nothing. */
    public void noteFullRunReason(String callerId, String reason) {
        if (callerId == null || callerId.isEmpty() || reason == null || reason.isBlank()) {
            return;
        }
        for (Running hire : running) {
            if (callerId.equals(hire.planId)) {
                synchronized (fullRunReasons) {
                    fullRunReasons.computeIfAbsent(callerId, plan -> new java.util.ArrayList<>()).add(reason.strip());
                    while (fullRunReasons.size() > KEPT) {
                        fullRunReasons.remove(fullRunReasons.keySet().iterator().next());
                    }
                }
            }
        }
    }

    /** The reasons the hire with this plan id gave for full test runs, in the order given, kept after the hire ends so its answer can carry them; none for any other id. */
    public List<String> fullRunReasonsOf(String planId) {
        synchronized (fullRunReasons) {
            return List.copyOf(fullRunReasons.getOrDefault(planId == null ? "" : planId, List.of()));
        }
    }

    /** The reasons each hire gave for its full test runs, by plan id; the newest {@value #KEPT} hires are kept (28h Stage 27, z27). */
    private final java.util.Map<String, List<String>> fullRunReasons = new java.util.LinkedHashMap<>();

    /**
     * Tells the subscribers, once for each stretch of quiet, of every running hire that has shown no sign of life for this many seconds or more
     * (28h Stage 27, z28, the first ceiling): the event {@code stalled}, so a card and a lead are told without anything having to ask. A hire that
     * acts and goes quiet again is told again. Answers who was told, as first name and seat.
     */
    public List<String> noteQuiet(long ceilingSeconds) {
        List<Event> told = new java.util.ArrayList<>();
        synchronized (this) {
            long now = System.currentTimeMillis();
            for (Running hire : running) {
                long idle = Math.max(0, now - hire.lastActivityMs) / 1000;
                if (!hire.planId.isEmpty() && idle >= ceilingSeconds && hire.quietToldAtMs != hire.lastActivityMs && runningNamesFor(hire.planId).isEmpty()) {
                    hire.quietToldAtMs = hire.lastActivityMs;
                    told.add(new Event("stalled", hire.planId, hire.name, hire.seat, hire.brief, hire.leadId, null, hire.background));
                }
            }
        }
        // Told outside the lock: a subscriber answers a host and takes locks of its own.
        told.forEach(this::tell);
        return told.stream().map(event -> event.name() + " · " + event.seat()).toList();
    }

    /**
     * Stops every running hire that has shown no sign of life for this many seconds or more (28h Stage 27, z28, the second ceiling): a hire that
     * goes quiet is not left running for ever, it is stopped the way a person's stop ends it, marked as a stall with how long it was quiet so its
     * answer can say why, and named in what this answers, as first name and seat. A hire with no plan open yet is not judged: nothing has been asked
     * of its brain.
     */
    public synchronized List<String> stopStalled(long ceilingSeconds) {
        List<String> stoppedNames = new java.util.ArrayList<>();
        long now = System.currentTimeMillis();
        for (Running hire : running) {
            long idle = Math.max(0, now - hire.lastActivityMs) / 1000;
            // 28h z102: a lead with a hire running under it is waiting, not quiet.
            if (!hire.planId.isEmpty() && idle >= ceilingSeconds && hire.stalledForSeconds < 0 && runningNamesFor(hire.planId).isEmpty()) {
                hire.stalledForSeconds = idle;
                stopped.add(hire.thread);
                hire.onStop.forEach(Runnable::run);
                hire.thread.interrupt();
                stoppedNames.add(hire.name + " · " + hire.seat);
            }
        }
        return stoppedNames;
    }

    /** What the ended hires under each lead used (28h z136), by the lead's plan id; added up as each hire's answer is kept. */
    private final java.util.Map<String, HireTotals> below = new ConcurrentHashMap<>();

    /** The one total of what the ended hires under this lead used, theirs and what they carried from below; none when none has ended. */
    public java.util.Optional<HireTotals> usedUnder(String leadPlanId) {
        return java.util.Optional.ofNullable(below.get(leadPlanId == null ? "" : leadPlanId));
    }

    /** Which attempt at its task each ended hire was, by its plan id, for the hires that corrected an earlier one (28h z137). */
    private final java.util.Map<String, Integer> attempts = new ConcurrentHashMap<>();

    /** Which attempt at its task this ended hire was: 1 unless it corrected an earlier one, and then one more than that one's. */
    public int attemptOf(String planId) {
        return attempts.getOrDefault(planId == null ? "" : planId, 1);
    }

    /** Keeps what the brain of the hire running on the current thread wrote to its caller, so its progress lines can be read while it runs (28h z85). */
    public void noteText(String chunk) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                synchronized (hire.written) {
                    hire.written.append(chunk);
                }
                hire.lastActivityMs = System.currentTimeMillis();
            }
        }
    }

    /** Writes again the line of a call the hire on the current thread already made, from the input its brain has now sent; it counts no call (28h z129). */
    public void noteCallInput(String id, com.fasterxml.jackson.databind.JsonNode input) {
        for (Running hire : running) {
            java.util.Map.Entry<Integer, String> line = hire.thread == Thread.currentThread() ? hire.lines.get(id) : null;
            if (line != null) {
                Progress seen = hire.progress;
                List<String> calls = new java.util.ArrayList<>(seen.calls());
                calls.set(line.getKey(), new ToolCall(line.getValue(), input).line(line.getValue()));
                hire.progress = new Progress(seen.toolSteps(), seen.contextTokens(), seen.costUsd(), List.copyOf(calls));
            }
        }
    }

    /**
     * As {@link #noteToolCall(String)}, with the call's id and input: the kept line names what the input looked at, and
     * {@link #noteCallInput} writes it again when the input arrives after the call was opened (28h z129).
     *
     * @param id    the call's id as the brain streamed it
     * @param title the call's title as the brain streamed it; a blank one is counted and not kept
     * @param input the call's input as far as the brain has sent it
     */
    public void noteToolCall(String id, String title, com.fasterxml.jackson.databind.JsonNode input) {
        boolean kept = title != null && !title.isBlank();
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread() && kept) {
                hire.lines.put(id, java.util.Map.entry(hire.progress.calls().size(), title));
            }
        }
        noteToolCall(kept ? new ToolCall(title, input).line(title) : title);
    }
}
