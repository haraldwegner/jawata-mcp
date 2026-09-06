package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.domain.DetectorCatalog;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.CodeAddress;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * EVERY ROUTED KIND MUST EMIT AN ADDRESS ITS OWN CURE COULD BE RUN FROM.
 *
 * <p>The per-row contract says a fix is "callable straight from the finding that names
 * it". Until now that was asserted of the FIXES — each row can be driven from a position
 * — and never of the FINDINGS. Those are different claims: a door that accepts a caret
 * proves nothing about whether the detector emits one. This is the other half.</p>
 *
 * <h2>Derived, not listed</h2>
 *
 * <p>The population is {@code CureCatalog.declaredKinds()} filtered to those the tier
 * derivation answers RUN for — so a kind that gains a runnable cure joins this test
 * without anyone remembering, which is the property the hand-written lists in this
 * sprint kept failing to have.</p>
 *
 * <h2>Why it builds its own registry</h2>
 *
 * <p>{@code CureTier.derive(kind)} consults the PROCESS registry, which a unit-test JVM
 * leaves empty because no tool has registered — so almost every kind would answer
 * CONSIDER for a reason about plumbing and this test would pass over nothing. The
 * registry here is built from the REAL doors, so "routed" means what it means in the
 * product.</p>
 */
class EveryRoutedFindingIsAcceptedByItsCureTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /**
     * The routed kinds this fixture cannot measure, and why each one — measured 2026-09-06.
     *
     * <ul>
     *   <li>{@code singleton} and {@code type_code} have NO Detector registered under their
     *       own kind in either catalog. They are reachable through {@code find_quality_issue}'s
     *       own path, so a user gets findings; what does not exist is a Detector this loop
     *       can call. {@code type_code}'s addresses ARE covered here all the same — {@code
     *       ocp} relabels its traces and keeps their symbol, so the same emission site is
     *       measured through that kind.</li>
     *   <li>{@code unused} and {@code divergent_change} have a detector and find nothing in
     *       {@code simple-maven}. That is a property of the fixture, not of the detector.</li>
     * </ul>
     */
    private static final java.util.SortedSet<String> EXPECTED_SILENT =
        new java.util.TreeSet<>(List.of(
            "singleton", "type_code", "unused", "divergent_change"));

    /** Every operation the real doors publish, spelled the way the cure table spells it. */
    private static List<String> registryOfRealDoors() {
        List<String> ops = new ArrayList<>(
            org.jawata.mcp.tools.RefactorToPatternTool.patternKinds());
        for (Tool door : List.<Tool>of(
                new org.jawata.mcp.tools.ExtractTool(() -> null, new RefactoringChangeCache()),
                new org.jawata.mcp.tools.InlineTool(() -> null, new RefactoringChangeCache()),
                new org.jawata.mcp.tools.MoveTool(() -> null, new RefactoringChangeCache()),
                new org.jawata.mcp.tools.DataTool(() -> null, new RefactoringChangeCache()),
                new org.jawata.mcp.tools.HierarchyTool(() -> null, new RefactoringChangeCache()),
                new org.jawata.mcp.tools.ApplyCleanupTool(() -> null, new RefactoringChangeCache()),
                new org.jawata.mcp.tools.ChangeMethodSignatureTool(
                    () -> null, new RefactoringChangeCache()),
                new org.jawata.mcp.tools.RefactorToPatternTool(
                    () -> null, new RefactoringChangeCache()))) {
            ops.add(door.getName());
            for (String kind : door.publishedKinds()) {
                ops.add(door.getName() + " kind=" + kind);
            }
        }
        return ops;
    }

    @Test
    @DisplayName("every routed kind's findings carry an address a door could resolve")
    void everyRoutedKindEmitsAUsableAddress() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        // BOTH registries. The Fowler smells are AbstractAstDetectors; the older quality
        // kinds are TOOLS adapted to the Detector interface, and they live in a catalog of
        // their own. A routed kind can come from either, so a test that consulted one
        // would report "no detector" for kinds that have one.
        DetectorCatalog catalog =
            org.jawata.mcp.tools.QualityDetectors.builtins(() -> service);
        FowlerDetectors.registerInto(catalog, () -> null);
        List<String> registry = registryOfRealDoors();
        ObjectMapper mapper = new ObjectMapper();

        List<String> routed = new ArrayList<>();
        for (String kind : CureCatalog.declaredKinds()) {
            if (CureTier.derive(kind, registry).tier() == CureTier.Tier.RUN) {
                routed.add(kind);
            }
        }
        // PROOF OF LIFE. An empty or tiny routed set would make every check below pass
        // over nothing — the shape this sprint has found repeatedly.
        assertTrue(routed.size() >= 10,
            "the cure table must yield a real routed population, or this asserts nothing."
                + " Routed: " + routed);

        Map<String, String> silent = new LinkedHashMap<>();
        Map<String, String> unusable = new LinkedHashMap<>();
        for (String kind : routed) {
            Detector detector = catalog.get(kind).orElse(null);
            if (detector == null) {
                // A routed kind with no detector cannot be reached from a finding at all.
                // That is a real gap and it is REPORTED rather than skipped.
                silent.put(kind, "no detector registered for this kind");
                continue;
            }
            ObjectNode args = mapper.createObjectNode();
            args.put("includeTests", true);
            ToolResponse r = detector.detect(service, args);
            if (!r.isSuccess()) {
                silent.put(kind, "detector failed: " + r.getError());
                continue;
            }
            List<Map<String, Object>> rows = findingsOf(r);
            if (rows.isEmpty()) {
                silent.put(kind, "no finding on the fixture project");
                continue;
            }
            for (Map<String, Object> row : rows) {
                CodeAddress address = addressOf(row);
                boolean named = address.symbol() != null && address.symbol().contains(".");
                if (!address.complete() || !named) {
                    unusable.putIfAbsent(kind, "symbol=" + row.get("symbol")
                        + " filePath=" + row.get("filePath") + " line=" + row.get("line"));
                    break;
                }
            }
        }

        assertTrue(unusable.isEmpty(),
            "these kinds declare a RUNNABLE cure and emit findings carrying no address a"
                + " door could be pointed at, so the cure is an instruction nobody can"
                + " follow:\n  " + join(unusable));

        // THE SILENT SET IS NAMED, NOT IGNORED. A kind that produced no finding was not
        // measured, and an unasserted skip is how a gate quietly stops covering things —
        // this sprint's own "a gate that cannot see zero". Equality, so a NEW silent kind
        // fails here and has to be explained; the four below are explained already.
        assertEquals(EXPECTED_SILENT, new java.util.TreeSet<>(silent.keySet()),
            "the set of routed kinds this fixture cannot measure has changed. Each one"
                + " below is either a kind with no Detector implementation or one whose"
                + " shape the fixture does not contain — and a kind ARRIVING here means a"
                + " detector stopped firing:\n  " + join(silent));
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> findingsOf(ToolResponse r) {
        Object findings = ((Map<String, Object>) r.getData()).get("findings");
        return findings == null ? List.of() : (List<Map<String, Object>>) findings;
    }

    private static CodeAddress addressOf(Map<String, Object> row) {
        Object line = row.get("line");
        Object column = row.get("column");
        return CodeAddress.of(new org.jawata.mcp.domain.Finding(
            String.valueOf(row.get("kind")),
            (String) row.get("filePath"),
            line instanceof Integer i ? i : -1,
            column instanceof Integer c ? c : -1,
            (String) row.get("severity"),
            String.valueOf(row.get("message")),
            (String) row.get("symbol")));
    }

    private static String join(Map<String, String> m) {
        List<String> lines = new ArrayList<>();
        m.forEach((k, v) -> lines.add(k + " -> " + v));
        return String.join("\n  ", lines);
    }
}
