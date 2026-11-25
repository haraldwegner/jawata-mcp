package org.jawata.mcp.access;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

/**
 * One place under one name, so that two spellings of the same place compare equal (Sprint 28h, S13, D20, ACL2; ported from the hook's
 * {@code normalise} in {@code local.rs}). A guard that compares what a path SAYS lets a link, a {@code ..} or a differently-cased name
 * walk past a rule written for the real place.
 *
 * <p>The longest part of the path that exists is resolved through the filesystem (links, junctions and short names are filesystem
 * facts no string rule reaches) and the part that does not exist yet is put back on, because a guard is asked about files that have
 * not been created. Case is folded only where this filesystem itself ignores it, found by asking it, never from the name of the
 * operating system.</p>
 */
public final class PlaceNames {

    private static volatile Boolean folds;

    private PlaceNames() {
    }

    static Path normalise(Path place) {
        Path out = realOf(place);
        return foldsCase() ? Path.of(out.toString().toLowerCase(Locale.ROOT)) : out;
    }

    /**
     * Where a path really leads, the one answer for judging a place and for writing there (28h z70), in the file system's own letter case.
     *
     * <p>Not normalised first: taking "lnk/.." out by its text names a place beside the link, where the system opens one beside the link's
     * target. The longest part that exists is resolved through the file system, which follows a link before a "..". Taking a ".." out of the rest
     * can uncover a link further on, so a path that changes by it is resolved again.</p>
     */
    public static Path realOf(Path place) {
        Path absolute = place.toAbsolutePath();
        Path existing = absolute;
        Deque<Path> tail = new ArrayDeque<>();
        Path resolved = null;
        while (existing != null) {
            try {
                resolved = existing.toRealPath();
                break;
            } catch (IOException e) {
                Path name = existing.getFileName();
                if (name == null) {
                    break;
                }
                tail.push(name);
                existing = existing.getParent();
            }
        }
        Path out = resolved == null ? absolute : resolved;
        if (resolved != null) {
            for (Path name : tail) {
                out = out.resolve(name);
            }
        }
        Path plain = out.normalize();
        return plain.equals(out) ? out : realOf(plain);
    }

    /**
     * Whether this filesystem ignores case: the temp directory is asked whether it answers to a differently-cased spelling of its own
     * name. A directory whose name has no letters to flip, or that cannot be asked, reads as NOT folding, the reading that refuses more.
     */
    private static boolean foldsCase() {
        Boolean known = folds;
        if (known != null) {
            return known;
        }
        boolean answer = false;
        String dir = System.getProperty("java.io.tmpdir", "");
        if (!dir.isEmpty()) {
            String flipped = dir.chars().anyMatch(Character::isLowerCase) ? dir.toUpperCase(Locale.ROOT) : dir.toLowerCase(Locale.ROOT);
            if (!flipped.equals(dir)) {
                answer = Files.exists(Path.of(dir)) && Files.exists(Path.of(flipped));
            }
        }
        folds = answer;
        return answer;
    }
}
