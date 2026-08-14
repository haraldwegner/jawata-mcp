package org.jawata.mcp.tools.verification;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Sprint 28a (1b, step M13) — the host boundary's dependency rule, enforced.
 *
 * <p>Section 3 of {@code docs/architect/ARCHITECTURE-mcp-host-boundary.md}
 * states four invariants. Without this test they are prose: the migration that
 * established them would hold exactly until the next contributor wrote
 * {@code new ProcessBuilder(...)} because it was closer to hand — and nothing
 * would notice until an operating system nobody develops on disagreed.</p>
 *
 * <p>The failure mode this closes is specific and expensive. Eight jawata-studio
 * releases in one day each fixed a different symptom of one design flaw:
 * platform knowledge scattered across call sites with no owning boundary. Every
 * defect was found by a human reading a folder listing on Windows, never by a
 * test, because a compile-time platform constant makes the failing branch
 * unrepresentable in a suite that runs elsewhere. A boundary is only worth
 * building if something keeps it.</p>
 *
 * <p>The scan is source text over the production roots, deliberately: the rule
 * is about what the SOURCE may contain, and a rule expressed against compiled
 * bindings cannot see a violation that has not been compiled yet.</p>
 */
class HostBoundaryRulesTest {

    /** Everything below the boundary lives here. */
    private static final String HOST_PACKAGE_DIR = "org/jawata/core/host";

    /** Production source roots, relative to the repository root. */
    private static final List<String> PRODUCTION_ROOTS =
        List.of("org.jawata.core/src", "org.jawata.mcp/src");

    /**
     * The repository root, or empty when the suite runs somewhere the sources
     * are not present (a packaged run). An honest skip beats a false pass.
     */
    private static Path repositoryRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("org.jawata.core/src"))) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> productionSources(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String relative : PRODUCTION_ROOTS) {
            Path dir = root.resolve(relative);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(files::add);
            }
        }
        return files;
    }

    private static boolean insideBoundary(Path file) {
        return file.toString().replace('\\', '/').contains(HOST_PACKAGE_DIR);
    }

    /** Production files OUTSIDE the boundary whose text matches {@code offence}. */
    private static List<String> offendersOutsideBoundary(Predicate<String> offence)
            throws IOException {
        Path root = repositoryRoot();
        assumeTrue(root != null,
            "sources are not present in this run — the boundary rule is UNPROVEN here, "
                + "not passing");

        List<String> offenders = new ArrayList<>();
        for (Path file : productionSources(root)) {
            if (insideBoundary(file)) {
                continue;
            }
            String text = Files.readString(file, StandardCharsets.UTF_8);
            if (offence.test(text)) {
                offenders.add(root.relativize(file).toString());
            }
        }
        return offenders;
    }

    @Test
    @DisplayName("invariant 1: no process is launched outside the boundary")
    void noProcessLaunchOutsideTheBoundary() throws IOException {
        List<String> offenders = offendersOutsideBoundary(t -> t.contains("new ProcessBuilder"));

        assertEquals(List.of(), offenders,
            "these production files construct a ProcessBuilder directly. Route the spawn "
                + "through org.jawata.core.host.HostProcesses — run() to run and capture, "
                + "start() when the child outlives the call. Eleven such sites once each "
                + "decided privately how an executable is spelled and whether a timeout "
                + "means anything.");
    }

    @Test
    @DisplayName("invariant 2: no os.name sniffing outside the boundary")
    void noOsSniffingOutsideTheBoundary() throws IOException {
        List<String> offenders = offendersOutsideBoundary(t -> t.contains("\"os.name\""));

        assertEquals(List.of(), offenders,
            "these production files read os.name directly. Ask org.jawata.core.host.HostOS "
                + "— it takes the value as an argument, which is what makes the Windows "
                + "branch reachable from a Linux runner. The predicate this replaced "
                + "classified Darwin as Windows, because \"darwin\" contains \"win\", and no "
                + "test could reach it.");
    }

    @Test
    @DisplayName("invariant 3: the boundary is a LEAF — it knows nothing above itself")
    void theBoundaryIsALeaf() throws IOException {
        Path root = repositoryRoot();
        assumeTrue(root != null, "sources are not present — UNPROVEN here, not passing");

        List<String> violations = new ArrayList<>();
        for (Path file : productionSources(root)) {
            if (!insideBoundary(file)) {
                continue;
            }
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                String trimmed = line.strip();
                if (!trimmed.startsWith("import ")) {
                    continue;
                }
                boolean forbidden = trimmed.startsWith("import org.jawata.mcp.")
                    || trimmed.startsWith("import org.eclipse.")
                    || (trimmed.startsWith("import org.jawata.core.")
                        && !trimmed.startsWith("import org.jawata.core.host."));
                if (forbidden) {
                    violations.add(root.relativize(file) + ": " + trimmed);
                }
            }
        }

        assertEquals(List.of(), violations,
            "the boundary must depend on the JDK alone. An import reaching UP into the "
                + "engine or the tools turns the anti-corruption layer into another "
                + "participant in the cycle it exists to break.");
    }

    @Test
    @DisplayName("invariant 4: POSIX-only file APIs stay boundary-internal")
    void posixOnlyApisAreBoundaryInternal() throws IOException {
        List<String> offenders = offendersOutsideBoundary(
            t -> t.contains("setPosixFilePermissions") || t.contains("PosixFileAttributeView"));

        assertEquals(List.of(), offenders,
            "these production files call a POSIX-only file API. It THROWS "
                + "UnsupportedOperationException on Windows, so a caller learns of it by "
                + "catching an exception that reads like a product bug. Ask "
                + "org.jawata.core.host.HostFs, which answers as a capability query.");
    }

    @Test
    @DisplayName("the boundary itself is where the host lives — the rules are not vacuously true")
    void theBoundaryActuallyContainsTheHostContact() throws IOException {
        Path root = repositoryRoot();
        assumeTrue(root != null, "sources are not present — UNPROVEN here, not passing");

        // A rule that passes because the thing it guards does not exist is not a
        // rule. If the boundary were emptied, every assertion above would still
        // be green; this one would not.
        Path hostDir = root.resolve("org.jawata.core/src").resolve(HOST_PACKAGE_DIR);
        assertTrue(Files.isDirectory(hostDir), "the boundary package must exist: " + hostDir);

        String adapter = Files.readString(
            hostDir.resolve("SystemHostProcesses.java"), StandardCharsets.UTF_8);
        assertTrue(adapter.contains("new ProcessBuilder"),
            "the boundary is where processes are launched; if this moved, the census "
                + "invariant above is measuring nothing");

        for (String required : List.of("HostOS.java", "HostPaths.java", "HostProcesses.java",
                "HostFs.java", "HostText.java")) {
            assertTrue(Files.isRegularFile(hostDir.resolve(required)),
                "the design names this seam: " + required);
        }
    }
}
