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

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d S9a.1 — WHY D3's DECLARED DIRECTION IS UNAVAILABLE, measured.
 *
 * <p>Stage 9 could not run D3 as written (AWAY from a human-written factory, then
 * TOWARD, compared against the human's bytes) and recorded the reason as a CORPUS
 * fact: no site in the fork qualified. S9a re-measured that, found one shape that
 * seemed to — {@code ChapterResult.success/failure}, Lombok-only, package-private
 * constructor, all twelve callers in its own package — and then ran the trip on a
 * fixture shaped on it before paying to vendor the real slice.</p>
 *
 * <p><b>The reason is not the corpus. It is the operations.</b> Both findings below
 * were measured here, and neither depends on which repository the input comes from,
 * so vendoring the Lombok slice would not have changed either one.</p>
 *
 * <h2>What this fixture is, and its limit</h2>
 *
 * <p>{@code Outcome} is {@code ChapterResult}'s shape: generic, two-argument
 * package-private constructor, two intention-named factories each fixing one argument
 * to a different constant. {@code Verdict} is {@code Outcome} with the type parameter
 * removed and NOTHING else changed — the discriminator between the two candidate
 * causes of the first finding.</p>
 *
 * <p><b>It is authored, so it is NOT the D3 case and is never reported as one.</b>
 * D3 asks for code we did not author. What an authored pair buys is a controlled
 * variable: the two classes differ in exactly one property, so the first test's
 * refusal has a cause rather than a correlation.</p>
 */
class PartialApplicationRoundTripProbeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool pattern;
    private InlineMethodTool inline;
    private ObjectMapper mapper;
    private Path outcomeFile;
    private Path pipelineFile;
    private Path verdictFile;
    private Path reviewFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("partial-factory");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        pattern = new RefactorToPatternTool(() -> service, cache);
        inline = new InlineMethodTool(() -> service, cache);
        mapper = new ObjectMapper();
        Path pkg = helper.getTempDirectory()
            .resolve("partial-factory/src/main/java/com/example/partial");
        outcomeFile = pkg.resolve("Outcome.java");
        pipelineFile = pkg.resolve("Pipeline.java");
        verdictFile = pkg.resolve("Verdict.java");
        reviewFile = pkg.resolve("Review.java");
    }

    /** Zero-based line of the first occurrence, so no caret is a magic number. */
    private static int lineOf(String source, String needle) {
        String[] lines = source.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            if (lines[i].contains(needle)) {
                return i;
            }
        }
        throw new AssertionError("source no longer contains: " + needle);
    }

    private static int columnOf(String source, int line, String needle) {
        return source.split("\n", -1)[line].indexOf(needle);
    }

    /**
     * FINDING ONE — the AWAY leg cannot invert a GENERIC factory, and it says so.
     *
     * <p>{@code static <K> Outcome<K> success(K val)} returns {@code new Outcome<>(val,
     * SUCCESS)}. That diamond infers from the METHOD's own type parameter, which stops
     * existing the moment the body is folded into a caller. Inlining is therefore
     * refused with {@code REFACTORING_BROKE_COMPILE} — the engine attempted it, saw
     * the errors, and undid the change rather than leaving the tree broken.</p>
     *
     * <p><b>Why this decides D3 rather than merely annoying it:</b> the real
     * {@code ChapterResult.success/failure} is generic in exactly this way, and so is
     * every other generic factory in the survey. A generic self-returning factory is
     * how the pattern is normally written — the first two surveys were blind to
     * generics precisely because they are so common. So the AWAY leg is unavailable
     * for the shape D3 needs, whatever corpus supplies it, and no fixture choice or
     * dependency concession reaches it.</p>
     *
     * <p>The refusal is the RIGHT behaviour and is asserted as such: an inline that
     * left the callers uncompilable would be far worse than one that declines.</p>
     */
    @Test
    @DisplayName("S9a.1: AWAY refuses a generic partial-application factory, and undoes itself")
    void awayRefusesAGenericFactory() throws Exception {
        String outcomeBefore = Files.readString(outcomeFile);
        String pipelineBefore = Files.readString(pipelineFile);

        assertTrue(outcomeBefore.contains("public static <K> Outcome<K> success(K val)"),
            "PROOF OF LIFE: the fixture must carry the GENERIC partial application, or"
                + " this test measures a shape other than the one D3 needs");
        assertTrue(pipelineBefore.split("Outcome\\.success\\(", -1).length - 1 >= 2,
            () -> "the fixture must start with at least two success() call sites:\n"
                + pipelineBefore);

        int awayLine = lineOf(outcomeBefore, "public static <K> Outcome<K> success(K val)");
        ObjectNode away = mapper.createObjectNode();
        away.put("filePath", outcomeFile.toString());
        away.put("line", awayLine);
        away.put("column", columnOf(outcomeBefore, awayLine, "success("));

        ToolResponse a = inline.execute(away);
        String outcomeNow = Files.readString(outcomeFile);

        assertFalse(a.isSuccess(),
            () -> "AWAY SUCCEEDED on a generic factory. That is a BETTER outcome than the"
                + " one recorded, and it reopens D3's direction — do not delete this test,"
                + " re-derive S9a.1 from it.\n\n" + outcomeNow);
        assertTrue(String.valueOf(a.getError()).contains("Cannot infer type arguments"),
            () -> "the refusal must be the INFERENCE one this finding is about; a different"
                + " refusal is a different finding: " + a.getError());

        // AND IT UNDID ITSELF — the half that makes the refusal safe rather than merely
        // unhelpful. A broken tree left behind would be the worse failure.
        assertEquals(outcomeBefore, Files.readString(outcomeFile),
            "the declaring file must be byte-identical after a refused inline");
        assertEquals(pipelineBefore, Files.readString(pipelineFile),
            "the CALLING file must be byte-identical after a refused inline");
    }

    /**
     * FINDING TWO — with genericity removed the trip RUNS, and still does not close,
     * because our TOWARD leg writes a different KIND of factory than a human does.
     *
     * <p>{@code Verdict} differs from {@code Outcome} in one property, so this test's
     * success at the AWAY leg is what attributes finding one to genericity rather than
     * to partial application in general.</p>
     *
     * <p><b>What the trip produces, and it is the answer D3 was asked for.</b> The
     * human wrote {@code success(String val)}, which HIDES {@code State.SUCCESS} behind
     * a name that states an intention. The operation writes {@code of(String value,
     * State state)} — a FORWARDER exposing every constructor parameter — and the call
     * sites become {@code Verdict.of(order, Outcome.State.SUCCESS)}. So the trip does
     * not merely fail to reproduce the human's text: it pushes the constant the human
     * deliberately hid back out to every caller, which is the opposite of what Replace
     * Constructor with Factory Method is for.</p>
     *
     * <p>This is recorded as a finding about the operation, not smoothed, per C9. It is
     * NOT asserted to be a defect — a forwarder is a defensible thing for a mechanical
     * refactoring to produce. What it is not is what a human wrote, and D3 exists to
     * measure exactly that difference.</p>
     */
    @Test
    @DisplayName("S9a.1: without generics the trip runs, and returns a forwarder where the human wrote a partial application")
    void theNonGenericTripReturnsAForwarder() throws Exception {
        String verdictBefore = Files.readString(verdictFile);
        String reviewBefore = Files.readString(reviewFile);

        assertTrue(verdictBefore.contains("public static Verdict success(String val)"),
            "the discriminator must differ from Outcome in exactly one way: no type parameter");
        // DERIVED from the fixture, not hardcoded. Writing the number by hand got it
        // wrong on the first run (three call sites, not two) — and a count of the thing
        // under test is the one number that must not be a transcription.
        int successCallSites = reviewBefore.split("Verdict\\.success\\(", -1).length - 1;
        assertTrue(successCallSites >= 2,
            () -> "PROOF OF LIFE: the fixture must start with at least two success() call"
                + " sites:\n" + reviewBefore);

        // ---- AWAY. Succeeds here, and that is what attributes finding one. ----
        int awayLine = lineOf(verdictBefore, "public static Verdict success(String val)");
        ObjectNode away = mapper.createObjectNode();
        away.put("filePath", verdictFile.toString());
        away.put("line", awayLine);
        away.put("column", columnOf(verdictBefore, awayLine, "success("));

        ToolResponse a = inline.execute(away);
        assertTrue(a.isSuccess(),
            () -> "AWAY refused on the NON-generic twin too, which would mean finding one"
                + " is about partial application rather than genericity — a wider finding"
                + " that must be re-derived, not patched: " + a.getError());

        String reviewMid = Files.readString(reviewFile);
        assertTrue(reviewMid.contains("new Verdict("),
            () -> "the fold must have reached the callers:\n" + reviewMid);

        // ---- TOWARD. A NEUTRAL name: passing "success" made the operation emit
        // `failure(v) { return success(v, FAILURE); }`, and that absurdity was the
        // test's own input rather than the operation's behaviour.
        int towardLine = lineOf(reviewMid, "new Verdict(");
        ObjectNode toward = mapper.createObjectNode();
        toward.put("kind", "replace_constructor_with_factory");
        toward.put("filePath", reviewFile.toString());
        toward.put("line", towardLine);
        toward.put("column", columnOf(reviewMid, towardLine, "new Verdict("));
        toward.put("factoryMethodName", "of");
        toward.put("protectConstructor", false);

        ToolResponse t = pattern.execute(toward);
        assertTrue(t.isSuccess(), () -> "TOWARD refused after a successful AWAY: " + t.getError());

        String verdictAfter = Files.readString(verdictFile);
        String reviewAfter = Files.readString(reviewFile);

        // THE MEASUREMENT, asserted as three specific differences rather than as one
        // inequality — an inequality would also hold for a stray newline, and would
        // keep passing if the operation's behaviour changed completely.
        assertFalse(verdictAfter.contains("public static Verdict success(String val)"),
            () -> "the human's partial application did not come back:\n" + verdictAfter);
        assertTrue(verdictAfter.contains("of(String value, State state)"),
            () -> "TOWARD produced a FORWARDER taking every constructor parameter:\n"
                + verdictAfter);
        assertEquals(successCallSites,
            reviewAfter.split("Outcome\\.State\\.SUCCESS", -1).length - 1,
            () -> "EVERY call site that used to hide the constant must now spell it — that"
                + " is the finding, stated as a one-to-one correspondence rather than as a"
                + " number, so it holds however many call sites the fixture has. The trip"
                + " did not merely fail to close; it moved information outward:\n"
                + reviewAfter);

        // And the whole-slice statement, kept last so the specific assertions above are
        // what fails first and says why.
        assertFalse(verdictAfter.equals(verdictBefore) && reviewAfter.equals(reviewBefore),
            "if the trip ever DOES close, the three assertions above are wrong and this"
                + " finding must be re-derived rather than adjusted");
    }
}
