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

    /**
     * How far up from the data dir to look. The resident's Eclipse {@code -data} is
     * {@code <data_root>/workspaces/<workspace>/} when studio spawns it directly, and
     * {@code <data_root>/workspaces/<workspace>/<uuid>/} when the launcher injects a session
     * subdirectory — so the registry is one or two levels up. Three is that plus one, and it is
     * BOUNDED rather than walked to the filesystem root on purpose: an unbounded search would
     * happily find a {@code residents.json} belonging to a different install, or to nobody, and
     * a wrong registry is worse than none because every address in it is confidently askable.
     */
    private static final int SEARCH_DEPTH = 3;

    private SiblingRegistry() {}

    /**
     * The directory holding {@link #FILE_NAME}, found by walking up from the resident's data
     * dir, or null when there is none within {@link #SEARCH_DEPTH}.
     *
     * <p>This mirrors the parent-fallback {@code JawataApplication} already performs for its own
     * {@code workspace.json}, and for the same reason: the launcher may or may not have injected
     * a session subdirectory, so the depth is not known to the process that has to find the
     * file.</p>
     */
    public static Path locate(Path dataDir) {
        Path dir = dataDir;
        for (int up = 0; up <= SEARCH_DEPTH && dir != null; up++) {
            if (Files.isRegularFile(dir.resolve(FILE_NAME))) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    /**
     * Everyone this engine can ask: the residents studio lists beside the registry {@link #locate}d from a data dir, then the live
     * Eclipse engines whose notes are in a folder (Sprint 28k, contract §14.1). Neither source is required; a machine with neither has no
     * siblings.
     *
     * @param dataDir  the engine's data folder, or null
     * @param notesDir the folder of Eclipse engine notes, or null for none
     * @param selfName this engine's workspace name, or null when it has none
     * @return the addressable siblings, studio's first
     */
    public static List<Sibling> around(Path dataDir, Path notesDir, String selfName) {
        return around(dataDir, notesDir, selfName, ProcessHandle.current().pid(), EngineNotes.OS);
    }

    static List<Sibling> around(Path dataDir, Path notesDir, String selfName, long selfPid, EngineNotes.Liveness liveness) {
        List<Sibling> siblings = new ArrayList<>(others(locate(dataDir), selfName));
        siblings.addAll(engineNotes(notesDir, selfName, selfPid, liveness));
        return List.copyOf(siblings);
    }

    /**
     * The Eclipse engines that are running, from their notes. A stale, malformed or newer-major note is not a sibling; a note of this very
     * process, or of a workspace this engine is named after, is not an <i>other</i> one.
     *
     * @param notesDir the folder of notes, or null
     * @param selfName this engine's workspace name, or null when it has none
     * @param selfPid  this engine's process id
     * @param liveness decides whether the process behind a note still runs
     * @return the live engines, each with the address its note gives and no key
     */
    static List<Sibling> engineNotes(Path notesDir, String selfName, long selfPid, EngineNotes.Liveness liveness) {
        List<Sibling> live = new ArrayList<>();
        for (EngineNotes.Reading reading : EngineNotes.read(notesDir, liveness)) {
            if (reading.status() == EngineNotes.Status.LIVE) {
                boolean itself = reading.pid() == selfPid || (selfName != null && selfName.equals(reading.workspaceName()));
                if (!itself) {
                    reading.sibling().ifPresent(live::add);
                }
            } else {
                log.debug("Engine note {} is not a sibling ({}): {}", reading.fileName(), reading.status(), reading.detail());
            }
        }
        return List.copyOf(live);
    }

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
