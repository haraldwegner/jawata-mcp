package org.jawata.mcp.execution;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

/**
 * Sprint 28g Stage 24 (D45) — the rule labels the seats in a directory ask for.
 *
 * <p>A rule is bound to a seat or an activity by an exact label ({@code seat:pm}, {@code release}).
 * A seat asks for its own {@code seat:<name>} and for each activity its file declares. The labels
 * a rule may carry are therefore whatever the seat files say, read here from the files themselves
 * and from nowhere else, so that a label cannot be asked for by a list that drifted from the files.</p>
 */
public final class SeatLabels {

    private SeatLabels() {
    }

    /**
     * The labels asked for, and what could not be read.
     *
     * @param labels every label some seat in the directory asks for, lower-cased
     * @param seatsRead how many seat files were read
     * @param unreadable the seat files that could not be parsed, each with the reason; a label such a
     *     file would have asked for is MISSING from {@code labels}, so a rule bound to it will be
     *     listed as unasked
     */
    public record Asked(Set<String> labels, int seatsRead, List<String> unreadable) {
    }

    /**
     * Read every {@code *.md} seat file in {@code dir}.
     *
     * @param dir the directory holding the seat files
     * @return the labels they ask for
     * @throws IOException when the directory cannot be listed: "no seats" and "could not look"
     *     would otherwise list every rule as dead
     */
    public static Asked in(Path dir) throws IOException {
        Set<String> labels = new LinkedHashSet<>();
        List<String> unreadable = new ArrayList<>();
        int read = 0;
        List<Path> files;
        try (Stream<Path> listing = Files.list(dir)) {
            files = listing.filter(p -> p.getFileName().toString().endsWith(".md")).sorted().toList();
        }
        for (Path file : files) {
            try {
                SeatHire seat = SeatHire.read(Files.readString(file, StandardCharsets.UTF_8));
                labels.add("seat:" + seat.seat().strip().toLowerCase(Locale.ROOT));
                for (String activity : seat.activities()) {
                    labels.add(activity.strip().toLowerCase(Locale.ROOT));
                }
                read++;
            } catch (IOException | RuntimeException e) {
                unreadable.add(file.getFileName() + ": " + e.getMessage());
            }
        }
        return new Asked(labels, read, unreadable);
    }
}
