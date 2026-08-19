package org.jawata.core.resolve;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

/**
 * Everything the workspace resolver needs to know about one bundle, read from
 * its manifest ONCE — pure data, no JDT, no resolution.
 *
 * <p>The Inventory half of the Inventory → Resolve → Apply pipeline
 * ({@code ARCHITECTURE-workspace-resolution.md}). Facts are parsed at
 * project-add and cached; the resolver reads the COMPLETE set, which is what
 * removes the order-dependence that produced the measured 431 (a project
 * could only see siblings registered before it).</p>
 *
 * @param symbolicName    the bundle identity ({@code Bundle-SymbolicName}, directives stripped)
 * @param version         {@code Bundle-Version}, or empty
 * @param requiredBundles {@code Require-Bundle} clauses with floors and directives KEPT
 * @param importedPackages {@code Import-Package} names ({@code java.*} excluded — the JRE
 *                        container provides those)
 * @param exportedPackages {@code Export-Package} names
 * @param fragmentHost    {@code Fragment-Host}, when this bundle is a fragment
 * @param bundleClassPath {@code Bundle-ClassPath} entries as declared ({@code .} included)
 * @param platformFilter  {@code Eclipse-PlatformFilter}, when declared
 */
public record BundleFacts(
    String symbolicName,
    Optional<String> version,
    List<OsgiHeaders.Requirement> requiredBundles,
    List<String> importedPackages,
    List<String> exportedPackages,
    Optional<OsgiHeaders.Requirement> fragmentHost,
    List<String> bundleClassPath,
    Optional<String> platformFilter
) {

    public BundleFacts {
        requiredBundles = List.copyOf(requiredBundles);
        importedPackages = List.copyOf(importedPackages);
        exportedPackages = List.copyOf(exportedPackages);
        bundleClassPath = List.copyOf(bundleClassPath);
    }

    /** Facts from a project directory's {@code META-INF/MANIFEST.MF}; empty when not a bundle. */
    public static Optional<BundleFacts> of(Path projectRoot) throws IOException {
        Path manifestPath = projectRoot.resolve("META-INF").resolve("MANIFEST.MF");
        if (!Files.isRegularFile(manifestPath)) {
            return Optional.empty();
        }
        try (InputStream in = Files.newInputStream(manifestPath)) {
            return of(new Manifest(in));
        }
    }

    /** Facts from an already-open manifest (jar or directory alike). */
    public static Optional<BundleFacts> of(Manifest manifest) {
        Attributes attrs = manifest.getMainAttributes();
        String rawName = attrs.getValue("Bundle-SymbolicName");
        if (rawName == null || rawName.isBlank()) {
            return Optional.empty();
        }
        return Optional.of(new BundleFacts(
            OsgiHeaders.nameOf(rawName),
            Optional.ofNullable(attrs.getValue("Bundle-Version")).map(String::trim),
            OsgiHeaders.requirements(attrs.getValue("Require-Bundle")),
            OsgiHeaders.names(attrs.getValue("Import-Package")).stream()
                .filter(p -> !p.startsWith("java.")).toList(),
            OsgiHeaders.names(attrs.getValue("Export-Package")),
            OsgiHeaders.requirements(attrs.getValue("Fragment-Host")).stream().findFirst(),
            OsgiHeaders.names(attrs.getValue("Bundle-ClassPath")),
            Optional.ofNullable(attrs.getValue("Eclipse-PlatformFilter")).map(String::trim)
        ));
    }
}
