package org.jawata.core.project;

import org.eclipse.jdt.core.IClasspathEntry;
import org.jawata.core.JdtServiceImpl;
import org.jawata.core.LoadedProject;
import org.jawata.core.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * THE PARITY GATE for the workspace-resolve cutover (plan Stage 11.0 → 11.3).
 *
 * <p>Each golden is the NORMALIZED, ORDERED raw classpath of a fixture set as
 * TODAY'S importer produces it, captured before any parsing or resolution code
 * changed (the capture commit precedes every 11.1+ edit — audit B5: goldens
 * captured after the change would compare new-to-new). Stage 11.3's exit is
 * byte-equality against these files; Stage 12.1 then changes behaviour
 * DELIBERATELY and updates exactly the goldens its flip explains.</p>
 *
 * <p>Regenerate with {@code -Djawata.goldens.update=true} — the test then
 * WRITES and fails, so an update can never silently pass as a check.</p>
 */
class ClasspathGoldenParityTest {

    private static final String UPDATE_PROPERTY = "jawata.goldens.update";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    // ------------------------------------------------------------------
    // the golden sets
    // ------------------------------------------------------------------

    @Test
    @DisplayName("golden: pde-bundle-a/b, provider first")
    void bundlesProviderFirst() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-b", "pde-bundle-a");
        check("pde-bundles-provider-first", service);
    }

    @Test
    @DisplayName("golden: pde-bundle-a/b, dependent first (order-dependence AS IT IS today)")
    void bundlesDependentFirst() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-bundle-a", "pde-bundle-b");
        check("pde-bundles-dependent-first", service);
    }

    @Test
    @DisplayName("golden: pde-sibling + pde-nonstandard-layout (.classpath project ref)")
    void siblingAndNonstandard() throws Exception {
        JdtServiceImpl service =
            helper.loadWorkspaceCopy("pde-sibling", "pde-nonstandard-layout");
        check("pde-sibling-nonstandard", service);
    }

    @Test
    @DisplayName("golden: pde-tycho-tests (the Fragment-Host fixture)")
    void tychoTests() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("pde-tycho-tests");
        check("pde-tycho-tests", service);
    }

    @Test
    @DisplayName("golden: pde-external(+tests) — the POOL-exercising set")
    void externalPool() throws Exception {
        // The property dance mirrors PdeExternalPoolTest: the running dist's
        // own bundles are the pool. CAPTURED at C11.3-start rather than C11.0 —
        // honestly recorded in the plan: the 11.1 parser swap was proven
        // behaviour-preserving on the other five goldens first.
        String distRoot = System.getProperty("jawata.dist.root");
        org.junit.jupiter.api.Assumptions.assumeTrue(distRoot != null,
            "jawata.dist.root must be set (the boot sets it)");
        String before = System.getProperty("jawata.bundle.pools");
        System.setProperty("jawata.bundle.pools",
            Path.of(distRoot, "bundles") + java.io.File.pathSeparator
                + Path.of(distRoot, "test-bundles") + java.io.File.pathSeparator
                + Path.of(distRoot));
        try {
            JdtServiceImpl service = helper.loadWorkspaceCopy("pde-external", "pde-external-tests");
            check("pde-external", service);
        } finally {
            if (before == null) {
                System.clearProperty("jawata.bundle.pools");
            } else {
                System.setProperty("jawata.bundle.pools", before);
            }
        }
    }

    @Test
    @DisplayName("golden: maven-custom-testdir (the non-PDE BYPASS must never change)")
    void plainMavenBypass() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy("maven-custom-testdir");
        check("maven-custom-testdir", service);
    }

    // ------------------------------------------------------------------
    // capture, normalize, compare
    // ------------------------------------------------------------------

    private void check(String goldenName, JdtServiceImpl service) throws Exception {
        String actual = render(service);
        Path golden = goldenPath(goldenName);
        if (Boolean.getBoolean(UPDATE_PROPERTY)) {
            Files.createDirectories(golden.getParent());
            Files.writeString(golden, actual);
            fail("golden '" + goldenName + "' REGENERATED — an update run never passes as "
                + "a check. Commit the file and re-run without -D" + UPDATE_PROPERTY);
        }
        if (!Files.exists(golden)) {
            fail("golden '" + goldenName + "' does not exist — capture it against the "
                + "PRE-CHANGE code with -D" + UPDATE_PROPERTY + "=true (plan C11.0). "
                + "Current output would have been:\n" + actual);
        }
        assertEquals(Files.readString(golden), actual,
            "classpath parity broken for '" + goldenName + "' — if this change is "
                + "DELIBERATE (a 12.1+ behaviour flip), regenerate the golden in the same "
                + "commit and say so; anything else is the cutover drifting");
    }

    /**
     * Render every project's raw classpath, normalized: machine-specific
     * segments become stable tokens, ORDER is preserved (order is part of the
     * contract — JDT resolves in classpath order).
     */
    private String render(JdtServiceImpl service) throws Exception {
        List<LoadedProject> projects = new ArrayList<>(service.allProjects());
        projects.sort(Comparator.comparing(p -> p.projectRoot().getFileName().toString()));
        StringBuilder out = new StringBuilder();
        for (LoadedProject p : projects) {
            String fixture = p.projectRoot().getFileName().toString();
            out.append("== ").append(fixture).append('\n');
            for (IClasspathEntry e : p.javaProject().getRawClasspath()) {
                out.append(kind(e.getEntryKind())).append(' ')
                   .append(normalize(e.getPath().toString(), p, projects))
                   .append(e.isExported() ? " exported" : "")
                   .append('\n');
            }
            out.append("unresolved=").append(p.unresolved().size()).append('\n');
        }
        return out.toString();
    }

    private String normalize(String path, LoadedProject self, List<LoadedProject> all) {
        // SEPARATORS FIRST, and this is the whole Windows story: JDT reports a
        // classpath entry with FORWARD slashes ("D:/a/.../dist/x.jar") while
        // System.getProperty and Path.toString hand back the platform's own
        // ("D:\a\...\dist"), so every substitution below silently missed and
        // three goldens compared a machine-specific absolute path against
        // ${DIST}. The classpaths were identical; only this method was
        // platform-blind. Caught by CI on Windows, invisible on Linux.
        String s = slashes(path);
        // Workspace project segments carry a path-derived hash: /jawata-<name>-<hash>
        s = s.replaceAll("/jawata-([^/]+?)-[0-9a-f]{8}", "/jawata-$1-HASH");
        // Absolute roots of the temp copies.
        for (LoadedProject p : all) {
            s = s.replace(slashes(p.projectRoot().toString()),
                "${PROJECT:" + p.projectRoot().getFileName() + "}");
        }
        String dist = System.getProperty("jawata.dist.root");
        if (dist != null) {
            s = s.replace(slashes(dist), "${DIST}");
        }
        String home = System.getProperty("user.home");
        if (home != null && !home.isBlank()) {
            s = s.replace(slashes(home), "${HOME}");
        }
        return s;
    }

    /** One separator for every OS, so a golden is a fact about wiring, not about a filesystem. */
    private static String slashes(String path) {
        return path.replace('\\', '/');
    }

    private static String kind(int entryKind) {
        return switch (entryKind) {
            case IClasspathEntry.CPE_SOURCE -> "SRC";
            case IClasspathEntry.CPE_LIBRARY -> "LIB";
            case IClasspathEntry.CPE_PROJECT -> "PRJ";
            case IClasspathEntry.CPE_CONTAINER -> "CON";
            case IClasspathEntry.CPE_VARIABLE -> "VAR";
            default -> "K" + entryKind;
        };
    }

    private static Path goldenPath(String name) {
        String fixturesDir = System.getProperty("jawata.test.fixtures");
        Path base = fixturesDir != null
            ? Path.of(fixturesDir).getParent()
            : Path.of("org.jawata.core.tests/test-resources").toAbsolutePath();
        return base.resolve("goldens").resolve("classpath").resolve(name + ".txt");
    }
}
