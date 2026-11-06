package org.jawata.mcp.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, contract §4.2 (ledger 28k-C5-15): {@link ChildJvm} is the only place that reads {@code java.home}. A site that reads it
 * itself answers with the engine's runtime whatever the host says the project runs on, which is the defect this resolver exists to end,
 * and nothing but a scan of the source notices a new one: the command line it builds looks right on the machine of whoever wrote it.
 */
class NoJavaHomeReadOutsideTheResolverTest {

    /** The engine's production source roots, relative to the repository root. */
    private static final List<String> PRODUCTION_ROOTS = List.of("org.jawata.core/src", "org.jawata.spi/src", "org.jawata.mcp/src");

    /** The one file that may name the property. */
    private static final String RESOLVER = "org/jawata/mcp/runtime/ChildJvm.java";

    private static Path repositoryRoot() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null; candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("org.jawata.mcp/src"))) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    @DisplayName("no production file but the resolver names the java.home property")
    void onlyTheResolverReadsJavaHome() throws IOException {
        Path root = repositoryRoot();
        assumeTrue(root != null, "sources are not present in this run: the rule is UNPROVEN here, not passing");

        List<String> offenders = new ArrayList<>();
        for (String relative : PRODUCTION_ROOTS) {
            Path dir = root.resolve(relative);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                for (Path file : (Iterable<Path>) walk.filter(p -> p.getFileName().toString().endsWith(".java"))::iterator) {
                    if (file.toString().replace('\\', '/').endsWith(RESOLVER)) {
                        continue;
                    }
                    if (Files.readString(file, StandardCharsets.UTF_8).contains("\"java.home\"")) {
                        offenders.add(root.relativize(file).toString());
                    }
                }
            }
        }

        assertEquals(List.of(), offenders,
            "these production files read java.home themselves. Ask org.jawata.mcp.runtime.ChildJvm, by the name of the project the JVM is "
                + "started for (or with none, for a program that is not a launch of a project's JVM): the host says which runtime a project runs on.");
    }
}
