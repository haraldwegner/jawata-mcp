package org.jawata.core.fixtures;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import java.util.stream.Stream;

/**
 * Jars for tests, GENERATED at test time — never committed.
 *
 * <p>The repo's global {@code *.jar} ignore rule has silently eaten committed
 * fixture jars four times (once failing a release run when a patched compiler
 * bundle was on disk but never in the commit). Generating at runtime removes
 * the class of failure instead of guarding it, and sidesteps Windows path
 * quirks in committed binary trees (plan Stage 11.0, risk R-git).</p>
 */
public final class TestJars {

    private TestJars() {
    }

    /**
     * Compile one class and jar it, with optional extra manifest headers.
     *
     * @param jar        where to write the jar (parent dirs created)
     * @param fqcn       the class to compile, e.g. {@code com.example.nested.one.FromFirstJar}
     * @param source     its full source text
     * @param headers    extra main-attribute headers (may be empty)
     */
    public static void classJar(Path jar, String fqcn, String source, Map<String, String> headers)
            throws IOException {
        Path work = Files.createTempDirectory("jawata-testjar-");
        try {
            Path src = work.resolve(fqcn.replace('.', '/') + ".java");
            Files.createDirectories(src.getParent());
            Files.writeString(src, source);
            JavaCompiler javac = ToolProvider.getSystemJavaCompiler();
            int rc = javac.run(null, null, null, "-d", work.toString(), src.toString());
            if (rc != 0) {
                throw new IOException("javac failed (" + rc + ") for " + fqcn);
            }
            Files.createDirectories(jar.getParent());
            try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar),
                    manifest(headers))) {
                try (Stream<Path> files = Files.walk(work)) {
                    for (Path p : files.filter(f -> f.toString().endsWith(".class")).toList()) {
                        out.putNextEntry(new JarEntry(
                            work.relativize(p).toString().replace('\\', '/')));
                        Files.copy(p, out);
                        out.closeEntry();
                    }
                }
            }
        } finally {
            deleteRecursively(work);
        }
    }

    /** A manifest-only bundle jar (pool fixtures: identity matters, content does not). */
    public static void bundleJar(Path jar, Map<String, String> headers) throws IOException {
        Files.createDirectories(jar.getParent());
        try (OutputStream out = Files.newOutputStream(jar);
                JarOutputStream jos = new JarOutputStream(out, manifest(headers))) {
            // manifest only
        }
    }

    /**
     * Place a bundle jar into a NESTED p2-style pool: {@code root/<name>/<version>/<name>-<version>.jar}
     * — the exact layout of {@code ~/.m2/repository/p2/osgi/bundle}, which the
     * current flat indexer cannot see (that blindness is what the 12.2 red
     * test pins).
     */
    public static Path nestedPoolBundle(Path poolRoot, String symbolicName, String version,
            Map<String, String> extraHeaders) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Bundle-ManifestVersion", "2");
        headers.put("Bundle-SymbolicName", symbolicName);
        headers.put("Bundle-Version", version);
        headers.putAll(extraHeaders);
        Path jar = poolRoot.resolve(symbolicName).resolve(version)
            .resolve(symbolicName + "-" + version + ".jar");
        bundleJar(jar, headers);
        return jar;
    }

    private static Manifest manifest(Map<String, String> headers) {
        Manifest m = new Manifest();
        m.getMainAttributes().putValue("Manifest-Version", "1.0");
        headers.forEach((k, v) -> m.getMainAttributes().putValue(k, v));
        return m;
    }

    private static void deleteRecursively(Path root) throws IOException {
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException e) {
                    // best effort — temp dir
                }
            });
        }
    }
}
