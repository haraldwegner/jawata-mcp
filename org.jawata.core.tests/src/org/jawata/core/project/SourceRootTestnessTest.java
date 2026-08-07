package org.jawata.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.jawata.core.fixtures.TestProjectHelper;
import org.jawata.core.workspace.WorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28 Stage 2 (D-UNWIRED's producer): the importer RECORDS test-ness, and
 * the record survives into the JDT model.
 *
 * <p>The importer always KNEW which roots were test code —
 * {@code readPomSourceDirs} returns main and test separately, {@code .classpath}
 * carries the {@code test} flag — and flattened both into one list, destroying
 * the knowledge before the model saw it. That destruction is why
 * {@code compile_workspace(scope=…)} misclassifies jawata's own test bundles
 * (mcp#9). This class pins the three-rule precedence:</p>
 *
 * <ol>
 *   <li>an EXPLICIT declaration (Tycho {@code eclipse-test-plugin} packaging ·
 *       Maven {@code testSourceDirectory}/{@code sourceDirectory} · the
 *       {@code .classpath} {@code test} flag, in either spelling);</li>
 *   <li>else the FOLDER CONVENTION ({@code src/test/**} / {@code src/main/**});</li>
 *   <li>else the CONTENT (classes importing {@code org.junit}/{@code org.testng}).</li>
 * </ol>
 *
 * <p>The ORDER is load-bearing and has its own test: a runner's
 * {@code src/main/java} imports JUnit because it runs tests, and content alone
 * would mislabel it.</p>
 *
 * <p>Every assertion here reads {@link IClasspathAttribute#TEST} off the raw
 * classpath — the model, not the importer's internals — because the model is
 * what Stage 3's consumer will read. No consumer reads the tag yet; that is
 * Stage 3, by design.</p>
 */
class SourceRootTestnessTest {

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

    // ============ Rule 1 — explicit declarations ============

    @Test
    @DisplayName("rule 1: the .classpath test flag tags, in BOTH spellings; absence is explicit main")
    void classpathTestFlagTagsInBothSpellings() throws Exception {
        IJavaProject jp = load("plain-eclipse");
        assertTrue(isTestRoot(jp, "src-test-java"),
            "the REAL nested <attribute name=\"test\"> spelling did not tag the root");
        assertTrue(isTestRoot(jp, "src-it-java"),
            "the direct test=\"true\" attribute spelling did not tag the root");
        assertFalse(isTestRoot(jp, "src-main-java"),
            "a .classpath src entry WITHOUT the flag is explicit main and must not be tagged");
    }

    @Test
    @DisplayName("rule 1: Tycho eclipse-test-plugin packaging tags the WHOLE bundle")
    void tychoTestPluginPackagingTagsTheBundle() throws Exception {
        // Sources flat under src/ (convention silent). The classes DO import
        // JUnit, so content would also say test — which is exactly why the
        // packaging must be read: the declaration is evidence, the imports are
        // coincidence.
        IJavaProject jp = load("pde-tycho-tests");
        assertTrue(isTestRoot(jp, "-src"),
            "an eclipse-test-plugin bundle's source root was not tagged test");
    }

    @Test
    @DisplayName("rule 1: Maven's explicit testSourceDirectory tags a NONSTANDARD dir")
    void mavenExplicitTestSourceDirectoryTags() throws Exception {
        // tests-src/ has no test-framework import and no conventional name —
        // only the pom's declaration can carry this.
        IJavaProject jp = load("maven-custom-testdir");
        assertTrue(isTestRoot(jp, "-tests-src"),
            "the pom's <testSourceDirectory> did not tag its nonstandard dir");
        assertFalse(isTestRoot(jp, "-code-src"),
            "the pom's <sourceDirectory> was tagged test");
    }

    // ============ Rule 2 — the folder convention ============

    @Test
    @DisplayName("rule 2: src/test/java tags by convention (Maven, no declarations)")
    void mavenConventionTags() throws Exception {
        IJavaProject jp = load("simple-maven");
        assertTrue(isTestRoot(jp, "src-test-java"),
            "src/test/java was not tagged by the folder convention");
        assertFalse(isTestRoot(jp, "src-main-java"),
            "src/main/java was tagged test");
    }

    @Test
    @DisplayName("rule 2: the Gradle sample's test source set is tagged")
    void gradleConventionTags() throws Exception {
        IJavaProject jp = load("simple-gradle");
        assertTrue(isTestRoot(jp, "src-test-java"),
            "the Gradle test source set was not tagged");
        assertFalse(isTestRoot(jp, "src-main-java"),
            "the Gradle main source set was tagged test");
    }

    @Test
    @DisplayName("rule ORDER: a runner's src/main/java imports JUnit and stays MAIN")
    void conventionOutranksContent() throws Exception {
        // The build/testrunner shape: production code that imports a test
        // framework because its JOB is running tests. If this test ever fails,
        // the precedence inverted and every test-runner in every user project
        // just became invisible to main-scope analysis.
        IJavaProject jp = load("runner-shape");
        assertFalse(isTestRoot(jp, "src-main-java"),
            "src/main/java was tagged test because its content imports JUnit — "
                + "the convention must outrank the content");
    }

    // ============ Rule 3 — the content ============

    @Test
    @DisplayName("rule 3: the flat own-tests-bundle shape is tagged by CONTENT alone")
    void flatTestsShapeTagsByContent() throws Exception {
        // jawata's own *.tests bundles: flat src/, no pom, no .classpath — the
        // shape mcp#9 misclassifies. Rules 1 and 2 are silent; the JUnit
        // imports are the only evidence. (The live-workspace form of this
        // assertion is C3's scope=main/test tool call against the real repo.)
        IJavaProject jp = load("flat-tests-shape");
        assertTrue(isTestRoot(jp, "-src"),
            "a flat root whose classes import org.junit was not tagged test");
    }

    // ============ helpers ============

    private IJavaProject load(String fixture) throws Exception {
        Path path = helper.getFixturePath(fixture);
        IProject project = workspaceManager.createLinkedProject("tag-" + fixture, path);
        return importer.configureJavaProject(project, path, workspaceManager);
    }

    /** Whether the source entry ending in {@code suffix} carries TEST=true. */
    private boolean isTestRoot(IJavaProject jp, String suffix) throws Exception {
        List<IClasspathEntry> matches = Arrays.stream(jp.getRawClasspath())
            .filter(e -> e.getEntryKind() == IClasspathEntry.CPE_SOURCE)
            .filter(e -> e.getPath().toString().endsWith(suffix))
            .toList();
        assertFalse(matches.isEmpty(), "no source root ends with " + suffix + "; roots: "
            + Arrays.stream(jp.getRawClasspath())
                .filter(e -> e.getEntryKind() == IClasspathEntry.CPE_SOURCE)
                .map(e -> e.getPath().toString()).toList());
        IClasspathEntry entry = matches.get(0);
        return Arrays.stream(entry.getExtraAttributes())
            .anyMatch(a -> IClasspathAttribute.TEST.equals(a.getName())
                && "true".equals(a.getValue()));
    }
}
