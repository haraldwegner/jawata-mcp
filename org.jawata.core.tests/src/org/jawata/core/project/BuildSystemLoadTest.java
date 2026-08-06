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
        Assumptions.assumeTrue(model.isPresent() && !model.get().classpathJars().isEmpty(),
            "the Gradle Tooling API produced no model here — it needs a Gradle distribution, and"
                + " returns nothing by design when there is none. This cell is unproven in this"
                + " environment, not passing.");
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
        assertRootsAre(load("plain-eclipse"), "src-main-java", "src-test-java");
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
        Assumptions.assumeTrue(entries.stream().anyMatch(p -> p.contains("org.eclipse")),
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
        return importer.configureJavaProject(project, path, workspaceManager);
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
