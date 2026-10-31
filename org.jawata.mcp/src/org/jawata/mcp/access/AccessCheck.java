package org.jawata.mcp.access;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

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

    private final Path policyFile;
    private final Function<String, List<Path>> workspaceOf;
    private final Path home;
    private final Path temp;

    /**
     * @param policyFile  the one declared policy file
     * @param workspaceOf the folders of the workspace a session is concerned with, by session id; empty when it has none
     * @param home        the person's home, which {@code ${home}} stands for and the {@code ~} spelling expands to, or null when unknown
     * @param temp        the machine's scratch directory, which {@code ${temp}} stands for
     */
    public AccessCheck(Path policyFile, Function<String, List<Path>> workspaceOf, Path home, Path temp) {
        this.policyFile = policyFile;
        this.workspaceOf = workspaceOf;
        this.home = home;
        this.temp = temp;
    }

    /** The sentence to refuse the call with, or nothing when every place it names is allowed or it names none. */
    public Optional<String> refuse(String sessionId, JsonNode arguments) {
        List<Path> workspace = workspaceOf.apply(sessionId);
        ToolPaths.Named named = ToolPaths.of(arguments, workspace, home);
        if (named.places().isEmpty() && named.unresolved().isEmpty()) {
            return Optional.empty();
        }
        AccessPolicy policy = AccessPolicy.read(policyFile, Map.of(
            "home", home == null ? List.of() : List.of(home),
            "temp", List.of(temp),
            "workspace", workspace));
        if (!named.unresolved().isEmpty()) {
            return Optional.of(refusal("'" + named.unresolved().get(0) + "' is a relative path and the session has no workspace to say where it is"));
        }
        for (Path place : named.places()) {
            AccessPolicy.Decision decision = policy.check(place);
            if (!decision.allowed()) {
                return Optional.of(refusal(decision.reason()));
            }
        }
        return Optional.empty();
    }

    private String refusal(String reason) {
        return "Access policy: " + reason + ". The agent may name only the places the policy file allows (" + policyFile
            + "); if this one is genuinely needed, the person changes that file or approves the call.";
    }
}
