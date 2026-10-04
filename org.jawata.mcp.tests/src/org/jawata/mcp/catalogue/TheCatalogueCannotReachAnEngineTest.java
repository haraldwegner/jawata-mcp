package org.jawata.mcp.catalogue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C8's clause, verbatim: <i>a test proves the module cannot reach a language engine</i>.
 *
 * <h2>jawata-duplicate: this IS {@code EmbedPackagePurityTest}'s mechanism, as instructed</h2>
 *
 * <p>{@code ARCHITECTURE-principal-and-verticals.md} says it five times (lines 162, 212, 387,
 * 442, 475): <i>"a purity test per fenced bundle, each a copy of {@code EmbedPackagePurityTest}'s
 * mechanism — read the compiled <b>class files'</b> constant pool rather than the imports,
 * because a constant pool names every type a method body touches, which an import scan misses —
 * and each carrying the same positive control."</i> So this is deliberately a second
 * implementation of one idea, per fence, which is what the architecture asked for.</p>
 *
 * <p><b>The first version of this file was an import scan, and a C8 audit was right to refuse
 * it.</b> Three ways it was weaker than this, each measured rather than argued:</p>
 *
 * <ol>
 *   <li>a <b>fully-qualified reference in a method body</b> needs no import line, and the
 *       catalogue's own sibling writes exactly that idiom one file over
 *       ({@code JavaEngineCoverage} spells {@code org.jawata.catalogue.Catalogue.byKey(...)}
 *       in full);</li>
 *   <li><b>{@code import static}</b> escaped it — {@code startsWith("import org.jawata.mcp.")}
 *       is false of {@code import static org.jawata.mcp.…}, confirmed by running the predicate;</li>
 *   <li>its compensating pom check read <b>one file's text</b> and could not see a
 *       {@code <dependencies>} block <b>inherited from the parent</b>, which would put JDT on
 *       this module's compile classpath while leaving the child pom clean. That check is
 *       retired here: the artifact scan is immune to inheritance by construction, because it
 *       reads what was actually compiled.</li>
 * </ol>
 *
 * <h2>A WHITELIST, which is the one change from the precedent</h2>
 *
 * <p>{@code EmbedPackagePurityTest} denies {@code org/jawata/} minus its own package. This
 * allows {@code java/} and {@code org/jawata/catalogue/} and denies everything else — so a
 * sixth third-party dependency is a violation by default rather than by being remembered. The
 * import scan it replaces had a five-prefix deny-list, and this file's own neighbour records
 * why that fails: <i>a policy expressed as names fails open the day one is added, because no
 * reference-updating refactoring touches a string literal.</i> A whitelist cannot fail open.</p>
 *
 * <h2>What it cannot see, stated rather than left to be found</h2>
 *
 * <p>The scanned population is derived from the module's SOURCE FILES, so a nested or anonymous
 * class compiled to a {@code Foo$Bar.class} that no source file names is not scanned. The module
 * has none today — every type is top-level — and the count assertion below fails if the source
 * set changes without this being revisited.</p>
 */
class TheCatalogueCannotReachAnEngineTest {

    private static final String MODULE = "org.jawata.catalogue";

    /** The internal-name prefixes a shared table may reference. Everything else is a violation. */
    private static final List<String> ALLOWED = List.of(
            "java/",
            "org/jawata/catalogue/");

    @Test
    @DisplayName("no compiled catalogue class references anything but the JDK and itself")
    void noCompiledClassReachesAnEngine() {
        List<String> classes = moduleClasses();
        // PROOF OF LIFE: with an empty population every violation map below is empty and this
        // whole check certifies a module it never read.
        assertFalse(classes.isEmpty(), "derived no classes from " + moduleRoot());

        Map<String, List<String>> violations = new LinkedHashMap<>();
        for (String fqn : classes) {
            byte[] bytes = classBytes(fqn);
            assertNotNull(bytes, "class must be on the test classpath: " + fqn);
            List<String> bad = disallowedReferences(bytes);
            if (!bad.isEmpty()) {
                violations.put(fqn, bad);
            }
        }
        assertAll(
                () -> assertEquals(
                        Map.of(),
                        violations,
                        "the shared catalogue references something outside the JDK and itself."
                                + " The whole point of promoting this table out of the test tree"
                                + " is that a second language can declare against it; a row that"
                                + " needs a Java engine to be read is Java's table again"),
                () -> assertEquals(
                        5,
                        classes.size(),
                        "the module's type count moved. That is not a failure in itself — but"
                                + " this check derives its population from SOURCE files, so a"
                                + " nested or anonymous class would be compiled and never"
                                + " scanned, and the comment saying there are none is now stale:"
                                + " " + classes));
    }

    @Test
    @DisplayName("the scanner can actually fail — a known-impure class must be reported")
    void thePurityCheckCanActuallyFail() {
        // A CHECK THAT CANNOT FAIL PROVES NOTHING, which is the precedent's own wording and the
        // half the import-scan version never had: gut its five-prefix list and all three of its
        // assertions still passed. This control is carried, not performed once by hand and
        // written into a commit message.
        byte[] bytes = classBytes("org.jawata.mcp.catalogue.JavaEngineCoverage");
        // Deliberately NOT a silent skip. A control that opts out when it cannot find its
        // subject turns the assertion above into one that can only pass.
        assertNotNull(
                bytes,
                "purity CONTROL could not load its subject class, so the assertion above is"
                        + " unverified rather than passing");
        List<String> found = disallowedReferences(bytes);
        assertFalse(
                found.isEmpty(),
                "the scanner must report the engine references of a class that genuinely has"
                        + " them — JavaEngineCoverage reaches RefactoringDoors, IJdtService and"
                        + " the tools package by design — otherwise the purity assertion above"
                        + " is vacuous");
    }

    @Test
    @DisplayName("the scanner sees what an import scan cannot — a body reference and a static import")
    void theScannerSeesWhatAnImportScanCannot() {
        // The two measured escapes of the version this replaces, asserted against a real class
        // rather than described. JavaEngineCoverage carries BOTH shapes: it writes
        // `org.jawata.catalogue.Catalogue.byKey(...)` fully qualified in a method body, and it
        // reaches org.jawata.mcp.* types that no catalogue class may touch.
        byte[] bytes = classBytes("org.jawata.mcp.catalogue.JavaEngineCoverage");
        assertNotNull(bytes, "the subject must be on the classpath");
        List<String> found = disallowedReferences(bytes);
        assertTrue(
                found.stream().anyMatch(r -> r.startsWith("org/jawata/mcp/")),
                "a constant pool names every type a method body touches; found: " + found);
    }

    @Test
    @DisplayName("the MANIFEST declares no Import-Package, so OSGi would refuse one at resolve time")
    void theManifestImportsNothing() throws IOException {
        // KEPT, where the pom text check was retired, and the difference is ownership. A bundle
        // descriptor is the contract between our jar and Equinox, which ships on someone else's
        // cadence — so a hand-maintained manifest is the persisted-schema case, not a guard over
        // a fact we own. The pom's effective model IS ours, and the artifact scan above reads
        // its outcome rather than its text.
        Path manifest = moduleRoot().resolve("META-INF/MANIFEST.MF");
        assertTrue(Files.exists(manifest), "looked for the module's manifest at " + manifest);
        String text = Files.readString(manifest);

        assertAll(
                () -> assertFalse(
                        text.contains("Import-Package"),
                        "the catalogue bundle has gained an Import-Package, so at resolve time a"
                                + " class could load from whatever happens to export it"),
                () -> assertFalse(
                        text.contains("Require-Bundle"),
                        "a Require-Bundle reaches a whole bundle rather than a package, which is"
                                + " the broader form of the same door"),
                () -> assertTrue(
                        text.contains("Export-Package: " + MODULE),
                        "PROOF OF LIFE: the bundle must still EXPORT its own package, or the two"
                                + " assertions above would be true of something useless"));
    }

    /** Every top-level type the module declares, derived from its sources rather than listed. */
    private static List<String> moduleClasses() {
        Path src = moduleRoot().resolve("src");
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(src)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".java")).sorted().toList()) {
                String rel = src.relativize(p).toString();
                out.add(rel.substring(0, rel.length() - ".java".length())
                        .replace(java.io.File.separatorChar, '.'));
            }
        } catch (IOException e) {
            throw new AssertionError("could not enumerate the module's sources under " + src, e);
        }
        return List.copyOf(out);
    }

    /**
     * Every internal class name in the constant pool that no allowed prefix covers.
     *
     * <p>Scanning the raw bytes for slash-bearing tokens is enough: class and signature entries
     * are stored as plain UTF-8, which is the precedent's own reasoning.</p>
     */
    private static List<String> disallowedReferences(byte[] bytes) {
        String blob = new String(bytes, StandardCharsets.ISO_8859_1);
        List<String> found = new ArrayList<>();
        int at = 0;
        while (at < blob.length()) {
            if (!isTypeStart(blob.charAt(at))) {
                at++;
                continue;
            }
            int end = at;
            while (end < blob.length() && isTypeChar(blob.charAt(end))) {
                end++;
            }
            String token = descriptorStripped(blob.substring(at, end));
            // Only tokens shaped like an internal name — at least one package separator. A bare
            // word is a method name, a field name or a string, and judging those would make this
            // report the row names of the very table it is checking.
            if (token.indexOf('/') > 0 && ALLOWED.stream().noneMatch(token::startsWith)
                    && !found.contains(token)) {
                found.add(token);
            }
            at = end + 1;
        }
        return found;
    }

    /**
     * An internal name with its DESCRIPTOR SIGILS removed — {@code [[Ljava/util/List} becomes
     * {@code java/util/List}.
     *
     * <p>The constant pool holds the same name two ways: bare in a {@code CONSTANT_Class}
     * entry, and descriptor-wrapped in a signature. The first version of this scan tokenised
     * from the first letter, so every descriptor arrived with its {@code L} attached and the
     * whitelist rejected the entire JDK — the check failed on its first run with
     * {@code Ljava/util/List} as a violation. The precedent avoids this by searching for one
     * substring rather than tokenising; a whitelist has to tokenise, so it has to normalise.</p>
     *
     * <p>The {@code L} is dropped only when what follows is itself a package-shaped name, so a
     * genuine type whose own name begins with L is not truncated.</p>
     */
    private static String descriptorStripped(String token) {
        int slash = token.indexOf('/');
        if (slash < 0) {
            return token;              // not a package-shaped name; the caller ignores it
        }
        // A METHOD DESCRIPTOR CONCATENATES ITS PARAMETERS, so the sigils before the name are a
        // run rather than a single character: `(Ljava/util/List;ILjava/lang/String;)V` tokenises
        // to `ILjava/lang/String`, where the `I` is the preceding int parameter. The second
        // version of this scan stripped one leading `L` and still reported those two as
        // violations. The name begins after the LAST sigil that precedes the first separator,
        // which is exact for every descriptor shape and leaves a bare internal name untouched.
        int lastL = token.lastIndexOf('L', slash);
        return lastL >= 0 ? token.substring(lastL + 1) : token;
    }

    private static boolean isTypeStart(char c) {
        return Character.isLetter(c);
    }

    private static boolean isTypeChar(char c) {
        return Character.isLetterOrDigit(c) || c == '/' || c == '_' || c == '$';
    }

    private static byte[] classBytes(String fqn) {
        String path = "/" + fqn.replace('.', '/') + ".class";
        try (InputStream in = TheCatalogueCannotReachAnEngineTest.class.getResourceAsStream(path)) {
            if (in == null) {
                return null;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    /** The module's own directory, found by walking up from wherever the runner started. */
    private static Path moduleRoot() {
        return RepoFiles.root().resolve(MODULE);
    }

}
