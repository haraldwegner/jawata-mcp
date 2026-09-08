package org.jawata.mcp.knowledge;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#57 — <b>prose is not Java, and the stamp that says it is decides whether the
 * resolver may touch it.</b>
 *
 * <p>A reseed of a directory of markdown produced 89 rows all stamped {@code java}, including
 * ones about USB-C ports and a broker's order book — because the store's insert defaults a
 * null language to {@code "java"} and the ingest passed null. Not cosmetic: {@code language}
 * GATES maintenance. The contract is that non-Java anchors are opaque to the JDT resolver and
 * are never staled, so those rows were exposed to resolution designed not to apply.</p>
 *
 * <p><b>The issue's preferred cure does not survive contact with the column</b>, and this test
 * pins why. It proposes that {@code null} mean "unclassified, and unclassified is not Java";
 * {@link StoredEntry#isJavaResolvable()} documents the opposite on purpose — <i>"Null/blank =
 * Java-era rows"</i> — because rows predating the column have no language and ARE Java.
 * Redefining null would silently exempt every legacy row from maintenance: a bigger and
 * quieter change than the one being fixed. So the caller declares what it ingests instead,
 * and the second case below is what keeps the old meaning intact.</p>
 */
class ProseIsNotStampedJavaTest {

    private H2ExperienceStore store;

    @BeforeEach
    void setUp() {
        store = H2ExperienceStore.open(null);
    }

    @AfterEach
    void tearDown() {
        store.close();
    }

    private void write(Path dir, String file, String frontmatter, String body) throws IOException {
        Files.writeString(dir.resolve(file), "---\n" + frontmatter + "\n---\n" + body);
    }

    private void load(Path dir) {
        new ExperienceMaintenance(store, fqn -> null).load(dir);
    }

    private StoredEntry theEntryNamed(String description) {
        return store.all().stream()
            .filter(e -> description.equals(e.summary()))
            .findFirst()
            .orElseThrow(() -> new AssertionError("no entry summarised '" + description
                + "'; got: " + store.all().stream().map(StoredEntry::summary).toList()));
    }

    @Test
    @DisplayName("mcp#57: prose with no language and no symbol is stamped markdown, not java")
    void proseIsStampedForWhatItIs(@TempDir Path dir) throws IOException {
        write(dir, "ports.md",
            "name: usb-c-ports-wedge\ndescription: both left USB-C ports die together"
                + "\nmetadata:\n  type: domain_fact",
            "The shared controller fails to resume from runtime suspend.\n");

        load(dir);
        StoredEntry e = theEntryNamed("both left USB-C ports die together");

        assertAll(
            () -> assertEquals("markdown", e.language(),
                "a story about hardware is not Java, and the stamp is what exposes it to a "
                    + "resolver that cannot see it; got: " + e.language()),
            () -> assertTrue(!e.isJavaResolvable(),
                "and the point of the stamp: this row must be OPAQUE to JDT maintenance, "
                    + "which is the store's own contract for non-Java anchors"));
    }

    @Test
    @DisplayName("mcp#57's control: a declared symbol keeps its Java anchor — the legacy meaning of null is untouched")
    void aDeclaredSymbolStaysJavaResolvable(@TempDir Path dir) throws IOException {
        write(dir, "anchored.md",
            "name: cure-table-boot\ndescription: the cure table refuses an ambiguous step at boot"
                + "\nsymbol: org.jawata.mcp.tools.smell.CureCatalog\nmetadata:\n  type: domain_fact",
            "It validates against the operation registry.\n");

        load(dir);
        StoredEntry e = theEntryNamed("the cure table refuses an ambiguous step at boot");

        assertAll(
            // A declared symbol IS a Java anchor. Stamping this "markdown" would stop the
            // staleness check that exists for exactly these rows — the fix quietly removing
            // the maintenance it was meant to target correctly.
            () -> assertTrue(e.isJavaResolvable(),
                "a row with a declared symbol must stay resolvable, or the fix has switched "
                    + "off the very maintenance it exists to aim correctly; got language: "
                    + e.language()),
            // CORRECTED BY THE RUN, and the correction is the point. I first asserted the
            // stored value is NULL. It is "java": the ingest passes null for this case and
            // the STORE's insert applies its own default — which is right here, because a
            // declared symbol is a Java anchor. The assertion above was over-specified: it
            // pinned an implementation detail (which layer supplies the value) instead of
            // the property that decides anything (whether the resolver may touch the row).
            // The column default is left exactly as it was, which is what "this change does
            // not redefine null" means.
            () -> assertEquals("java", e.language(),
                "the ingest passes null here and the store's own default supplies java — "
                    + "unchanged behaviour, and correct for a row that has a Java anchor; "
                    + "got: " + e.language()));
    }

    @Test
    @DisplayName("mcp#57: an author's declared language always wins")
    void aDeclaredLanguageIsHonoured(@TempDir Path dir) throws IOException {
        write(dir, "rusty.md",
            "name: a-rust-lesson\ndescription: a lesson anchored in rust"
                + "\nlanguage: rust\nmetadata:\n  type: domain_fact",
            "Ownership moves on assignment.\n");

        load(dir);
        StoredEntry e = theEntryNamed("a lesson anchored in rust");

        assertAll(
            () -> assertEquals("rust", e.language(),
                "a declared language is never overridden; got: " + e.language()),
            () -> assertTrue(!e.isJavaResolvable(),
                "and it is opaque to JDT, which is what the language field is for"));
    }
}
