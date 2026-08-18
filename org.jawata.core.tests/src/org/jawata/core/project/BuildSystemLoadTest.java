package org.jawata.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.jawata.core.fixtures.TestProjectHelper;
import org.jawata.core.workspace.WorkspaceManager;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 (D-IMPORTER): every declared build system must LOAD, not merely be
 * DETECTED.
 *
 * <p>Detection was already covered — three Bazel tests wrote a marker file into
 * a temp directory and asserted the enum. Nothing exercised source discovery,
 * jar resolution, output exclusion or the language level, and the gap hid four
 * real defects that this class now pins:</p>
 *
 * <ol>
 *   <li><b>Bazel source roots were the package directory.</b> Bazel puts its
 *       {@code BUILD} file in the PACKAGE directory, so {@code java/com/example}
 *       became a source root while its classes declare {@code package
 *       com.example} — JDT then expected the default package and every class in
 *       every Bazel project failed to compile. Detection passed, roots were
 *       found, jars resolved, output was excluded, and the project was
 *       unusable.</li>
 *   <li><b>No project received its declared Java language level.</b> Nothing in
 *       the product set compiler compliance for ANY build system; every project
 *       silently took the workspace default.</li>
 *   <li><b>An unrecognised layout loaded with no sources at all</b>, reporting
 *       success — so every listing and scan over it was empty by construction.
 *       </li>
 *   <li><b>The ignore list was filtered by leaf name, not pruned.</b>
 *       {@code build} was rejected while {@code build/generated/com/example}
 *       passed, so generated output was mounted as source. (Found by the C1
 *       audit, together with a strict-decoding read that let one non-UTF-8 file
 *       abort a whole project load, and an unvalidated level being pushed to
 *       JDT.)</li>
 * </ol>
 *
 * <h2>The property matrix</h2>
 *
 * <p>Five load properties, five build systems, one named test per cell. A cell
 * that cannot be proven here says so and says why — it is never left to look
 * covered.</p>
 *
 * <pre>
 *                 P1 roots     P2 jars        P3 output    P4 level     P5 usable
 *   Maven         exact set    jupiter jar    no target/   17 applied   findType
 *   Gradle        exact set    tooling API*   trap unseen  17 applied   findType
 *   Bazel         root≠pkg     lib type       trap unseen  17 applied   findType
 *   plain Eclipse exact set    lib type       no bin/      17 applied   findType
 *   PDE           exact set    Require-Bdl    no bin/      BREE 17      findType
 * </pre>
 *
 * <p>* Gradle jar resolution runs through the Gradle Tooling API, which needs a
 * Gradle distribution and by design returns nothing when there is none. That
 * cell therefore SKIPS rather than passes when the environment cannot support
 * it — an environment-conditional result reported as one.</p>
 *
 * <h2>Why each level assertion is falsifiable</h2>
 *
 * <p>{@link #assertLevelApplied} asserts first that the workspace default is
 * NOT the expected level. Without that, "the declared level was applied" passes
 * on a project that was never touched, which is exactly the defect. If the
 * default ever becomes 17, these tests fail and say to move the fixtures,
 * rather than quietly becoming decoration.</p>
 */
class BuildSystemLoadTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ProjectImporter importer;
    private WorkspaceManager workspaceManager;

    @BeforeEach
    void setUp() throws Exception {
        importer = new ProjectImporter();
        workspaceManager = new WorkspaceManager();
        workspaceManager.initialize();
    }

    // ================= Maven =================

    @Test
    @DisplayName("maven P1: the mounted roots are EXACTLY the two conventional ones")
    void mavenMountsExactlyTheConventionalRoots() throws Exception {
        assertRootsAre(load("simple-maven"), "src-main-java", "src-test-java");
    }

    @Test
    @DisplayName("maven P2: declared dependency jars reach the classpath")
    void mavenResolvesDeclaredDependencies() throws Exception {
        // Deliberately NOT assumption-guarded, unlike the Gradle cell: this
        // needs junit-jupiter 5.11.4 in the local repository, and building this
        // project puts it there (build/tests-core/pom.xml declares the same
        // version). A machine that can run this suite has it; if it is missing,
        // that is a broken environment and should fail loudly.
        List<String> libs = libraryPaths(load("simple-maven"));
        assertTrue(libs.stream().anyMatch(p -> p.contains("junit-jupiter")),
            "the pom declares junit-jupiter, but no such jar reached the classpath: " + libs);
    }

    @Test
    @DisplayName("maven P3: a class planted in target/ is not mounted")
    void mavenExcludesTheOutputTree() throws Exception {
        // maven-level-17 plants target/classes-src/com/example/MavenGhost.java.
        // Without a planted class this asserted the absence of output from a
        // fixture that HAS no output — true on any code, including code that
        // mounts target/ wholesale.
        IJavaProject jp = load("maven-level-17");
        assertTrue(sourceRootPaths(jp).stream().noneMatch(p -> p.contains("target")),
            "a path under target/ was mounted as source: " + sourceRootPaths(jp));
        assertNull(jp.findType("com.example.MavenGhost"),
            "a class under target/ resolved as project source");
    }

    @Test
    @DisplayName("maven P4: the level declared in the pom is APPLIED, not just readable")
    void mavenAppliesDeclaredLevel() throws Exception {
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("maven-level-17")),
            "maven.compiler.release=17 was not read from the pom");
        assertLevelApplied(load("maven-level-17"), "17");
    }

    @Test
    @DisplayName("maven P5: a real question about the code is answerable")
    void mavenLoadsUsableTypes() throws Exception {
        assertNotNull(load("simple-maven").findType("com.example.HelloWorld"),
            "com.example.HelloWorld did not resolve");
    }

    // ================= Gradle =================

    @Test
    @DisplayName("gradle P1: the mounted roots are EXACTLY the two source sets")
    void gradleMountsExactlyBothSourceSets() throws Exception {
        assertRootsAre(load("simple-gradle"), "src-main-java", "src-test-java");
    }

    @Test
    @DisplayName("gradle P2: jars from the Gradle model reach the classpath (skips without a Gradle distribution)")
    void gradleResolvesModelJars() throws Exception {
        Path fixture = helper.getFixturePath("simple-gradle");
        Optional<ProjectImporter.GradleProjectModel> model =
            ProjectImporter.readGradleProjectModel(fixture);
        // The message names the RIGHT cause now (C1, audit round 6). It used to
        // say "no Gradle distribution", which was false on the machine the
        // evidence was produced on — Gradle 8.10 was cached there throughout and
        // the model resolved fine. The cell aborted because the FIXTURE declared
        // no dependencies, so getClasspath() was empty by construction and no
        // environment could have made it pass. A stated cause that is wrong is
        // worse than an unexplained skip: it sends the next reader to the
        // machine instead of the fixture.
        Assumptions.assumeTrue(model.isPresent(),
            "the Gradle Tooling API produced no model here — it needs a Gradle distribution and"
                + " returns nothing by design when there is none. UNPROVEN in this environment,"
                + " not passing.");
        Assumptions.assumeFalse(model.get().classpathJars().isEmpty(),
            "the Gradle model resolved but listed no jars. The fixture declares a flat-dir"
                + " dependency, so this means the model could not resolve it — UNPROVEN, not"
                + " passing.");
        List<String> libs = libraryPaths(load("simple-gradle"));
        assertFalse(libs.isEmpty(),
            "the Gradle model listed " + model.get().classpathJars().size()
                + " jars, none of which reached the classpath");
    }

    @Test
    @DisplayName("gradle P3: the build/ output tree is not mounted")
    void gradleExcludesTheOutputTree() throws Exception {
        IJavaProject jp = load("simple-gradle");
        assertTrue(sourceRootPaths(jp).stream().noneMatch(p -> p.contains("build")),
            "a path under build/ was mounted as source: " + sourceRootPaths(jp));
        assertNull(jp.findType("com.example.GradleGhost"),
            "a generated type under build/ resolved — output was mounted beside the sources");
    }

    @Test
    @DisplayName("gradle P4: sourceCompatibility is APPLIED as the language level")
    void gradleAppliesSourceCompatibility() throws Exception {
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("simple-gradle")),
            "sourceCompatibility = '17' was not read from build.gradle");
        assertLevelApplied(load("simple-gradle"), "17");
    }

    @Test
    @DisplayName("gradle P5: a real question about the code is answerable")
    void gradleLoadsUsableTypes() throws Exception {
        assertNotNull(load("simple-gradle").findType("com.example.Calculator"),
            "com.example.Calculator did not resolve");
    }

    // ================= Bazel =================

    @Test
    @DisplayName("bazel P1: the source ROOT is found, not the package directory")
    void bazelSourceRootIsTheRootNotThePackageDir() throws Exception {
        IJavaProject jp = load("simple-bazel");
        List<String> roots = sourceRootPaths(jp);

        // java/com/example holds the BUILD file and classes declaring
        // package com.example, so the ROOT is java/ — the directory the package
        // path is relative to. Adding the package dir is the defect this pins.
        assertTrue(roots.stream().anyMatch(p -> p.endsWith("-java")),
            "expected a source root linked from java/, got " + roots);
        assertTrue(roots.stream().anyMatch(p -> p.endsWith("-javatests")),
            "expected a source root linked from javatests/, got " + roots);
        assertTrue(roots.stream().noneMatch(p -> p.contains("com-example")),
            "a Bazel PACKAGE directory was added as a source root — every class in "
                + "the project would land in the wrong package: " + roots);
    }

    @Test
    @DisplayName("bazel P2: a jar from the output tree is on the classpath AND usable")
    void bazelResolvesJarsFromOutputTree() throws Exception {
        IJavaProject jp = load("simple-bazel");
        assertTrue(libraryPaths(jp).stream().anyMatch(p -> p.endsWith("libexample.jar")),
            "bazel-bin/libexample.jar did not reach the classpath: " + libraryPaths(jp));
        // The path being present proves only that a string was added. The type
        // resolving proves the jar is readable and on the model.
        assertNotNull(jp.findType("com.example.lib.BazelLib"),
            "libexample.jar is on the classpath but its type does not resolve");
    }

    @Test
    @DisplayName("bazel P3: the output tree is EXCLUDED from source discovery")
    void bazelOutputTreeIsExcluded() throws Exception {
        IJavaProject jp = load("simple-bazel");
        // The fixture plants a BUILD.bazel AND a .java inside bazel-bin/ — a scan
        // that fails to exclude bazel-* would take the bait.
        assertTrue(sourceRootPaths(jp).stream().noneMatch(p -> p.contains("bazel-bin")),
            "the Bazel OUTPUT tree was mounted as source: " + sourceRootPaths(jp));
        assertNull(jp.findType("com.example.Generated"),
            "a generated type under bazel-bin/ resolved as project source");
    }

    @Test
    @DisplayName("bazel P4: the level declared in javacopts is APPLIED")
    void bazelAppliesJavacoptsLanguageLevel() throws Exception {
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("simple-bazel")),
            "javacopts = [\"--release\", \"17\"] was not read");
        assertLevelApplied(load("simple-bazel"), "17");
    }

    @Test
    @DisplayName("bazel P5: a real question about the code is answerable")
    void bazelLoadsUsableTypes() throws Exception {
        IJavaProject jp = load("simple-bazel");
        // The point of P1-P4: after them, the model must actually work.
        assertNotNull(jp.findType("com.example.Greeter"),
            "com.example.Greeter did not resolve — the project loaded but is not usable");
        assertNotNull(jp.findType("com.example.GreeterFactory"),
            "com.example.GreeterFactory did not resolve");
    }

    // ================= plain Eclipse =================

    @Test
    @DisplayName("plain eclipse P1: the mounted roots are EXACTLY the .classpath src entries")
    void plainEclipseMountsExactlyItsClasspathEntries() throws Exception {
        // Three since Stage 2: src/it/java carries the DIRECT test="true"
        // spelling, so both flag forms live in one fixture.
        assertRootsAre(load("plain-eclipse"), "src-main-java", "src-test-java", "src-it-java");
    }

    @Test
    @DisplayName("plain eclipse P2: the kind=\"lib\" entry is honoured")
    void plainEclipseResolvesItsLibEntry() throws Exception {
        IJavaProject jp = load("plain-eclipse");
        assertTrue(libraryPaths(jp).stream().anyMatch(p -> p.endsWith("example-lib.jar")),
            "the .classpath lib entry did not reach the classpath: " + libraryPaths(jp));
        assertNotNull(jp.findType("com.example.libs.EclipseLib"),
            "example-lib.jar is on the classpath but its type does not resolve");
    }

    @Test
    @DisplayName("plain eclipse P3: a class planted in the declared output dir is not mounted")
    void plainEclipseExcludesItsOutputDirectory() throws Exception {
        // The fixture plants bin/com/example/EclipseGhost.java — bin is what its
        // .classpath declares as the output directory.
        IJavaProject jp = load("plain-eclipse");
        assertTrue(sourceRootPaths(jp).stream()
                .noneMatch(p -> p.endsWith("-bin") || p.endsWith("/bin")),
            "the .classpath output directory was mounted as a source root: " + sourceRootPaths(jp));
        assertNull(jp.findType("com.example.EclipseGhost"),
            "a class under the declared output directory resolved as project source");
    }

    @Test
    @DisplayName("plain eclipse P4: the level in .settings is APPLIED")
    void plainEclipseAppliesSettingsCompliance() throws Exception {
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("plain-eclipse")),
            "org.eclipse.jdt.core.compiler.compliance=17 was not read from .settings");
        assertLevelApplied(load("plain-eclipse"), "17");
    }

    @Test
    @DisplayName("plain eclipse P5: a real question about the code is answerable")
    void plainEclipseLoadsUsableTypes() throws Exception {
        assertNotNull(load("plain-eclipse").findType("com.example.Inventory"),
            "com.example.Inventory did not resolve");
    }

    // ================= Eclipse PDE =================

    @Test
    @DisplayName("pde P1: the bundle's source root is mounted, and only it")
    void pdeMountsItsSourceRoot() throws Exception {
        List<String> roots = sourceRootPaths(load("pde-bundle-a"));
        assertEquals(1, roots.size(), "expected exactly the bundle's own src/, got " + roots);
        assertTrue(roots.get(0).endsWith("src"), "expected a root ending in src, got " + roots);
    }

    @Test
    @DisplayName("pde P2: a Require-Bundle dependency resolves")
    void pdeResolvesRequireBundle() throws Exception {
        // PDE's dependency mechanism is Require-Bundle, resolved against the
        // external bundle pools — not a jar list in a build file. bundle-a
        // requires org.eclipse.osgi, which the pool has.
        //
        // Guarded like the Gradle cell, and for the same reason: the pool is
        // ~/.p2/pool/plugins or -Djawata.dist.root, and a machine with neither
        // cannot resolve ANY bundle. That is an absent environment, not a
        // defect, and reporting it as a failure would train the reader to
        // ignore a red test.
        IJavaProject jp = load("pde-bundle-a");
        List<String> entries = allClasspathEntries(jp);
        // Guard on LIBRARY entries, not on the string "org.eclipse" (C1 audit
        // round 3): the JRE container's own path is
        // org.eclipse.jdt.launching.JRE_CONTAINER, so the earlier guard matched
        // on every machine and never once skipped — a guard that cannot fire is
        // not a guard, and the comment claiming it behaved like the Gradle cell
        // was false. A pool contributes jars; without one there are none.
        Assumptions.assumeTrue(!libraryPaths(jp).isEmpty(),
            "no external bundle pool on this machine (~/.p2/pool/plugins or -Djawata.dist.root)"
                + " — PDE dependency resolution is unproven here, not passing. Entries: " + entries);
        assertTrue(entries.stream().anyMatch(p -> p.contains("org.eclipse.osgi")),
            "the Require-Bundle dependency on org.eclipse.osgi did not reach the classpath: "
                + entries);
    }

    @Test
    @DisplayName("pde P3: a class planted in bin/ is not mounted")
    void pdeExcludesOutputDirectories() throws Exception {
        // The fixture plants bin/com/example/PdeGhost.java. Before it existed
        // this asserted that a bundle with no bin/ directory had not mounted
        // one — an assertion no code could fail.
        IJavaProject jp = load("pde-bundle-a");
        assertTrue(sourceRootPaths(jp).stream()
                .noneMatch(p -> p.endsWith("-bin") || p.endsWith("/bin")),
            "an output directory was mounted as a source root: " + sourceRootPaths(jp));
        assertNull(jp.findType("com.example.PdeGhost"),
            "a class under bin/ resolved as project source");
    }

    @Test
    @DisplayName("pde P4: the level from Bundle-RequiredExecutionEnvironment is APPLIED")
    void pdeAppliesBreeLanguageLevel() throws Exception {
        // A PDE bundle states its level in the manifest, and rarely carries a
        // .settings file — so before this was read, every OSGi project silently
        // took the default: the defect this sprint exists to end, left standing
        // for one build system.
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("pde-bundle-a")),
            "Bundle-RequiredExecutionEnvironment: JavaSE-17 was not read from the manifest");
        assertLevelApplied(load("pde-bundle-a"), "17");
    }

    @Test
    @DisplayName("pde P5: a real question about the code is answerable")
    void pdeLoadsUsableTypes() throws Exception {
        assertNotNull(load("pde-bundle-a").findType("com.example.a.SpecificGreeter"),
            "com.example.a.SpecificGreeter did not resolve");
    }

    // ================= the properties no build system owns =================

    @Test
    @DisplayName("UNKNOWN: an unrecognised project still loads its sources and says what it is")
    void unknownBuildSystemStillLoads() throws Exception {
        // No pom, no gradle, no bazel marker, no MANIFEST, no .classpath —
        // detection must say UNKNOWN, and the project must still mount what it has
        // rather than loading empty and reporting success.
        Path fixture = helper.getFixturePath("unknown-layout");
        assertEquals(ProjectImporter.BuildSystem.UNKNOWN, importer.detectBuildSystem(fixture),
            "an unmarked project was not classified UNKNOWN");
        IJavaProject jp = load("unknown-layout");
        assertFalse(sourceRootPaths(jp).isEmpty(),
            "an UNKNOWN project mounted no sources — it would load empty and look healthy");
        assertNotNull(jp.findType("com.example.Widget"),
            "the discovered root was mounted but its type does not resolve");
    }

    @Test
    @DisplayName("the ignore list PRUNES the walk — generated output under build/ is never mounted")
    void ignoredDirectoriesArePrunedNotFiltered() throws Exception {
        // The discovery walk is the one place source roots are found rather than
        // declared, so it is the only place this can go wrong. Filtering the
        // LEAF name rejects `build` and accepts `build/generated/com/example`.
        IJavaProject jp = load("unknown-layout");
        assertTrue(sourceRootPaths(jp).stream().noneMatch(p -> p.contains("build")),
            "a directory under build/ was mounted as a source root: " + sourceRootPaths(jp));
        assertNull(jp.findType("com.example.Ghost"),
            "generated output under build/ resolved as project source");
    }

    @Test
    @DisplayName("one non-UTF-8 source does not abort the load of the project around it")
    void aNonUtf8SourceDoesNotAbortTheLoad() throws Exception {
        // Files.lines() decodes lazily and strictly: a malformed byte surfaces
        // as an UncheckedIOException from inside source discovery, which the
        // IOException handler around it does not catch. One legacy-encoded file
        // anywhere in the tree took the whole project down.
        IJavaProject jp = load("unknown-layout");
        assertNotNull(jp.findType("com.example.Widget"),
            "a sibling of the non-UTF-8 file did not resolve — the load was aborted");
    }

    @Test
    @DisplayName("DETECTION survives a non-UTF-8 manifest — the widest blast radius of the three")
    void detectionSurvivesANonUtf8Manifest() throws Exception {
        // hasManifestSymbolicName runs from detectBuildSystem on EVERY load, so
        // its strict lazy decode was the worst instance of this defect — worse
        // than the .java one, because a Bundle-Vendor with an umlaut is
        // ordinary. It had no control: every fixture manifest was clean UTF-8,
        // so the fix could have been reverted with nothing going red.
        //
        // The fixture's Bundle-Vendor carries a raw Latin-1 byte and sits
        // BEFORE Bundle-SymbolicName, so the scan must decode it to get there.
        Path fixture = helper.getFixturePath("pde-latin1-manifest");
        assertEquals(ProjectImporter.BuildSystem.ECLIPSE_PDE, importer.detectBuildSystem(fixture),
            "a bundle with a legacy-encoded manifest was not detected as PDE");
        assertNotNull(load("pde-latin1-manifest").findType("com.example.latin.Vendor"),
            "the project did not load past its own manifest");
    }

    @Test
    @DisplayName("a licence header does not fool the package scan into the default package")
    void aLicenceHeaderDoesNotDefeatThePackageScan() throws Exception {
        // The scan stops at the first type declaration, because a package
        // declaration cannot appear after one. Testing that against RAW lines
        // means a header line beginning "class hierarchy…" or "record of
        // changes…" ends the scan — the file is declared to be in the default
        // package, and every class under its directory lands in the wrong one.
        // Which is the defect the whole package scan exists to prevent.
        IJavaProject jp = load("unknown-layout");
        assertNotNull(jp.findType("com.acme.headered.Headered"),
            "a type whose file carries an asterisk-less licence header did not resolve in its"
                + " declared package — the scan stopped inside the comment");
    }

    @Test
    @DisplayName("an UNUSABLE declaration does not suppress a usable one below it")
    void anUnusableDeclarationDoesNotSuppressAUsableOne() throws Exception {
        // unresolved-plus-bree declares ${java.version} in its pom AND
        // JavaSE-17 in its manifest. Returning at the first source that
        // declared ANYTHING means the unusable one wins and the project gets no
        // level at all — a project that states its level twice ending up worse
        // off than one that states it once.
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("unresolved-plus-bree")),
            "an unresolved pom property suppressed the usable level declared below it");
    }

    @Test
    @DisplayName("the language level is read from the SOURCE tree, not the output tree")
    void theLevelComesFromTheSourceTreeNotTheOutputTree() throws Exception {
        // simple-bazel's real BUILD file declares 17; the generated copy under
        // bazel-bin/ declares 21. Which number arrives says which tree was
        // read — and the output tree is the one source discovery deliberately
        // excludes, so reading a level out of it is reading a generated file.
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("simple-bazel")),
            "the level was taken from the generated BUILD file in bazel-bin/");
    }

    @Test
    @DisplayName("GUARD (not a control): an unreadable directory does not abort the load")
    void anUnreadableDirectoryDoesNotAbortTheLoad() throws Exception {
        // HONEST LABEL. This is a guard, not a discriminator, and it was claimed
        // as a control for five unchecked-catch widenings until an audit
        // reverted them and watched it stay green.
        //
        // It cannot discriminate, for a structural reason: walkPruned already
        // catches at the top level, so the LOAD survives an unreadable
        // directory with or without the per-visit handling. What the handling
        // changes is how much of the tree is still scanned afterwards — and
        // asserting that needs the unreadable directory to be visited BEFORE
        // the readable one, which no filesystem guarantees. A test that depends
        // on directory-iteration order is a coin toss wearing a control's
        // clothes; that is precisely what the first version of this was.
        //
        // Kept because "one unreadable directory must not take a project down"
        // is worth pinning. Its limits are stated rather than implied, and the
        // five widenings are recorded in the dossier as UNCONTROLLED.
        // The unreadable directory is named so it sorts BEFORE the readable
        // one. The first version used "blocked" and "example", and on this
        // filesystem the readable directory happened to be walked first — so
        // the roots were already collected when the walk hit the unreadable
        // one, and the test passed against the UNFIXED code. It was not a
        // control; it was a coin toss that had come up heads.
        // The layout matters twice over. It must NOT use src/ or src/main/java,
        // because those are mounted by CONVENTION and the discovery walk — the
        // only code path that can meet an unreadable directory — never runs at
        // all. The first version of this test used src/, so it exercised
        // nothing and passed against the unfixed code.
        //
        // And the unreadable directory sorts FIRST, so a walk that gives up on
        // it never reaches the readable one.
        Path root = helper.getTempDirectory().resolve("unreadable-project");
        Path blocked = root.resolve("legacy/aaa-blocked");
        java.nio.file.Files.createDirectories(blocked);
        java.nio.file.Files.writeString(blocked.resolve("Hidden.java"),
            "package aaa.blocked;\npublic class Hidden {}\n");
        Path good = root.resolve("legacy/com/example");
        java.nio.file.Files.createDirectories(good);
        java.nio.file.Files.writeString(good.resolve("Reachable.java"),
            "package com.example;\npublic class Reachable {}\n");
        boolean chmodded = blocked.toFile().setReadable(false, false);
        Assumptions.assumeTrue(chmodded && !java.nio.file.Files.isReadable(blocked),
            "this filesystem or user (root ignores the read bit) cannot make a directory"
                + " unreadable — the case is unproven here, not passing");

        try {
            IProject project = workspaceManager.createLinkedProject("load-unreadable", root);
            IJavaProject jp = importer.configureJavaProject(project, root, workspaceManager).javaProject();
            assertNotNull(jp.findType("com.example.Reachable"),
                "a readable sibling did not resolve — one unreadable directory took the"
                    + " whole project down, which is the same over-reaction as the strict"
                    + " decode. Mounted roots: " + sourceRootPaths(jp));
        } finally {
            blocked.toFile().setReadable(true, false);   // so the fixture can be cleaned up
        }
    }

    @Test
    @DisplayName("a missing fixture says so, instead of failing as if the code were wrong")
    void aMissingFixtureIsNamedAsMissing() {
        // This guard exists because thirteen fixtures were once absent from
        // every clone but this machine, and their tests failed as though the
        // importer were broken.
        IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class,
            () -> helper.getFixturePath("no-such-fixture-anywhere"));
        assertTrue(e.getMessage().contains("No such test fixture"),
            "the failure did not name the missing fixture: " + e.getMessage());
    }

    @Test
    @DisplayName("discovered roots are never NESTED inside one another")
    void discoveredRootsAreNotNested() throws Exception {
        // code/ holds a class declaring `package com.example`, so the walk
        // derives code/ as a root. code/com/example/util/ holds one declaring
        // nothing, so the walk derives THAT directory as a root too — inside
        // the first. JDT then expects the default package there and reports
        // "The declared package … does not match" on legal code, and the same
        // file is counted twice.
        IJavaProject jp = load("nested-roots");
        List<String> roots = sourceRootPaths(jp);
        assertEquals(1, roots.size(),
            "expected exactly one root; a nested root was mounted inside another: " + roots);
        assertNotNull(jp.findType("com.example.Foo"),
            "com.example.Foo did not resolve");
    }

    @Test
    @DisplayName("a package declaration sharing its line with an import is still one")
    void aPackageDeclarationSharingItsLineIsStillOne() throws Exception {
        // `package com.acme.oneline; import java.util.List;` is legal on one
        // line. Requiring the whole line to BE the declaration sent it to the
        // type check, which declared it the default package.
        assertNotNull(load("package-with-import").findType("com.acme.oneline.OneLine"),
            "a package declaration sharing its line with an import was not recognised");
    }

    @Test
    @DisplayName("a level JDT accepts is not refused by the shape check")
    void anAncientButRealLevelIsAccepted() throws Exception {
        // javap on the shipped JDT jar declares VERSION_1_1..VERSION_1_8 and
        // VERSION_9..VERSION_27. The previous bound refused 1.1 and 1.2 while
        // accepting 28-49 — wrong in both directions, because it was invented
        // rather than read.
        assertEquals(Optional.of("1.2"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("maven-level-1-2")),
            "a level JDT accepts was refused by the shape check");
    }

    @Test
    @DisplayName("a stray no-package file at the root does not suppress the real roots")
    void aStrayRootFileDoesNotSuppressTheRealRoots() throws Exception {
        // The stray file yields the PROJECT ROOT as a candidate, because it
        // declares nothing. De-nesting then let that suppress code/, and the
        // real code stopped resolving — the fix for nested roots producing the
        // exact failure the discovery path exists to prevent.
        assertNotNull(load("stray-root-file").findType("com.example.Foo"),
            "a stray file at the project root suppressed the real source root");
    }

    @Test
    @DisplayName("the root derivation never escapes the project")
    void theRootDerivationNeverEscapesTheProject() throws Exception {
        // A file at the project root declaring a two-segment package asks for
        // two parents the project does not have, so the derivation walked
        // ABOVE it. Outside the project, that root sorted shallowest and
        // suppressed everything.
        IJavaProject jp = load("package-at-root");
        assertTrue(sourceRootPaths(jp).stream().noneMatch(String::isEmpty),
            "a source root was derived outside the project: " + sourceRootPaths(jp));
        assertNotNull(jp.findType("com.example.Foo"),
            "the correctly-laid-out code did not resolve");
    }

    @Test
    @DisplayName("a TAB after `package` is a package declaration")
    void aTabAfterPackageIsStillADeclaration() throws Exception {
        // `package\tcom.acme.tabbed;` is legal Java. Requiring the literal
        // "package " sent it to the type check, which declared it the default
        // package — so its directory became its own source root and the class
        // landed in the wrong package.
        assertNotNull(load("tabbed-package").findType("com.acme.tabbed.Tabbed"),
            "a type whose file separates `package` from its name with a tab did not resolve"
                + " in its declared package");
    }

    @Test
    @DisplayName("a multi-module pom with a legacy-encoded byte still yields its modules")
    void aLegacyEncodedAggregatorStillYieldsItsModules() throws Exception {
        // isMultiModuleProject and getModules read the pom with the same strict
        // decode. Swallowed, they return false and an empty list — so an
        // aggregator loads as a single-module project and its modules' sources
        // are never mounted at all. E1 claimed this was fixed; nothing tested it.
        Path fixture = helper.getFixturePath("maven-latin1-aggregator");
        assertTrue(importer.isMultiModuleProject(fixture),
            "an aggregator with one legacy-encoded byte was not recognised as multi-module");
        assertFalse(importer.getModules(fixture).isEmpty(),
            "the modules of a legacy-encoded aggregator pom were not found");
    }

    @Test
    @DisplayName("a legacy-encoded byte in a pom does not erase the declared level")
    void aLegacyEncodedPomStillDeclaresItsLevel() throws Exception {
        // The sprint's headline defect, in the place it was NOT fixed for two
        // whole build systems. Files.readString decodes strictly and its
        // MalformedInputException is a CHECKED IOException — so the catch
        // looked correct while turning "a byte I could not decode" into "this
        // project declares no level".
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("maven-latin1-pom")),
            "one legacy-encoded byte in a comment erased the level the pom declares");
        assertLevelApplied(load("maven-latin1-pom"), "17");
    }

    @Test
    @DisplayName("a legacy-encoded byte in a build script does not erase the declared level")
    void aLegacyEncodedGradleScriptStillDeclaresItsLevel() throws Exception {
        assertEquals(Optional.of("17"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("gradle-latin1-script")),
            "one legacy-encoded byte erased the level build.gradle declares");
        assertLevelApplied(load("gradle-latin1-script"), "17");
    }

    @Test
    @DisplayName("a level that is not a real Java release is refused, not pushed to JDT")
    void anImpossibleLevelIsRefused() throws Exception {
        // 99 and 1234 passed the earlier shape check. A typo'd
        // <maven.compiler.release>177</...> reaching setOption is exactly the
        // class of value the check exists to refuse.
        assertEquals(Optional.empty(),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("maven-impossible-level")),
            "a level that is not a Java release was accepted");
    }

    @Test
    @DisplayName("a bare Java 8 in a pom becomes JDT's 1.8, rather than being refused")
    void bareMajorVersionIsNormalizedNotRefused() throws Exception {
        // <maven.compiler.source>8</maven.compiler.source> is ordinary, correct
        // Maven meaning Java 8. JDT's name for that level is "1.8", and a bare
        // "8" handed to setOption is a value it does not recognise — so the
        // shape check must NORMALIZE it, not reject it as a non-version.
        assertEquals(Optional.of("1.8"),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("maven-level-8")),
            "a bare 8 was not normalized to JDT's 1.8");
    }

    @Test
    @DisplayName("a level the build tool would resolve is REFUSED rather than pushed to JDT")
    void unresolvedPropertyLevelIsRefused() throws Exception {
        // <maven.compiler.source>${java.version}</maven.compiler.source> reads
        // textually as the literal "${java.version}". Setting that as a
        // compliance level configures the compiler with a non-version.
        assertEquals(Optional.empty(),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("maven-unresolved-level")),
            "an unresolved build property was accepted as a Java language level");
        assertEquals(workspaceDefaultLevel(),
            load("maven-unresolved-level").getOption(JavaCore.COMPILER_COMPLIANCE, true),
            "the project did not keep the default level");
    }

    @Test
    @DisplayName("a project declaring nothing keeps the default rather than a guess")
    void undeclaredLevelIsLeftAlone() throws Exception {
        // unknown-layout carries a README and sources — no pom, no gradle, no
        // manifest, no settings.
        assertEquals(Optional.empty(),
            ProjectImporter.readComplianceLevel(helper.getFixturePath("unknown-layout")),
            "a level was invented for a project that declares none");
        assertEquals(workspaceDefaultLevel(),
            load("unknown-layout").getOption(JavaCore.COMPILER_COMPLIANCE, true),
            "the project did not keep the default level");
    }

    // ================= helpers =================

    private IJavaProject load(String fixture) throws Exception {
        Path path = helper.getFixturePath(fixture);
        IProject project = workspaceManager.createLinkedProject("load-" + fixture, path);
        return importer.configureJavaProject(project, path, workspaceManager).javaProject();
    }

    /** The level a project gets when nothing is applied to it. */
    private static String workspaceDefaultLevel() {
        return JavaCore.getOption(JavaCore.COMPILER_COMPLIANCE);
    }

    /**
     * Assert the project compiles at {@code expected} — having first asserted
     * that this is NOT what it would have got by doing nothing.
     *
     * <p>Without the first assertion, "the declared level was applied" passes
     * against a project the importer never touched, which is precisely the
     * defect being pinned.</p>
     */
    private static void assertLevelApplied(IJavaProject jp, String expected) {
        assertNotEquals(expected, workspaceDefaultLevel(),
            "this assertion cannot fail: the workspace default is already " + expected
                + ", so applying it changes nothing. Move the fixture to a level that differs.");
        assertEquals(expected, jp.getOption(JavaCore.COMPILER_COMPLIANCE, true),
            "the declared level never reached the project — it silently took the default");
    }

    /** Assert the mounted source roots are exactly these, by count and by name. */
    private void assertRootsAre(IJavaProject jp, String... expectedSuffixes) throws Exception {
        List<String> roots = sourceRootPaths(jp);
        assertEquals(expectedSuffixes.length, roots.size(),
            "expected exactly " + Arrays.toString(expectedSuffixes) + ", got " + roots);
        for (String suffix : expectedSuffixes) {
            assertTrue(roots.stream().anyMatch(p -> p.endsWith(suffix)),
                "expected a root ending in " + suffix + ", got " + roots);
        }
    }

    private List<String> sourceRootPaths(IJavaProject jp) throws Exception {
        return Arrays.stream(jp.getRawClasspath())
            .filter(e -> e.getEntryKind() == IClasspathEntry.CPE_SOURCE)
            .map(e -> e.getPath().toString())
            .toList();
    }

    /** Every raw classpath entry, whatever its kind — for the PDE cell, where
     *  a resolved requirement may arrive as a library OR a project entry. */
    private List<String> allClasspathEntries(IJavaProject jp) throws Exception {
        return Arrays.stream(jp.getRawClasspath())
            .map(e -> e.getEntryKind() + ":" + e.getPath())
            .toList();
    }

    private List<String> libraryPaths(IJavaProject jp) throws Exception {
        return Arrays.stream(jp.getRawClasspath())
            .filter(e -> e.getEntryKind() == IClasspathEntry.CPE_LIBRARY)
            .map(e -> e.getPath().toString())
            .toList();
    }
}
