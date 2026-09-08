package org.jawata.mcp;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#19 — a package one bundle uses from another must be IMPORTED, and no test could see it.
 *
 * <p><b>Why the suite is structurally blind to this.</b> {@code org.jawata.core.tests} and
 * {@code org.jawata.mcp.tests} are FRAGMENTS of their hosts. A fragment's classes share the
 * host's classloader, so they resolve packages the real bundle cannot. That is not a gap in
 * coverage that more tests would close: 1812 tests passed against a product that could not
 * start, because every one of them ran on a classloader the shipped bundle does not have.
 * A package added to {@code org.jawata.core}'s {@code Export-Package} and never added to
 * {@code org.jawata.mcp}'s {@code Import-Package} threw {@code NoClassDefFoundError} on the
 * first tool call and was caught only at the release gate.
 *
 * <p><b>This check needs no OSGi runtime</b>, which is the point: it is derivable from the
 * sources and the manifests, so it runs in the suite rather than at release time.
 *
 * <p><b>Scope, stated rather than implied.</b> It judges {@code org.jawata.*} packages only —
 * the ones this repository owns and can get wrong by adding a package on one side of a bundle
 * boundary. Third-party packages ({@code org.eclipse.*}, {@code com.fasterxml.*}) reach the
 * bundles through {@code Require-Bundle} on the platform, whose manifests live in jars this
 * check does not open; a missing one of those fails at build time rather than silently at
 * boot. The reported defect was one of ours, and so is every defect of this shape that the
 * fragment arrangement can hide.
 */
class EveryCrossBundlePackageIsImportedTest {

    /** The real, non-fragment bundles. A fragment is excluded BECAUSE it shares its host's
     *  classloader — which is the very reason this defect is invisible to the suite. */
    private static final List<String> BUNDLES = List.of("org.jawata.core", "org.jawata.mcp");

    private static final Pattern PACKAGE_DECL =
        Pattern.compile("^\\s*package\\s+([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
    private static final Pattern IMPORT_DECL =
        Pattern.compile("^\\s*import\\s+(?:static\\s+)?([a-zA-Z0-9_.]+)\\s*;", Pattern.MULTILINE);
    /**
     * A fully-qualified use, anywhere in the file. Java does not require an import to use a
     * type: {@code org.jawata.core.host.HostFs.deleteRecursively(p)} is a reference the OSGi
     * resolver must satisfy and no {@code import} line records. Measured when this was added:
     * FOUR core packages are reached that way from mcp and none by that route alone, so there
     * is no live hole — but a check whose name says "references" must see them, or it does not
     * hold the property it is named for.
     */
    private static final Pattern QUALIFIED_USE =
        Pattern.compile("\\b(org\\.jawata\\.[a-zA-Z0-9_.]+)");

    // ---------------------------------------------------------------- the predicate

    /**
     * The whole rule, as a pure function so it can be driven with inputs this repository does
     * not currently contain. Without that seam the only way to see this check fail would be to
     * break a real manifest — and this issue's own second trap is that a manifest-only edit
     * does not rebuild the jar, so such a "control" would prove nothing about the shipped
     * artifact.
     */
    static Set<String> missingImports(Set<String> referenced, Set<String> own,
                                      Set<String> imported, Set<String> viaRequireBundle) {
        return referenced.stream()
            .filter(p -> !own.contains(p))
            .filter(p -> !imported.contains(p))
            .filter(p -> !viaRequireBundle.contains(p))
            .collect(Collectors.toCollection(TreeSet::new));
    }

    @Test
    @DisplayName("the rule itself: only a package that is foreign, unimported and unrequired is missing")
    void thePredicateDiscriminates() {
        Set<String> referenced = Set.of("org.jawata.core.host", "org.jawata.core.search",
            "org.jawata.mcp.tools", "org.jawata.core.resolve");
        assertEquals(Set.of("org.jawata.core.resolve"), missingImports(
            referenced,
            Set.of("org.jawata.mcp.tools"),          // its own package
            Set.of("org.jawata.core.host"),          // imported outright
            Set.of("org.jawata.core.search")));      // reached through Require-Bundle

        // CONTROL: with the same input and nothing declared, EVERY foreign package is missing —
        // so the assertion above is discriminating rather than trivially empty.
        assertEquals(
            new TreeSet<>(Set.of("org.jawata.core.host", "org.jawata.core.resolve",
                "org.jawata.core.search")),
            missingImports(referenced, Set.of("org.jawata.mcp.tools"), Set.of(), Set.of()));
    }

    // ---------------------------------------------------------------- the real check

    @Test
    @DisplayName("every org.jawata package a bundle references is its own, imported, or required")
    void everyCrossBundlePackageIsDeclared() throws Exception {
        Path repo = repoRoot();
        Map<String, Set<String>> ownPackages = new java.util.LinkedHashMap<>();
        for (String bundle : BUNDLES) {
            ownPackages.put(bundle, packagesDeclaredIn(repo.resolve(bundle).resolve("src")));
        }
        Set<String> allOurPackages = ownPackages.values().stream()
            .flatMap(Set::stream).collect(Collectors.toSet());
        assertFalse(allOurPackages.isEmpty(),
            "PROOF OF LIFE: no org.jawata packages were found at all under " + repo
                + " — the sources were not read, so this check measured nothing");

        List<String> complaints = new ArrayList<>();
        for (String bundle : BUNDLES) {
            Manifest manifest = manifestOf(repo.resolve(bundle));
            Set<String> imported = headerPackages(manifest, "Import-Package");
            Set<String> required = headerPackages(manifest, "Require-Bundle");
            Set<String> viaRequire = new LinkedHashSet<>();
            for (String other : BUNDLES) {
                if (required.contains(other)) {
                    viaRequire.addAll(headerPackages(manifestOf(repo.resolve(other)), "Export-Package"));
                }
            }
            Set<String> referenced = ourPackagesReferencedIn(
                repo.resolve(bundle).resolve("src"), allOurPackages);

            // PROOF OF LIFE, per bundle and on the CROSS-BUNDLE axis specifically. The
            // emptiness check above only says some packages were read; it would still pass if
            // the import scan silently found no FOREIGN package at all, and then "nothing is
            // missing" would be true of a check that examined nothing. org.jawata.mcp really
            // does use org.jawata.core — that is the whole subject — so a bundle whose foreign
            // set came back empty means the scan broke, not that the bundle is self-contained.
            Set<String> foreign = new TreeSet<>(referenced);
            foreign.removeAll(ownPackages.get(bundle));
            if (bundle.equals("org.jawata.mcp")) {
                assertFalse(foreign.isEmpty(), "PROOF OF LIFE: " + bundle + " must be seen"
                    + " referencing packages from another bundle — it uses org.jawata.core"
                    + " throughout. An empty set means the import scan found nothing and this"
                    + " check is passing over a population of zero");
            }

            Set<String> missing = missingImports(
                referenced, ownPackages.get(bundle), imported, viaRequire);
            // ...and the other direction: an IMPORTED package that nothing exports. A mistyped
            // import name satisfies the check above — it is declared, after all — and then
            // fails to resolve at boot, which is the same outage from the opposite side.
            Set<String> exportedByUs = new LinkedHashSet<>();
            for (String other : BUNDLES) {
                exportedByUs.addAll(headerPackages(manifestOf(repo.resolve(other)), "Export-Package"));
            }
            Set<String> importedButUnexported = new TreeSet<>(imported);
            importedButUnexported.removeAll(exportedByUs);
            if (!importedButUnexported.isEmpty()) {
                complaints.add(bundle + " imports " + importedButUnexported + ", which no"
                    + " org.jawata bundle EXPORTS — a name that is declared and still cannot"
                    + " resolve at boot. Check the spelling against the owning bundle's"
                    + " Export-Package.");
            }

            if (!missing.isEmpty()) {
                complaints.add(bundle + " uses " + missing + " but neither declares them nor"
                    + " imports them. Add each to Import-Package in " + bundle
                    + "/META-INF/MANIFEST.MF — and note that a manifest-only edit does NOT"
                    + " rebuild the jar (maven-jar-plugin does not track manifestFile as an"
                    + " input), so run `mvn clean` or the fix ships stale.");
            }
        }
        assertTrue(complaints.isEmpty(), String.join("\n", complaints));
    }

    // ---------------------------------------------------------------- reading the inputs

    /**
     * The repository root, from the test bundle's OWN location first — mcp#53's lesson: a
     * check that resolves its input through the working directory is a check about its
     * launcher. The working directory stays as a fallback for an IDE or plain-Maven run.
     */
    private static Path repoRoot() {
        List<Path> starts = new ArrayList<>();
        try {
            var source = EveryCrossBundlePackageIsImportedTest.class
                .getProtectionDomain().getCodeSource();
            if (source != null && source.getLocation() != null) {
                Path here = Path.of(source.getLocation().toURI());
                starts.add(Files.isRegularFile(here) ? here.getParent() : here);
            }
        } catch (Exception e) {
            // fall through to the working directory
        }
        starts.add(Path.of("").toAbsolutePath());
        List<String> tried = new ArrayList<>();
        for (Path start : starts) {
            for (Path dir = start; dir != null; dir = dir.getParent()) {
                tried.add(dir.toString());
                if (Files.isDirectory(dir.resolve("org.jawata.core").resolve("META-INF"))) {
                    return dir;
                }
            }
        }
        throw new AssertionError("could not find the repository root; looked in: " + tried);
    }

    private static Manifest manifestOf(Path bundleDir) throws Exception {
        try (InputStream in = Files.newInputStream(bundleDir.resolve("META-INF/MANIFEST.MF"))) {
            return new Manifest(in);
        }
    }

    /**
     * The org.jawata entries of an OSGi header. Each clause is {@code name;attr=...}, comma
     * separated — but a comma inside a quoted attribute (a version range like
     * {@code "[1.0,2.0)"}) does NOT separate clauses, which is why this splits on quoting
     * rather than on commas alone.
     */
    private static Set<String> headerPackages(Manifest manifest, String header) {
        String value = manifest.getMainAttributes().getValue(header);
        if (value == null) {
            return Set.of();
        }
        Set<String> names = new LinkedHashSet<>();
        StringBuilder clause = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i <= value.length(); i++) {
            char c = i < value.length() ? value.charAt(i) : ',';
            if (c == '"') {
                quoted = !quoted;
            }
            if (c == ',' && !quoted) {
                String name = clause.toString().split(";")[0].trim();
                if (name.startsWith("org.jawata")) {
                    names.add(name);
                }
                clause.setLength(0);
            } else {
                clause.append(c);
            }
        }
        return names;
    }

    private static Set<String> packagesDeclaredIn(Path src) throws Exception {
        return scan(src, PACKAGE_DECL, (pkg, all) -> pkg.startsWith("org.jawata") ? pkg : null);
    }

    /**
     * Which of OUR packages this source tree USES — through an import, and through a
     * fully-qualified name, which needs no import at all. A reference names a TYPE, so the
     * package is the longest prefix that is actually a package we declare — computing it that
     * way rather than "drop the last segment" is what keeps a nested type or a static import
     * from inventing a package that does not exist and failing this check for nothing.
     */
    private static Set<String> ourPackagesReferencedIn(Path src, Set<String> allOurPackages)
            throws Exception {
        Pick longestPackagePrefix = (used, all) -> {
            if (!used.startsWith("org.jawata")) {
                return null;
            }
            String best = null;
            for (String candidate : all) {
                if (used.startsWith(candidate + ".")
                        && (best == null || candidate.length() > best.length())) {
                    best = candidate;
                }
            }
            return best;
        };
        Set<String> referenced = new TreeSet<>(scan(src, IMPORT_DECL, longestPackagePrefix, allOurPackages));
        // ...and the uses no import line records. A package named only in a COMMENT would be
        // demanded here too; that is the direction to err in, and the failure names the package
        // so a reader can see which it is. Measured when this was added: widening produced no
        // new finding, so nothing is currently being demanded that is not genuinely used.
        referenced.addAll(scan(src, QUALIFIED_USE, longestPackagePrefix, allOurPackages));
        return referenced;
    }

    /**
     * The file with comments and string literals blanked out.
     *
     * <p>WITHOUT THIS THE WIDENED SCAN IS WRONG, and it was — measured the moment it ran.
     * {@code org.jawata.core} carries two javadoc {@code @link}s pointing UP at
     * {@code org.jawata.mcp}, which is perfectly legitimate prose and not a reference at all;
     * the check demanded core import them. A comment is not a use.
     *
     * <p>Deliberately naive, and safe in the direction it errs: this hunts fully-qualified
     * TYPE references, so anything inside a comment or a string is prose either way, and
     * over-stripping can only lose a "reference" that was never one. Characters are replaced
     * rather than deleted so offsets and line structure survive.
     */
    static String codeOnly(String text) {
        StringBuilder out = new StringBuilder(text.length());
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            char next = i + 1 < text.length() ? text.charAt(i + 1) : '\0';
            if (c == '/' && next == '/') {
                while (i < text.length() && text.charAt(i) != '\n') { out.append(' '); i++; }
            } else if (c == '/' && next == '*') {
                out.append("  "); i += 2;
                while (i < text.length()
                        && !(text.charAt(i) == '*' && i + 1 < text.length() && text.charAt(i + 1) == '/')) {
                    out.append(text.charAt(i) == '\n' ? '\n' : ' '); i++;
                }
                if (i < text.length()) { out.append("  "); i += 2; }
            } else if (c == '"' || c == '\'') {
                char quote = c;
                out.append(' '); i++;
                while (i < text.length() && text.charAt(i) != quote) {
                    if (text.charAt(i) == '\\' && i + 1 < text.length()) { out.append(' '); i++; }
                    out.append(text.charAt(i) == '\n' ? '\n' : ' '); i++;
                }
                if (i < text.length()) { out.append(' '); i++; }
            } else {
                out.append(c); i++;
            }
        }
        return out.toString();
    }

    @Test
    @DisplayName("a package named only in a comment or a string is not a reference")
    void proseIsNotAReference() {
        String source = """
            package com.example;
            // org.jawata.core.commented
            /** {@link org.jawata.core.javadoc} */
            class A {
                String s = "org.jawata.core.stringy";
                void m() { org.jawata.core.real.Thing.use(); }
            }
            """;
        String code = codeOnly(source);
        assertAll(
            () -> assertFalse(code.contains("org.jawata.core.commented"), "a line comment is not a use"),
            () -> assertFalse(code.contains("org.jawata.core.javadoc"), "a javadoc @link is not a use"),
            () -> assertFalse(code.contains("org.jawata.core.stringy"), "a string literal is not a use"),
            // CONTROL: the real reference must survive, or this helper would make the whole
            // check vacuous by blanking everything.
            () -> assertTrue(code.contains("org.jawata.core.real.Thing"), "a real reference survives"));
    }

    private interface Pick { String of(String match, Set<String> context); }

    private static Set<String> scan(Path src, Pattern pattern, Pick pick) throws Exception {
        return scan(src, pattern, pick, Set.of());
    }

    private static Set<String> scan(Path src, Pattern pattern, Pick pick, Set<String> context)
            throws Exception {
        Set<String> found = new TreeSet<>();
        if (!Files.isDirectory(src)) {
            return found;
        }
        try (Stream<Path> files = Files.walk(src)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = codeOnly(Files.readString(file, StandardCharsets.UTF_8));
                Matcher m = pattern.matcher(text);
                while (m.find()) {
                    String picked = pick.of(m.group(1), context);
                    if (picked != null) {
                        found.add(picked);
                    }
                }
            }
        }
        return found;
    }
}
