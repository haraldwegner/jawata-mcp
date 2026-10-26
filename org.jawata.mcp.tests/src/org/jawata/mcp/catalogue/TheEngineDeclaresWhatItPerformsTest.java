package org.jawata.mcp.catalogue;

import org.jawata.catalogue.CatalogueRow;
import org.jawata.catalogue.Refactorings;
import org.jawata.mcp.java.JavaEngineCoverage;
import org.jawata.mcp.java.JavaEngineCoverage.Declaration;
import org.jawata.mcp.java.JavaEngineCoverage.Verdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C8's clause: <i>the Java engine's declaration RECOMPUTES its own 62 of 66 rather than being
 * told it</i>.
 *
 * <h2>What moved, and why a test could not have satisfied this</h2>
 *
 * <p>{@code PerformedRefactoringCountTest} already joined its third field against the shipped
 * doors, and that join was correct. It was also inside a test, which means the PRODUCT could not
 * answer "what do I perform?" — only a test could, and only about itself. A coverage declaration
 * that no production code can read is not a declaration; it is an assertion about one.</p>
 *
 * <p>So the mapping is now {@link JavaEngineCoverage} and the number comes out of
 * {@code performs(shippedOperations())}. This class asserts what that computes. The distinction
 * is not academic: a second language's provider will make the same declaration about the same
 * keys, and there is nowhere in a Java test for it to put it.</p>
 *
 * <h2>Which assertion here is the discriminator</h2>
 *
 * <p>The JOIN is — {@code claimsNotShipped} names the refactoring that silently stopped being
 * performed. The 62 is a SIZE GUARD: with an empty unshipped list it is the catalogue's own 66
 * less its four declines, so it fails when the TABLE loses a row rather than when the PRODUCT
 * loses an operation. Both are worth failing on and they are different claims, which is why they
 * run under one {@code assertAll} — in sequence the join threw first and the count was never
 * observed below 62, a vacuity a C9 auditor found in the predecessor of this very test.</p>
 */
class TheEngineDeclaresWhatItPerformsTest {

    /** {@code r.add(new Row(23, "Move Field", "move kind=field"));} — number and operation. */
    private static final Pattern OLD_ROW =
            Pattern.compile("new Row\\((\\d+),\\s*\"[^\"]+\",\\s*(null|\"[^\"]+\")\\)");

    private static final String OLD_TABLE =
            "org.jawata.mcp.tests/src/org/jawata/mcp/tools/PerformedRefactoringCountTest.java";

    @Test
    @DisplayName("every catalogue row has a declaration — no row is left in the third state")
    void everyCatalogueRowHasADeclaration() {
        Set<String> rows = new LinkedHashSet<>();
        for (CatalogueRow r : Refactorings.all()) {
            rows.add(r.key());
        }
        Set<String> declared = new LinkedHashSet<>();
        List<String> twice = new ArrayList<>();
        for (Declaration d : JavaEngineCoverage.declarations()) {
            if (!declared.add(d.key())) {
                twice.add(d.key());
            }
        }
        assertAll(
                // EQUALITY, not containment. Containment one way would accept a row nobody
                // declared; the other way would accept a declaration about a row that does not
                // exist. The third state — neither performed nor declined — is exactly what a
                // missing declaration is, and from outside it is indistinguishable from an
                // oversight.
                () -> assertEquals(rows, declared,
                        "the engine must say something about every row of the lane, and nothing"
                                + " about a row the lane does not hold"),
                () -> assertEquals(List.of(), twice, "a row is declared twice"));
    }

    @Test
    @DisplayName("62 of 66, RECOMPUTED by the engine from what its doors actually publish")
    void theSixtyTwoIsRecomputedByTheEngine() {
        Set<String> shipped = JavaEngineCoverage.shippedOperations();

        // PROOF OF LIFE. With an empty or tiny operation set every declaration reads as not
        // performed, the count is zero, and the failure would look like a missing row rather
        // than a broken join. The floor is deliberately well under the real surface.
        assertTrue(shipped.size() >= 40,
                "the engine must publish a real operation surface here, or this whole test is"
                        + " measuring a construction failure: " + shipped);

        List<String> notShipped = JavaEngineCoverage.claimsNotShipped(shipped);
        int counted = JavaEngineCoverage.performs(shipped);

        assertAll(
                () -> assertEquals(List.of(), notShipped,
                        "a declaration claims an operation the engine does not publish, so a kind"
                                + " renamed or dropped shows up here naming the refactoring it"
                                + " silently stopped performing"),
                () -> assertEquals(62, counted,
                        "the sprint's headline figure, computed rather than asserted: this engine"
                                + " performs 62 of Fowler's 66. Counted " + counted + " of "
                                + Refactorings.all().size() + " rows over " + shipped.size()
                                + " shipped operations"));
    }

    @Test
    @DisplayName("a declined row names no operation and carries a reason a reader can weigh")
    void aDeclinedRowCarriesItsReason() {
        List<Declaration> declined = new ArrayList<>();
        for (Declaration d : JavaEngineCoverage.declarations()) {
            if (d.verdict() == Verdict.NOT_APPLICABLE) {
                declined.add(d);
            }
        }
        assertEquals(4, declined.size(),
                "the spec declines exactly four with his signature — Fowler 3, 6, 20 and 66. This"
                        + " number may move, but it moves in the same change that adds or removes"
                        + " a declination");
        for (Declaration d : declined) {
            assertNull(d.operation(), d.key() + " is declined and yet names an operation");
            // A one-word note would satisfy "a declination exists" while telling a reader
            // nothing, which is the state NOT_APPLICABLE-is-not-NONE replaces.
            assertTrue(d.note() != null && d.note().length() > 60,
                    d.key() + " must carry a reason, not a placeholder: " + d.note());
        }
    }

    @Test
    @DisplayName("the record refuses a declaration that says nothing — all three shapes")
    void theRecordRefusesAnEmptyClaim() {
        assertAll(
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Declaration("fowler:1", Verdict.PERFORMS, null, null),
                        "a PERFORMS with no operation is a claim with no content"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Declaration("fowler:3", Verdict.NOT_APPLICABLE, "extract kind=method", "x"),
                        "a decline that names an operation contradicts itself"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Declaration("fowler:3", Verdict.NOT_APPLICABLE, null, "  "),
                        "a blank reason is the silence this verdict exists to replace"),
                () -> assertThrows(IllegalArgumentException.class,
                        () -> new Declaration("", Verdict.PERFORMS, "extract kind=method", null),
                        "a declaration must say which row it is about"));
    }

    /**
     * THE TRANSCRIPTION CONTROL, and it is deliberately temporary.
     *
     * <p>The 66 operations were copied out of {@code PerformedRefactoringCountTest}. Copying 66
     * strings by hand is exactly the operation that produces one wrong kind in a row nobody
     * re-reads — and a wrong kind would be caught by the join only if it named something
     * unpublished. A kind that exists but belongs to a DIFFERENT refactoring would pass every
     * other assertion here.</p>
     *
     * <p><b>DELETE THIS TEST when that table goes.</b> It is a scaffold with a stated expiry.</p>
     */
    @Test
    @DisplayName("all 66 operations match the table this replaced, row for row")
    void theOperationsMatchTheTableItReplaced() throws IOException {
        // THROUGH THE ONE OWNER. This held a byte-identical private copy of the walk until a C8
        // audit found it — one file from `RepoFiles`, in the same package, which is package-
        // private and was already visible here. `RepoFiles`' own javadoc had congratulated
        // itself on catching "a fourth copy one move away"; this WAS that copy, and the
        // consolidation it described was of three.
        Path old = RepoFiles.sourceOf(OLD_TABLE);
        assertTrue(old != null && Files.exists(old),
                "the control's subject is gone — if PerformedRefactoringCountTest's table has"
                        + " been deleted, DELETE THIS TEST TOO rather than leaving one that"
                        + " cannot fail. Looked for " + OLD_TABLE + " above "
                        + Path.of("").toAbsolutePath());

        Map<String, String> theirs = new LinkedHashMap<>();
        Matcher m = OLD_ROW.matcher(Files.readString(old));
        while (m.find()) {
            String op = m.group(2);
            theirs.put("fowler:" + m.group(1), "null".equals(op) ? null : op.substring(1, op.length() - 1));
        }
        // PROOF OF LIFE: a pattern that matched nothing would make the comparison below a
        // comparison of two empty maps, and this control would certify anything.
        assertEquals(66, theirs.size(),
                "the old table did not yield 66 rows — the reader is wrong, not the data");

        Map<String, String> ours = new LinkedHashMap<>();
        for (Declaration d : JavaEngineCoverage.declarations()) {
            ours.put(d.key(), d.operation());
        }
        assertEquals(theirs, ours,
                "an operation was mistyped or attached to the wrong row in the move to"
                        + " production — including a kind that exists but performs a DIFFERENT"
                        + " refactoring, which no other assertion here would catch");
    }

}
