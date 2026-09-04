package org.jawata.mcp.tools.refactoring;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WHICH STAGE 6 ROWS HAVE BEEN DEMONSTRATED ON CODE WE DID NOT AUTHOR, and which have not.
 *
 * <p>The per-row contract asks for a fork-corpus demonstration per row. At C6 <b>one of the
 * twelve has one</b>. A C6 audit found the other eleven absent AND unexplained, and was
 * right that those two states read identically from outside — Stage 3 wrote its reasons
 * down, Stage 6 had not.</p>
 *
 * <p>This file is the written state, and it is a TEST rather than a comment so it cannot
 * quietly stop being true. It fails when the count changes in either direction: build a
 * slice and this goes red until the list is updated, delete one and the same. A note in a
 * markdown file would have done neither.</p>
 *
 * <h2>This is an OPEN clause, not a waived one</h2>
 *
 * <p>Stage 3 declared a deviation because three of its rows had no candidate ANYWHERE in
 * the corpus — measured exhaustively over 1884 files, and recorded in
 * {@link ForkShapeCensusTest}. Stage 6's eleven are different: they are not blocked, they
 * are unbuilt. 786 of the fork's 1354 main-source files (58%) are usable; the remaining 568
 * import Lombok, whose generated members JDT cannot see, so a rewrite there cannot be shown
 * to compile and the gate correctly refuses it.</p>
 *
 * <h2>What the one demonstration bought, which is the argument for the other eleven</h2>
 *
 * <p>Row 36's slice broke that row three times before it passed, and every one was a shape
 * the hand-written fixture could not have contained: a receiver written two ways in one
 * class, a class forwarding to two fields refused outright, and — the sharpest — the
 * forwarder's own name emitted where the DELEGATE's name belonged, which the fixture hid
 * because both names were the same word. That row had already passed its unit tests, its
 * parity golden and two reviews.</p>
 */
class Stage6ForkDemonstrationStatusTest {

    private static final String TESTS =
        "org.jawata.mcp.tests/src/org/jawata/mcp/tools/refactoring";

    /** Row → the fork slice that demonstrates it, or null while it has none. */
    private static final Map<String, String> ROWS = new java.util.LinkedHashMap<>(Map.ofEntries(
        Map.entry("36 Remove Middle Man", "RemoveMiddleManForkSliceTest.java"),
        Map.entry("23 Move Field", ""),
        Map.entry("17 Inline Class", ""),
        Map.entry("38 Remove Subclass", ""),
        Map.entry("24 Move Function (static)", "MapReduceForkSliceTest.java"),
        Map.entry("25 Move Statements into Function", ""),
        Map.entry("26 Move Statements to Callers", ""),
        Map.entry("49 Replace Inline Code", "MessagingForkSliceTest.java"),
        Map.entry("5 Combine Functions into Class", ""),
        Map.entry("48 Replace Function with Command", "MapReduceForkSliceTest.java"),
        Map.entry("64 Split Phase", "MapReduceForkSliceTest.java"),
        Map.entry("58 Replace Temp with Query", "MapReduceForkSliceTest.java")));

    @Test
    @DisplayName("this says exactly which of Stage 6's twelve rows are demonstrated on the fork")
    void theForkDemonstrationCountIsWhatIsWrittenDown() {
        Path root = Path.of(System.getProperty("user.dir")).resolve(TESTS);
        // Walk up: the runner's working directory is the dist, not the repository.
        for (Path at = Path.of("").toAbsolutePath(); at != null && !Files.isDirectory(root);
                at = at.getParent()) {
            root = at.resolve(TESTS);
        }
        assertTrue(Files.isDirectory(root),
            "PROOF OF LIFE: without the test sources this checks nothing. Looked for "
                + TESTS);

        List<String> wrong = new ArrayList<>();
        long claimed = 0;
        for (Map.Entry<String, String> row : ROWS.entrySet()) {
            if (row.getValue().isEmpty()) {
                continue;
            }
            claimed++;
            if (!Files.isRegularFile(root.resolve(row.getValue()))) {
                wrong.add(row.getKey() + " claims " + row.getValue() + ", which is not there");
            }
        }
        assertTrue(wrong.isEmpty(), String.join("\n  ", wrong));

        assertEquals(6, claimed,
            "SIX rows of twelve are demonstrated on code we did not author. If that number"
                + " moved, this list is what says so — update it in the same commit as the"
                + " slice, or the count and the reality drift apart, which is the state a"
                + " C6 audit found and named.");
        assertEquals(12, ROWS.size(),
            "and all twelve rows are listed, so a row cannot be quietly dropped from the"
                + " question: " + ROWS.keySet());
    }
}
