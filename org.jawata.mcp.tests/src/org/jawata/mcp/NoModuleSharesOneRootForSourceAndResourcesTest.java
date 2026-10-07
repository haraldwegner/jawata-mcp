package org.jawata.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 28g F-28g-EDITOR — a module may not declare one directory as BOTH a source root and a
 * resource root, because Maven accepts that and an Eclipse build path cannot represent it.
 *
 * <h2>What it cost, which is why this exists at all</h2>
 *
 * <p>Sprint 28g S8 ({@code 64628c24}) added {@code org.jawata.catalogue} and built it from
 * {@code build/catalogue/pom.xml}, which declared {@code ../../org.jawata.catalogue/src} as
 * its {@code sourceDirectory} AND as its resource {@code directory}, excluding the sources —
 * the obvious way to ship a data file that lives beside the classes. Maven builds that
 * correctly and the jar is right. An Eclipse build path cannot hold two source folders at one
 * location, so the Maven-to-Eclipse integration could not configure that module, and every
 * {@code org.jawata.catalogue} reference read as UNRESOLVED in a JDT-backed editor — including
 * inside {@code JavaEngineCoverage}, the deliverable of the same stage. It shipped that way for
 * three days.</p>
 *
 * <h2>Why no existing gate could see it, which is the part worth keeping</h2>
 *
 * <p>{@code compile_workspace} is clean, because jawata compiles every module's source on ONE
 * classpath and never consults a build path. The suite is green, because the jar is correct.
 * The checkpoint audits read the artifact. <b>The whole instrument set is blind to
 * editor-representability by construction</b> — so the failure is invisible to everything
 * except opening the project in an editor, which no gate does. Of the nine modules under
 * {@code build/} exactly one had the overlap and exactly one could not be resolved.</p>
 *
 * <h2>What this does NOT cover, said so nobody reads it as wider</h2>
 *
 * <p>It checks ONE representable-build-path property: a declared resource root equal to a
 * declared source root, in either the main or the test pair. Nested source roots, and an output
 * directory colliding with a source directory, are the same family and are NOT checked here. The
 * general statement the measurement supports is that Maven's build model is strictly wider than
 * the Eclipse build path, and this closes the one corner of it that has actually bitten.</p>
 */
class NoModuleSharesOneRootForSourceAndResourcesTest {

    /**
     * The modules this check cannot do without, named rather than counted — the same device
     * {@code EveryCrossBundlePackageIsImportedTest} uses and for the same reason.
     *
     * <p>{@code catalogue} is here because it is the module the defect was found on, and
     * {@code mcp} because it is the module that could not see it. Lose either from the
     * derivation — a pom moved, a directory renamed — and this check would go on passing while
     * no longer reading the two poms it is about.</p>
     */
    private static final List<String> REQUIRED = List.of("build/catalogue", "build/mcp");

    private static final String SOURCE_DIRECTORY = "sourceDirectory";

    private static final String TEST_SOURCE_DIRECTORY = "testSourceDirectory";

    @Test
    @DisplayName("no module declares one directory as both a source root and a resource root")
    void noModuleSharesOneRootForSourceAndResources() {
        Path root = repoRootOfThisCheckout();
        List<Path> poms = modulePoms(root);

        for (String required : REQUIRED) {
            assertTrue(
                poms.stream().anyMatch(p -> root.relativize(p.getParent()).toString().equals(required)),
                () -> "the derivation lost a module this check cannot do without: expected "
                    + required + "/pom.xml under " + root + "; found "
                    + poms.stream().map(p -> root.relativize(p.getParent()).toString()).toList()
                    + " — left alone this check would pass without reading the poms it is about");
        }

        Map<String, int[]> declaredBy = new LinkedHashMap<>();
        List<String> collisions = new ArrayList<>();

        for (Path pom : poms) {
            String text = read(pom);
            Path base = pom.getParent();
            Map<String, Path> sources = new LinkedHashMap<>();
            putIfPresent(sources, SOURCE_DIRECTORY, single(text, SOURCE_DIRECTORY), base);
            putIfPresent(sources, TEST_SOURCE_DIRECTORY, single(text, TEST_SOURCE_DIRECTORY), base);

            List<Path> resources = new ArrayList<>();
            resources.addAll(resolveAll(directoriesIn(section(text, "resources")), base));
            resources.addAll(resolveAll(directoriesIn(section(text, "testResources")), base));

            declaredBy.put(root.relativize(pom.getParent()).toString(),
                           new int[] { sources.size(), resources.size() });

            for (Map.Entry<String, Path> source : sources.entrySet()) {
                for (Path resource : resources) {
                    if (!resource.equals(source.getValue())) {
                        continue;
                    }
                    collisions.add(
                        root.relativize(pom) + " declares its resource root at the same path as"
                            + " its " + source.getKey() + ":\n"
                            + "    " + source.getKey() + " : " + root.relativize(source.getValue()) + "\n"
                            + "    resource         : " + root.relativize(resource) + "\n"
                            + "  Maven accepts this and builds correctly; an Eclipse build path"
                            + " cannot hold two source folders at one location, so the module"
                            + " becomes unresolvable in a JDT-backed editor while this build stays"
                            + " green and says nothing. Give the resource a root of its own —"
                            + " build/mcp/pom.xml is the shape to copy.");
                }
            }
        }

        // THE VACUITY GUARD, keyed to the two modules this check is about rather than to a
        // count of them all.
        //
        // IT WAS A GLOBAL FLOOR OF THREE, AND A MUTATION PROVED THAT SLACK. Measured 2026-10-07
        // across every module pom: 10 source roots and 7 resource roots, not the 3 and 3 the
        // first version assumed from a five-module scan. So deleting BOTH of mcp's resource
        // roots still left five, the floor still passed, and the mutation aimed at it stayed
        // GREEN — the assertion was not dead, it was set too low to bind. A count over modules
        // this check does not care about can always be propped up by the ones it does not read.
        for (String required : REQUIRED) {
            int[] seen = declaredBy.getOrDefault(required, new int[] { 0, 0 });
            assertTrue(
                seen[0] > 0 && seen[1] > 0,
                () -> "the parse read no " + (seen[0] == 0 ? "source" : "resource")
                    + " root out of " + required + "/pom.xml (" + seen[0] + " source, "
                    + seen[1] + " resource) — that module declares both, so the comparison"
                    + " above looked at nothing for the module this check exists for. A failure"
                    + " of the check, not a clean result.");
        }

        assertTrue(collisions.isEmpty(), () -> String.join("\n\n", collisions));
    }

    // ---------------------------------------------------------------- reading the inputs

    /**
     * Deliberately NOT a third implementation of locating the checkout.
     *
     * <p>Two already exist — the code-source-anchored one in
     * {@code EveryCrossBundlePackageIsImportedTest} (mcp#53's lesson: a check that resolves its
     * input through the working directory is a check about its launcher) and a
     * fixtures-property one in {@code CatalogueAddressesOpenTest}, which anchors on a different
     * directory. This reuses the first, which is why its visibility was widened to the package
     * rather than its body copied. The two existing ones genuinely differ in anchor and in
     * start points, so they are RECORDED as a duplicate rather than merged here: merging them
     * would change the behaviour of two tests in closed checkpoints, which is not this
     * change's business.</p>
     */
    private static Path repoRootOfThisCheckout() {
        return EveryCrossBundlePackageIsImportedTest.repoRoot();
    }

    /**
     * Every module pom under {@code build/}, excluding build output.
     *
     * <p>{@code target/} and {@code bin/} are skipped because both carry copies: a pom is
     * packaged into {@code target/classes/META-INF/maven/}, and {@code build/tests-mcp/bin/}
     * holds a stray one. Judging a copy would report a collision against a path that is not a
     * build input.</p>
     */
    private static List<Path> modulePoms(Path root) {
        Path build = root.resolve("build");
        try (Stream<Path> walk = Files.walk(build)) {
            return walk.filter(p -> p.getFileName().toString().equals("pom.xml"))
                       .filter(p -> {
                           String rel = build.relativize(p).toString();
                           return !rel.contains("target/") && !rel.contains("bin/");
                       })
                       .sorted()
                       .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("could not enumerate the module poms under " + build, e);
        }
    }

    private static String read(Path pom) {
        try {
            return Files.readString(pom);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + pom, e);
        }
    }

    private static String single(String text, String element) {
        Matcher m = Pattern.compile("<" + element + ">([^<]*)</" + element + ">").matcher(text);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * The body of one named section, so {@code <directory>} is read only where it means a
     * resource root — a plugin configuration may carry that element with another meaning.
     */
    private static String section(String text, String element) {
        Matcher m = Pattern.compile("<" + element + ">(.*?)</" + element + ">", Pattern.DOTALL)
                           .matcher(text);
        return m.find() ? m.group(1) : "";
    }

    private static List<String> directoriesIn(String sectionBody) {
        List<String> found = new ArrayList<>();
        Matcher m = Pattern.compile("<directory>([^<]*)</directory>").matcher(sectionBody);
        while (m.find()) {
            found.add(m.group(1).trim());
        }
        return found;
    }

    private static void putIfPresent(Map<String, Path> into, String name, String raw, Path base) {
        if (raw != null && !raw.isBlank()) {
            into.put(name, resolve(raw, base));
        }
    }

    private static List<Path> resolveAll(List<String> raw, Path base) {
        List<Path> resolved = new ArrayList<>();
        for (String one : raw) {
            resolved.add(resolve(one, base));
        }
        return resolved;
    }

    /**
     * {@code ${project.basedir}} is the pom's own directory, and these poms point outside it,
     * so the result is normalized before being compared — {@code build/mcp/../../org.jawata.mcp/src}
     * and {@code org.jawata.mcp/src} are the same folder and must compare equal.
     */
    private static Path resolve(String declared, Path base) {
        String expanded = declared.replace("${project.basedir}", base.toString());
        return base.resolve(expanded).normalize();
    }
}
