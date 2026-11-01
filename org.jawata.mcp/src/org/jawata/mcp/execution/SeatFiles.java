package org.jawata.mcp.execution;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * The one reader of a folder of seat files (Sprint 28h, S14, D21).
 *
 * <p>Two consumers need the seats a folder holds: the scheduler, which wants the ones that declare a schedule, and the prompt list, which
 * wants the ones a person has not switched off. Each used to need its own walk of the folder and its own reading of the frontmatter; this is
 * the walk, so a seat file is found, named and parsed in one place and a file that cannot be read is reported by name, never skipped
 * quietly.</p>
 */
final class SeatFiles {

    private SeatFiles() {
    }

    /**
     * One seat file read.
     *
     * @param name  the seat's {@code name:}, or the file's name without {@code .md} when it declares none
     * @param file  the file
     * @param front its frontmatter, as written
     * @param body  everything below the frontmatter: the seat's stance
     */
    record SeatFile(String name, Path file, Map<String, String> front, String body) {
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
                String text = Files.readString(file);
                Map<String, String> front = Frontmatter.frontmatter(text);
                String name = Frontmatter.emptyToNull(front.get("name"));
                if (name == null) {
                    name = file.getFileName().toString().replaceFirst("\\.md$", "");
                }
                seats.add(new SeatFile(name, file, front, Frontmatter.body(text)));
            } catch (IOException | RuntimeException e) {
                unreadable.add(file.getFileName() + ": " + e.getMessage());
            }
        }
        return new Listing(seats, unreadable);
    }
}
