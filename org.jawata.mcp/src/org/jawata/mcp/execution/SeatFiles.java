package org.jawata.mcp.execution;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * The one reader of seat files (Sprint 28h, S14, D21; Sprint 28k, Amendment 3, M10).
 *
 * <p>Two consumers need the seats a folder holds: the scheduler, which wants the ones that declare a schedule, and the prompt list, which
 * wants the ones a person has not switched off. Each used to need its own walk of the folder and its own reading of the frontmatter; this is
 * the walk, so a seat file is found, named and parsed in one place and a file that cannot be read is reported by name, never skipped
 * quietly. The seats sealed in the engine's own bundle are read here too, from inside the bundle and never extracted.</p>
 */
final class SeatFiles {

    /** Where the sealed seats sit in the engine bundle, as {@code org.jawata.mcp/resources/seats} puts them. */
    static final String SEALED_FOLDER = "seats";

    private SeatFiles() {
    }

    /**
     * One seat file read.
     *
     * @param name  the seat's {@code name:}, or the file's name without {@code .md} when it declares none
     * @param file  the file; for a sealed seat its place inside the bundle, {@code seats/<file>}, which names it and is never opened
     * @param front its frontmatter, as written
     * @param body  everything below the frontmatter: the seat's stance
     * @param text  the whole file as written, which a hire reads its seat from; empty for a seat a stored workflow adds and no file declares
     */
    record SeatFile(String name, Path file, Map<String, String> front, String body, String text) {
    }

    /**
     * What reading a folder found.
     *
     * @param seats      every {@code *.md} in the folder that could be read, sorted by file name
     * @param unreadable a line for each file or folder that could not be read
     */
    record Listing(List<SeatFile> seats, List<String> unreadable) {

        Listing {
            seats = List.copyOf(seats);
            unreadable = List.copyOf(unreadable);
        }
    }

    /** Read every {@code *.md} of the folder. A folder that cannot be listed is one unreadable line and no seats. */
    static Listing read(Path folder) {
        List<Path> candidates;
        try (Stream<Path> listing = Files.list(folder)) {
            candidates = listing.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        } catch (IOException e) {
            return new Listing(List.of(), List.of(folder + ": " + e.getMessage()));
        }
        List<SeatFile> seats = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (Path file : candidates) {
            try {
                seats.add(parsed(file, Files.readString(file)));
            } catch (IOException | RuntimeException e) {
                unreadable.add(file.getFileName() + ": " + e.getMessage());
            }
        }
        return new Listing(seats, unreadable);
    }

    /**
     * Read the seats sealed in the bundle that holds {@code owner}, through the framework, so a bundle installed as a jar or as a folder is
     * read the same way and nothing is extracted. Outside a framework there is no bundle to read, which is one unreadable line and no seats.
     *
     * @param owner a class of the bundle whose seats are read
     * @return the seats, sorted by file name
     */
    static Listing sealed(Class<?> owner) {
        Bundle bundle = FrameworkUtil.getBundle(owner);
        if (bundle == null) {
            return new Listing(List.of(), List.of("the engine runs outside an OSGi framework, so it has no sealed seats to read"));
        }
        Enumeration<URL> entries = bundle.findEntries(SEALED_FOLDER, "*.md", false);
        // a list that can be sorted either way: a bundle with no seats folder has none, which is an answer, not a failure
        List<URL> found = entries == null ? new ArrayList<>() : new ArrayList<>(Collections.list(entries));
        found.sort(Comparator.comparing(URL::getPath));
        List<SeatFile> seats = new ArrayList<>();
        List<String> unreadable = new ArrayList<>();
        for (URL entry : found) {
            String fileName = entry.getPath().substring(entry.getPath().lastIndexOf('/') + 1);
            try (InputStream in = entry.openStream()) {
                seats.add(parsed(Path.of(SEALED_FOLDER, fileName), new String(in.readAllBytes(), StandardCharsets.UTF_8)));
            } catch (IOException | RuntimeException e) {
                unreadable.add(SEALED_FOLDER + "/" + fileName + ": " + e.getMessage());
            }
        }
        return new Listing(seats, unreadable);
    }

    /** One seat file's text, named by its {@code name:} or else its file name. */
    private static SeatFile parsed(Path file, String text) {
        Map<String, String> front = Frontmatter.frontmatter(text);
        String name = Frontmatter.emptyToNull(front.get("name"));
        if (name == null) {
            name = file.getFileName().toString().replaceFirst("\\.md$", "");
        }
        return new SeatFile(name, file, front, Frontmatter.body(text), text);
    }
}
