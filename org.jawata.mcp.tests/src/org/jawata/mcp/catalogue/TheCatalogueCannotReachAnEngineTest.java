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
 * <p>The population is every CLASS FILE the module compiles to, so a nested or anonymous type is
 * scanned like any other. It read the source files until a C8 audit named that hole — a file path
 * maps to one name, so a {@code Foo$Bar.class} was compiled and never opened while the count
 * assertion still read 5. Measured when it was changed: five class files against five sources,
 * so the hole was latent rather than live.</p>
 *
 * <p>What remains outside it: a type the module loads REFLECTIVELY by a name it computes would
 * not appear in any constant pool, and no bytecode scan can see that. The module has no
 * reflection today and the manifest check below is the second fence — a reflective load still
 * needs the package resolved at runtime, which an empty {@code Import-Package} refuses.</p>
 */
class TheCatalogueCannotReachAnEngineTest {

    private static final String MODULE = "org.jawata.catalogue";

    /** The internal-name prefixes a shared table may reference. Everything else is a violation. */
    private static final List<String> ALLOWED = List.of(
            "java/",
            "org/jawata/catalogue/");

    /**
     * The known-impure class the two controls below are run against.
     *
     * <p>It is the catalogue's own sibling one package over, so it is the honest subject: it
     * declares against the shared table and reaches a Java engine to do it, which is exactly what
     * a catalogue class may not do.</p>
     */
    private static final String CONTROL = "org.jawata.mcp.catalogue.JavaEngineCoverage";

    /**
     * Where the module's classes land, repo-relative.
     *
     * <p>The build lays the reactor's output out under {@code build/}, which is why this is not
     * {@code <module>/target}. Resolved through {@link RepoFiles}, the same mechanism this
     * package uses for every other committed artifact it has to find from a runner whose working
     * directory is not the repository root.</p>
     */
    private static final String COMPILED_OUTPUT = "build/catalogue/target/classes";

    @Test
    @DisplayName("no compiled catalogue class references anything but the JDK and itself")
    void noCompiledClassReachesAnEngine() {
        List<String> classes = moduleClasses();
        // PROOF OF LIFE: with an empty population every violation map below is empty and this
        // whole check certifies a module it never read.
        assertFalse(classes.isEmpty(), "derived no classes from " + COMPILED_OUTPUT);

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
                        "the module's COMPILED type count moved. That is not a failure in itself"
                                + " — but a new type is a new chance to reach an engine, and this"
                                + " number is the one thing a drifting population cannot fake:"
                                + " " + classes));
    }

    @Test
    @DisplayName("the scanner can actually fail — a known-impure class must be reported")
    void thePurityCheckCanActuallyFail() {
        // A CHECK THAT CANNOT FAIL PROVES NOTHING, which is the precedent's own wording and the
        // half the import-scan version never had: gut its five-prefix list and all three of its
        // assertions still passed. This control is carried, not performed once by hand and
        // written into a commit message.
        byte[] bytes = classBytes(CONTROL);
        // Deliberately NOT a silent skip. A control that opts out when it cannot find its
        // subject turns the assertion above into one that can only pass.
        assertNotNull(
                bytes,
                "purity CONTROL could not load its subject class, so the assertion above is"
                        + " unverified rather than passing");
        // THE SUBJECT'S OWN NAME IS EXCLUDED, which is what a C8 audit refused the first version
        // over: `org/jawata/mcp/catalogue/JavaEngineCoverage` is in every copy of its own class
        // file and matches no allowed prefix, so this read as a pass with every engine reference
        // deleted.
        //
        // WHAT SURVIVES THE FILTER IS SEVEN, measured on the shipped class rather than listed
        // from memory: org/jawata/mcp/refactoring/{OperationRegistry,RefactoringChangeCache},
        // org/jawata/mcp/tools/{AbstractTool,FrontDoor,RefactoringDoors},
        // org/jawata/core/IJdtService and org/jawata/mcp/knowledge/ExperienceStore.
        //
        // THE FIRST VERSION OF THIS COMMENT NAMED FIVE, and a round-3 audit caught it: the two
        // it omitted are exactly the two that make the mutation's prefix count FOUR rather than
        // two — so the comment reproduced, as prose, the same incompleteness that made this
        // repair's first mutation attempt a false negative. (§7's own row has all four prefixes
        // right; only this comment was short.)
        //
        // One of the seven is a STORE rather than an engine, so the assertion message below is
        // narrower than the needle: what the fence forbids is any reference outside the JDK and
        // the catalogue itself, which is wider than "an engine" and is the property that makes
        // the table shareable.
        List<String> found = foreignReferencesOf(bytes);
        assertFalse(
                found.isEmpty(),
                "the scanner must report the engine references of a class that genuinely has"
                        + " them — JavaEngineCoverage reaches RefactoringDoors and the refactoring"
                        + " package by design — otherwise the purity assertion above is vacuous");
    }

    @Test
    @DisplayName("the scanner sees a type the import block does not name — the mechanism itself")
    void theScannerSeesWhatAnImportScanCannot() throws IOException {
        // REWRITTEN AFTER A C8 AUDIT, because the previous version's claim was false twice over
        // and its needle was satisfied by the subject's own class name.
        //
        //   - its DisplayName promised "a static import", and the subject has none: twelve
        //     imports, not one of them static;
        //   - its other promised shape, the fully-qualified body reference, is to
        //     org.jawata.catalogue.Catalogue — which the WHITELIST covers, so it can never
        //     appear in a disallowed list at all;
        //   - and `anyMatch(startsWith("org/jawata/mcp/"))` is true of
        //     `org/jawata/mcp/catalogue/JavaEngineCoverage`, the subject itself.
        //
        // The mechanism is still real and this is what demonstrates it, both halves measured:
        // the pool NAMES org/jawata/catalogue/Catalogue, and the source's import block does NOT
        // — it imports CatalogueRow and Refactorings only. So an import scan misses a type the
        // pool sees, on the real idiom, with nothing inferred.
        byte[] bytes = classBytes(CONTROL);
        assertNotNull(bytes, "the subject must be on the classpath");
        Path source = RepoFiles.sourceOf(
                "org.jawata.mcp/src/" + CONTROL.replace('.', '/') + ".java");
        assertNotNull(source, "the subject's source must be readable to check its import block");
        String text = Files.readString(source);
        List<String> pool = allReferences(bytes);

        assertAll(
                // EXACT equality, not containment: `.../Catalogue` is a prefix of
                // `.../CatalogueRow`, so a substring test would be answered by the row type.
                () -> assertTrue(
                        pool.contains("org/jawata/catalogue/Catalogue"),
                        "the constant pool must name the type reached fully qualified in a method"
                                + " body, which is the whole reason this fence reads the pool;"
                                + " pool: " + pool),
                () -> assertFalse(
                        text.contains("import org.jawata.catalogue.Catalogue;"),
                        "the subject has gained an import for Catalogue, so an import scan would"
                                + " now see it too and this no longer demonstrates the difference"
                                + " — point it at a type that is still body-only"),
                // PROOF OF LIFE on the import half: the block is real and this test is reading
                // it, so the absence above is an absence rather than an unread file.
                () -> assertTrue(
                        text.contains("import org.jawata.catalogue.CatalogueRow;"),
                        "PROOF OF LIFE: the import block must be readable, or the assertion above"
                                + " is true of a file this test failed to find"));
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

    /**
     * Every type the module COMPILES TO — nested and anonymous ones included.
     *
     * <p>This derived the population from SOURCE FILES until a C8 audit named the hole: a file
     * path maps to exactly one name, so a nested or anonymous class is compiled to a
     * {@code Foo$Bar.class} that the population never reaches and the scan never opens — while
     * the count assertion beside it still read 5, which is the very case the old comment named
     * as its reason for existing. It counted FILES and its message claimed TYPES.</p>
     *
     * <p>The compiled set has no such gap: it IS what ships, {@code $} names and all. Measured
     * at the time of writing — five class files against five sources, so the hole was latent
     * rather than live, which is why nothing had failed.</p>
     *
     * <p>An ABSENT output directory FAILS rather than skips, for this file's own stated reason:
     * a control that opts out when it cannot find its subject turns the assertion it guards into
     * one that can only pass.</p>
     */
    private static List<String> moduleClasses() {
        Path classes = RepoFiles.sourceOf(COMPILED_OUTPUT);
        assertNotNull(
                classes,
                "could not find the catalogue module's compiled output at " + COMPILED_OUTPUT
                        + " — the population would be empty and every assertion over it vacuous");
        Path pkg = classes.resolve(MODULE.replace('.', '/'));
        List<String> out = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(pkg)) {
            for (Path p : walk.filter(f -> f.toString().endsWith(".class")).sorted().toList()) {
                String rel = classes.relativize(p).toString();
                out.add(rel.substring(0, rel.length() - ".class".length())
                        .replace(java.io.File.separatorChar, '.'));
            }
        } catch (IOException e) {
            throw new AssertionError("could not enumerate the module's classes under " + pkg, e);
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
        return allReferences(bytes).stream()
                .filter(token -> ALLOWED.stream().noneMatch(token::startsWith))
                .toList();
    }

    /**
     * EVERY internal class name in the constant pool, allowed or not.
     *
     * <p>Split out of {@link #disallowedReferences} so the mechanism test above can assert that
     * one EXACT name is present. A substring test could not: {@code org/jawata/catalogue/Catalogue}
     * is a prefix of {@code org/jawata/catalogue/CatalogueRow}, so
     * {@code pool.contains(".../Catalogue")} is satisfied by the row type and would prove nothing
     * about the name it was written for — which is the vacuity class a C8 audit refused this file
     * over twice.</p>
     */
    private static List<String> allReferences(byte[] bytes) {
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
            if (token.indexOf('/') > 0 && !found.contains(token)) {
                found.add(token);
            }
            at = end + 1;
        }
        return found;
    }

    /**
     * The subject's references to OTHER types — its own internal name and its nested types gone.
     *
     * <p><b>A C8 audit found BOTH controls above satisfied by the subject's own
     * {@code this_class} entry</b>, which every copy of its class file carries unconditionally
     * and which no allowed prefix covers. With every engine reference deleted from it they would
     * still have passed, so they proved the scanner emits a token rather than that it detects an
     * engine — and the mutation offered as their proof could not tell the two apart, because
     * whitelisting {@code org/jawata/mcp/} whitelists the subject's own name and its engine
     * references in one move. Dropping the subject's own name is what makes them about the thing
     * they are named for.</p>
     *
     * <p>Noted in fairness rather than as an excuse: the precedent this file was told to copy has
     * the same hole, controlling on a class its deny rule also catches by that class's own name.
     * Copying the mechanism faithfully included copying the hole; closing it here is one
     * filter.</p>
     */
    private static List<String> foreignReferencesOf(byte[] bytes) {
        String own = CONTROL.replace('.', '/');
        return disallowedReferences(bytes).stream()
                .filter(r -> !r.equals(own) && !r.startsWith(own + "$"))
                .toList();
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
