package org.jawata.mcp.catalogue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C8's clause, verbatim: <i>a test proves the module cannot reach a language engine</i>.
 *
 * <h2>Why this exists when the property is already enforced twice by construction</h2>
 *
 * <p>{@code build/catalogue/pom.xml} declares no dependencies, so a reference to a JDT or
 * {@code org.jawata.mcp} type would not compile; and the checked-in MANIFEST has no
 * {@code Import-Package}, so OSGi would refuse one at resolve time too. Both are real and both
 * are better than a test, because they fail earlier.</p>
 *
 * <p><b>Neither of them is asserted by anything, and that is the gap.</b> A property enforced by
 * a build file is one pom edit away from being lost, and the edit would look like ordinary work —
 * adding Jackson to read a resource, say, which is precisely the temptation the pattern lane
 * created one commit ago and declined. This repository's own record is that an unasserted
 * property is indistinguishable from a forgotten one: C8's own exit clause says "a test proves
 * it", not "the build arranges it".</p>
 *
 * <p>Three independent checks, because the purity can be lost three ways and each one is silent
 * in a different place: the source could import an engine type (caught at compile time, but only
 * once a dependency exists to make it resolve), the pom could gain the dependency that makes such
 * an import compile, and the MANIFEST could gain the import that makes OSGi allow it at runtime.
 * A single check on any one of them would pass while another had already been opened.</p>
 */
class TheCatalogueCannotReachAnEngineTest {

    private static final String MODULE = "org.jawata.catalogue";

    /**
     * The packages a shared table may never name.
     *
     * <p>{@code org.jawata.catalogue} itself is obviously excluded. {@code java.*} is fine — the
     * standard library is not an engine, and the pattern lane reads its resource with it.</p>
     */
    private static final List<String> ENGINE_PACKAGES = List.of(
            "org.eclipse",
            "org.jawata.mcp",
            "org.jawata.core",
            "com.fasterxml",
            "org.junit");

    @Test
    @DisplayName("no source file in the module imports an engine package")
    void noSourceReachesAnEngine() throws IOException {
        Path src = moduleRoot().resolve("src");
        assertTrue(Files.isDirectory(src), "the module's source root is missing: " + src);

        List<Path> sources;
        try (Stream<Path> walk = Files.walk(src)) {
            sources = walk.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        }
        // PROOF OF LIFE: with no sources found, every offender list below is empty and this
        // whole class would certify a module it never read.
        assertFalse(sources.isEmpty(), "found no sources under " + src + " — the walk is wrong");

        List<String> offenders = new ArrayList<>();
        for (Path p : sources) {
            for (String line : Files.readAllLines(p)) {
                String t = line.strip();
                if (!t.startsWith("import ")) {
                    // Only IMPORTS are read, and the narrowness is deliberate: a javadoc
                    // sentence mentioning JDT is a sentence, and this file's own siblings
                    // discuss engines at length. A fully-qualified reference in code would
                    // escape this check and is caught by the pom instead — which is why the
                    // three checks are kept separate rather than merged into one.
                    continue;
                }
                for (String engine : ENGINE_PACKAGES) {
                    if (t.startsWith("import " + engine + ".")) {
                        offenders.add(p.getFileName() + ": " + t);
                    }
                }
            }
        }
        assertEquals(
                List.of(),
                offenders,
                "the shared catalogue imports an engine's package. The whole point of promoting"
                        + " this table out of the test tree is that a second language can declare"
                        + " against it; a row that needs a Java engine to be read is Java's table"
                        + " again");
    }

    @Test
    @DisplayName("the module's pom declares no dependencies, so an engine import could not compile")
    void thePomDeclaresNoDependencies() throws IOException {
        Path pom = repoRoot().resolve("build/catalogue/pom.xml");
        assertTrue(Files.exists(pom), "looked for the module's pom at " + pom);
        String text = Files.readString(pom);

        // A <dependencies> element at all, empty or not. An empty one is still the door being
        // installed, and the next edit only has to walk through it.
        assertFalse(
                text.contains("<dependencies>"),
                "build/catalogue/pom.xml has gained a <dependencies> block. That is how the"
                        + " purity is lost: the first dependency always has a good reason — a"
                        + " JSON parser for the pattern lane was the one this sprint declined —"
                        + " and after it an engine import compiles");
        // The inherited parent is fine and expected; this asserts it is the only coordinate.
        assertTrue(text.contains("<artifactId>org.jawata.catalogue</artifactId>"),
                "PROOF OF LIFE: this is not the pom I think it is");
    }

    @Test
    @DisplayName("the MANIFEST declares no Import-Package, so OSGi would refuse one at resolve time")
    void theManifestImportsNothing() throws IOException {
        Path manifest = moduleRoot().resolve("META-INF/MANIFEST.MF");
        assertTrue(Files.exists(manifest), "looked for the module's manifest at " + manifest);
        String text = Files.readString(manifest);

        assertAll(
                () -> assertFalse(
                        text.contains("Import-Package"),
                        "the catalogue bundle has gained an Import-Package. The build-time half"
                                + " and this half are not redundant: a dependency can be added"
                                + " with provided scope and compile fine while OSGi refuses it,"
                                + " and an Import-Package can be added while the pom stays clean"
                                + " and the class loads from whatever happens to export it"),
                () -> assertFalse(
                        text.contains("Require-Bundle"),
                        "a Require-Bundle reaches a whole bundle rather than a package, which is"
                                + " the broader form of the same door"),
                () -> assertTrue(
                        text.contains("Export-Package: " + MODULE),
                        "PROOF OF LIFE: the bundle must still EXPORT its own package, or the"
                                + " engine side could not read the table at all and the two"
                                + " assertions above would be true of something useless"));
    }

    /** The module's own directory, found by walking up from wherever the runner started. */
    private static Path moduleRoot() {
        return repoRoot().resolve(MODULE);
    }

    /**
     * The repository root, searched for rather than assumed.
     *
     * <p>The suite runs from the dist, whose working directory is not reliably the repository
     * root — the same reason the transcription controls search for their subject.</p>
     */
    private static Path repoRoot() {
        for (Path p = Path.of("").toAbsolutePath(); p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(MODULE))) {
                return p;
            }
        }
        throw new IllegalStateException(
                "could not find " + MODULE + " above " + Path.of("").toAbsolutePath());
    }
}
