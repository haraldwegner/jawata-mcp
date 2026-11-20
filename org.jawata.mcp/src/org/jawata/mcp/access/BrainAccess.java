package org.jawata.mcp.access;

import java.nio.file.Path;
import java.util.List;
import java.util.function.Function;

/**
 * The folders the engine's access check allows, for the brains the engine starts (28h-C27-31, his word 2026-11-20: "the workspace of VSCode
 * has to be included. Otherwise the jawata agents have no access"). A hired brain runs in a client of its own whose guard keeps its own rule;
 * the engine tells each brain it starts these folders, so that guard allows what the engine allows. The engine publishes the same reading its
 * {@link AccessCheck} uses, so the two cannot drift apart; a host with no access check publishes nothing, and its brains are told no folder.
 */
public final class BrainAccess {

    /** The engine's reading of the workspace a session may work in; null when no access check is installed. */
    private static volatile Function<String, List<Path>> published;

    /** The folder of the editor windows' lists, which a brain's guard reads live; null when there is none. */
    private static volatile Path editorLists;

    private BrainAccess() {
    }

    /**
     * Publishes the reading the engine's access check uses and the folder of the editor windows' lists; nulls withdraw them, which the
     * engine does when it stops, so nothing answers for a workspace that is gone.
     */
    public static void publish(Function<String, List<Path>> workspaceOf, Path editorListsFolder) {
        published = workspaceOf;
        editorLists = editorListsFolder;
    }

    /** The folder of the editor windows' lists, for a brain's guard to read what the windows hold now (audit 2026-11-20, bug E). */
    public static java.util.Optional<Path> editorLists() {
        return java.util.Optional.ofNullable(editorLists);
    }

    /** The folders a brain started for {@code session} may work in; none when nothing is published or the reading fails. */
    public static List<Path> folders(String session) {
        Function<String, List<Path>> reading = published;
        if (reading == null) {
            return List.of();
        }
        try {
            return reading.apply(session);
        } catch (RuntimeException unreadable) {
            return List.of();
        }
    }
}
