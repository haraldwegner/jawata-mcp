package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.jawata.mcp.engine.JawataEngine;
import org.jawata.spi.host.EngineHost;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkUtil;

/**
 * Sprint 28k, Stage 8 (contract §18 ring 1; rules R1, R2, R10 to R12): the language SPI and the neutral packages of the engine reference
 * no Java tooling, no user interface, no application launcher and do not end the process. Read from the CLASS FILES, not from the
 * source: an import check misses what a method reference or an inferred type brings in, which is how a fourth package hid in the
 * resident's bytecode at this stage.
 *
 * <p>A scan that cannot fail proves nothing, so the same scan is run over {@link PurityControl}, which does all three things, and must
 * find them.</p>
 */
class PurityScanTest {

    /** Packages whose class files must not appear in a neutral class (internal names, with a trailing slash). */
    private static final List<String> FORBIDDEN = List.of("org/eclipse/jdt/", "org/jawata/core/", "org/eclipse/ui/", "org/eclipse/swt/",
        "org/eclipse/equinox/app/");

    /** The one place the neutral core may reach into the model package: the JDT-free leaf (rule R12). */
    private static final String ALLOWED_LEAF = "org/jawata/core/host/";

    /** The engine's neutral packages (rule R11). */
    private static final List<String> NEUTRAL_PACKAGES = List.of("knowledge", "execution", "learn", "transport", "protocol", "embed",
        "domain", "field", "models", "change", "engine");

    @Test
    @DisplayName("THE CONTROL — the scan finds the JDT type, the jawata core type and System.exit in a class that has them")
    void theScanCanFail() throws IOException {
        Set<String> found = referencesIn(bytesOf(PurityControl.class));

        assertTrue(found.stream().anyMatch(r -> r.startsWith("org/eclipse/jdt/")), "the JDT reference was not seen: " + found);
        assertTrue(found.stream().anyMatch(r -> r.startsWith("org/jawata/core/")), "the core reference was not seen: " + found);
        assertTrue(found.contains("java/lang/System.exit"), "the call that ends the process was not seen: " + found);
    }

    @Test
    @DisplayName("the control is judged impure by the same rule that judges the real classes")
    void theRuleJudgesTheControlImpure() throws IOException {
        assertFalse(violations(referencesIn(bytesOf(PurityControl.class))).isEmpty());
    }

    @Test
    @DisplayName("the language SPI references no JDT, no jawata core, no UI, no launcher, and never ends the process")
    void theSpiIsPure() throws IOException {
        List<String> violations = scan(FrameworkUtil.getBundle(EngineHost.class), "/org/jawata/spi");

        assertEquals(List.of(), violations);
    }

    /**
     * What the class files show that the import-based measure of the contract (R11, R12) did not: each entry is a class and the prefix it
     * still reaches, with the reason it stands. The list may only shrink: an entry that no longer occurs fails the test, so a fix cannot
     * leave its excuse behind. Ledger row C8-1.
     */
    private static final List<String> KNOWN_DEVIATIONS = List.of();

    @Test
    @DisplayName("the engine's neutral packages reference no JDT, only the JDT-free leaf of the core, no UI, no launcher, and never end the process, beyond the named deviations")
    void theNeutralPackagesArePure() throws IOException {
        Bundle engine = FrameworkUtil.getBundle(JawataEngine.class);
        List<String> violations = new ArrayList<>();
        for (String neutral : NEUTRAL_PACKAGES) {
            violations.addAll(scan(engine, "/org/jawata/mcp/" + neutral));
        }

        List<String> unexpected = new ArrayList<>(violations);
        unexpected.removeAll(KNOWN_DEVIATIONS);
        List<String> mended = new ArrayList<>(KNOWN_DEVIATIONS);
        mended.removeAll(violations);

        assertEquals(List.of(), unexpected, "a neutral class reaches Java or the platform");
        assertEquals(List.of(), mended, "a named deviation no longer occurs: take it off the list");
    }

    /** The Java provider's own package: the engine, which serves every language, may not name a class of it (ledger 28k-C9-9). */
    private static final String JAVA_PROVIDER = "org/jawata/mcp/java/";

    private static List<String> namesOfTheJavaProvider(Set<String> references) {
        return references.stream().filter(reference -> reference.startsWith(JAVA_PROVIDER)).toList();
    }

    @Test
    @DisplayName("THE CONTROL — the rule that finds the Java provider's package finds it in the Java provider")
    void theScanSeesTheJavaProviderPackage() throws IOException {
        assertFalse(namesOfTheJavaProvider(referencesIn(bytesOf(org.jawata.mcp.java.JavaProvider.class))).isEmpty());
    }

    @Test
    @DisplayName("the engine package names no class of the Java provider, so it serves any language without knowing Java")
    void theEngineNamesNoJavaProviderClass() throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = scanFolder(FrameworkUtil.getBundle(JawataEngine.class), "org/jawata/mcp/engine/", violations,
            PurityScanTest::namesOfTheJavaProvider);

        assertTrue(scanned > 0, "no class file was found under the engine package: the scan looked at nothing");
        assertEquals(List.of(), violations);
    }

    private static List<String> scan(Bundle bundle, String folder) throws IOException {
        List<String> violations = new ArrayList<>();
        int scanned = scanFolder(bundle, folder.substring(1) + "/", violations);
        assertTrue(scanned > 0, "no class file was found under " + folder + ": the scan looked at nothing");
        Collections.sort(violations);
        return violations;
    }

    /**
     * Walks the bundle's OWN entries, one folder at a time: {@code findEntries} would include the test fragments attached to the bundle,
     * whose classes live in the same packages and are not part of what ships.
     */
    private static int scanFolder(Bundle bundle, String folder, List<String> violations) throws IOException {
        return scanFolder(bundle, folder, violations, PurityScanTest::violations);
    }

    private static int scanFolder(Bundle bundle, String folder, List<String> violations,
            java.util.function.Function<Set<String>, List<String>> rule) throws IOException {
        int scanned = 0;
        Enumeration<String> paths = bundle.getEntryPaths(folder);
        while (paths != null && paths.hasMoreElements()) {
            String path = paths.nextElement();
            if (path.endsWith("/")) {
                scanned += scanFolder(bundle, path, violations, rule);
            } else if (path.endsWith(".class")) {
                scanned++;
                URL url = bundle.getEntry(path);
                try (InputStream in = url.openStream()) {
                    for (String violation : rule.apply(referencesIn(in.readAllBytes()))) {
                        violations.add("/" + path + " -> " + violation);
                    }
                }
            }
        }
        return scanned;
    }

    private static List<String> violations(Set<String> references) {
        List<String> violations = new ArrayList<>();
        for (String reference : references) {
            boolean forbidden = FORBIDDEN.stream().anyMatch(reference::startsWith) && !reference.startsWith(ALLOWED_LEAF);
            if (forbidden || reference.equals("java/lang/System.exit")) {
                violations.add(reference);
            }
        }
        return violations;
    }

    private static byte[] bytesOf(Class<?> type) throws IOException {
        try (InputStream in = type.getResourceAsStream(type.getSimpleName() + ".class")) {
            return in.readAllBytes();
        }
    }

    /**
     * What a class file refers to: every class named in its constant pool, the types inside every descriptor, and every method it calls as
     * {@code owner.name}. String constants are not read, so a message that mentions a package is not a reference to it.
     */
    static Set<String> referencesIn(byte[] classFile) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classFile));
        in.readInt(); // magic
        in.readUnsignedShort();
        in.readUnsignedShort();
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] classNameAt = new int[count];
        int[] refClassAt = new int[count];
        int[] refNameAndTypeAt = new int[count];
        int[] nameAt = new int[count];
        int[] descriptorAt = new int[count];
        int[] tag = new int[count];
        Set<Integer> stringConstants = new TreeSet<>();
        for (int i = 1; i < count; i++) {
            tag[i] = in.readUnsignedByte();
            switch (tag[i]) {
                case 1 -> utf8[i] = in.readUTF();
                case 3, 4 -> in.readInt();
                case 5, 6 -> {
                    in.readLong();
                    i++;
                }
                case 7 -> classNameAt[i] = in.readUnsignedShort();
                case 8 -> stringConstants.add(in.readUnsignedShort());
                case 19, 20 -> in.readUnsignedShort();
                case 9, 10, 11 -> {
                    refClassAt[i] = in.readUnsignedShort();
                    refNameAndTypeAt[i] = in.readUnsignedShort();
                }
                case 12 -> {
                    nameAt[i] = in.readUnsignedShort();
                    descriptorAt[i] = in.readUnsignedShort();
                }
                case 15 -> {
                    in.readUnsignedByte();
                    in.readUnsignedShort();
                }
                case 16 -> descriptorAt[i] = in.readUnsignedShort();
                case 17, 18 -> {
                    in.readUnsignedShort();
                    in.readUnsignedShort();
                }
                default -> throw new IOException("unknown constant pool tag " + tag[i]);
            }
        }
        Set<String> references = new TreeSet<>();
        for (int i = 1; i < count; i++) {
            switch (tag[i]) {
                case 1 -> {
                    // Method and field descriptors, generic signatures and local variable types are plain UTF-8 entries that only an
                    // attribute or a member points at; a string a program prints is excluded, because it names nothing it uses.
                    if (!stringConstants.contains(i)) {
                        addDescriptorTypes(references, utf8[i]);
                    }
                }
                case 7 -> addClassName(references, utf8[classNameAt[i]]);
                case 12, 16 -> addDescriptorTypes(references, utf8[descriptorAt[i]]);
                case 10, 11 -> {
                    String owner = utf8[classNameAt[refClassAt[i]]];
                    references.add(owner + "." + utf8[nameAt[refNameAndTypeAt[i]]]);
                }
                default -> { }
            }
        }
        return references;
    }

    private static void addClassName(Set<String> into, String name) {
        // An array class is named by its descriptor, which carries the element type.
        if (name.startsWith("[")) {
            addDescriptorTypes(into, name);
        } else {
            into.add(name);
        }
    }

    private static void addDescriptorTypes(Set<String> into, String descriptor) {
        int at = 0;
        while ((at = descriptor.indexOf('L', at)) >= 0) {
            int end = descriptor.indexOf(';', at);
            if (end < 0) {
                return;
            }
            into.add(descriptor.substring(at + 1, end));
            at = end + 1;
        }
    }
}
