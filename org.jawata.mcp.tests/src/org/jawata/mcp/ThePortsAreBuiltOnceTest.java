package org.jawata.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * THE PORTS THE TOOLS HOLD COME FROM THE PROVIDER, NOT FROM A SECOND CONSTRUCTION (Sprint 28k, E12, ledger 28k-C3-8).
 *
 * <p>At C3 the Java provider built memory's anchors and the test runtime, and the tools built their own beside it: production code
 * called {@code JavaSymbolAnchors.over} in four places and constructed a {@code JavaTestRuntime} in three, so a second language
 * provider could not have replaced them without finding every one. The measure E12 closes is one of each, inside
 * {@code JavaProvider}. This reads the production sources and holds it there.</p>
 *
 * <p>Comment lines are not counted: a javadoc that names the call is not a caller.</p>
 */
class ThePortsAreBuiltOnceTest {

    private static final String PROVIDER = "org/jawata/mcp/java/JavaProvider.java";

    /** A call of the static {@code JavaSymbolAnchors.over}, qualified or not, directly or as a method reference. */
    private static final Pattern ANCHORS_BUILT = Pattern.compile("JavaSymbolAnchors(\\.|::)over\\b");

    /** A construction of {@code JavaTestRuntime}, qualified or not. */
    private static final Pattern RUNTIME_BUILT = Pattern.compile("new\\s+([\\w.]+\\.)?JavaTestRuntime\\s*\\(");

    /** The production files under {@code src} with a code line containing a match, relative and slash-separated. */
    private static TreeSet<String> filesWithACodeLineContaining(Path src, Pattern pattern) throws IOException {
        TreeSet<String> files = new TreeSet<>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator) {
                for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                    String code = line.strip();
                    if (!code.startsWith("*") && !code.startsWith("//") && !code.startsWith("/*") && pattern.matcher(code).find()) {
                        files.add(src.relativize(file).toString().replace('\\', '/'));
                    }
                }
            }
        }
        return files;
    }

    private static Path productionSources() {
        return EveryCrossBundlePackageIsImportedTest.repoRoot().resolve("org.jawata.mcp").resolve("src");
    }

    @Test
    @DisplayName("memory's anchors are built by the provider alone: JavaSymbolAnchors.over has one production caller")
    void memorysAnchorsAreBuiltByTheProviderAlone() throws IOException {
        assertEquals(new TreeSet<>(List.of(PROVIDER)),
            filesWithACodeLineContaining(productionSources(), ANCHORS_BUILT));
    }

    @Test
    @DisplayName("the test runtime is built by the provider alone: new JavaTestRuntime has one production caller")
    void theTestRuntimeIsBuiltByTheProviderAlone() throws IOException {
        assertEquals(new TreeSet<>(List.of(PROVIDER)),
            filesWithACodeLineContaining(productionSources(), RUNTIME_BUILT));
    }

    @Test
    @DisplayName("control: a second construction is seen, qualified or not, and a comment that names it is not")
    void aSecondConstructionIsSeenAndACommentIsNot(@TempDir Path src) throws IOException {
        Files.createDirectories(src.resolve("a"));
        Files.writeString(src.resolve("a/Provider.java"), "class Provider { Object p() { return new JavaTestRuntime(m); } }\n");
        Files.writeString(src.resolve("a/Tool.java"), "class Tool { Object t() { return new JavaTestRuntime(m); } }\n");
        Files.writeString(src.resolve("a/Qualified.java"),
            "class Qualified { Object q() { return new org.jawata.mcp.java.JavaTestRuntime(m); } }\n");
        Files.writeString(src.resolve("a/Doc.java"), "/**\n * built by new JavaTestRuntime(m) in the provider\n */\nclass Doc { }\n");

        assertEquals(new TreeSet<>(List.of("a/Provider.java", "a/Qualified.java", "a/Tool.java")),
            filesWithACodeLineContaining(src, RUNTIME_BUILT));
    }
}
