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
 * {@code JavaProvider}. This reads the production sources and holds it there: a call of {@code over}, a construction of the runtime,
 * and a direct construction of the anchors (allowed in the provider and in the class's own factory), a construction being a
 * {@code new} or a constructor reference.</p>
 *
 * <p>Comment lines are not counted: a javadoc that names the call is not a caller.</p>
 */
class ThePortsAreBuiltOnceTest {

    private static final String PROVIDER = "org/jawata/mcp/java/JavaProvider.java";

    /** The class's own factory, which is where {@code over} builds the instance it hands out. */
    private static final String ANCHORS_FACTORY = "org/jawata/mcp/java/JavaSymbolAnchors.java";

    /** A call of the static {@code JavaSymbolAnchors.over}, qualified or not, directly or as a method reference. */
    private static final Pattern ANCHORS_BUILT = Pattern.compile("JavaSymbolAnchors(\\.|::)over\\b");

    /** A construction of {@code JavaSymbolAnchors}, qualified or not, by {@code new} or as a constructor reference. */
    private static final Pattern ANCHORS_CONSTRUCTED =
        Pattern.compile("new\\s+([\\w.]+\\.)?JavaSymbolAnchors\\s*\\(|JavaSymbolAnchors\\s*::\\s*new\\b");

    /** A construction of {@code JavaTestRuntime}, qualified or not, by {@code new} or as a constructor reference. */
    private static final Pattern RUNTIME_BUILT =
        Pattern.compile("new\\s+([\\w.]+\\.)?JavaTestRuntime\\s*\\(|JavaTestRuntime\\s*::\\s*new\\b");

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
    @DisplayName("the anchors are constructed by the provider and the class's own factory alone")
    void theAnchorsAreConstructedByTheProviderAndTheFactoryAlone() throws IOException {
        assertEquals(new TreeSet<>(List.of(PROVIDER, ANCHORS_FACTORY)),
            filesWithACodeLineContaining(productionSources(), ANCHORS_CONSTRUCTED));
    }

    @Test
    @DisplayName("control: a second construction is seen, qualified, by new or as a constructor reference, and a comment that names it is not")
    void aSecondConstructionIsSeenAndACommentIsNot(@TempDir Path src) throws IOException {
        Files.createDirectories(src.resolve("a"));
        Files.writeString(src.resolve("a/Provider.java"), "class Provider { Object p() { return new JavaTestRuntime(m); } }\n");
        Files.writeString(src.resolve("a/Tool.java"), "class Tool { Object t() { return new JavaTestRuntime(m); } }\n");
        Files.writeString(src.resolve("a/Qualified.java"),
            "class Qualified { Object q() { return new org.jawata.mcp.java.JavaTestRuntime(m); } }\n");
        Files.writeString(src.resolve("a/Reference.java"),
            "class Reference { java.util.function.Function<Object, Object> r = JavaTestRuntime::new; }\n");
        Files.writeString(src.resolve("a/Doc.java"), "/**\n * built by new JavaTestRuntime(m) in the provider\n */\nclass Doc { }\n");
        Files.writeString(src.resolve("a/AnchorsPlain.java"), "class AnchorsPlain { Object p() { return new JavaSymbolAnchors(s); } }\n");
        Files.writeString(src.resolve("a/AnchorsQualified.java"),
            "class AnchorsQualified { Object q() { return new org.jawata.mcp.java.JavaSymbolAnchors(s); } }\n");
        Files.writeString(src.resolve("a/AnchorsReference.java"),
            "class AnchorsReference { java.util.function.Function<Object, Object> r = JavaSymbolAnchors::new; }\n");
        Files.writeString(src.resolve("a/AnchorsOver.java"), "class AnchorsOver { Object o() { return JavaSymbolAnchors.over(m); } }\n");
        Files.writeString(src.resolve("a/AnchorsDoc.java"), "/**\n * built by new JavaSymbolAnchors(s) in the provider\n */\nclass AnchorsDoc { }\n");

        assertEquals(new TreeSet<>(List.of("a/Provider.java", "a/Qualified.java", "a/Reference.java", "a/Tool.java")),
            filesWithACodeLineContaining(src, RUNTIME_BUILT));
        assertEquals(new TreeSet<>(List.of("a/AnchorsPlain.java", "a/AnchorsQualified.java", "a/AnchorsReference.java")),
            filesWithACodeLineContaining(src, ANCHORS_CONSTRUCTED));
        assertEquals(new TreeSet<>(List.of("a/AnchorsOver.java")),
            filesWithACodeLineContaining(src, ANCHORS_BUILT));
    }
}
