package org.jawata.mcp.catalogue;

import org.jawata.catalogue.CatalogueRow;
import org.jawata.catalogue.Refactorings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The catalogue table itself: its size, its keys, and that it names no language.
 *
 * <p>Sprint 28g D39's measure, and the architecture's P2 step 3 verify clause. Every assertion
 * here is about the TABLE, never about what an engine can do with it — that is each engine's
 * own coverage declaration and is checked on the engine's side.</p>
 *
 * <p>The 66-row claim is scoped to the {@code fowler:} block rather than to the table's size,
 * which is what lets a {@code cpp:} row join without breaking a count that was never about
 * it.</p>
 *
 * <h2>Why this lives in {@code org.jawata.mcp.catalogue} and not in {@code org.jawata.catalogue}</h2>
 *
 * <p>It was written in the latter and that was a defect. This bundle is an OSGi FRAGMENT of
 * {@code org.jawata.mcp}, and the host now imports {@code org.jawata.catalogue} from the
 * catalogue bundle — so a fragment class declared in that same package is a SPLIT PACKAGE: the
 * package resolves to the exporting bundle and the fragment's own classes in it may not be
 * found. The symptom was nondeterministic, which is what makes it worth a paragraph: the two
 * classes loaded and passed on one run and came back
 * {@code ClassNotFoundException … cannot be found by org.jawata.mcp_4.3.3} on the next, with
 * only a string literal changed between them. A test that loads in some runs and not others is
 * a bug, never a flake, and the bug was the package.</p>
 */
class CatalogueIntegrityTest {

    @Test
    @DisplayName("Fowler's block is 66 rows, numbered 1 to 66 with none missing or doubled")
    void theFowlerBlockIsComplete() {
        List<CatalogueRow> fowler = Refactorings.withOrigin(Refactorings.FOWLER);
        Set<String> ids = new LinkedHashSet<>();
        for (CatalogueRow r : fowler) {
            assertTrue(ids.add(r.id()), "two rows share the key " + r.key());
        }
        List<String> missing = new ArrayList<>();
        for (int n = 1; n <= 66; n++) {
            if (!ids.contains(String.valueOf(n))) {
                missing.add("fowler:" + n);
            }
        }
        assertAll(
                () -> assertEquals(66, fowler.size(), "the published catalogue is 66 refactorings"),
                () -> assertEquals(List.of(), missing, "a row of the published catalogue is absent"));
    }

    @Test
    @DisplayName("every key is namespaced by its origin, so a second language needs no renumbering")
    void everyKeyCarriesItsOrigin() {
        for (CatalogueRow r : Refactorings.all()) {
            assertTrue(r.key().contains(":"), "an un-namespaced key: " + r.key());
            assertFalse(r.origin().isBlank(), "a key with no origin: " + r.key());
            assertFalse(r.id().isBlank(), "a key with no id: " + r.key());
        }
        // The constructor refuses the bare form outright, so the property cannot be lost by
        // someone adding a row the easy way.
        assertThrows(IllegalArgumentException.class, () -> new CatalogueRow("23", "Move Field"));
        assertThrows(IllegalArgumentException.class, () -> new CatalogueRow(":23", "Move Field"));
    }

    @Test
    @DisplayName("no row names a language — the table is shared, not Java's")
    void noRowNamesALanguage() {
        // The point of promoting the table: it must read the same to a C++ or Rust engine. A
        // row whose NAME carried a language would be a row only one engine could mean.
        List<String> languages = List.of("java", "kotlin", "c++", "cpp", "rust", "python", "jdt");
        List<String> offenders = new ArrayList<>();
        for (CatalogueRow r : Refactorings.all()) {
            String lower = r.name().toLowerCase();
            for (String lang : languages) {
                if (lower.contains(lang)) {
                    offenders.add(r.key() + " = " + r.name());
                }
            }
        }
        assertEquals(List.of(), offenders, "a catalogue row names a language");
    }

    @Test
    @DisplayName("the row carries no operation — what to do about it is an engine's claim, not the table's")
    void theRowCarriesNoOperation() {
        // A row is (key, name). If an operation ever reappears on it, the table has become one
        // engine's again, which is the state D39 exists to leave.
        assertEquals(
                2,
                CatalogueRow.class.getRecordComponents().length,
                "a catalogue row is its key and its name: "
                        + List.of(CatalogueRow.class.getRecordComponents()));
    }
}
