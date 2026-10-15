package org.jawata.mcp.coverage;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * REL44-19 — a nested type named the way the source names it finds its class file.
 *
 * <p><b>The case.</b> Asked which tests reach {@code ExperienceRetrieval.GateTally} and its members,
 * the impacted-tests answer said NONE — while the test it should have named executes both members
 * on every check it makes. The lookup turned the name into a path by replacing every dot with a
 * slash, so {@code …ExperienceRetrieval.GateTally} became {@code …/ExperienceRetrieval/GateTally.class};
 * the compiler writes {@code …/ExperienceRetrieval$GateTally.class}. A type that is not found is a
 * type no evidence is read for, and "reaches no test" was the answer to a question nobody asked.</p>
 *
 * <p>The source form is the one people and agents write — {@code search_symbols}, briefs, the FQN
 * a finding names — so it is the one that must work, beside the binary form with {@code $}.</p>
 */
class ANestedTypeIsFoundByItsSourceNameTest {

    @Test
    void aNestedTypeResolvesFromItsSourceNameAndItsBinaryName(@TempDir Path root) throws Exception {
        Path outer = classFile(root, "a/b/Outer.class");
        Path inner = classFile(root, "a/b/Outer$Inner.class");
        Path deep = classFile(root, "a/b/Outer$Inner$Deep.class");
        CoverageManifest manifest = new CoverageManifest();
        manifest.classRoots = List.of(root.toString());

        assertAll(
            () -> assertEquals(inner, CoverageAttribution.classFile(manifest, "a.b.Outer.Inner"),
                "the source name of a nested type finds the class the compiler wrote"),
            () -> assertEquals(deep, CoverageAttribution.classFile(manifest, "a.b.Outer.Inner.Deep"),
                "and so does a type nested two deep"),
            () -> assertEquals(inner, CoverageAttribution.classFile(manifest, "a.b.Outer$Inner"),
                "the binary name still works"),
            () -> assertEquals(outer, CoverageAttribution.classFile(manifest, "a.b.Outer"),
                "a top-level type is unchanged"),
            () -> assertNull(CoverageAttribution.classFile(manifest, "a.b.Missing"),
                "a type with no class file is still not found"));
    }

    private static Path classFile(Path root, String relative) throws Exception {
        Path file = root.resolve(relative);
        Files.createDirectories(file.getParent());
        Files.write(file, new byte[] {(byte) 0xCA, (byte) 0xFE});
        return file;
    }
}
