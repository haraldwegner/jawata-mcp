package org.jawata.core.project;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * mcp#39 — <b>a settings file that cannot be trusted must not be silently believed.</b>
 *
 * <p>{@code readEclipseCompliance} took the FIRST matching line. Found live on a real project
 * whose {@code .settings/org.eclipse.jdt.core.prefs} carried committed merge-conflict markers,
 * that meant it read the {@code HEAD} side of a conflict — applying a sub-14 level to code the
 * build compiles at 21, and producing 48 language-level errors with nothing anywhere saying
 * the file was garbage.</p>
 *
 * <p><b>The severity comes from the level being VALID.</b> {@code 12} passes every check this
 * reader has: it matches the accepted-level pattern, it is a real Java version, and JDT sets to
 * it without complaint. So no downstream gate can catch it — the answer is well-formed and
 * wrong, which is this repository's deepest defect class and the one the reader's own
 * {@code IOException} branch already argues about in its comment: a file that exists and cannot
 * be read is not the same fact as a project that declares nothing.</p>
 *
 * <p><b>Falling back is not a guess.</b> The reader sits at the head of a chain — settings, then
 * {@code pom.xml}, then Gradle, then the manifest's BREE — so declining to answer hands the
 * question to the next source that CAN answer it. That is why these tests assert the pom's
 * level rather than an empty Optional: an empty result would prove only that the reader gave
 * up, and the thing worth pinning is that the right answer arrives anyway.</p>
 *
 * <p>Eclipse itself tolerated the same file silently for months, which is exactly why an honest
 * reader must not.</p>
 */
class ComplianceReaderDegradedInputTest {

    /** Both sides declare a VALID level, which is what makes the wrong one undetectable. */
    private static final String CONFLICTED = """
        eclipse.preferences.version=1
        <<<<<<< HEAD
        org.eclipse.jdt.core.compiler.compliance=12
        org.eclipse.jdt.core.compiler.source=12
        =======
        org.eclipse.jdt.core.compiler.compliance=21
        org.eclipse.jdt.core.compiler.source=21
        >>>>>>> origin/master
        """;

    /** The same disagreement WITHOUT markers — a hand-merged file that kept both lines. */
    private static final String DISAGREEING = """
        eclipse.preferences.version=1
        org.eclipse.jdt.core.compiler.compliance=12
        org.eclipse.jdt.core.compiler.compliance=21
        """;

    /** A repeated key that agrees with itself is untidy, not untrustworthy. */
    private static final String AGREEING = """
        eclipse.preferences.version=1
        org.eclipse.jdt.core.compiler.compliance=21
        org.eclipse.jdt.core.compiler.compliance=21
        """;

    private static final String CLEAN = """
        eclipse.preferences.version=1
        org.eclipse.jdt.core.compiler.compliance=21
        """;

    /** Declares 17, so a fall-through is visible as a DIFFERENT level rather than as absence. */
    private static final String POM = """
        <?xml version="1.0" encoding="UTF-8"?>
        <project xmlns="http://maven.apache.org/POM/4.0.0">
            <modelVersion>4.0.0</modelVersion>
            <groupId>com.example</groupId>
            <artifactId>degraded-settings</artifactId>
            <version>1.0.0</version>
            <properties>
                <maven.compiler.release>17</maven.compiler.release>
            </properties>
        </project>
        """;

    private static Path projectWith(Path root, String name, String prefs) throws Exception {
        Path project = root.resolve(name);
        Files.createDirectories(project.resolve(".settings"));
        Files.writeString(project.resolve(".settings/org.eclipse.jdt.core.prefs"), prefs);
        Files.writeString(project.resolve("pom.xml"), POM);
        return project;
    }

    @Test
    @DisplayName("mcp#39: conflict markers make the settings file untrusted, and the pom answers")
    void aConflictedSettingsFileFallsThroughToTheNextSource(@TempDir Path root) throws Exception {
        Path project = projectWith(root, "conflicted", CONFLICTED);

        // THE DEFECT: this was Optional[12] — the HEAD side of somebody's unresolved merge,
        // applied to every file in the project.
        assertEquals(Optional.of("17"), ProjectImporter.readComplianceLevel(project),
            "a prefs file containing conflict markers must not decide the language level");
    }

    @Test
    @DisplayName("mcp#39: two compliance keys that DISAGREE are degraded input, markers or not")
    void duplicateKeysThatDisagreeAreDegraded(@TempDir Path root) throws Exception {
        Path project = projectWith(root, "disagreeing", DISAGREEING);

        // A file can lose its markers and keep the damage — a hand-resolved merge that kept
        // both lines reads as valid and still cannot say which level the project wants.
        assertEquals(Optional.of("17"), ProjectImporter.readComplianceLevel(project),
            "two disagreeing compliance keys must not be resolved by picking the first");
    }

    @Test
    @DisplayName("THE CONTROL — a clean settings file still wins over the pom")
    void aCleanSettingsFileStillDecides(@TempDir Path root) throws Exception {
        // Without this, a "fix" that simply stopped reading .settings would pass both cases
        // above while removing the feature, and the two would be indistinguishable.
        Path project = projectWith(root, "clean", CLEAN);

        assertEquals(Optional.of("21"), ProjectImporter.readComplianceLevel(project),
            "the settings file is still the first source when it is trustworthy");
    }

    @Test
    @DisplayName("THE CONTROL — a repeated key that AGREES is untidy, not untrusted")
    void duplicateKeysThatAgreeAreNotDegraded(@TempDir Path root) throws Exception {
        // The rule is DISAGREEMENT, not repetition. A guard on "the key appears twice" would
        // pass the case above by refusing a file that says one thing clearly, and would then
        // be a rule about tidiness wearing a correctness rule's clothes.
        Path project = projectWith(root, "agreeing", AGREEING);

        assertEquals(Optional.of("21"), ProjectImporter.readComplianceLevel(project),
            "a key repeated with the SAME value states one level and should be believed");
    }
}
