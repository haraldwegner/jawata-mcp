package org.jawata.mcp.catalogue;

import org.jawata.catalogue.CatalogueRow;
import org.jawata.catalogue.Refactorings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE TRANSCRIPTION CONTROL, and it is deliberately temporary.
 *
 * <p>{@code Refactorings} was written by copying 66 names out of
 * {@code PerformedRefactoringCountTest}. Copying 66 strings by hand is exactly the operation
 * that produces one wrong character in a name nobody re-reads, and a renamed refactoring would
 * then ship silently — every other test here asserts counts and keys, which a typo passes
 * straight through.</p>
 *
 * <p>So this reads the OLD table's source text and compares the names, in order, against the
 * new one. It is the only test here that reads a file rather than calling code, and that is on
 * purpose: the old table is about to be deleted, and until it is, its text is the authority the
 * new class was transcribed from.</p>
 *
 * <p><b>DELETE THIS CLASS in the step that deletes {@code PerformedRefactoringCountTest}'s
 * table.</b> It is a scaffold with a stated expiry, not a standing invariant — once the old
 * table is gone there is nothing to compare against and the file-reading would become a test of
 * its own fixture. {@link CatalogueIntegrityTest} is the standing guard.</p>
 */
class CatalogueMatchesTheTableItReplacedTest {

    /** {@code r.add(new Row(23, "Move Field", "move kind=field"));} — number and name. */
    private static final Pattern OLD_ROW = Pattern.compile("new Row\\((\\d+),\\s*\"([^\"]+)\"");

    private static final String OLD_TABLE =
            "org.jawata.mcp.tests/src/org/jawata/mcp/tools/PerformedRefactoringCountTest.java";

    @Test
    @DisplayName("all 66 names match the table this replaced, in order, character for character")
    void theNamesMatchTheOldTable() throws IOException {
        Path old = oldTableSource();
        assertTrue(
                old != null && Files.exists(old),
                "the control's subject is gone — if PerformedRefactoringCountTest's table has been "
                        + "deleted, DELETE THIS CLASS TOO rather than leaving a test that cannot "
                        + "fail. Looked for " + OLD_TABLE + " above " + Path.of("").toAbsolutePath());

        List<String> theirs = new ArrayList<>();
        Matcher m = OLD_ROW.matcher(Files.readString(old));
        while (m.find()) {
            theirs.add(m.group(1) + " " + m.group(2));
        }
        // PROOF OF LIFE: without it, a pattern that matched nothing would make the comparison
        // below a comparison of two empty lists, and this control would certify anything.
        assertEquals(66, theirs.size(), "the old table did not yield 66 rows — the reader is wrong, not the data");

        List<String> ours = new ArrayList<>();
        for (CatalogueRow r : Refactorings.withOrigin(Refactorings.FOWLER)) {
            ours.add(r.id() + " " + r.name());
        }
        assertEquals(theirs, ours, "a name was mistyped or reordered in the move to production");
    }

    /**
     * The old table's source file, found by walking up from wherever the runner started.
     *
     * <p>The suite runs from the dist, whose working directory is not reliably the repository
     * root, so the path is searched for rather than assumed — and a miss returns {@code null}
     * so the assertion above can say what it looked for instead of failing on a bare
     * {@code NoSuchFileException}.</p>
     */
    private static Path oldTableSource() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            Path candidate = p.resolve(OLD_TABLE);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        return null;
    }
}
