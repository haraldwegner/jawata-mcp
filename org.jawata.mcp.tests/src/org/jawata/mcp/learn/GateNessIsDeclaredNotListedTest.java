package org.jawata.mcp.learn;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.OperationRegistry;
import org.jawata.mcp.tools.OperationSurface;
import org.jawata.mcp.tools.Tool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28g S9a M1 — GATE-NESS IS DECLARED BY THE TOOL, not listed in {@code learn/}.
 *
 * <p>Gate-ness lived as a byte-identical {@code Set.of("compile_workspace",
 * "get_diagnostics", "run_tests")} in THREE classes of this package at once — the event tap,
 * these server checks and the experience recorder — each with its own reader, beside the same
 * three names a fourth time in the steering message {@code seatOutcomeCheck} returns. Four
 * places to keep in step and nothing able to notice, because no reference-updating
 * refactoring touches a string literal. {@code ArchitectGate}, in this same package, had
 * already made this exact repair for structural-ness and recorded what the old shape cost it:
 * the gate <em>"went quiet for pull-up, push-down and the method move without anything
 * failing"</em>.</p>
 *
 * <h2>Why there are TWO assertions and not one</h2>
 *
 * <p>The behavioural half alone is not enough, and that is measured rather than assumed.
 * {@link #gateNessFollowsTheToolUnderANameNoLiteralEverHeld()} goes red while ANY ONE of the
 * three copies is the thing being read — but it goes GREEN the moment the copy on the path it
 * drives is replaced, leaving the other two standing. Two of the three were readable only
 * from classes this test does not enter. So {@link #noGateNameSetSurvivesOutsideTheRegistry()}
 * is a census over production source, and it is the assertion that can see a copy nobody
 * calls. That is the shape {@code OperationRegistry} records against itself: <em>"a list in
 * another package cannot know that a name it holds has stopped existing."</em></p>
 *
 * <p><b>This test spells no gate name.</b> The needles come from the three tools' own
 * {@link Tool#isGate()} declarations, so the census cannot become the fifth copy of the list
 * it exists to forbid.</p>
 */
class GateNessIsDeclaredNotListedTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Production source roots, relative to the repository root. */
    private static final List<String> PRODUCTION_ROOTS =
        List.of("org.jawata.core/src", "org.jawata.mcp/src");

    /** The ONE file allowed to hold the gate names together: the registry they now live in. */
    private static final String THE_REGISTRY = "OperationRegistry.java";

    private OperationRegistry.Snapshot borrowed;

    @BeforeEach
    void captureTheSingleton() {
        borrowed = OperationRegistry.theRegistry().snapshot();
    }

    @AfterEach
    void restoreTheSingleton() {
        OperationRegistry.theRegistry().restore(borrowed);
    }

    /**
     * The three shipped gates and a registry holding them — both asked of
     * {@link org.jawata.mcp.fixtures.ShippedGates}, which the four lane tests this change
     * repaired also read. Two implementations of "who are the gates" is the defect this
     * whole step removes, so the fixture is shared rather than copied.
     */
    private static List<Tool> theGateTools() {
        return org.jawata.mcp.fixtures.ShippedGates.tools();
    }

    private static OperationRegistry registryOfRealTools() {
        return org.jawata.mcp.fixtures.ShippedGates.registry();
    }

    private static JsonNode seatOutcomeArguments() {
        try {
            return MAPPER.readTree("{\"kind\":\"record\",\"operation\":\"seat:refactor\"}");
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    // ---------------------------------------------------------------- (i) the behaviour

    @Test
    @DisplayName("a tool declaring itself a gate is believed under a name no literal ever held")
    void gateNessFollowsTheToolUnderANameNoLiteralEverHeld() {
        // A GATE THAT NO SET EVER CONTAINED. This is the whole point: with gate-ness held as
        // a literal, this name could never be a gate however the tool declared itself, so
        // this assertion is red at HEAD by construction rather than by coincidence.
        String freshGate = "verify_the_thing";
        Tool declaresItselfAGate = new Tool() {
            @Override
            public String getName() {
                return freshGate;
            }

            @Override
            public String getDescription() {
                return "a gate, declared rather than listed";
            }

            @Override
            public Map<String, Object> getInputSchema() {
                return Map.of();
            }

            @Override
            public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }

            @Override
            public boolean isGate() {
                return true;
            }
        };

        OperationRegistry registry = new OperationRegistry();
        registry.register(declaresItselfAGate.getName(), declaresItselfAGate.publishedKinds(),
            declaresItselfAGate.isMechanical(), declaresItselfAGate.isStructural(),
            declaresItselfAGate.isGate(), declaresItselfAGate.structuralKinds(), "kind");

        SessionLedger ledger = new SessionLedger();
        ledger.record(CallerIdentity.supplied("s1"), new SessionLedger.CallRecord(freshGate, true, 0,
            System.currentTimeMillis(), null));
        ServerChecks checks = new ServerChecks(null, null, registry);

        assertNull(checks.acceptance(CallerIdentity.supplied("s1"), "experience",
                seatOutcomeArguments(), ledger),
            "the session called a tool that DECLARES itself a gate, so the seat outcome is"
                + " gated and the lane must stay silent — with gate-ness held as a literal"
                + " this name is not in any set and the check fires");
    }

    @Test
    @DisplayName("the CONTROL: a tool that declares nothing is still not a gate")
    void aToolThatDeclaresNothingIsNotAGate() {
        // Without this, the assertion above passes against a lane that treats EVERY call as
        // a gate — which is the same answer as no enforcement at all.
        OperationRegistry registry = registryOfRealTools();
        SessionLedger ledger = new SessionLedger();
        ledger.record(CallerIdentity.supplied("s2"), new SessionLedger.CallRecord("search_symbols", true, 0,
            System.currentTimeMillis(), null));
        ServerChecks checks = new ServerChecks(null, null, registry);

        ToolResponse refused = checks.acceptance(CallerIdentity.supplied("s2"),
            "experience", seatOutcomeArguments(), ledger);
        assertNotNull(refused, "a seat outcome over a session that ran NO gate must be flagged");
        assertAll(
            // KEYED ON THE CODE, not on prose. This assertion used to pin the steer by the
            // sentence "A gate you did not run has NOT passed", and it broke the moment that
            // sentence was reworded to cover a gate that ran and FAILED — a true statement
            // about the refusal, matched by a needle that had nothing to do with its identity.
            // A code is what a caller branches on and what a test should read.
            () -> assertEquals("SEAT_GATE_NOT_RUN", refused.getError().getCode(),
                "the refusal's identity is its CODE: " + refused.getError().getCode()),
            () -> assertTrue(refused.getError().getMessage().contains("SEAT DISCIPLINE"),
                "and the message is the seat-discipline steer rather than some other check: "
                    + refused.getError().getMessage()));
    }

    @Test
    @DisplayName("the MESSAGE names the gates by asking, so a retired gate leaves the text too")
    void theSeatDisciplineMessageNamesTheRegisteredGates() {
        // The fourth hardcoding was PROSE — the steer re-enumerated all three names — which
        // isGate() alone would have left standing while reading as fixed.
        OperationRegistry registry = registryOfRealTools();
        SessionLedger ledger = new SessionLedger();
        ledger.record(CallerIdentity.supplied("s3"), new SessionLedger.CallRecord("search_symbols", true, 0,
            System.currentTimeMillis(), null));

        String steer = refusalMessage(new ServerChecks(null, null, registry)
            .acceptance(CallerIdentity.supplied("s3"), "experience",
                seatOutcomeArguments(), ledger));
        assertNotNull(steer, "proof of life: the steer must be produced at all");
        for (String gate : registry.gates()) {
            assertTrue(steer.contains(gate),
                "the steer must name every registered gate; '" + gate + "' is missing from: "
                    + steer);
        }

        // AND IT MUST NOT NAME A GATE THAT IS NOT REGISTERED, which is what makes the loop
        // above a derivation rather than a coincidence against a hardcoded sentence.
        OperationRegistry oneGateOnly = new OperationRegistry();
        oneGateOnly.register("compile_only_fixture", List.of(), false, false, true,
            Set.of(), "kind");
        SessionLedger other = new SessionLedger();
        other.record(CallerIdentity.supplied("s4"), new SessionLedger.CallRecord("search_symbols", true, 0,
            System.currentTimeMillis(), null));
        String narrow = refusalMessage(new ServerChecks(null, null, oneGateOnly)
            .acceptance(CallerIdentity.supplied("s4"), "experience",
                seatOutcomeArguments(), other));
        assertNotNull(narrow, "proof of life for the narrow registry");
        assertTrue(narrow.contains("compile_only_fixture"),
            "it names the gate this registry actually holds: " + narrow);
        for (String shipped : registry.gates()) {
            assertFalse(narrow.contains(shipped),
                "and it must NOT name '" + shipped + "', which this registry never"
                    + " registered — a sentence still spelling the shipped three would pass"
                    + " the loop above and fail here: " + narrow);
        }
    }

    @Test
    @DisplayName("the publish seam carries gate-ness: the one production write forwards it")
    void theOperationSurfaceForwardsGateNess() {
        // registryOfRealTools() calls register DIRECTLY, so it cannot see OperationSurface
        // dropping tool.isGate() on the floor. This drives the single production write.
        for (Tool gate : theGateTools()) {
            OperationSurface.publish(gate);
        }
        for (Tool gate : theGateTools()) {
            assertTrue(OperationRegistry.theRegistry().isGate(gate.getName()),
                gate.getName() + " declares isGate() and OperationSurface.publish is the one"
                    + " production write, so the registry must answer for it");
        }
        assertFalse(OperationRegistry.theRegistry().isGate("search_symbols"),
            "and the seam must not make everything a gate");
    }

    // ---------------------------------------------------------------- (ii) the census

    @Test
    @DisplayName("no production file outside the registry holds the gate names as a set")
    void noGateNameSetSurvivesOutsideTheRegistry() throws IOException {
        Set<String> gateNames = new TreeSet<>();
        for (Tool gate : theGateTools()) {
            gateNames.add(gate.getName());
        }
        assertEquals(3, gateNames.size(),
            "proof of life: the three shipped gates must be found by asking them, or this"
                + " census scans for nothing and passes having looked at nothing");

        Path root = repositoryRoot();
        assumeTrue(root != null,
            "the production sources are not present — an honest skip beats a false pass");

        Set<String> offenders = new LinkedHashSet<>();
        for (Path file : productionSources(root)) {
            if (file.getFileName().toString().equals(THE_REGISTRY)) {
                continue;
            }
            String flat = codeOnly(Files.readString(file)).replaceAll("\\s+", "");
            // Every Set.of(...) in the file, then: does one of them name every gate? Matching
            // the arguments as a SET rather than the literal text means a re-introduced copy
            // cannot slip through by reordering its three names.
            Matcher setOf = Pattern.compile("Set\\.of\\(([^()]*)\\)").matcher(flat);
            while (setOf.find()) {
                String arguments = setOf.group(1);
                boolean holdsEveryGate = gateNames.stream()
                    .allMatch(name -> arguments.contains('"' + name + '"'));
                if (holdsEveryGate) {
                    offenders.add(root.relativize(file).toString());
                }
            }
        }

        assertEquals(Set.of(), offenders,
            "gate-ness is declared by each tool through Tool#isGate() and answered by"
                + " OperationRegistry. A Set.of naming every gate outside the registry is the"
                + " shape that went stale three times over: these files hold one — "
                + offenders);
    }

    /**
     * The source with its COMMENTS REMOVED, because the census must read code.
     *
     * <p><b>The first version of this census did not, and its own first run is what found
     * that.</b> It named FOUR files, and all four were honest: {@code Tool#isGate()},
     * {@code EventTap}, {@code ToolExperienceRecorder} and {@code ServerChecks} each QUOTE
     * the deleted {@code Set.of(...)} in their javadoc, because explaining why gate-ness
     * moved requires saying what it moved from. A census over raw text cannot tell a live
     * list from the documentation of a dead one, so it would have refused exactly the prose
     * that records the repair — and the only way to keep it green would have been to delete
     * that prose.</p>
     *
     * <p>Known bound, stated rather than discovered later: a {@code //} inside a string
     * literal (a URL) truncates that line, so a gate-name set written after one on the SAME
     * line would be missed. No such line exists, and the direction is the safe one for a
     * census whose job is to name offenders rather than to prove their absence.</p>
     */
    private static String codeOnly(String source) {
        return source.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
    }

    /**
     * The repository root, or null when the suite runs where the sources are not present.
     *
     * <p>Copied in shape from {@code HostBoundaryRulesTest}, deliberately: the alternative is
     * an absolute path, and a census test that hard-coded one developer's home directory into
     * committed source is why {@code ForkShapeCensusTest} was deleted.</p>
     */
    private static Path repositoryRoot() {
        for (Path candidate = Path.of("").toAbsolutePath(); candidate != null;
                candidate = candidate.getParent()) {
            if (Files.isDirectory(candidate.resolve("org.jawata.core/src"))) {
                return candidate;
            }
        }
        return null;
    }

    private static List<Path> productionSources(Path root) throws IOException {
        List<Path> files = new ArrayList<>();
        for (String relative : PRODUCTION_ROOTS) {
            Path dir = root.resolve(relative);
            if (!Files.isDirectory(dir)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(dir)) {
                walk.filter(p -> p.getFileName().toString().endsWith(".java")).forEach(files::add);
            }
        }
        return files;
    }
    /**
     * S9a M3: a seat outcome is REFUSED through the refusal channel rather than
     * appended as steering, so the sentence these cases read lives in the
     * refusal's message. Null means acceptance let the call through.
     */
    private static String refusalMessage(org.jawata.mcp.models.ToolResponse refused) {
        return refused == null ? null : refused.getError().getMessage();
    }
}
