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
    /**
     * @param entries        the wire tail to apply
     * @param unresolved     the honest misses
     * @param exportUpgrades absolute jar paths a {@code Bundle-ClassPath}
     *                       declares that an existing NON-WIRE {@code .classpath}
     *                       entry already occupies — the manifest makes them the
     *                       bundle's surface, so the surviving entry must become
     *                       {@code exported=true} (found live at C13.2: the
     *                       com.jats2.libs pattern — dedupe kept the entry and
     *                       lost the visibility, and 33 of 41 residual errors
     *                       were dependents not seeing slf4j through it)
     */
    public record WireResult(List<IClasspathEntry> entries, List<UnresolvedRequirement> unresolved,
            java.util.Set<IPath> exportUpgrades) {
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
            ExternalBundlePool pool,
            BundleFacts facts,
            java.nio.file.Path projectRoot,
            List<String> junitBundles,
            Function<String, Optional<IJavaProject>> workspaceProjects,
            Set<IPath> occupiedLibs,
            Set<IPath> occupiedProjects) {
        List<IClasspathEntry> entries = new ArrayList<>();
        List<UnresolvedRequirement> unresolved = new ArrayList<>();
        Set<IPath> libs = new HashSet<>(occupiedLibs);
        Set<IPath> projects = new HashSet<>(occupiedProjects);

        // Sprint 28 (mcp#3): the JDT JUnit container — a synthetic project
        // has no containers, so JUnit annotations did not resolve and
        // find_tests reported ZERO test classes in a tree with three test
        // source folders.
        //
        // Rendered FIRST in the wire tail (C13.2's live finding): the test
        // framework the .classpath explicitly asks for must outrank whatever
        // a sibling bundle happens to (re-)export — com.jats2.libs' exported
        // junit-4.5.jar arrived through a PRJ entry ahead of the container's
        // org.junit 4.13.2 and shadowed assertNotEquals. Eclipse's own
        // container wins the same way, by order.
        for (String symbolicName : junitBundles) {
            Optional<java.nio.file.Path> jar = pool.bundleJar(symbolicName);
            if (jar.isPresent()) {
                IPath eclipsePath = new Path(jar.get().toString());
                if (libs.add(eclipsePath)) {
                    entries.add(wireLibrary(eclipsePath, false));
                }
            } else {
                log.debug("JUnit container bundle '{}' not found in the external pools; skipping",
                        symbolicName);
                unresolved.add(UnresolvedRequirement.junitContainer(symbolicName));
            }
        }

        // Providers in the resolver's order — workspace projects rendered
        // inline, jar providers collected and rendered AFTER the JUnit
        // bundles (the pre-12.2 entry order, which the goldens pin).
        List<PlatformResolver.Provider> jarProviders = new ArrayList<>();
        for (PlatformResolver.Provider provider : wiring.providers()) {
            if (provider.workspaceBundle().isEmpty()) {
                jarProviders.add(provider);
                continue;
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

        // Jar providers from the resolver — Require-Bundle, Import-Package,
        // re-export closure and platform-matched fragments alike (12.2: the
        // pool-fallback that used to live HERE dissolved into the resolver).
        // exported=true: a required bundle's classes are part of this
        // project's runtime surface for dependents.
        for (PlatformResolver.Provider provider : jarProviders) {
            IPath eclipsePath = new Path(provider.jar().orElseThrow().toString());
            if (libs.add(eclipsePath)) {
                entries.add(wireLibrary(eclipsePath, true));
            }
        }

        // Bundle-ClassPath (12.3): the project's OWN nested jars, exported=true
        // — a JDT project entry exposes only exported entries, so without the
        // flag the lib-container pattern contributes NOTHING to dependents. A
        // jar the .classpath ALSO names dedupes by absolute path (R25); a
        // declared-but-missing jar is an honest row, never silence.
        java.util.Set<IPath> exportUpgrades = new HashSet<>();
        for (String bcp : facts.bundleClassPath()) {
            String entry = bcp.trim();
            if (entry.isEmpty() || ".".equals(entry)) {
                continue; // "." is the project's own output — nothing to mount
            }
            java.nio.file.Path nested = projectRoot.resolve(entry).toAbsolutePath().normalize();
            if (java.nio.file.Files.isRegularFile(nested)) {
                IPath eclipsePath = new Path(nested.toString());
                if (libs.add(eclipsePath)) {
                    entries.add(wireLibrary(eclipsePath, true));
                } else if (occupiedLibs.contains(eclipsePath)) {
                    // Occupied — usually by the .classpath naming the same jar
                    // (R25). The entry survives ONCE, but the manifest makes it
                    // part of the bundle's surface: the survivor must be
                    // exported, or dependents lose every class in it.
                    exportUpgrades.add(eclipsePath);
                }
            } else if (java.nio.file.Files.isDirectory(nested)) {
                // A Bundle-ClassPath DIRECTORY (e.g. a resources/ folder) is
                // bundle layout, not a missing jar — its content rides the
                // source roots at dev time. No entry, no row. (Found live at
                // C13.2: com.jats2.model declares src/.../resources/ and got a
                // false miss-row.)
                continue;
            } else {
                unresolved.add(new UnresolvedRequirement("Bundle-ClassPath", entry,
                    "declared by the manifest, but no such file under the project root"));
            }
        }

        // A POOL jar whose manifest declares nested Bundle-ClassPath entries
        // carries them INSIDE the packaged jar — jawata cannot mount a
        // jar-in-jar, and saying nothing would be the forbidden silent miss
        // (R19). One honest row per nested entry.
        for (PlatformResolver.Provider provider : jarProviders) {
            java.nio.file.Path jar = provider.jar().orElseThrow();
            pool.factsOf(jar).ifPresent(jarFacts -> {
                for (String bcp : jarFacts.bundleClassPath()) {
                    String entry = bcp.trim();
                    if (!entry.isEmpty() && !".".equals(entry)) {
                        unresolved.add(new UnresolvedRequirement("Bundle-ClassPath",
                            jarFacts.symbolicName() + "!/" + entry,
                            "the entry lives inside the packaged pool jar " + jar.getFileName()
                                + " — a jar-in-jar cannot be mounted; classes in it will not resolve"));
                    }
                }
            });
        }

        // The resolver's misses. Import-Package has ONE miss shape, so it
        // routes through the canonical factory (kind string + reason live in
        // one place); Require-Bundle reasons vary per case and travel verbatim.
        for (PlatformResolver.UnresolvedReport report : wiring.unresolved()) {
            unresolved.add("Import-Package".equals(report.kind())
                ? UnresolvedRequirement.importPackage(report.name())
                : new UnresolvedRequirement(report.kind(), report.name(), report.reason()));
        }
        return new WireResult(List.copyOf(entries), List.copyOf(unresolved),
            java.util.Set.copyOf(exportUpgrades));
    }

    private static IClasspathEntry wireLibrary(IPath jar, boolean exported) {
        return JavaCore.newLibraryEntry(jar, null, null, null,
            new IClasspathAttribute[] {
                JavaCore.newClasspathAttribute(WIRE_ATTRIBUTE, "true") },
            exported);
    }
}
