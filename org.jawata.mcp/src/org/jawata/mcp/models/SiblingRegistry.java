package org.jawata.mcp.models;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * mcp#27 stage 1 — <b>the other jawata servers on this machine, so a miss can be an answer
 * instead of a guess.</b>
 *
 * <p>A machine runs one resident per workspace, and each can see only its own projects. Asked
 * for a symbol it does not have, a resident today says a shrug: {@code WorkspaceIdentity
 * .elsewhereHint()} tells the caller that some OTHER server may hold it, without knowing
 * whether one is running, which one, or whether it holds anything at all.</p>
 *
 * <p>This is the half that says WHO to ask. Studio already allocates and records the
 * {@code (workspace, port, token)} of every resident it manages; this reads that list back and
 * removes THIS server from it, which is the whole of the class's job. Asking is a separate
 * concern and deliberately not here.</p>
 *
 * <h2>Where the file lives, and why it is not studio's own config</h2>
 *
 * <p>Studio's layout is {@code <data_root>/workspaces/<workspace_name>/}, with
 * {@code workspace.json} written into each one before the resident spawns. A registry naming
 * EVERY workspace cannot live inside any one of them, so it sits one level up, at
 * {@code <data_root>/workspaces/residents.json} — reachable from a resident by the same walk it
 * already does to find its own {@code workspace.json}.</p>
 *
 * <p>Studio's {@code projects.json} holds the same facts and is deliberately NOT read: it is a
 * Rust application's private settings file, and pointing a Java process at its internal layout
 * is a join that breaks silently the first time studio reorganises its own config. The registry
 * is a published contract; {@code projects.json} is not.</p>
 *
 * <h2>Absence is normal and is not degradation</h2>
 *
 * <p>A resident launched by hand, by Cursor or by Claude Code has no studio behind it and so no
 * registry. That is the ordinary case, not a fault: it means there are no siblings to ask, and
 * the honest answer is an empty list. Only a registry that EXISTS and cannot be read is worth a
 * word, and that is logged rather than thrown — a search must not fail because a hint could not
 * be assembled.</p>
 */
public final class SiblingRegistry {

    private static final Logger log = LoggerFactory.getLogger(SiblingRegistry.class);
    private static final ObjectMapper OM = new ObjectMapper();

    /** The published file name, written by studio and read here. */
    public static final String FILE_NAME = "residents.json";

    /**
     * One running resident: where it listens and what it calls itself.
     *
     * @param workspaceName the name the sibling introduces itself by, quoted back to the caller
     * @param port          the sibling's loopback port
     * @param token         its bearer token; a resident refuses an unauthenticated caller, so a
     *                      registry entry without one names a server we cannot actually ask
     */
    public record Sibling(String workspaceName, int port, String token) {}

    private SiblingRegistry() {}

    /**
     * Every resident in the registry EXCEPT this one, or empty when there is no registry.
     *
     * <p>Self-exclusion is by workspace name, which is what both sides key on — studio writes
     * the name it launched the resident under, and the resident carries the same name from its
     * own {@code workspace.json}. A server that peeked itself would report its own miss back as
     * a sibling's answer, which is worse than not peeking at all.</p>
     *
     * @param registryDir where {@link #FILE_NAME} lives, or null
     * @param selfName    this server's workspace name; null excludes nothing, because a server
     *                    that does not know its own name cannot recognise itself in a list
     */
    public static List<Sibling> others(Path registryDir, String selfName) {
        if (registryDir == null) {
            return List.of();
        }
        Path file = registryDir.resolve(FILE_NAME);
        if (!Files.isRegularFile(file)) {
            // The ordinary case for a hand-launched resident: no studio, no siblings.
            return List.of();
        }
        try {
            JsonNode root = OM.readTree(Files.readString(file));
            JsonNode residents = root.path("residents");
            if (!residents.isArray()) {
                log.warn("{} exists but declares no 'residents' array — no siblings will be"
                    + " consulted. A registry that cannot be read is not the same fact as a"
                    + " machine with one resident.", file);
                return List.of();
            }
            List<Sibling> others = new ArrayList<>();
            for (JsonNode entry : residents) {
                String name = entry.path("workspaceName").asText("");
                int port = entry.path("port").asInt(0);
                String token = entry.path("token").asText("");
                if (name.isBlank() || port <= 0) {
                    // A row we cannot address is dropped rather than guessed at.
                    continue;
                }
                if (selfName != null && selfName.equals(name)) {
                    continue;
                }
                others.add(new Sibling(name, port, token));
            }
            return List.copyOf(others);
        } catch (Exception e) {
            // A search must not fail because a hint could not be assembled. Said out loud,
            // because "could not read the registry" and "there are no siblings" are different
            // facts and the empty list alone cannot tell them apart.
            log.warn("{} exists and could not be read ({}) — answering as if this machine has"
                + " one resident, which it may not.", file, e.toString());
            return List.of();
        }
    }
}
