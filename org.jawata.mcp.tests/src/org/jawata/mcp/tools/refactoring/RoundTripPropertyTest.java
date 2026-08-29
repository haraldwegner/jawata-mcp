package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.InlineMethodTool;
import org.jawata.mcp.tools.RefactorToPatternTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d S9.1 / D3 — THE ROUND-TRIP FIXED POINT, on code we did not author.
 *
 * <p>S9.0 established the mechanism on an authored fixture and recorded why the
 * direction is reversed from D3's wording: running AWAY first needs a
 * human-authored self-returning static factory, and a survey of the whole fork
 * found six such sites in four classes, every one carrying Lombok. This runs the
 * property where the clause actually points — at human-written originals.</p>
 *
 * <h2>The corpus, and why this slice</h2>
 *
 * <p>{@code fork-abstract-factory} is byte-identical to the pinned fork and
 * already vendored, dependency-free. It carries SIX originals of the exact shape
 * the trip needs: {@code OrcArmy}, {@code OrcCastle}, {@code OrcKing},
 * {@code ElfArmy}, {@code ElfCastle}, {@code ElfKing} — each with an IMPLICIT
 * default constructor and exactly one call site, inside its kingdom factory.
 * C9 asks for three; six run here because they are free and because a property
 * that holds on one shape and fails on its sibling is worth knowing.</p>
 *
 * <p><b>The implicit constructor is the interesting part, not an accident of the
 * corpus.</b> Most real code has no explicit one. The TOWARD direction has to
 * invent a constructor to wrap, and the AWAY direction then has to leave the
 * file exactly as it found it — including removing a factory method that did not
 * exist before either direction ran.</p>
 *
 * <h2>What a failure here means</h2>
 *
 * <p>C9 says divergences are recorded as findings, not smoothed. So this asserts
 * the property and names the class that broke it; a red is a measurement about
 * the operations, not a broken test, and it is reported rather than relaxed.</p>
 */
class RoundTripPropertyTest {

    /** The six human-authored originals, each constructed once by its factory. */
    private static final List<String> ORIGINALS = List.of(
        "OrcArmy", "OrcCastle", "OrcKing", "ElfArmy", "ElfCastle", "ElfKing");

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool pattern;
    private InlineMethodTool inline;
    private ObjectMapper mapper;
    private Path projectRoot;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("fork-abstract-factory");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        pattern = new RefactorToPatternTool(() -> service, cache);
        inline = new InlineMethodTool(() -> service, cache);
        mapper = new ObjectMapper();
        projectRoot = helper.getTempDirectory().resolve("fork-abstract-factory");
        pkg = projectRoot.resolve("src/main/java/com/iluwatar/abstractfactory");
    }

    private static int lineOf(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("source no longer contains: " + needle);
    }

    /** Every .java under the slice, mapped to its exact bytes. */
    private Map<Path, String> snapshot() throws Exception {
        Map<Path, String> out = new LinkedHashMap<>();
        try (Stream<Path> walk = Files.walk(projectRoot)) {
            for (Path p : walk.filter(Files::isRegularFile)
                              .filter(p -> p.toString().endsWith(".java"))
                              .sorted()
                              .toList()) {
                // ISO-8859-1 is a lossless byte-to-char mapping, so string
                // equality here IS byte equality.
                out.put(p, new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1));
            }
        }
        return out;
    }

    private static String firstDifference(Map<Path, String> before, Map<Path, String> after) {
        for (Map.Entry<Path, String> e : before.entrySet()) {
            String now = after.get(e.getKey());
            if (now == null) {
                return e.getKey().getFileName() + " was DELETED";
            }
            if (!now.equals(e.getValue())) {
                return e.getKey().getFileName() + " differs:\n---- after ----\n" + now;
            }
        }
        for (Path p : after.keySet()) {
            if (!before.containsKey(p)) {
                return p.getFileName() + " was CREATED and not removed";
            }
        }
        return null;
    }

    @Test
    @DisplayName("S9.1: TOWARD then AWAY is the identity on six fork originals")
    void theRoundTripIsAFixedPointOnHumanCode() throws Exception {
        Map<Path, String> pristine = snapshot();
        assertEquals(12, pristine.size(),
            "PROOF OF LIFE: the slice must be the whole twelve-file fork module, or the"
                + " comparison below is against something smaller than it looks");
        assertTrue(pristine.keySet().stream().anyMatch(p -> p.endsWith("OrcArmy.java")),
            "the snapshot must contain the originals it is about to move");

        for (String original : ORIGINALS) {
            // THE CARET GOES ON THE CONSTRUCTOR INVOCATION, IN THE FACTORY FILE
            // — measured, not assumed. Pointing at the type declaration is
            // refused with "Selected entity is not a constructor invocation or
            // definition", because these classes have NO explicit constructor
            // to point at. An implicit default constructor has exactly one
            // addressable form, its call site, and S8.4 established that JDT
            // will introduce a factory from there.
            Path factoryFile = pkg.resolve(
                (original.startsWith("Orc") ? "Orc" : "Elf") + "KingdomFactory.java");
            Path file = pkg.resolve(original + ".java");
            String callSites = Files.readString(factoryFile);

            ObjectNode toward = mapper.createObjectNode();
            toward.put("kind", "replace_constructor_with_factory");
            toward.put("filePath", factoryFile.toString());
            int line = lineOf(callSites, "return new " + original + "();");
            toward.put("line", line);
            toward.put("column",
                callSites.split("\n", -1)[line].indexOf("new " + original + "()"));
            toward.put("factoryMethodName", "create");
            // Load-bearing: the default privatises the constructor, and inlining
            // the factory would then rewrite call sites into something they
            // cannot reach. The trip is only defined where the old path stays open.
            toward.put("protectConstructor", false);

            ToolResponse t = pattern.execute(toward);
            assertTrue(t.isSuccess(),
                () -> "TOWARD refused on " + original + ": " + t.getError());

            // PROOF OF LIFE, PER ITERATION, and it is the assertion this test
            // most needs. Everything below compares the slice with its pristine
            // bytes — which a pair of operations that BOTH did nothing would
            // satisfy perfectly, while reporting success. So the midpoint must
            // actually differ from the start, or the fixed point being measured
            // is the trivial one.
            String mid = Files.readString(file);
            assertTrue(mid.contains("create("),
                () -> "TOWARD reported success on " + original + " and produced no"
                    + " factory. The comparison below would then pass on a pair of"
                    + " no-ops:\n" + mid);
            assertTrue(firstDifference(pristine, snapshot()) != null,
                "TOWARD reported success on " + original + " and left the slice"
                    + " byte-identical, so the round trip below has nothing to undo"
                    + " and closes trivially");

            // AWAY.
            int fline = lineOf(mid, " create(");
            ObjectNode away = mapper.createObjectNode();
            away.put("filePath", file.toString());
            away.put("line", fline);
            away.put("column", mid.split("\n", -1)[fline].indexOf("create("));

            ToolResponse a = inline.execute(away);
            assertTrue(a.isSuccess(),
                () -> "AWAY refused on " + original + ": " + a.getError());

            // THE FIXED POINT, over the WHOLE SLICE rather than the two files
            // the trip named — the call site lives in a third file, and a trip
            // that tidied something else on the way past would still satisfy a
            // two-file check.
            String diff = firstDifference(pristine, snapshot());
            assertEquals(null, diff,
                "ROUND TRIP DID NOT CLOSE on " + original + ". "
                    + "TOWARD then AWAY must be the identity on human code; it is not. "
                    + (diff == null ? "" : diff));
        }
    }
}
