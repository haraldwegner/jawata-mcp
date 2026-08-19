package org.jawata.core.project;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.jawata.core.resolve.BundleFacts;
import org.jawata.core.resolve.PlatformResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The Apply half of Inventory → Resolve → Apply: turn a bundle's computed
 * wiring into JDT classpath entries. Nothing here decides — the resolver
 * decided; this renders (the architect's reduction/rendering split).
 *
 * <p><b>Every wire-produced entry is MARKED</b> with {@link #WIRE_ATTRIBUTE},
 * which is what makes the 12.1 delta-apply safe: a re-resolve replaces exactly
 * the wired tail and never touches source entries, whose linked folders are
 * delete+create (risk R3).</p>
 *
 * <p>The POOL half below is the pre-pipeline code moved verbatim (same
 * {@code ExternalBundlePool} calls, same order, same exported flags) — it
 * dissolves into the resolver at 12.2.</p>
 */
public final class ClasspathApplier {

    private static final Logger log = LoggerFactory.getLogger(ClasspathApplier.class);

    /** Marks an entry as wire-produced — the delta-apply's selector. */
    public static final String WIRE_ATTRIBUTE = "jawata.wire";

    private ClasspathApplier() {
    }

    /** Is this entry one the wire owns (and may therefore replace)? */
    public static boolean isWire(IClasspathEntry entry) {
        for (IClasspathAttribute a : entry.getExtraAttributes()) {
            if (WIRE_ATTRIBUTE.equals(a.getName()) && "true".equals(a.getValue())) {
                return true;
            }
        }
        return false;
    }

    /** One bundle's computed wire tail plus its honest misses. */
    public record WireResult(List<IClasspathEntry> entries, List<UnresolvedRequirement> unresolved) {
    }

    /**
     * Compute one bundle's wire tail. Pure over its inputs — no workspace
     * mutation happens here; the caller decides whether and how to apply.
     *
     * @param wiring            the resolver's decision for this bundle
     * @param facts             the bundle's own manifest facts
     * @param junitBundles      JUnit-container stand-ins from the .classpath
     * @param workspaceProjects symbolic name → live {@link IJavaProject} — a
     *                          FUNCTION, not the old name registry, which 12.1
     *                          deleted (name-only, last-writer-wins, never
     *                          invalidated)
     * @param occupiedLibs      absolute lib paths already on the non-wire
     *                          classpath — the R25 dedupe across ALL sources
     * @param occupiedProjects  project paths already present
     */
    public static WireResult computeWire(PlatformResolver.Wiring wiring,
            BundleFacts facts,
            List<String> junitBundles,
            Function<String, Optional<IJavaProject>> workspaceProjects,
            Set<IPath> occupiedLibs,
            Set<IPath> occupiedProjects) {
        List<IClasspathEntry> entries = new ArrayList<>();
        List<UnresolvedRequirement> unresolved = new ArrayList<>();
        Set<IPath> libs = new HashSet<>(occupiedLibs);
        Set<IPath> projects = new HashSet<>(occupiedProjects);

        // 1. Workspace providers, in the resolver's order.
        for (PlatformResolver.Provider provider : wiring.providers()) {
            if (provider.workspaceBundle().isEmpty()) {
                continue; // jar providers arrive at 12.2, through the resolver
            }
            Optional<IJavaProject> sibling =
                workspaceProjects.apply(provider.workspaceBundle().get());
            if (sibling.isEmpty()) {
                // The resolver wired a name the workspace no longer answers for —
                // reported, never silently skipped.
                unresolved.add(UnresolvedRequirement.requireBundle(
                    provider.workspaceBundle().get()));
                continue;
            }
            IPath projPath = sibling.get().getPath();
            if (projects.add(projPath)) {
                entries.add(JavaCore.newProjectEntry(projPath, null, true,
                    new IClasspathAttribute[] {
                        JavaCore.newClasspathAttribute(WIRE_ATTRIBUTE, "true") },
                    false));
            }
        }

        // 2. The POOL fallback — pre-pipeline code, verbatim (see class doc).
        List<String> unresolvedRequires = wiring.unresolved().stream()
            .filter(u -> "Require-Bundle".equals(u.kind()))
            .map(PlatformResolver.UnresolvedReport::name)
            .toList();
        List<String> importedPackages = facts.importedPackages();
        if (!unresolvedRequires.isEmpty() || !importedPackages.isEmpty() || !junitBundles.isEmpty()) {
            ExternalBundlePool pool = ExternalBundlePool.index(ExternalBundlePool.defaultPoolDirs());
            int external = 0;

            // Sprint 28 (mcp#3): the JDT JUnit container — a synthetic project
            // has no containers, so JUnit annotations did not resolve and
            // find_tests reported ZERO test classes in a tree with three test
            // source folders.
            for (String symbolicName : junitBundles) {
                Optional<java.nio.file.Path> jar = pool.bundleJar(symbolicName);
                if (jar.isPresent()) {
                    IPath eclipsePath = new Path(jar.get().toString());
                    if (libs.add(eclipsePath)) {
                        entries.add(wireLibrary(eclipsePath, false));
                        external++;
                    }
                } else {
                    log.debug("JUnit container bundle '{}' not found in the external pools; skipping",
                            symbolicName);
                    unresolved.add(UnresolvedRequirement.junitContainer(symbolicName));
                }
            }
            for (String required : unresolvedRequires) {
                Optional<java.nio.file.Path> jar = pool.bundleJar(required);
                if (jar.isPresent()) {
                    IPath eclipsePath = new Path(jar.get().toString());
                    if (libs.add(eclipsePath)) {
                        // exported=true: a required bundle's classes are part of
                        // this project's runtime surface for dependents.
                        entries.add(wireLibrary(eclipsePath, true));
                        external++;
                    }
                } else {
                    log.debug("Require-Bundle '{}' not found in workspace or external pools; skipping",
                            required);
                    unresolved.add(UnresolvedRequirement.requireBundle(required));
                }
            }
            for (String pkg : importedPackages) {
                Optional<java.nio.file.Path> jar = pool.packageProvider(pkg);
                if (jar.isPresent()) {
                    IPath eclipsePath = new Path(jar.get().toString());
                    if (libs.add(eclipsePath)) {
                        entries.add(wireLibrary(eclipsePath, true));
                        external++;
                    }
                } else {
                    log.debug("Import-Package '{}' has no provider in the external pools; skipping", pkg);
                    unresolved.add(UnresolvedRequirement.importPackage(pkg));
                }
            }
            if (external > 0) {
                log.debug("Resolved {} PDE requirement(s) from the external bundle pools", external);
            }
        }
        return new WireResult(List.copyOf(entries), List.copyOf(unresolved));
    }

    private static IClasspathEntry wireLibrary(IPath jar, boolean exported) {
        return JavaCore.newLibraryEntry(jar, null, null, null,
            new IClasspathAttribute[] {
                JavaCore.newClasspathAttribute(WIRE_ATTRIBUTE, "true") },
            exported);
    }
}
