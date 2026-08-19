package org.jawata.core.project;

import org.eclipse.core.runtime.IPath;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.JavaCore;
import org.jawata.core.resolve.BundleFacts;
import org.jawata.core.resolve.PlatformResolver;
import org.jawata.core.workspace.WorkspaceManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * The Apply half of Inventory → Resolve → Apply: turn a bundle's computed
 * wiring into JDT classpath entries. Nothing here decides — the resolver
 * decided; this renders (the architect's reduction/rendering split, the same
 * ruling that fixed store_health).
 *
 * <p><b>Every wire-produced entry is MARKED</b> with the classpath attribute
 * {@link #WIRE_ATTRIBUTE} so the 12.1 delta-apply can replace exactly the
 * wired tail and never touch source entries, whose linked folders are
 * delete+create (risk R3).</p>
 *
 * <p>At the C11.3 cutover the POOL half below is today's code MOVED VERBATIM
 * (same {@code ExternalBundlePool} calls, same order, same exported flags) —
 * the checkpoint changes shape, not behaviour, and the byte-equal goldens are
 * the proof. Stage 12.2 swaps this pool rendering for resolver-driven
 * selection (nested layout, fragments, the one-pass precedence fix).</p>
 */
final class ClasspathApplier {

    private static final Logger log = LoggerFactory.getLogger(ClasspathApplier.class);

    /** Marks an entry as wire-produced — the 12.1 delta-apply's selector. */
    static final String WIRE_ATTRIBUTE = "jawata.wire";

    private ClasspathApplier() {
    }

    /**
     * Render one bundle's wiring plus the pool fallback.
     *
     * @param wiring       the resolver's decision for this bundle (workspace half)
     * @param facts        the bundle's own manifest facts
     * @param junitBundles JUnit-container stand-in bundle names from the .classpath
     * @return every requirement that could not be satisfied, with its reason
     */
    static List<UnresolvedRequirement> apply(PlatformResolver.Wiring wiring,
            BundleFacts facts,
            List<String> junitBundles,
            WorkspaceManager workspaceManager,
            List<IClasspathEntry> entries,
            Set<IPath> addedLibPaths,
            Set<IPath> addedProjectPaths) {
        List<UnresolvedRequirement> unresolved = new ArrayList<>();

        // 1. Workspace providers, in the resolver's (declaration) order.
        int bundleEntries = 0;
        for (PlatformResolver.Provider provider : wiring.providers()) {
            if (provider.workspaceBundle().isEmpty()) {
                continue; // jar providers arrive at 12.2, through the resolver
            }
            Optional<org.eclipse.jdt.core.IJavaProject> sibling = workspaceManager == null
                ? Optional.empty()
                : workspaceManager.resolveBundle(provider.workspaceBundle().get());
            if (sibling.isEmpty()) {
                // The resolver saw a sibling the registry no longer answers for —
                // a liveness gap, reported rather than silently skipped.
                unresolved.add(UnresolvedRequirement.requireBundle(
                    provider.workspaceBundle().get()));
                continue;
            }
            IPath projPath = sibling.get().getPath();
            if (addedProjectPaths.add(projPath)) {
                entries.add(JavaCore.newProjectEntry(projPath, null, true,
                    new IClasspathAttribute[] {
                        JavaCore.newClasspathAttribute(WIRE_ATTRIBUTE, "true") },
                    false));
                bundleEntries++;
            }
        }

        // 2. The POOL fallback — today's code, moved verbatim (see class doc).
        List<String> unresolvedRequires = wiring.unresolved().stream()
            .filter(u -> "Require-Bundle".equals(u.kind()))
            .map(PlatformResolver.UnresolvedReport::name)
            .toList();
        List<String> importedPackages = facts.importedPackages();
        if (!unresolvedRequires.isEmpty() || !importedPackages.isEmpty() || !junitBundles.isEmpty()) {
            ExternalBundlePool pool = ExternalBundlePool.index(ExternalBundlePool.defaultPoolDirs());
            int external = 0;

            // Sprint 28 (mcp#3): the JDT JUnit container. Eclipse resolves
            // JUNIT_CONTAINER/<n> to the JUnit runtime; jawata's synthetic
            // project has no containers at all, so JUnit annotations did not
            // resolve and find_tests reported ZERO test classes in a tree that
            // had three test source folders.
            for (String symbolicName : junitBundles) {
                Optional<java.nio.file.Path> jar = pool.bundleJar(symbolicName);
                if (jar.isPresent()) {
                    IPath eclipsePath = new Path(jar.get().toString());
                    if (addedLibPaths.add(eclipsePath)) {
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
                    if (addedLibPaths.add(eclipsePath)) {
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
                    if (addedLibPaths.add(eclipsePath)) {
                        entries.add(wireLibrary(eclipsePath, true));
                        external++;
                    }
                } else {
                    log.debug("Import-Package '{}' has no provider in the external pools; skipping", pkg);
                    unresolved.add(UnresolvedRequirement.importPackage(pkg));
                }
            }
            if (external > 0) {
                log.info("Resolved {} PDE requirement(s) from the external bundle pools", external);
            }
        }
        if (bundleEntries > 0) {
            log.info("Resolved {} Require-Bundle entries from the workspace bundle pool", bundleEntries);
        }
        return unresolved;
    }

    private static IClasspathEntry wireLibrary(IPath jar, boolean exported) {
        return JavaCore.newLibraryEntry(jar, null, null, null,
            new IClasspathAttribute[] {
                JavaCore.newClasspathAttribute(WIRE_ATTRIBUTE, "true") },
            exported);
    }
}
