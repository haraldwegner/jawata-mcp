package org.jawata.core.resolve;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The Resolve half of Inventory → Resolve → Apply: given the COMPLETE picture
 * — every workspace bundle's facts plus the external pools — decide each
 * bundle's wiring.
 *
 * <p>An INTERFACE, deliberately (the architecture's D1 seam): the first
 * implementation is a deterministic graph walk; an Equinox-backed resolver can
 * replace it behind this seam if the walk's fidelity proves insufficient —
 * evidence-gated, never assumed. Implementations are PURE: no JDT, no
 * filesystem, no clock — every input arrives as data, which is what makes the
 * whole semantic surface unit-testable anywhere (D-THREE).</p>
 */
public interface PlatformResolver {

    /**
     * The current machine, for fragment selection. An explicit INPUT — the
     * resolver never reads system properties, so fragment tests are
     * deterministic on every CI OS and the production caller injects the real
     * triple exactly once.
     *
     * @param os   {@code osgi.os}: linux, win32, macosx
     * @param ws   {@code osgi.ws}: gtk, win32, cocoa
     * @param arch {@code osgi.arch}: x86_64, aarch64
     */
    record Platform(String os, String ws, String arch) {
    }

    /** One available jar in an external pool, with the facts read from its manifest. */
    record PoolBundle(BundleFacts facts, Path jar) {
    }

    /**
     * One bundle's provider: EITHER a workspace project (by symbolic name)
     * or a pool jar — never both. Workspace wins by rule.
     */
    record Provider(Optional<String> workspaceBundle, Optional<Path> jar, boolean reexported) {
        public static Provider ofWorkspace(String symbolicName, boolean reexported) {
            return new Provider(Optional.of(symbolicName), Optional.empty(), reexported);
        }

        public static Provider ofJar(Path jar, boolean reexported) {
            return new Provider(Optional.empty(), Optional.of(jar), reexported);
        }
    }

    /**
     * One bundle's computed wiring.
     *
     * @param symbolicName whose wiring this is
     * @param providers    every provider, in deterministic order — workspace
     *                     project references and pool jars, re-export closure
     *                     included, fragments attached beside their host
     * @param unresolved   every requirement that could not be satisfied, with
     *                     the reason — the honest list Stage 8 built the
     *                     reporting contract for
     */
    record Wiring(String symbolicName, List<Provider> providers,
                  List<UnresolvedReport> unresolved) {
    }

    /** What was asked for and not found — pure-data twin of the reporting row. */
    record UnresolvedReport(String kind, String name, String reason) {
    }

    /**
     * Resolve every workspace bundle against the complete picture.
     *
     * @param workspace every loaded PDE bundle's facts, keyed by symbolic name
     * @param pool      every candidate pool jar (duplicates and fragments included;
     *                  arbitration is the resolver's job)
     * @param platform  the running machine's triple, for fragment selection
     * @return wiring per workspace bundle, keyed by symbolic name
     */
    Map<String, Wiring> resolve(Map<String, BundleFacts> workspace,
                                List<PoolBundle> pool,
                                Platform platform);
}
