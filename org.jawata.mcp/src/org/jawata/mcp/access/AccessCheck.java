package org.jawata.mcp.access;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The access policy applied to one tool call (Sprint 28h, S13, D20): every place the call names is put to the policy file, and the call is
 * refused when any place is refused. It is the same for every client, because it sits where every call passes (ACL8).
 *
 * <p><b>The file is read on every call</b>, so changing it changes the next decision and nothing else does (ACL1). <b>The workspace is the
 * caller's own</b> (ACL4): {@code ${workspace}} stands for the folders of the workspace the calling session is concerned with, so a task
 * on one project is refused a sibling project that sits in the same manifest. A session that declares none has no workspace, and the entry
 * allows nothing.</p>
 */
public final class AccessCheck {

    /** The policy of the tool call served on this thread, for the files the tool finds itself; none outside a call a check guards. */
    private static final ThreadLocal<Supplier<AccessPolicy>> SERVING = new ThreadLocal<>();

    private final Path policyFile;
    private final Function<String, List<Path>> workspaceOf;
    private final Path home;
    private final Path temp;
    private final Supplier<Optional<Path>> seatsFolder;

    /**
     * @param policyFile  the one declared policy file
     * @param workspaceOf the folders of the workspace a session is concerned with, by session id; empty when it has none
     * @param home        the person's home, which {@code ${home}} stands for and the {@code ~} spelling expands to, or null when unknown
     * @param temp        the machine's scratch directory, which {@code ${temp}} stands for
     */
    public AccessCheck(Path policyFile, Function<String, List<Path>> workspaceOf, Path home, Path temp) {
        this(policyFile, workspaceOf, home, temp, Optional::empty);
    }

    /**
     * @param seatsFolder the folder the person's seat files are in, as the host set it, read at each call; a call may name a seat file in it
     *                    though the policy file does not list it, never one on the deny list
     */
    public AccessCheck(Path policyFile, Function<String, List<Path>> workspaceOf, Path home, Path temp,
        Supplier<Optional<Path>> seatsFolder) {
        this.policyFile = policyFile;
        this.workspaceOf = workspaceOf;
        this.home = home;
        this.temp = temp;
        this.seatsFolder = seatsFolder;
    }

    /** The sentence to refuse the call with, or nothing when every place it names is allowed or it names none. */
    public Optional<String> refuse(String sessionId, JsonNode arguments) {
        List<Path> workspace = workspaceOf.apply(sessionId);
        ToolPaths.Named named = ToolPaths.of(arguments, workspace, home);
        if (named.places().isEmpty() && named.unresolved().isEmpty()) {
            return Optional.empty();
        }
        AccessPolicy policy = policyFor(workspace);
        if (!named.unresolved().isEmpty()) {
            return Optional.of(refusal("'" + named.unresolved().get(0) + "' is a relative path and the session has no workspace to say where it is"));
        }
        for (Path place : named.places()) {
            AccessPolicy.Decision decision = policy.check(place);
            if (!decision.allowed() && !(named.seatFiles().contains(place) && inSeatsFolder(place) && !policy.denies(place))) {
                return Optional.of(refusal(decision.reason()));
            }
        }
        return Optional.empty();
    }

    /**
     * Runs {@code call} for {@code sessionId} with {@link #openable()} answering for it. A tool that finds files by itself, such as a search
     * through a folder, names only the folder in its arguments, so each file it is about to open is put to the policy there (28h z70). The
     * policy is read only if the tool asks, and once for the call.
     */
    public <T> T whileServing(String sessionId, Supplier<T> call) {
        Supplier<AccessPolicy> before = SERVING.get();
        AccessPolicy[] read = new AccessPolicy[1];
        SERVING.set(() -> {
            if (read[0] == null) {
                read[0] = policyFor(workspaceOf.apply(sessionId));
            }
            return read[0];
        });
        try {
            return call.get();
        } finally {
            if (before == null) {
                SERVING.remove();
            } else {
                SERVING.set(before);
            }
        }
    }

    /** Whether the tool call served on this thread may open a file it found; every file when no check guards the call. */
    public static Predicate<Path> openable() {
        Supplier<AccessPolicy> serving = SERVING.get();
        return serving == null ? place -> true : place -> serving.get().check(place).allowed();
    }

    /**
     * Whether the person denied a file the tool call served on this thread found. Asked where a call may read folders the policy does not
     * allow by itself, such as the memory folders a load is given, and a denied file in them stays denied.
     */
    public static Predicate<Path> denied() {
        Supplier<AccessPolicy> serving = SERVING.get();
        return serving == null ? place -> false : place -> serving.get().denies(place);
    }

    private boolean inSeatsFolder(Path place) {
        return seatsFolder.get().map(folder -> PlaceNames.normalise(place).startsWith(PlaceNames.normalise(folder))).orElse(false);
    }

    private AccessPolicy policyFor(List<Path> workspace) {
        AccessPolicy policy = AccessPolicy.read(policyFile, Map.of(
            "home", home == null ? List.of() : List.of(home),
            "temp", List.of(temp),
            "workspace", workspace));
        return policy;
    }

    /**
     * Writes the policy that ships with jawata to {@code file} when there is none, and does nothing when there is one (ACL1, ACL8): the
     * file is the person's, so it is created once and never overwritten, and a restart cannot undo a change they made.
     *
     * @return whether the file was written
     */
    public static boolean installDefault(Path file) throws IOException {
        if (Files.exists(file)) {
            return false;
        }
        try (InputStream shipped = AccessCheck.class.getResourceAsStream("/access/access-policy.default.json")) {
            if (shipped == null) {
                throw new IOException("the default access policy is not among the resources of this build");
            }
            Files.createDirectories(file.toAbsolutePath().getParent());
            Files.copy(shipped, file);
            return true;
        }
    }

    private String refusal(String reason) {
        return "Access policy: " + reason + ". The agent may name only the places the policy file allows (" + policyFile
            + "); if this one is genuinely needed, the person changes that file or approves the call.";
    }
}
