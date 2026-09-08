package org.jawata.mcp.models;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Supplier;

/**
 * Sprint 28a (D11) — <b>a wrong-workspace question answers itself</b>. A machine
 * with several jawata servers (one per workspace) gives the agent no way to tell
 * which serves what: every server used to introduce itself with the same static
 * instructions, and a symbol that lives in the OTHER workspace came back as a
 * bare empty result — observed live 2026-08-11, and again 2026-08-16 when a
 * search against the wrong workspace returned {@code results: []} with nothing
 * naming the place to look.
 *
 * <p>This holder carries the one fact that disambiguates: WHICH workspace this
 * server serves, and its projects. Installed once at boot from
 * {@code workspace.json} (before the message loop starts, so the initialize
 * handshake never races the async project load), preferring the LIVE loaded
 * project keys once they exist. Three surfaces read it: the initialize
 * {@code instructions} (the agent sees the roster before choosing a server),
 * every {@code SYMBOL_NOT_FOUND} hint, and the empty-search steering line.</p>
 */
public final class WorkspaceIdentity {

    private static final int MAX_NAMED_PROJECTS = 12;

    private static volatile String workspaceName;
    private static volatile List<String> configuredProjects = List.of();
    private static volatile Supplier<List<String>> liveProjectKeys;
    private static volatile Supplier<String> loadFailure;

    private WorkspaceIdentity() {}

    /**
     * Install the boot-time identity: the manager-written workspace name (may be
     * null on manual launches) and the configured project roots. Call before the
     * message loop starts; safe to call at most once per process in production.
     */
    public static void install(String name, List<Path> projectRoots) {
        workspaceName = name == null || name.isBlank() ? null : name;
        configuredProjects = projectRoots == null ? List.of()
            : projectRoots.stream()
                .map(p -> p.getFileName() == null ? p.toString() : p.getFileName().toString())
                .toList();
    }

    /**
     * The live loaded-project keys, preferred over the boot list once non-empty —
     * they reflect later {@code load_project}/{@code project(action=add|remove)}
     * calls the boot file never sees. The supplier may return an empty list while
     * the async load is still running; the boot list covers that window.
     */
    public static void installLiveKeys(Supplier<List<String>> supplier) {
        liveProjectKeys = supplier;
    }

    /**
     * The terminal load failure, when there is one — supplied so the identity can
     * tell "still loading" from "will never load" (mcp#32).
     *
     * <p>The boot list exists to cover the async-loading window. It kept
     * answering after a TERMINAL failure too, so a workspace whose only project
     * failed to load introduced that project as PRESENT while {@code
     * health_check} reported {@code projectCount: 0} — the server contradicting
     * itself in two answers to the same agent. The supplier returns null while
     * the load is fine or still running, and the reason once it has failed.</p>
     */
    public static void installLoadFailure(Supplier<String> supplier) {
        loadFailure = supplier;
    }

    /**
     * mcp#65: whether the async project load is STILL RUNNING. Supplied, like the failure
     * reason beside it, so this class stays a statement of identity rather than a reader of
     * the application's lifecycle. Null until installed, and a server without it behaves
     * exactly as before.
     *
     * <p>This is the LOADING state itself and not a count comparison standing in for it. Live
     * keys shorter than the configured list would be the obvious proxy and it is wrong twice:
     * a project removed at run time makes it read LOADING forever, and a workspace with no
     * boot file has no denominator to compare against.</p>
     */
    private static volatile Supplier<Boolean> loading;

    /** mcp#65: install the still-loading supplier (application wiring). */
    public static void installLoading(Supplier<Boolean> supplier) {
        loading = supplier;
    }

    /** True while the async load is still running — a broken supplier answers false. */
    private static boolean readLoading() {
        Supplier<Boolean> supplier = loading;
        if (supplier == null) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(supplier.get());
        } catch (Exception e) {
            return false; // never let this break an error response
        }
    }

    /**
     * mcp#27 stage 1: the other residents on this machine, supplied rather than read here so
     * this class stays a pure statement of identity. Null until installed.
     */
    private static volatile Supplier<List<SiblingRegistry.Sibling>> siblings;

    /** mcp#27: install the sibling-resident supplier (application wiring). */
    public static void installSiblings(Supplier<List<SiblingRegistry.Sibling>> supplier) {
        siblings = supplier;
    }

    /**
     * mcp#27 stage 1: ASKING the siblings, supplied for the same reason the list is — the
     * question goes over the network, and this class states who this server is. Null until
     * installed, and a server with no peek installed still names its siblings.
     */
    private static volatile java.util.function.Function<String, SiblingPeek.Sweep> peek;

    /** mcp#27: install the sibling-peek function (application wiring). */
    public static void installPeek(java.util.function.Function<String, SiblingPeek.Sweep> fn) {
        peek = fn;
    }

    /** Test hook — a static holder that cannot be cleared poisons every later test. */
    static void reset() {
        workspaceName = null;
        configuredProjects = List.of();
        liveProjectKeys = null;
        loadFailure = null;
        loading = null;
        siblings = null;
        peek = null;
    }

    /** The siblings this server knows of, or empty — a broken supplier answers empty. */
    private static List<SiblingRegistry.Sibling> readSiblings() {
        Supplier<List<SiblingRegistry.Sibling>> supplier = siblings;
        if (supplier == null) {
            return List.of();
        }
        try {
            List<SiblingRegistry.Sibling> found = supplier.get();
            return found == null ? List.of() : found;
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * This server's workspace name, or null when it has none.
     *
     * <p>mcp#27: the sibling registry keys on this — it is how a resident recognises ITSELF in
     * a list of every resident on the machine, and a server that peeked itself would report its
     * own miss back as a sibling's answer.</p>
     */
    public static String name() {
        return workspaceName;
    }

    /** True once {@link #install} gave this server something to say about itself. */
    public static boolean installed() {
        return workspaceName != null || !configuredProjects.isEmpty();
    }

    /**
     * The self-introduction appended to the initialize instructions, or null when
     * nothing was installed (plain-classpath tests, bare manual launches).
     */
    public static String describe() {
        if (!installed()) {
            return null;
        }
        return "THIS SERVER'S WORKSPACE" + (workspaceName == null ? "" : " ('" + workspaceName + "')")
            + ": " + projectSummary()
            + ". A machine can run several jawata servers, one per workspace — pick the one"
            + " whose projects match your question; the others cannot see this code.";
    }

    /**
     * The one-line redirect for a symbol this workspace does not contain — appended
     * to SYMBOL_NOT_FOUND hints and empty-search steering. Null when not installed.
     */
    public static String elsewhereHint() {
        if (!installed()) {
            return null;
        }
        // mcp#65: DURING THE LOAD, the sentence below is not merely unhelpful, it is wrong.
        // It sends the agent to another server for a symbol THIS one is in the middle of
        // acquiring — and on a 194-module workspace that window is minutes long, which is
        // exactly when a fresh session consults a catalogue address. The miss is not a
        // negative answer here; it is no answer yet, and the two must not read alike.
        if (readLoading()) {
            return "This is the" + (workspaceName == null ? "" : " '" + workspaceName + "'")
                + " workspace (" + projectSummary() + "). NOT YET ANSWERABLE rather than"
                + " absent: this workspace may well hold the symbol once its projects finish"
                + " loading. Ask again, or call health_check, which reports when the load is"
                + " done — do not conclude from this that the symbol does not exist.";
        }

        String hint = "This is the" + (workspaceName == null ? "" : " '" + workspaceName + "'")
            + " workspace (" + projectSummary() + ") — a symbol that lives in another"
            + " project tree is served by that tree's own jawata server, not this one.";

        // mcp#27 stage 1: NAME them when we know them. The sentence above is a true statement
        // about how jawata is deployed and useless as an instruction — it tells an agent that
        // some other server might help without saying whether one is running or which. Where
        // studio has published a registry we can say exactly who else is up.
        List<SiblingRegistry.Sibling> others = readSiblings();
        if (others.isEmpty()) {
            // NOT "no other servers are running" — an absent registry means we do not KNOW of
            // any, which is a different fact and the one this codebase keeps having to
            // separate. A hand-launched resident has no studio behind it and no registry, and
            // its machine may still be full of siblings.
            return hint;
        }
        return hint + " Running here: "
            + others.stream().map(SiblingRegistry.Sibling::workspaceName)
                .collect(java.util.stream.Collectors.joining(", "))
            + ".";
    }

    /**
     * mcp#27 stage 1 — the same redirect for a NAMED symbol, which can be asked about rather
     * than merely pointed at.
     *
     * <p>{@link #elsewhereHint()} can only say who else is running. Given the symbol, this asks
     * them, and a miss becomes <i>"workspace X has it, in project Y"</i> — the difference
     * between a true statement about how jawata is deployed and an answer.</p>
     *
     * <p>Falls back to the naming-only hint whenever the question cannot be asked: no peek
     * installed, no siblings known, or a symbol no sibling could resolve. It never fails and
     * never throws — the caller is already reporting a miss, and this can only add to it.</p>
     */
    public static String elsewhereHint(String symbol) {
        String named = elsewhereHint();
        if (named == null) {
            return null;
        }
        // THE LOOP GUARD. This request came from another resident's miss path, so answering
        // it must not start a peek of our own — two residents pointed at each other would
        // recurse until something gives. The header is sent by SiblingPeek and set on this
        // thread by the transport; honouring it here is what makes sending it a guard rather
        // than a comment.
        if (SiblingPeek.servingAPeek()) {
            return named;
        }
        // mcp#65: and do not peek while THIS workspace is still loading. A sibling's answer
        // would overwrite "not yet answerable" with a confident redirect, when the more
        // useful fact is that the symbol may be here in a minute — and the walk would spend
        // a network budget on the miss path at the one moment the machine is busiest.
        if (readLoading()) {
            return named;
        }
        java.util.function.Function<String, SiblingPeek.Sweep> ask = peek;
        String typeName = askableTypeName(symbol);
        if (ask == null || typeName == null || readSiblings().isEmpty()) {
            return named;
        }
        SiblingPeek.Sweep sweep;
        try {
            sweep = ask.apply(typeName);
        } catch (Exception e) {
            // A miss must not become a failure because the hint could not be enriched.
            return named;
        }
        if (sweep == null || sweep.listed() == 0) {
            return named;
        }
        // Replaces the "Running here: …" tail rather than appending to it: having ASKED them,
        // naming who is up and then saying what they said is two answers to one question.
        return elsewhereHintBase() + " " + sweep.describe();
    }

    /**
     * The type a sibling could actually look up, or null when there is nothing askable.
     *
     * <p>A sibling is asked {@code inspect(kind=source, typeName=…)}, which resolves a
     * fully-qualified TYPE. So a member form is trimmed to its type — {@code com.foo.Bar#run}
     * is a question about {@code com.foo.Bar} — and a name with no package is refused rather
     * than asked, because it resolves nowhere and the walk would spend the whole budget to
     * learn nothing.</p>
     */
    static String askableTypeName(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            return null;
        }
        String type = symbol.trim();
        int member = type.indexOf('#');
        if (member >= 0) {
            type = type.substring(0, member);
        }
        int params = type.indexOf('(');
        if (params >= 0) {
            type = type.substring(0, params);
        }
        type = type.trim();
        // WHITESPACE IS THE DISCRIMINATOR, and it is here because the live two-resident probe
        // found prose reaching this method: `ToolResponse.symbolNotFound` takes a MESSAGE, and
        // its callers pass sentences like "'com.foo.Bar' not found in workspace scope … it is
        // gone, not moved." An earlier version refused that sentence only because it happened
        // to end in a full stop — the right answer for the wrong reason, and a sentence
        // without trailing punctuation would have been asked about verbatim.
        if (type.isEmpty() || type.chars().anyMatch(Character::isWhitespace)) {
            return null;
        }
        return type.contains(".") && !type.endsWith(".") && !type.startsWith(".") ? type : null;
    }

    /** The workspace sentence without the sibling tail, so the peek's answer can replace it. */
    private static String elsewhereHintBase() {
        return "This is the" + (workspaceName == null ? "" : " '" + workspaceName + "'")
            + " workspace (" + projectSummary() + ") — a symbol that lives in another"
            + " project tree is served by that tree's own jawata server, not this one.";
    }

    /** The terminal failure reason, or null — a broken supplier answers null. */
    private static String readLoadFailure() {
        Supplier<String> supplier = loadFailure;
        if (supplier == null) {
            return null;
        }
        try {
            String reason = supplier.get();
            return reason == null || reason.isBlank() ? null : reason;
        } catch (Exception e) {
            return null; // never let this break an error response
        }
    }

    private static String projectSummary() {
        List<String> live = null;
        Supplier<List<String>> supplier = liveProjectKeys;
        if (supplier != null) {
            try {
                live = supplier.get();
            } catch (Exception e) {
                live = null; // a broken supplier must never break an error response
            }
        }
        boolean nothingLive = live == null || live.isEmpty();
        // mcp#65: the LOADING window, stated instead of papered over. A 194-module workspace
        // takes minutes, and for that whole window the boot list below answers as though its
        // projects were PRESENT — which is the same over-claim mcp#32 removed for a terminal
        // failure, arriving from the other side. What is true here is a progress figure, and
        // it is worth more than a list: it tells a reader the workspace is filling up rather
        // than that it is empty or complete.
        if (readLoading()) {
            int ready = nothingLive ? 0 : live.size();
            if (configuredProjects.isEmpty()) {
                return "STILL LOADING — " + ready + " project(s) ready so far";
            }
            // The ROSTER still travels, because naming it is this line's other job: the
            // initialize instructions exist so an agent can pick the right server, and a
            // server that answered only "still loading" for minutes could not be chosen at
            // all. What changes is that the roster is labelled CONFIGURED rather than
            // presented as loaded.
            String shown = configuredProjects.stream().limit(MAX_NAMED_PROJECTS)
                .collect(java.util.stream.Collectors.joining(", "));
            int more = configuredProjects.size() - MAX_NAMED_PROJECTS;
            return "STILL LOADING — " + ready + " of " + configuredProjects.size()
                + " configured project(s) ready so far; configured: " + shown
                + (more > 0 ? " … and " + more + " more" : "");
        }
        if (nothingLive) {
            // mcp#32: a TERMINAL failure ends the boot list's mandate. Naming
            // the configured projects here would claim as present exactly the
            // projects the same server reports as zero.
            String failure = readLoadFailure();
            if (failure != null) {
                return (configuredProjects.isEmpty()
                        ? "no project loaded"
                        : configuredProjects.size() + " configured project(s) FAILED to load ("
                            + String.join(", ", configuredProjects) + ")")
                    + " — " + failure;
            }
        }
        List<String> names = nothingLive ? configuredProjects : live;
        if (names.isEmpty()) {
            return "no projects loaded yet";
        }
        String shown = names.stream().limit(MAX_NAMED_PROJECTS)
            .collect(java.util.stream.Collectors.joining(", "));
        int more = names.size() - MAX_NAMED_PROJECTS;
        return names.size() + " project(s): " + shown + (more > 0 ? " … and " + more + " more" : "");
    }
}
