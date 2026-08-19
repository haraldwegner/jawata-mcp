package org.jawata.core.resolve;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The deterministic graph walk behind the {@link PlatformResolver} seam.
 *
 * <p>What it deliberately is NOT: an OSGi resolver. No {@code uses}
 * constraints, no singleton arbitration, no split-package mediation — the
 * dev-time comprehension model tolerates looseness by design (the ruled
 * architecture), and the seam exists precisely so a real Equinox-backed
 * resolver can replace this if the looseness ever produces measurably wrong
 * navigation.</p>
 *
 * <p>The rules, each one ruled at C10 or fixed at audit:</p>
 * <ul>
 *   <li><b>Workspace wins.</b> A loaded project always beats a pool jar of
 *       the same name — the workspace is the developer's truth.</li>
 *   <li><b>Newest satisfying the declared floor.</b> The measured manifests
 *       declare floors or nothing; picking the newest that satisfies them is
 *       a legal reading and needs no train knowledge.</li>
 *   <li><b>ONE selection pass</b> (audit R7): the winner per symbolic name is
 *       chosen first; the exported-package view derives from winners only, so
 *       the two answers can never disagree.</li>
 *   <li><b>Fragments</b> (R9): filtered to the injected platform triple —
 *       LDAP {@code Eclipse-PlatformFilter} when present, symbolic-name
 *       suffix as fallback — newest satisfying the host floor, attached
 *       beside their host. A hostless fragment contributes nothing.</li>
 *   <li><b>Cycles are legal</b> (D5): the walk carries a visited set, never a
 *       stack overflow, never an error.</li>
 *   <li><b>Re-export closure</b> ({@code visibility:=reexport}): a provider's
 *       reexported requirements ride along transitively — the graph property
 *       that closes {@code #11}.</li>
 *   <li><b>Optional misses are still reported</b> (13.1's flagged default):
 *       {@code resolution:=optional} rows keep counting until Harald rules
 *       otherwise — excluding them silently changes studio-visible numbers.</li>
 * </ul>
 */
public final class GraphWalkResolver implements PlatformResolver {

    private static final Logger log = LoggerFactory.getLogger(GraphWalkResolver.class);

    @Override
    public Map<String, Wiring> resolve(Map<String, BundleFacts> workspace,
                                       List<PoolBundle> pool,
                                       Platform platform) {
        Selection selection = select(workspace, pool, platform);
        Map<String, Wiring> out = new LinkedHashMap<>();
        for (Map.Entry<String, BundleFacts> e : workspace.entrySet()) {
            out.put(e.getKey(), wire(e.getKey(), e.getValue(), workspace, selection));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // the ONE selection pass (R7)
    // ------------------------------------------------------------------

    /** The pool after arbitration: one winner per symbolic name, packages derived from winners. */
    private record Selection(Map<String, PoolBundle> winnerByName,
                             Map<String, PoolBundle> winnerByExportedPackage,
                             Map<String, List<PoolBundle>> fragmentsByHost) {
    }

    private Selection select(Map<String, BundleFacts> workspace,
                             List<PoolBundle> pool,
                             Platform platform) {
        Map<String, PoolBundle> byName = new HashMap<>();
        Map<String, List<PoolBundle>> fragmentsByHost = new HashMap<>();
        for (PoolBundle candidate : pool) {
            BundleFacts f = candidate.facts();
            if (f.fragmentHost().isPresent()) {
                if (fragmentFits(f, platform)) {
                    fragmentsByHost
                        .computeIfAbsent(f.fragmentHost().get().name(), k -> new ArrayList<>())
                        .add(candidate);
                }
                continue; // a fragment is never a provider in its own right
            }
            PoolBundle previous = byName.get(f.symbolicName());
            byName.merge(f.symbolicName(), candidate, GraphWalkResolver::newerOf);
            if (previous != null) {
                // The promised arbitration LOG (11.2): duplicate symbolic names
                // are decided deterministically, and the decision is visible.
                log.debug("pool arbitration: {} {} vs {} -> {}",
                    f.symbolicName(), version(previous), version(candidate),
                    version(byName.get(f.symbolicName())));
            }
        }
        // Highest-version fragment per host, platform-filtered above.
        for (Map.Entry<String, List<PoolBundle>> e : fragmentsByHost.entrySet()) {
            Map<String, PoolBundle> bySuffix = new LinkedHashMap<>();
            for (PoolBundle frag : e.getValue()) {
                bySuffix.merge(frag.facts().symbolicName(), frag, GraphWalkResolver::newerOf);
            }
            e.setValue(new ArrayList<>(bySuffix.values()));
        }
        // Packages DERIVE from the winners — the two maps can no longer
        // disagree (the old pool merged names by highest version while
        // packages kept first-seen; measured answering with two different
        // jars for one bundle).
        Map<String, PoolBundle> byPackage = new HashMap<>();
        for (PoolBundle winner : byName.values()) {
            for (String pkg : winner.facts().exportedPackages()) {
                byPackage.merge(pkg, winner, GraphWalkResolver::newerOf);
            }
        }
        return new Selection(byName, byPackage, fragmentsByHost);
    }

    private static PoolBundle newerOf(PoolBundle a, PoolBundle b) {
        return compareVersions(version(a), version(b)) >= 0 ? a : b;
    }

    private static String version(PoolBundle b) {
        return b.facts().version().orElse("0");
    }

    // ------------------------------------------------------------------
    // wiring one bundle
    // ------------------------------------------------------------------

    private Wiring wire(String name, BundleFacts facts,
                        Map<String, BundleFacts> workspace, Selection selection) {
        List<Provider> providers = new ArrayList<>();
        List<UnresolvedReport> unresolved = new ArrayList<>();
        Set<String> seenBundles = new LinkedHashSet<>();
        Set<Path> seenJars = new LinkedHashSet<>();
        seenBundles.add(name); // never wire a bundle to itself through a cycle

        for (OsgiHeaders.Requirement req : facts.requiredBundles()) {
            requireBundle(req, workspace, selection, providers, unresolved,
                seenBundles, seenJars, false);
        }
        for (String pkg : facts.importedPackages()) {
            importPackage(pkg, workspace, selection, providers, unresolved, seenBundles, seenJars);
        }
        return new Wiring(name, List.copyOf(providers), List.copyOf(unresolved));
    }

    /**
     * Satisfy one Require-Bundle clause; on a reexporting provider, walk its
     * reexported requirements too (visited-set — cycles are data, not errors).
     */
    private void requireBundle(OsgiHeaders.Requirement req,
                               Map<String, BundleFacts> workspace,
                               Selection selection,
                               List<Provider> providers,
                               List<UnresolvedReport> unresolved,
                               Set<String> seenBundles,
                               Set<Path> seenJars,
                               boolean viaReexport) {
        if (!seenBundles.add(req.name())) {
            return; // already wired on this walk (or the bundle itself)
        }
        BundleFacts sibling = workspace.get(req.name());
        if (sibling != null) {
            // WORKSPACE WINS — and the floor is deliberately not enforced
            // against a sibling: the developer's checked-out source IS the
            // version under work, whatever its manifest says.
            providers.add(Provider.ofWorkspace(req.name(), viaReexport));
            walkReexports(sibling, workspace, selection, providers, unresolved,
                seenBundles, seenJars);
            return;
        }
        PoolBundle jar = selection.winnerByName().get(req.name());
        if (jar != null && satisfiesFloor(jar, req.versionFloor())) {
            addJar(jar, selection, providers, seenJars, viaReexport);
            walkReexports(jar.facts(), workspace, selection, providers, unresolved,
                seenBundles, seenJars);
            return;
        }
        // Optional misses REPORT (13.1's flagged default: excluding them
        // changes studio-visible numbers; the shape is unchanged either way).
        unresolved.add(new UnresolvedReport("Require-Bundle", req.name(),
            jar == null
                ? "no workspace project registers this symbolic name, and no jar in the "
                    + "external bundle pools declares it"
                : "the pools' best version " + version(jar) + " does not satisfy the declared "
                    + "floor " + req.versionFloor().orElse("(none)")));
    }

    private void walkReexports(BundleFacts provider,
                               Map<String, BundleFacts> workspace,
                               Selection selection,
                               List<Provider> providers,
                               List<UnresolvedReport> unresolved,
                               Set<String> seenBundles,
                               Set<Path> seenJars) {
        for (OsgiHeaders.Requirement transitive : provider.requiredBundles()) {
            if (transitive.reexport()) {
                requireBundle(transitive, workspace, selection, providers, unresolved,
                    seenBundles, seenJars, true);
            }
        }
    }

    private void importPackage(String pkg,
                               Map<String, BundleFacts> workspace,
                               Selection selection,
                               List<Provider> providers,
                               List<UnresolvedReport> unresolved,
                               Set<String> seenBundles,
                               Set<Path> seenJars) {
        for (Map.Entry<String, BundleFacts> sibling : workspace.entrySet()) {
            if (sibling.getValue().exportedPackages().contains(pkg)) {
                if (seenBundles.add(sibling.getKey())) {
                    providers.add(Provider.ofWorkspace(sibling.getKey(), false));
                }
                return;
            }
        }
        PoolBundle jar = selection.winnerByExportedPackage().get(pkg);
        if (jar != null) {
            addJar(jar, selection, providers, seenJars, false);
            return;
        }
        unresolved.add(new UnresolvedReport("Import-Package", pkg,
            "no workspace project and no jar in the external bundle pools exports this package"));
    }

    /** A jar provider brings its platform-matched fragments along (R9/#11: SWT's classes live there). */
    private void addJar(PoolBundle jar, Selection selection, List<Provider> providers,
                        Set<Path> seenJars, boolean reexported) {
        if (seenJars.add(jar.jar())) {
            providers.add(Provider.ofJar(jar.jar(), reexported));
        }
        for (PoolBundle fragment : selection.fragmentsByHost()
                .getOrDefault(jar.facts().symbolicName(), List.of())) {
            if (hostFloorSatisfied(fragment, jar) && seenJars.add(fragment.jar())) {
                providers.add(Provider.ofJar(fragment.jar(), reexported));
            }
        }
    }

    // ------------------------------------------------------------------
    // fragments (R9)
    // ------------------------------------------------------------------

    /** LDAP platform filter when declared; symbolic-name suffix as the fallback. */
    static boolean fragmentFits(BundleFacts fragment, Platform platform) {
        Optional<String> filter = fragment.platformFilter();
        if (filter.isPresent()) {
            String f = filter.get().replace(" ", "");
            return f.contains("osgi.os=" + platform.os())
                && f.contains("osgi.ws=" + platform.ws())
                && f.contains("osgi.arch=" + platform.arch());
        }
        // No filter: a platform-suffixed name (…gtk.linux.x86_64) must match
        // the triple; an unsuffixed fragment is platform-neutral and fits.
        String name = fragment.symbolicName();
        boolean namesAnyPlatform = name.matches(".*\\.(gtk|win32|cocoa)\\..*");
        return !namesAnyPlatform
            || name.endsWith("." + platform.ws() + "." + platform.os() + "." + platform.arch());
    }

    private static boolean hostFloorSatisfied(PoolBundle fragment, PoolBundle host) {
        Optional<String> floor = fragment.facts().fragmentHost().flatMap(OsgiHeaders.Requirement::versionFloor);
        return floor.isEmpty() || compareVersions(version(host), floor.get()) >= 0;
    }

    private static boolean satisfiesFloor(PoolBundle jar, Optional<String> floor) {
        return floor.isEmpty() || compareVersions(version(jar), floor.get()) >= 0;
    }

    /** Numeric-segment version comparison; a missing segment is zero, a qualifier ignored. */
    static int compareVersions(String a, String b) {
        String[] as = a.split("\\.");
        String[] bs = b.split("\\.");
        for (int i = 0; i < 3; i++) {
            int ai = segment(as, i);
            int bi = segment(bs, i);
            if (ai != bi) {
                return Integer.compare(ai, bi);
            }
        }
        return 0;
    }

    private static int segment(String[] parts, int i) {
        if (i >= parts.length) {
            return 0;
        }
        try {
            return Integer.parseInt(parts[i].replaceAll("[^0-9].*$", ""));
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
