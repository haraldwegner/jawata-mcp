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
    public record Progress(int toolSteps, OptionalLong contextTokens, OptionalDouble costUsd) {
    }

    private static final class Running {
        final String seat;
        final String brief;
        /** The first name this hire carries, given when it was registered (Stage 24, D55). */
        final String name;
        /** The id of the caller that issued this hire: when that caller is a hired lead, the plan id of the lead's own hire (Stage 24, D55). */
        final String leadId;
        /** This hire's own plan id, known once its plan is open; empty before. */
        volatile String planId = "";
        /** Whether this hire runs on an engine thread started for it, so its result comes back as a new turn and not as the call's answer. */
        volatile boolean background;
        /** The packages this hire has claimed to work in (Stage 24, M13); empty when it claimed none, and emptied when the hire ends. */
        volatile java.util.Set<String> claims = java.util.Set.of();
        /** When the claims were made, in milliseconds. */
        volatile long claimedAtMs;
        final Thread thread;
        final List<Runnable> onStop = new CopyOnWriteArrayList<>();
        volatile Progress progress = new Progress(0, OptionalLong.empty(), OptionalDouble.empty());
        /** Whether the brain is compacting its own context right now, as its stream last said. */
        volatile boolean compacting;

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

        /** Whether the person stopped this hire; true once, then the mark is gone. */
        public boolean wasStopped() {
            return stopped.remove(hire.thread);
        }

        /** The brain the person moved this hire onto, if the stop was a Replace (D53 (i)); answered once, then the mark is gone. */
        public Optional<Replacement> replacement() {
            return Optional.ofNullable(replacements.remove(hire.thread));
        }

        @Override
        public void close() {
            synchronized (HireRegistry.this) {
                hire.claims = java.util.Set.of();
                running.remove(hire);
                stopped.remove(hire.thread);
                replacements.remove(hire.thread);
            }
        }
    }

    /** The brain, and the model of it, a running hire is to continue on after its stop (D53 (i)). The model is empty for a brain that takes none. */
    public record Replacement(SeatHire.Brain brain, String model) {
    }

    private static final HireRegistry SHARED = new HireRegistry();

    private final List<Running> running = new CopyOnWriteArrayList<>();
    private final Set<Thread> stopped = ConcurrentHashMap.newKeySet();
    private final java.util.Map<Thread, Replacement> replacements = new ConcurrentHashMap<>();

    /** The one registry of running hires in this process, which the hire tool fills and the agent face reads. */
    public static HireRegistry shared() {
        return SHARED;
    }

    /**
     * Registers a hire of this seat for this brief as running on the current thread. A stop mark left on this thread by an earlier hire is
     * dropped: it would otherwise end this hire the moment its brain's process started. Registering, stopping and closing take turns, so a
     * stop cannot mark a thread whose hire has just closed.
     */
    public synchronized Registration begin(String seat, String brief) {
        return begin(seat, brief, "");
    }

    /**
     * Registers the hire as {@link #begin(String, String)} does and gives it the next first name of this conversation, the id of the caller
     * that issued it, so two hires of one seat in one conversation can be told apart.
     */
    public synchronized Registration begin(String seat, String brief, String conversationKey) {
        Running hire = new Running(seat, normal(brief), HireNames.shared().assign(conversationKey), conversationKey == null ? "" : conversationKey,
            Thread.currentThread());
        stopped.remove(hire.thread);
        hire.background = backgroundNext.remove(hire.thread);
        running.add(hire);
        return new Registration(hire);
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

    /** Counts one more tool call for the hire running on the current thread; a thread running no hire is ignored. */
    public void noteToolStep() {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                Progress seen = hire.progress;
                hire.progress = new Progress(seen.toolSteps() + 1, seen.contextTokens(), seen.costUsd());
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
                    costUsd.isPresent() ? costUsd : seen.costUsd());
            }
        }
    }

    /** Records that the brain of the hire running on the current thread began or ended compacting its context; a thread running no hire is ignored. */
    public void noteCompaction(boolean compacting) {
        for (Running hire : running) {
            if (hire.thread == Thread.currentThread()) {
                hire.compacting = compacting;
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
     */
    public final class Inbox implements AutoCloseable {
        private final java.util.concurrent.LinkedBlockingQueue<Event> reports = new java.util.concurrent.LinkedBlockingQueue<>();
        private final java.util.concurrent.atomic.AtomicInteger out = new java.util.concurrent.atomic.AtomicInteger();
        private final Runnable unsubscribe;

        private Inbox(String leadId) {
            this.unsubscribe = subscribe(event -> {
                if (!leadId.equals(event.leadId()) || !event.background()) {
                    return;
                }
                if ("started".equals(event.kind())) {
                    out.incrementAndGet();
                } else if ("finished".equals(event.kind())) {
                    out.decrementAndGet();
                    reports.add(event);
                }
            });
        }

        /** How many of this lead's background hires have started and not yet reported. */
        public int outstanding() {
            return out.get();
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
            return all;
        }

        /** Whether no report is waiting to be read. */
        public boolean isEmpty() {
            return reports.isEmpty();
        }

        @Override
        public void close() {
            unsubscribe.run();
        }
    }

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
                                .truncatedTo(java.time.temporal.ChronoUnit.MINUTES));
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
     */
    public synchronized List<String> stopTree(String planId) {
        List<String> stoppedNames = new java.util.ArrayList<>();
        for (Running hire : running) {
            boolean below = false;
            String above = hire.leadId;
            for (int steps = 0; steps < 16 && !above.isEmpty() && !below; steps++) {
                below = above.equals(planId);
                above = issuedBy.getOrDefault(above, "");
            }
            if (hire.planId.equals(planId) || below) {
                stopped.add(hire.thread);
                hire.onStop.forEach(Runnable::run);
                hire.thread.interrupt();
                stoppedNames.add(hire.name + " · " + hire.seat);
            }
        }
        return stoppedNames;
    }

    /** What each plan was, as first name and seat, kept so a report can say whose hire it was after that hire has ended. */
    private final java.util.Map<String, String> labels = new ConcurrentHashMap<>();

    /** The finished background hires no connection has taken the report of, by the chat session they trace back to, until one does. */
    private final java.util.Map<String, List<Event>> waiting = new ConcurrentHashMap<>();

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

    /**
     * Stops every running hire of this seat for this brief so that it continues on another brain (D53 (i)), and answers how many there were.
     * The hire's own run reads the mark and hires the seat again; nothing else is told. Only the brain's process is ended and the thread is not
     * interrupted: the run then reads to the end of the output and keeps what the brain has said, which an interrupt would lose, and the next brain needs it.
     */
    public synchronized int replace(String seat, String brief, SeatHire.Brain brain, String model) {
        int replaced = 0;
        for (Running hire : running) {
            if (hire.seat.equals(seat) && hire.brief.equals(normal(brief))) {
                replacements.put(hire.thread, new Replacement(brain, model));
                stopped.add(hire.thread);
                hire.onStop.forEach(Runnable::run);
                replaced++;
            }
        }
        return replaced;
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
        Event ended = new Event("finished", plan, answer.path("name").asText(""), seat, normal(brief), issuedBy.getOrDefault(plan, ""), answer,
            backgroundPlans.remove(plan));
        // A background hire whose lead is not a running hire, a chat or a hire that has ended, has nobody running to read its report from an inbox:
        // it is kept until a connection takes it, so a chat closed while it worked is told when it is opened again (ND4).
        if (ended.background() && !isRunningPlan(ended.leadId())) {
            List<Event> held = waiting.computeIfAbsent(rootOf(ended.leadId()), root -> new CopyOnWriteArrayList<>());
            held.add(ended);
            while (held.size() > 100) {
                held.remove(0);
            }
        }
        tell(ended);
    }

    /** The answer of the latest hire of this seat for this brief, or none when this engine ran no such hire. */
    public synchronized Optional<com.fasterxml.jackson.databind.JsonNode> answerOf(String seat, String brief) {
        return Optional.ofNullable(finished.get(seat + "\u0000" + normal(brief)));
    }

    private static String normal(String brief) {
        return brief == null ? "" : brief.strip();
    }
}
