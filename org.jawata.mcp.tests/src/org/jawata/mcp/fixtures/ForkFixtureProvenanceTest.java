package org.jawata.mcp.fixtures;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — the fork fixtures' provenance is a CLAIM, and this is the only
 * thing that reads it.
 *
 * <p>C3 and C7 both rest on the same clause: an operation must be demonstrated "on code
 * we did not author". Everything backing that clause lives in each fixture's
 * {@code PROVENANCE.md} — which upstream commit the files came from, and which module
 * they were taken from. Written in prose, checked by nobody, and re-verified by hand at
 * most once when the fixture was created.</p>
 *
 * <p>It broke twice in one batch. One fixture shipped with no {@code PROVENANCE.md} at
 * all, and that same fixture's directory name differs from its fork module's name
 * ({@code fork-rate-limiting} against {@code rate-limiting-pattern}) — so a reviewer
 * looking for the source by pattern-matching the fixture name found nothing and reported
 * every file as absent from upstream. The files were fine. The record of where they came
 * from was not there to read.</p>
 *
 * <h2>What this can and cannot check</h2>
 *
 * <p>It CANNOT verify byte-identity against the pin: that needs the fork checkout, which
 * is not present on every machine that runs this suite. What it can do is insist the
 * claim is present and specific enough that the manual check is reproducible — the commit
 * to compare against, and the module to compare with. A note missing either leaves the
 * next person guessing, which is exactly what happened.</p>
 *
 * <p>Two spellings of the module row are accepted because two conventions are already in
 * the tree and both are honest: an early fixture uses {@code | Module |}, later ones use
 * {@code | Path in the fork |}. Forcing one would be churn; requiring neither would be
 * the hole.</p>
 */
class ForkFixtureProvenanceTest {

    /** The upstream commit every fork fixture in the tree is copied from. */
    private static final String PIN = "22a34127d0b08449c24cf7e230c04a097deca2f3";

    /** How the module of origin is named. Either spelling satisfies the clause. */
    private static final List<String> MODULE_ROWS = List.of("Path in the fork", "| Module |");

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("every vendored fork fixture says where it came from, specifically enough to re-check")
    void everyForkFixtureCarriesItsProvenance() throws IOException {
        Path fixturesRoot = helper.getFixturePath("simple-maven").getParent();
        List<Path> forkFixtures = new ArrayList<>();
        try (Stream<Path> entries = Files.list(fixturesRoot)) {
            entries.filter(Files::isDirectory)
                .filter(p -> p.getFileName().toString().startsWith("fork-"))
                .sorted()
                .forEach(forkFixtures::add);
        }

        // PROOF OF LIFE. If the enumeration finds nothing — a moved fixtures root, a
        // renamed prefix — every assertion below is skipped and the test passes while
        // checking no fixture at all. That is the failure this whole class exists to
        // refuse, and it would be the easiest one to reintroduce here.
        assertTrue(forkFixtures.size() >= 4,
            "expected at least the four vendored fork fixtures under " + fixturesRoot
                + ", found " + forkFixtures.size() + ": " + forkFixtures
                + " — the enumeration is broken, so this test is checking nothing");

        List<String> problems = new ArrayList<>();
        for (Path fixture : forkFixtures) {
            String name = fixture.getFileName().toString();
            Path note = fixture.resolve("PROVENANCE.md");
            if (!Files.isRegularFile(note)) {
                problems.add(name + ": no PROVENANCE.md. The clause it backs — demonstrated"
                    + " on code we did not author — has nothing behind it.");
                continue;
            }
            String text = Files.readString(note, StandardCharsets.UTF_8);
            if (!text.contains(PIN)) {
                problems.add(name + ": PROVENANCE.md does not name the pinned commit "
                    + PIN + ", so there is nothing to re-check the files against.");
            }
            if (MODULE_ROWS.stream().noneMatch(text::contains)) {
                problems.add(name + ": PROVENANCE.md does not name the fork module it came"
                    + " from. The fixture directory name is NOT a reliable substitute —"
                    + " fork-rate-limiting comes from rate-limiting-pattern, and a review"
                    + " that guessed reported every file as missing upstream.");
            }
        }

        assertTrue(problems.isEmpty(),
            "fork fixture provenance is incomplete:\n  " + String.join("\n  ", problems));
    }
}
