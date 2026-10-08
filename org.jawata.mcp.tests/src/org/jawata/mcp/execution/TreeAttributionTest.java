package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;

import org.jawata.core.host.HostCommand;
import org.jawata.core.host.HostProcesses;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What a hired run changed, and when that cannot be said (Sprint 28g S16, C16).
 *
 * <p>C16's first clause ends with <i>"every changed file attributed to the run"</i>. The reason it
 * needs its own instrument is that a brain edits through its OWN tools: no gate reports those
 * writes, no tap sees them, and the brain's answer is prose — so the directory before and after is
 * the only witness there is.</p>
 *
 * <p>The PAIR at the centre is the same shape as the gate runner's: a run that changed nothing and
 * a run nobody could look at both have no files to name, and only the presence of the list tells
 * them apart. Collapsing them tells a reader the brain touched nothing on the strength of nobody
 * having checked, immediately before they decide whether to accept its proposal.</p>
 */
class TreeAttributionTest {

    private Path dir;
    private HostProcesses host;

    @BeforeEach
    void setUp() throws Exception {
        dir = Files.createTempDirectory("jawata-tree-attribution-");
        host = HostProcesses.system();
    }

    @AfterEach
    void tearDown() throws Exception {
        if (dir != null) {
            try (var walk = Files.walk(dir)) {
                walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                    try {
                        Files.deleteIfExists(p);
                    } catch (Exception ignored) {
                        // best effort; the temp dir is the OS's problem after this
                    }
                });
            }
        }
    }

    /** A git working tree, which is the one instrument that can see a brain's own writes. */
    private Path aRepository() throws Exception {
        Path repo = Files.createDirectory(dir.resolve("repo"));
        host.run(HostCommand.of(List.of("git", "init", "-q")).in(repo));
        // A committed file, so the "already dirty" and "deleted" cases below have something real
        // to act on rather than an untracked file git would report either way.
        Files.writeString(repo.resolve("tracked.txt"), "one\n");
        host.run(HostCommand.of(List.of("git", "add", "tracked.txt")).in(repo));
        host.run(HostCommand.of(List.of("git", "-c", "user.email=p@p", "-c", "user.name=probe",
            "commit", "-qm", "base")).in(repo));
        return repo;
    }

    @Test
    @DisplayName("THE PAIR: a clean tree reports an EMPTY list, and an uncensusable one reports NO list")
    void nothingChangedAndNobodyLookedAreDifferentAnswers() throws Exception {
        Path repo = aRepository();

        TreeAttribution.Attribution clean = TreeAttribution.before(host, repo).after();
        // A plain temp directory: no git, so no claim. This is what a hire into an arbitrary
        // working directory really gets, which is why the honest answer had to be designed.
        TreeAttribution.Attribution unknown = TreeAttribution.before(host, dir).after();

        assertAll(
            () -> assertTrue(clean.changed().isPresent(), "the census ran"),
            () -> assertTrue(clean.changed().get().isEmpty(), "and found nothing changed"),
            () -> assertEquals(0, clean.count().orElseThrow()),
            // ...and the other one has no list at all, where a zero would have read identically.
            () -> assertTrue(unknown.changed().isEmpty(),
                "a directory that is not a working tree must not answer 'nothing changed'"),
            () -> assertTrue(unknown.count().isEmpty()),
            () -> assertTrue(unknown.basis().contains("NOT ATTRIBUTABLE"), unknown.basis()),
            () -> assertTrue(unknown.basis().contains("git"),
                "and it names the instrument it lacks: " + unknown.basis()),
            // BOTH carry a basis, always. A list with no stated basis invites a reader to assume
            // an exactness this cannot have — see the bound below.
            () -> assertFalse(clean.basis().isBlank()));
    }

    @Test
    @DisplayName("a file written, a file deleted, and a file edited are each attributed")
    void itNamesWhatTheRunDidToTheTree() throws Exception {
        Path repo = aRepository();
        TreeAttribution census = TreeAttribution.before(host, repo);

        // The three things a brain does to a directory. The DELETION is the one a naive diff
        // misses: it is present in the before census and absent from the after, so a comparison
        // that only walks the new reading would never see it.
        Files.writeString(repo.resolve("written.txt"), "new\n");
        Files.writeString(repo.resolve("tracked.txt"), "one\ntwo\n");
        Files.createDirectory(repo.resolve("sub"));
        Files.writeString(repo.resolve("sub/nested.txt"), "deep\n");

        List<String> changed = census.after().changed().orElseThrow();

        assertAll(
            () -> assertTrue(changed.contains("written.txt"), changed.toString()),
            () -> assertTrue(changed.contains("tracked.txt"),
                "an edit to a tracked file is a change: " + changed),
            () -> assertTrue(changed.contains("sub/nested.txt"),
                "and it descends, because --untracked-files=all is asked for: " + changed));
    }

    @Test
    @DisplayName("a file that DISAPPEARS between the two readings is attributed too")
    void aPathGoneFromTheSecondReadingIsStillAChange() throws Exception {
        Path repo = aRepository();
        // Dirty BEFORE the census, so the first reading holds it and the second does not.
        Files.writeString(repo.resolve("transient.txt"), "here\n");
        TreeAttribution census = TreeAttribution.before(host, repo);

        Files.delete(repo.resolve("transient.txt"));

        List<String> changed = census.after().changed().orElseThrow();
        assertTrue(changed.contains("transient.txt"),
            "a file the run removed was changed by it as surely as one it wrote: " + changed);
    }

    @Test
    @DisplayName("a file whose STATUS changes while it stays present is attributed")
    void aStagedFileIsAChangeEvenThoughItWasAlreadyDirty() throws Exception {
        Path repo = aRepository();
        // Untracked before the census: git reports "??".
        Files.writeString(repo.resolve("staged.txt"), "new\n");
        TreeAttribution census = TreeAttribution.before(host, repo);

        // The run STAGES it. Now git reports "A " — the path is in BOTH readings and its status
        // differs, which is the one shape a presence-only comparison cannot see. It is also real
        // behaviour: a brain that writes and then stages its work.
        host.run(HostCommand.of(List.of("git", "add", "staged.txt")).in(repo));

        List<String> changed = census.after().changed().orElseThrow();

        assertTrue(changed.contains("staged.txt"),
            "a path present in both readings with a DIFFERENT status was changed by the run: "
                + changed);
    }

    @Test
    @DisplayName("THE BOUND, asserted: a file already modified and modified AGAIN is invisible")
    void anAlreadyDirtyFileEditedAgainCannotBeSeen() throws Exception {
        Path repo = aRepository();
        Files.writeString(repo.resolve("tracked.txt"), "one\nalready edited\n");
        TreeAttribution census = TreeAttribution.before(host, repo);

        // The run edits it a second time. Porcelain reports a status LETTER, not a content hash,
        // so both readings are " M" and the two cases cannot be told apart.
        Files.writeString(repo.resolve("tracked.txt"), "one\nedited again by the run\n");

        TreeAttribution.Attribution attribution = census.after();

        assertAll(
            // This is a LIMITATION pinned, not a behaviour wanted. It is asserted so the basis
            // string stays true: a reader who took the list for "everything the brain touched"
            // would be wrong in exactly this case, and the basis is what warns them.
            () -> assertFalse(attribution.changed().orElseThrow().contains("tracked.txt"),
                "the limitation is real, and the basis must keep saying so"),
            () -> assertTrue(attribution.basis().contains("ALREADY modified"),
                "the basis names the case it cannot see: " + attribution.basis()));
    }

    @Test
    @DisplayName("a directory that is not there, and a missing host, are absences rather than throws")
    void itNeverThrowsAtASetupItCannotPerform() {
        assertAll(
            () -> assertTrue(TreeAttribution.before(host, dir.resolve("no-such-dir")).after()
                .changed().isEmpty()),
            () -> assertTrue(TreeAttribution.before(host, dir.resolve("no-such-dir")).after()
                .basis().contains("not a directory")),
            // A hire must not fail because its attribution could not be set up: the run is the
            // point and the attribution is a report about it.
            () -> assertTrue(TreeAttribution.before(null, dir).after().changed().isEmpty()));
    }
}
