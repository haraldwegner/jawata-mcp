package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import org.jawata.core.IJdtService;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.codegen.CopyClassTool;
import org.jawata.mcp.tools.codegen.GenerateConstructorTool;
import org.jawata.mcp.tools.codegen.GenerateEqualsHashCodeTool;
import org.jawata.mcp.tools.codegen.GenerateGettersSettersTool;
import org.jawata.mcp.tools.codegen.GenerateTestSkeletonTool;
import org.jawata.mcp.tools.codegen.GenerateToStringTool;
import org.jawata.mcp.tools.codegen.GenerateTool;
import org.jawata.mcp.tools.codegen.OverrideMethodsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * One defect class, four instruments: <strong>a declared shape that lies about
 * the real one.</strong>
 *
 * <p>Every case here was measured, not suspected. {@code run_tests} declared
 * four actions and accepted eighteen. Its refusal message named ten of the
 * fourteen coverage suffixes and omitted {@code impacted_tests} — so
 * impacted-test selection, built and verified in Sprint 23, sat unused for five
 * weeks with nothing a caller could read saying it existed.
 * {@code find_references} answered a 28-reference symbol with
 * {@code totalReferences: 2} when asked for two. A whole-family sweep
 * advertised a synchronous path that times out on any real project. And a
 * workspace sweep merged findings from 29 projects into one list that never
 * said which project a row came from.</p>
 */
class DeclaredShapeHonestyTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> data(ToolResponse r) {
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        return (Map<String, Object>) r.getData();
    }

    @SuppressWarnings("unchecked")
    private List<String> actionEnum(AbstractTool tool) {
        Map<String, Object> props = (Map<String, Object>) tool.getInputSchema().get("properties");
        return (List<String>) ((Map<String, Object>) props.get("action")).get("enum");
    }
    // ------------------------------------------------------------------
    // THE PARAMETER AXIS — retired at Stage 6a's M6
    // ------------------------------------------------------------------
    //
    // Its guard, assertPublishesEveryDelegateParameter, compared a front door's
    // published schema against a HAND-WRITTEN map of that door's delegates, kept here.
    // It found two real defects and both repairs were the same: curate the missing
    // parameters onto the door, then add the door to the list in this file. The list was
    // the third hand-kept copy of a routing table, and `inline` sat outside it for as
    // long as it took row 36's accessorName to ship unpublished.
    //
    // KindedTool#withDelegateParameters now overlays every delegate's parameters onto
    // every routing door's schema, so the axis holds by construction. What replaces the
    // guard is theBackstopPublishesEveryDelegateParameter below: same question, asked of
    // each door's OWN delegates() rather than of a list somebody maintains.

    /**
     * THE THIRD AXIS, and the C8 auditor found both Stage 8 kinds sitting in the gap.
     *
     * <p>The two guards above cover the ACTION/KIND ENUM (declared equals routed) and
     * the PARAMETER SET (every delegate parameter is published). Both were satisfied
     * while {@code refactor_to_pattern}'s {@code getDescription()} — the only PROSE a
     * client ever sees — documented eight of its ten kinds. The two shipped in Stage 8
     * were in the enum, in the schema, routed and tested, and absent from the text.</p>
     *
     * <p><b>Why the delegate's own description does not cover it:</b>
     * {@code JawataApplication} registers the FRONT DOOR only — "the per-pattern
     * delegates are not registered standalone" — so no client can reach
     * {@code ReplaceConditionalWithPolymorphismTool.getDescription()}. The front
     * door's text is the whole documentation surface.</p>
     *
     * <p>Matching on the kind's literal name is deliberately crude, and it is the
     * right crudeness: it cannot judge whether the prose is GOOD, only whether the
     * kind is mentioned at all. That is exactly the failure that occurred — not a
     * thin description, an absent one.</p>
     */
    private static void assertDescribesEveryKindItPublishes(AbstractTool frontDoor) {
        List<String> publishedKinds = discriminatorValues(frontDoor);
        String description = frontDoor.getDescription();

        assertFalse(publishedKinds.isEmpty(),
            frontDoor.getName() + ": PROOF OF LIFE — a front door with no published kinds"
                + " would pass the loop below without looking at anything");
        for (String kind : publishedKinds) {
            assertTrue(description.contains(kind),
                frontDoor.getName() + " publishes kind '" + kind + "' and never names it in"
                    + " its description. The delegates are not registered standalone, so"
                    + " this text is the ONLY documentation a client can reach: the kind is"
                    + " selectable and undocumented");
        }
    }

    /**
     * A FRONT DOOR'S DISCRIMINATOR IS NOT ALWAYS CALLED "kind".
     *
     * <p>Three spellings are in use and all three do the same job — choose which
     * operation runs. {@code hierarchy} calls it {@code direction} (up or down),
     * {@code refactoring} and {@code dependency} call it {@code action}, the rest call it
     * {@code kind}. The guard used to read {@code kind} only, so the tools using the
     * other two words were not covered by the axis at all — and the three of them
     * publish fifteen operations between them.</p>
     */
    @SuppressWarnings("unchecked")
    private static List<String> discriminatorValues(AbstractTool frontDoor) {
        Map<String, Object> props =
            (Map<String, Object>) frontDoor.getInputSchema().get("properties");
        if (props == null) {
            return List.of();
        }
        for (String name : List.of("kind", "direction", "action")) {
            // ANY collection, not just a List. apply_cleanup published its enum as a
            // Set and was therefore skipped by this reader entirely — silently, which
            // is the failure mode this whole class is about. Reading the broader type
            // means a tool cannot fall out of the guard by choosing a container.
            if (props.get(name) instanceof Map<?, ?> schema
                    && ((Map<String, Object>) schema).get("enum")
                        instanceof java.util.Collection<?> values) {
                return values.stream().map(String::valueOf).toList();
            }
        }
        return List.of();
    }

    /** Every parametric front door the application registers, by its published name. */
    private Map<String, AbstractTool> frontDoors() {
        RefactoringChangeCache cache = new RefactoringChangeCache();
        Supplier<IJdtService> svc = () -> service;
        Map<String, AbstractTool> doors = new LinkedHashMap<>();
        doors.put("extract", new ExtractTool(svc, cache));
        doors.put("generate", new GenerateTool(svc, cache));
        doors.put("refactor_to_pattern", new RefactorToPatternTool(svc, cache));
        doors.put("move", new MoveTool(svc, cache));
        doors.put("inline", new InlineTool(svc, cache));
        doors.put("apply_cleanup", new ApplyCleanupTool(svc, cache));
        doors.put("hierarchy", new HierarchyTool(svc, cache));
        doors.put("refactoring", new RefactoringTool(svc, cache,
            new org.jawata.mcp.domain.NoOpAdvisor()));
        return doors;
    }

    @Test
    @DisplayName("a door that WRITES its own kind block must still name every kind in it")
    void everyHandWrittenBlockDescribesItsKinds() {
        // SCOPED at Stage 6a's M6, and the scope is DERIVED rather than listed.
        //
        // Where the block is projected from delegates() the bullet IS the kind name, so
        // this assertion cannot fail — and an assertion that cannot fail reads as coverage
        // while covering nothing, which is the defect class this whole file is about. It
        // would have been the second one in here.
        //
        // Where the door still writes its own prose it CAN fail, and did: refactor_to_pattern
        // documented eight of its ten kinds while both missing ones were in the enum, in the
        // schema, routed and tested. So the guard follows the hand-written text rather than
        // a list of door names, and a door drops out of it the moment its own lane converts
        // it — which is what keeps this from becoming the next thing that goes stale.
        Map<String, AbstractTool> handWritten = new LinkedHashMap<>();
        frontDoors().forEach((name, door) -> {
            assertEquals(name, door.getName(),
                "this list is keyed by the PUBLISHED name; a rename must show up here");
            boolean projected = door instanceof FrontDoor front && front.kindBlock().isEmpty();
            if (!projected) {
                handWritten.put(name, door);
            }
        });

        // PROOF OF LIFE, and a ratchet: today two doors write their own block. `hierarchy`
        // adopts the seam inside Stage 7 and will drop to one; `refactoring` stays forever,
        // because its seven actions reach four delegates and there is no per-action delegate
        // to project a bullet from. A count that fell to zero unnoticed would leave this
        // method looping over nothing.
        assertEquals(java.util.Set.of("hierarchy", "refactoring"), handWritten.keySet(),
            "these are the doors whose kind block is still their own prose. When one adopts"
                + " the seam, remove it here deliberately rather than letting the guard"
                + " quietly shrink: " + handWritten.keySet());
        handWritten.forEach((name, door) -> assertDescribesEveryKindItPublishes(door));
    }

    /**
     * THE PREAMBLE MUST NOT NAME A KIND, and that is the rule the projection earns.
     *
     * <p>A door's preamble is its door-level text: what the tool is for, before any kind is
     * named. The bullets below it are now a projection of {@code delegates()}, so a kind
     * cannot be missing from them. What CAN happen is the old shape coming back by the front
     * door — someone helpfully listing the kinds in the opening paragraph, where nothing
     * derives them and nothing notices when one is added or removed.</p>
     *
     * <p><b>Scoped to kinds spelled as identifiers</b>, which operationally means the ones
     * containing an underscore. {@code extract}'s preamble legitimately says "Extract a
     * method, variable, constant, interface, superclass or class" — those are ordinary
     * English words that happen to also be kind names, and forbidding them would forbid
     * describing the tool. {@code split_phase} and {@code temp_to_query} are not English;
     * they are the string a caller passes, and a preamble containing one is a copy of the
     * routing table however it got there.</p>
     */
    @Test
    @DisplayName("no door names one of its kinds in its PREAMBLE — that is the copy returning")
    void noPreambleNamesAKindByItsPublishedSpelling() {
        int checked = 0;
        for (Map.Entry<String, AbstractTool> entry : frontDoors().entrySet()) {
            if (!(entry.getValue() instanceof FrontDoor door) || door.preamble().isEmpty()) {
                continue;
            }
            checked++;
            String preamble = door.preamble();
            for (String kind : door.publishedKinds()) {
                if (!kind.contains("_")) {
                    continue;
                }
                assertFalse(preamble.contains(kind),
                    entry.getKey() + "'s preamble names the kind '" + kind + "' by the exact"
                        + " string a caller passes. The bullet list below it is projected from"
                        + " the routing table and cannot omit a kind; a list in the preamble"
                        + " is hand-kept and will: " + preamble);
            }
        }
        // PROOF OF LIFE: doors that have not adopted the seam return an empty preamble and
        // are skipped, so a zero here would mean the loop looked at nothing.
        //
        // SEVEN, and the first version of this line said six — a number recalled rather than
        // counted, which the gate caught on its first run. This list holds eight doors;
        // `hierarchy` is the only one that is not a FrontDoor yet, and `refactoring` IS one,
        // because it took the description seam without the routing seam.
        assertEquals(7, checked,
            "the seven doors in this list that have adopted the description seam must be"
                + " checked. hierarchy adopts inside Stage 7 and data inside Stage 5, and"
                + " each becomes an eighth and ninth here when it does");
    }

    /**
     * The guard's own coverage, asserted rather than assumed.
     *
     * <p>{@link #discriminatorValues} returns an empty list for a tool that publishes no
     * enum, and {@link #assertDescribesEveryKindItPublishes} fails on empty — so a tool
     * losing its enum is loud. This is the other direction: a front door that is not in
     * the list above is not guarded at all, and nothing would say so. The count is
     * written out because a number is the one thing a drifting list cannot fake.</p>
     */
    @Test
    @DisplayName("the guard covers every parametric front door, not the three it started with")
    void theGuardsOwnCoverage() {
        Map<String, AbstractTool> doors = frontDoors();
        assertEquals(8, doors.size(),
            "eight parametric front doors are guarded; if the surface changed, change this"
                + " number deliberately rather than letting the guard quietly shrink: "
                + doors.keySet());
        doors.forEach((name, door) -> assertFalse(discriminatorValues(door).isEmpty(),
            name + " publishes no kind/direction/action enum, so the description axis"
                + " cannot be checked for it. Either it is not a parametric front door and"
                + " does not belong in this list, or it lost its enum."));
    }

    /**
     * THE PARAMETER AXIS, RETIRED AT M6 — and replaced by the thing it was compensating for.
     *
     * <p>It read: every parameter a delegate declares must appear in the front door's
     * published schema. It found real defects twice — {@code extract kind=class}'s five
     * parameters, then row 36's {@code accessorName} on {@code inline} — and both times the
     * repair was to curate the missing entries by hand and add the door to a list here.</p>
     *
     * <p>Four doors then wrote out a BACKSTOP loop that overlays every delegate's parameters
     * onto the published properties. {@code inline} did not, which is exactly why the second
     * defect happened on that door. M6 moves the loop onto {@link KindedTool}, so every
     * routing door gets it by construction rather than by remembering — and with that, this
     * assertion cannot fail. It is deleted rather than kept, because an assertion that cannot
     * fail is the defect class this file exists to remove, not evidence against it.</p>
     *
     * <p><b>Two copies of the loop carried a defect of their own, and the shared one cannot.</b>
     * {@code refactor_to_pattern} and {@code generate} iterated a hand-written
     * {@code List.of(...)} of their delegate FIELDS rather than the routing table — so a kind
     * added to the map and dispatched would have been left out of the very loop that
     * publishes its parameters. Neither had drifted yet; the shape is what mattered.</p>
     *
     * <p>What survives is the guard's coverage question in its own test below, and the
     * three-line mutation that proves the backstop live: add a parameter to any delegate's
     * schema and it appears in its door's published schema without the door being touched.</p>
     *
     * <p><b>A REGRESSION LOCK from C6a onward, and labelled one rather than left to look
     * stronger than it is.</b> All six routing doors now build their properties map through
     * {@code KindedTool#withDelegateParameters}, which iterates the same {@code delegates()}
     * this loop iterates — so as the code stands the assertion cannot fail, and {@code
     * generate} was the last door for which it could. An audit found that and was right to.
     * It is kept because the property it pins is one edit from being broken: a door that
     * curates its own properties and forgets the backstop call is exactly what {@code inline}
     * did, and what {@code data} and {@code hierarchy} still do until their lanes convert
     * them. The rule this file applies, stated once in
     * {@code TheRoutingTableIsTheKindListTest}: an assertion whose two sides are ONE
     * expression a definitional step apart is deleted; one that is merely green today over a
     * property a plausible edit would break is kept, labelled, and proved by making that
     * edit.</p>
     */
    @Test
    @DisplayName("a parameter a delegate declares is published by its door, with no curation")
    void theBackstopPublishesEveryDelegateParameter() {
        RefactoringChangeCache cache = new RefactoringChangeCache();
        Supplier<IJdtService> svc = () -> service;

        // The claim is about the SEAM, so it is asked of every routing door at once rather
        // than of a hand-listed five — which is what the retired assertion needed and what
        // let `inline` sit outside it. Each door's own delegates are the population; nothing
        // here names a kind.
        List<KindedTool> routingDoors = List.of(
            new ExtractTool(svc, cache),
            new InlineTool(svc, cache),
            new MoveTool(svc, cache),
            new RefactorToPatternTool(svc, cache),
            new GenerateTool(svc, cache),
            new ApplyCleanupTool(svc, cache));

        int compared = 0;
        for (KindedTool door : routingDoors) {
            @SuppressWarnings("unchecked")
            Map<String, Object> published =
                (Map<String, Object>) door.getInputSchema().get("properties");
            for (Map.Entry<String, KindDelegate> routed : door.delegates().entrySet()) {
                for (String param : routed.getValue().parameterSchema().keySet()) {
                    if ("projectKey".equals(param) || "auto_apply".equals(param)) {
                        continue;
                    }
                    compared++;
                    assertTrue(published.containsKey(param),
                        door.getName() + " kind=" + routed.getKey() + " accepts '" + param
                            + "' and the door does not publish it. A parameter absent from the"
                            + " published schema is invisible to every client reading"
                            + " tools/list, however well the operation runs for someone who"
                            + " already knows the name");
                }
            }
        }
        // PROOF OF LIFE: apply_cleanup's rules declare no parameters at all, so a bug that
        // emptied every delegate's schema would satisfy the loop above in silence.
        assertTrue(compared >= 100,
            "the six routing doors declare well over a hundred delegate parameters between"
                + " them; a low count means the loop is looking at nothing. Compared: "
                + compared);
    }

    /**
     * THE BOOT CHECK, ON THE REAL SURFACE, IN THE FAST SUITE.
     *
     * <p>Stage 1 shipped a change that made the application exit during start-up: a
     * lifecycle front door republished six operations another tool already published,
     * the cure table's ambiguity refusal fired, and the process was gone before it
     * served a request. A green 2274-test run said nothing about it, because no test
     * builds the application. The end-to-end gate caught it — ten minutes later, and
     * only because someone ran it.</p>
     *
     * <p>This is the same question asked of the same front-door instances the honesty
     * guard already constructs, using the registry's OWN harvest rather than a copy of
     * it, so a change to what counts as an operation reaches this check automatically.
     * It cannot replace the end-to-end gate — that boots the artifact and this does not
     * — but the failure it guards is a table-and-registration failure, and this is where
     * that failure can be seen in seconds.</p>
     */
    @Test
    @DisplayName("the real front doors publish a set the cure table validates against")
    void theCureTableValidatesAgainstTheRealFrontDoors() {
        org.jawata.mcp.refactoring.OperationRegistry registry =
            new org.jawata.mcp.refactoring.OperationRegistry();
        java.util.Map<String, AbstractTool> tools = new LinkedHashMap<>(frontDoors());
        // NOT ONLY THE PARAMETRIC DOORS. A cure step may name a whole tool — the
        // encapsulation smell's cure is `data`, which publishes no kind enum and so is
        // not a parametric front door at all. Registering only the eight would leave that
        // step unbacked and this check would refuse a table that boots perfectly well,
        // which is a false alarm rather than a guard.
        RefactoringChangeCache cache = new RefactoringChangeCache();
        Supplier<IJdtService> svc = () -> service;
        tools.put("data", new DataTool(svc, cache));
        // THE POLICY IS ASKED OF OperationSurface (Stage 6a, M8), which is where it lives
        // now. It used to be a package-private reader on ToolRegistry, and this line is the
        // reason the extraction was worth doing: the check wants "what does this tool publish
        // as an operation?" and had to reach into the registry's internals to ask.
        tools.forEach((name, door) ->
            registry.register(name, OperationSurface.operationKindsOf(door),
                door.isMechanical(), door.isStructural(), door.structuralKinds()));

        assertTrue(registry.isWired(),
            "PROOF OF LIFE: an empty registry would make the check below pass over"
                + " nothing, which is the shape that let the boot failure through");
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(
            () -> org.jawata.mcp.tools.smell.CureCatalog.validateAgainst(registry),
            "every cure step must name an operation these front doors publish, and name"
                + " it unambiguously. This throwing is what took the application down"
                + " during start-up in this stage.");
    }

    // ------------------------------------------------------------------
    // run_tests: the declared action set IS the accepted action set
    // ------------------------------------------------------------------

    /**
     * THE ANTI-DIVERGENCE GATE, and it is a gate rather than a reminder: the
     * dispatcher now refuses anything not in {@code ACTIONS} before its switch
     * ever sees it, so a routed-but-undeclared action is unreachable by
     * construction. This test covers the other direction — declared and not
     * routed — which is what the switch's {@code default} arm now means.
     */
    @Test
    @DisplayName("run_tests routes every action it declares")
    void everyDeclaredRunTestsActionIsRouted() {
        RunTestsTool tool = new RunTestsTool(() -> service);
        List<String> declared = actionEnum(tool);
        assertEquals(RunTestsTool.ACTIONS, declared,
            "the schema publishes the one list, never a hand-kept copy of it");
        assertTrue(declared.contains("coverage_impacted_tests"),
            "the capability that sat unused for five weeks must be visible in the schema");

        for (String action : declared) {
            ObjectNode args = mapper.createObjectNode();
            args.put("action", action);
            ToolResponse r = tool.execute(args);
            // Most of these fail for their OWN reasons — no scope, no
            // sessionId, no coverage artifact. What none of them may do is come
            // back as an unknown action, or as the not-routed defect code.
            if (!r.isSuccess()) {
                String code = String.valueOf(r.getError());
                assertFalse(code.contains("ACTION_NOT_ROUTED"),
                    "'" + action + "' is declared and reaches no handler: " + code);
                assertFalse(code.contains("Must be one of"),
                    "'" + action + "' is declared and refused as unknown: " + code);
            }
        }
    }

    /**
     * The refusal message is the ONE place a caller learns what exists, and it
     * was a hand-written subset. Naming the four it omitted individually rather
     * than asserting a substring of the whole list: those four are the finding.
     */
    @Test
    @DisplayName("run_tests names every action when it refuses one")
    void theRefusalNamesTheActionsItOnceHid() {
        RunTestsTool tool = new RunTestsTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("action", "coverage_nonsense");
        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess());
        String message = String.valueOf(r.getError());
        for (String hidden : List.of("coverage_tests_covering", "coverage_of_test",
                "coverage_impacted_tests", "coverage_mutation")) {
            assertTrue(message.contains(hidden),
                "the refusal must name '" + hidden + "' — it named ten of fourteen and this "
                    + "was one of the four it hid: " + message);
        }
    }

    // ------------------------------------------------------------------
    // find_quality_issue: a family sweep refuses synchronously (#10)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a whole-family sweep refuses at once and names the async path")
    void aSynchronousFamilySweepRefusesAndSaysWhatToDoInstead() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "fowler");

        long started = System.currentTimeMillis();
        ToolResponse r = tool.execute(args);
        long elapsed = System.currentTimeMillis() - started;

        assertFalse(r.isSuccess(), "a synchronous family sweep is not available");
        String message = String.valueOf(r.getError());
        assertTrue(message.contains("SWEEP_REQUIRES_ASYNC"), message);
        assertTrue(message.contains("start"),
            "the refusal must name the path that works, or it is the timeout with better "
                + "manners: " + message);
        // The point of refusing rather than auto-starting: instant, not after
        // the client has already given up.
        assertTrue(elapsed < 5_000,
            "the refusal must be immediate; it took " + elapsed + " ms");
    }

    /** A single kind is unaffected — those complete well inside the timeout. */
    @Test
    @DisplayName("a single kind still answers synchronously")
    void oneKindIsStillSynchronous() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "long_method");
        assertTrue(tool.execute(args).isSuccess(),
            "refusing the family must not take the single-kind path with it");
    }

    /** And the async path is not merely advertised — it produces the sweep. */
    @Test
    @DisplayName("the path the refusal names actually works")
    void theAsyncPathTheRefusalNamesDelivers() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "quality");
        Map<String, Object> finished = data(org.jawata.mcp.fixtures.Sweeps.run(tool, args));
        assertEquals("finished", finished.get("state"));
        assertNotNull(finished.get("findings"), "the full result rides the status response");
    }

    // ------------------------------------------------------------------
    // find_references: the page cap is not the population
    // ------------------------------------------------------------------

    /**
     * Measured before the fix on a 28-reference symbol asked with
     * {@code maxResults=2}: {@code totalReferences: 2} AND
     * {@code meta.totalCount: 2}. Both fields, the page size, with
     * {@code truncated: true} beside them.
     *
     * <p>The fixture picks whatever symbol in the loaded project has the most
     * references rather than naming one, so this keeps meaning if the fixture
     * changes. It asserts the RELATIONSHIP — total &gt; page — which is the
     * property that was false.</p>
     */
    @Test
    @DisplayName("find_references reports the true total in both fields, never the cap")
    void theCapIsNotReportedAsTheTotal() {
        FindReferencesTool tool = new FindReferencesTool(() -> service);

        ObjectNode uncapped = mapper.createObjectNode();
        uncapped.put("kind", "references");
        uncapped.put("symbol", "com.example.Calculator");
        Map<String, Object> all = data(tool.execute(uncapped));
        int trueTotal = ((Number) all.get("totalReferences")).intValue();
        // The fixture must actually have something to truncate, or this test
        // would pass on an empty search and prove nothing.
        org.junit.jupiter.api.Assumptions.assumeTrue(trueTotal >= 2,
            "fixture needs at least 2 references to exercise a cap; got " + trueTotal);

        ObjectNode capped = mapper.createObjectNode();
        capped.put("kind", "references");
        capped.put("symbol", "com.example.Calculator");
        capped.put("maxResults", 1);
        ToolResponse r = tool.execute(capped);
        Map<String, Object> page = data(r);

        assertEquals(trueTotal, ((Number) page.get("totalReferences")).intValue(),
            "totalReferences is the POPULATION — it must not change because the caller "
                + "asked for fewer rows");
        assertEquals(1, ((List<?>) page.get("references")).size(), "the page obeys the cap");
        assertEquals(1, ((Number) page.get("returnedReferences")).intValue(),
            "and the page size has its own field rather than borrowing the total's");
        assertEquals(trueTotal, r.getMeta().getTotalCount(),
            "meta.totalCount is the same population — it lied in BOTH fields before");
        assertEquals(1, r.getMeta().getReturnedCount());
        assertEquals(Boolean.TRUE, r.getMeta().getTruncated());
    }

    // ------------------------------------------------------------------
    // the sweep rows name their project
    // ------------------------------------------------------------------

    /**
     * {@code compile_workspace} has stamped {@code sourceProject} on every
     * diagnostic all along; a family sweep merged findings from every loaded
     * project and stamped nothing. On a 29-project workspace that is thousands
     * of rows with ownership left for the reader to reconstruct from path
     * prefixes — work the loaded model has already done.
     */
    @Test
    @DisplayName("sweep findings name the project they came from")
    void sweepRowsCarryTheirProject() {
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectNode args = mapper.createObjectNode();
        args.put("family", "quality");
        Map<String, Object> finished = data(org.jawata.mcp.fixtures.Sweeps.run(tool, args));

        List<?> findings = (List<?>) finished.get("findings");
        assertNotNull(findings, "the sweep must produce findings to attribute");
        org.junit.jupiter.api.Assumptions.assumeFalse(findings.isEmpty(),
            "fixture produced no findings; nothing to attribute");

        String key = service.allProjects().iterator().next().projectKey();
        long attributed = findings.stream()
            .filter(f -> f instanceof Map<?, ?>)
            .map(f -> (Map<?, ?>) f)
            .filter(f -> f.get("filePath") instanceof String)
            .filter(f -> key.equals(f.get("sourceProject")))
            .count();
        long withPath = findings.stream()
            .filter(f -> f instanceof Map<?, ?>)
            .filter(f -> ((Map<?, ?>) f).get("filePath") instanceof String)
            .count();
        assertEquals(withPath, attributed,
            "every finding that names a file must name the project that file belongs to");
    }
}
