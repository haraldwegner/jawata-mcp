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
        // THE CALLER'S OWN PATH, not a catalog assembled here. This used to build the two
        // registries by hand — the Fowler smells and the tool-adapted quality kinds — which
        // was a copy of what `FindQualityIssueTool` does in its own constructor, and it
        // ALSO meant the test drove `Detector.detect` directly and so never saw the cure
        // join the dispatch applies. A C8b audit found the consequence: three detectors ship
        // no executable cure and this gate passed them. Going through the tool is what makes
        // "what the caller receives" the thing being measured.
        org.jawata.mcp.tools.FindQualityIssueTool door =
            new org.jawata.mcp.tools.FindQualityIssueTool(() -> service);
        DetectorCatalog catalog =
            org.jawata.mcp.tools.QualityDetectors.builtins(() -> service);
        FowlerDetectors.registerInto(catalog, () -> null);
        List<String> registry = registryOfRealDoors();

        // THE PROCESS REGISTRY IS POPULATED FOR THE DURATION, because the product's is.
        // `Cures.attach` derives the tier against the PROCESS registry — correctly, that is
        // what a running server has — and a unit-test JVM leaves it empty because no tool has
        // registered, so every kind would answer CONSIDER for a reason about plumbing and the
        // `cures` assertion below would fail for all of them. Publishing the real doors
        // through the real publish path is what makes this test's process resemble the one
        // the claim is about. Borrowed and given back, the way the sibling door gate does it.
        org.jawata.mcp.refactoring.OperationRegistry operations =
            org.jawata.mcp.refactoring.OperationRegistry.theRegistry();
        org.jawata.mcp.refactoring.OperationRegistry.Snapshot borrowed = operations.snapshot();
        operations.clear();
        for (org.jawata.mcp.tools.AbstractTool published
                : org.jawata.mcp.tools.RefactoringDoors.all(
                    () -> service, new RefactoringChangeCache())) {
            org.jawata.mcp.tools.OperationSurface.publish(published);
        }
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
        try {
        for (String kind : routed) {
            Detector detector = catalog.get(kind).orElse(null);
            if (detector == null) {
                // A routed kind with no detector cannot be reached from a finding at all.
                // That is a real gap and it is REPORTED rather than skipped.
                silent.put(kind, "no detector registered for this kind");
                continue;
            }
            ObjectNode args = mapper.createObjectNode();
            args.put("kind", kind);
            args.put("includeTests", true);
            ToolResponse r = door.execute(args);
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
                if (address.symbol() == null || !address.symbol().contains(".")) {
                    // The `complete()` term that used to sit beside this is GONE, and a C8b
                    // audit is why: a qualified symbol satisfies complete()'s FIRST branch,
                    // so `!complete() || !named` could never fire on the complete() half.
                    // Two conditions, one of them unreachable, reading as two checks.
                    unusable.putIfAbsent(kind, "symbol=" + row.get("symbol")
                        + " filePath=" + row.get("filePath") + " line=" + row.get("line"));
                    break;
                }
                // AND THE PRODUCT MUST HAVE RENDERED THE STEPS, not merely carried an address
                // they could be built from. This is the clause the test was NAMED for and did
                // not check: it rebuilt the address from the row's raw fields, so a detector
                // that emitted no `cures` at all passed. Three did — including the smell step
                // 9 itself added — and only the built artifact said so.
                if (!(row.get("cures") instanceof List<?> cures) || cures.isEmpty()) {
                    unusable.putIfAbsent(kind, "the finding carries a usable address and NO"
                        + " cures array, so the cure reached the caller as prose only:"
                        + " symbol=" + row.get("symbol"));
                    break;
                }
            }
        }
        } finally {
            // GIVEN BACK whatever happened, including a failing assertion above — a test that
            // left the singleton holding nine doors would silently change what every later
            // class in this shard derives.
            operations.restore(borrowed);
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

    /**
     * INVARIANT A's SECOND HALF — the door is ASKED, not reasoned about.
     *
     * <p>The test above proves a routed finding carries a qualified address. That is not the
     * same claim as "the cure can be run from it": a symbol can be perfectly well-formed and
     * name something the door cannot resolve, and the caller would only find out at the door.
     * S8b step 5 recorded this half as OWED rather than counting it as done, because it needs
     * the door population step 8 derives. Step 8 derived it, so this is that debt.</p>
     *
     * <h2>What counts as a failure, and it is the architecture's own list</h2>
     *
     * <p>A refusal is fine — most preconditions in this product are refusals, and a finding
     * naming a shape a cure declines is a true answer. What is NOT fine is a refusal ABOUT
     * THE ADDRESS: {@code filePath}, {@code line}/{@code column}, {@code symbol}, or the
     * door's own discriminator. Those are the parts the FINDING supplied, so a refusal naming
     * one of them says the product pointed its own cure at something the cure cannot take.</p>
     *
     * <p>The judgement is made on the error CODE first and on the named parameter second,
     * never on a prose substring alone — this sprint has three recorded cases of a needle
     * that two different refusals both print, which proves only that something declined.</p>
     *
     * <p><b>{@code auto_apply=false} throughout.</b> The claim is about ACCEPTANCE, so the
     * doors stage rather than apply: nothing is written to the fixture copy, and a door that
     * gets as far as computing a change has accepted the address by definition.</p>
     */
    @Test
    @DisplayName("every routed cure's door accepts the address its own finding carries")
    void everyRoutedCureAcceptsTheAddressItIsGiven() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        DetectorCatalog catalog =
            org.jawata.mcp.tools.QualityDetectors.builtins(() -> service);
        FowlerDetectors.registerInto(catalog, () -> null);
        List<String> registry = registryOfRealDoors();
        ObjectMapper mapper = new ObjectMapper();

        // THE DOOR POPULATION IS THE APPLICATION'S OWN, not a list written here. That is
        // step 8's whole deliverable, and a hand-written mirror in this file would be the
        // fifth copy of the fact it deleted.
        Map<String, org.jawata.mcp.tools.AbstractTool> doors = new LinkedHashMap<>();
        for (org.jawata.mcp.tools.AbstractTool door
                : org.jawata.mcp.tools.RefactoringDoors.all(
                    () -> service, new RefactoringChangeCache())) {
            doors.put(door.getName(), door);
        }

        Map<String, String> rejected = new LinkedHashMap<>();
        int examined = 0;
        for (String kind : CureCatalog.declaredKinds()) {
            if (CureTier.derive(kind, registry).tier() != CureTier.Tier.RUN) {
                continue;
            }
            Detector detector = catalog.get(kind).orElse(null);
            if (detector == null) {
                continue;   // named and explained by the test above; not re-reported here
            }
            ObjectNode args = mapper.createObjectNode();
            args.put("includeTests", true);
            ToolResponse r = detector.detect(service, args);
            if (!r.isSuccess()) {
                continue;
            }
            List<Map<String, Object>> rows = findingsOf(r);
            if (rows.isEmpty()) {
                continue;
            }
            CodeAddress address = addressOf(rows.get(0));
            for (CureCatalog.Cure cure : CureCatalog.curesFor(kind)) {
                String recipe = cure.recipe();
                int marker = recipe == null ? -1 : recipe.indexOf(" kind=");
                if (marker < 0) {
                    // A design-only cure, or a bare pattern operation that names no door —
                    // neither is a door call and neither is this invariant's subject.
                    continue;
                }
                org.jawata.mcp.tools.AbstractTool door =
                    doors.get(recipe.substring(0, marker));
                if (door == null) {
                    continue;
                }
                examined++;
                String failure = addressRefusalOf(door,
                    recipe.substring(marker + " kind=".length()), address, mapper);
                if (failure != null) {
                    // The ADDRESS is in the message, not just the refusal: a reader of this
                    // failure needs to see what was handed over, or the only way to find out
                    // is another run.
                    rejected.put(kind + " -> " + recipe,
                        failure + "   [address: " + address.arguments() + "]");
                }
            }
        }

        // PROOF OF LIFE. Every `continue` above is a legitimate skip, and enough of them
        // together would leave this test asserting over nothing — the shape this sprint has
        // found five times. The floor is well under the count so it measures emptiness
        // rather than pinning today's number.
        int drove = examined;
        assertTrue(examined >= 15,
            () -> "this gate drove only " + drove + " cure/door pairs; with that few, an"
                + " empty failure list says nothing about the routing table");

        assertTrue(rejected.isEmpty(),
            "these cures are routed from a finding whose address their own door will not"
                + " take, so the product renders an instruction it then refuses:\n  "
                + join(rejected));
    }

    /**
     * Drive one door with one finding's address, and return the ADDRESS-shaped refusal it
     * gave, or null when it accepted the address (whatever it then did).
     */
    private static String addressRefusalOf(org.jawata.mcp.tools.AbstractTool door,
                                           String kind, CodeAddress address,
                                           ObjectMapper mapper) {
        String discriminator = door instanceof org.jawata.mcp.tools.FrontDoor front
            ? front.discriminator() : "kind";
        ObjectNode args = mapper.createObjectNode();
        args.put(discriminator, kind);
        args.put("auto_apply", false);
        for (Map.Entry<String, Object> entry : address.arguments().entrySet()) {
            if (entry.getValue() instanceof Integer number) {
                args.put(entry.getKey(), number);
            } else {
                args.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        }
        org.jawata.mcp.models.ToolResponse response;
        try {
            response = door.execute(args);
        } catch (Throwable reachedTheDelegate) {
            // Past the door's own guards, which is the acceptance being asserted — the same
            // reading EveryRenderedInvocationIsAcceptedByItsDoorTest states for its catch.
            return null;
        }
        if (response == null || response.isSuccess() || response.getError() == null) {
            return null;
        }
        org.jawata.mcp.models.ErrorInfo error = response.getError();
        String code = String.valueOf(error.getCode());
        String message = String.valueOf(error.getMessage());
        if (org.jawata.mcp.models.ErrorInfo.SYMBOL_NOT_FOUND.equals(code)
            || org.jawata.mcp.models.ErrorInfo.FILE_NOT_FOUND.equals(code)
            || org.jawata.mcp.models.ErrorInfo.INVALID_COORDINATES.equals(code)) {
            return code + " / " + message;
        }
        if (org.jawata.mcp.models.ErrorInfo.INVALID_PARAMETER.equals(code)) {
            for (String named
                    : List.of("symbol", "typeName", "filePath", "line", "column",
                        discriminator)) {
                if (message.contains("'" + named + "'")) {
                    return code + " / " + message;
                }
            }
        }
        return null;
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
