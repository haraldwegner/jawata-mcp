package org.jawata.mcp.refactoring;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE PER-STEP GATE INSIDE {@link RecipeEngine}, WHICH NOTHING ASSERTED.
 *
 * <p>C2's exit criterion asks that "the parity gate held <b>at every step</b>". The gate is
 * real — {@code RecipeEngine.run} sends every step through {@code GatedApply.perform(...,
 * Mode.REPORT)} and rolls the whole recipe back when a step writes something that is not Java
 * — and a C2 re-audit found that no test in the suite reached it. Measured then: {@code
 * GatedApply} had thirteen references and every one was production, and the string the refusal
 * prints occurred once in the whole workspace, in {@code RecipeEngine} itself. Reverting the
 * gate to the bare {@code ChangeEngine.perform} it was before C6 left the suite green.</p>
 *
 * <h2>Why this is a synthetic step and not a fork-corpus row</h2>
 *
 * <p>The criterion says "shown on the fork corpus", and that half is met differently and is
 * worth stating plainly rather than blurring: on foreign code the recipes RUN, so their steps
 * produce valid Java and the gate correctly never fires. A gate is only shown to work by a
 * step that trips it, and no correct recipe supplies one. So the trip is constructed here —
 * {@code RecipeEngine.Step} is a public functional interface returning a {@code Change}, which
 * is the seam that makes this assertable at all — and the fork slices continue to show the
 * other half, that a real recipe holds together end to end on code we did not author.</p>
 *
 * <h2>The pair, because either half alone proves nothing</h2>
 *
 * <p>A refusal on its own would also be produced by an engine that refused everything, and an
 * acceptance on its own by an engine with no gate. The control below writes a comment, which
 * is valid Java in the same position, and requires it through.</p>
 *
 * <h2>And a third case, because the first two cannot reach the rollback</h2>
 *
 * <p>A SINGLE-step recipe never executes {@code rollback}'s loop: the step's undo is appended
 * to the list AFTER the refusal check, so the list is empty when the rollback runs, and what
 * restores the file is {@code GatedApply}'s own undo. The engine's javadoc promises more than
 * that — "every already-applied step is rolled back before returning an error" — and a
 * two-step recipe is the smallest thing that asks for it.</p>
 */
class RecipeStepGateTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** The fixture is incidental — any compilable unit does; only what we insert matters. */
    private static final String FIXTURE = "src/main/java/com/example/DeadCodeTargets.java";

    private record Bench(JdtServiceImpl service, Path file, IFile target, String before) {}

    private Bench load() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path file = service.allProjects().iterator().next().projectRoot().resolve(FIXTURE);
        ICompilationUnit cu = service.getCompilationUnit(file);
        assertNotNull(cu, "PROOF OF LIFE: the fixture must resolve, or this asserts nothing");
        return new Bench(service, file, (IFile) cu.getResource(),
            Files.readString(file, StandardCharsets.UTF_8));
    }

    /** A step that splices text at the very top of the unit, ahead of its package clause. */
    private static RecipeEngine.Step insert(IFile target, String text) {
        return insertAt(target, 0, text);
    }

    /**
     * A step that splices text at a chosen offset — needed for mcp#80, because the cases
     * below must land INSIDE the class body. Offset 0 is ahead of the package clause, where
     * anything but a comment is a SYNTAX error, and a syntax error is undone by the per-step
     * gate. The state mcp#80 is about is the opposite one: valid Java that does not RESOLVE,
     * which every step correctly lets through and which nothing used to look at afterwards.
     */
    private static RecipeEngine.Step insertAt(IFile target, int offset, String text) {
        return () -> ChangeEngine.fromFileEdits("gate probe",
            Map.of(target, List.<TextEdit>of(new InsertEdit(offset, text))));
    }

    /** The offset just inside the unit's final closing brace — i.e. the last class's body. */
    private static int insideClassBody(String source) {
        int brace = source.lastIndexOf('}');
        assertTrue(brace > 0, "PROOF OF LIFE: the fixture must have a class body to write into");
        return brace;
    }

    @Test
    @DisplayName("a step that writes code which does not parse is refused, and the file is put back")
    void aStepWritingUnparseableCodeIsRefusedAndRolledBack() throws Exception {
        Bench b = load();

        RecipeEngine.Result r = RecipeEngine.run("gate probe",
            List.of(insert(b.target(), "}}} this is not java {{{\n")), b.service());

        assertFalse(r.ok(), "a step that wrote unparseable Java must not be reported as done;"
            + " without the gate this SUCCEEDS and the caller is handed a broken file");
        assertNotNull(r.error(), "and the failure must say something");
        assertTrue(r.error().contains("wrote code that does not parse"),
            "the refusal must name what happened — the recipe engine's own wording, which no"
                + " other branch prints: " + r.error());
        // WHAT PUTS THE FILE BACK HERE IS GatedApply, NOT the recipe's rollback loop, and the
        // first version of this message credited the wrong one. With a SINGLE step the undo
        // list is still empty when rollback() runs — the step's own undo is appended after
        // the refusal check — so the loop body never executes. GatedApply.perform undoes its
        // own application before returning refused(). The multi-step case below is the one
        // that reaches the loop, and it exists because an architect watch read this message
        // against the engine and found it naming a mechanism that had not run.
        assertEquals(b.before(), Files.readString(b.file(), StandardCharsets.UTF_8),
            "and the step must be UNDONE, not merely reported — here by GatedApply's own undo,"
                + " which is what refuses a step that wrote something that is not Java");
    }

    @Test
    @DisplayName("a recipe whose SECOND step declines rolls the FIRST one back")
    void aLaterStepDecliningRollsTheEarlierOneBack() throws Exception {
        Bench b = load();

        // THE ONLY SHAPE THAT REACHES RecipeEngine's rollback LOOP. Step one applies and its
        // undo is recorded; step two trips the gate; the loop then has something to undo. The
        // engine's javadoc promises exactly this — "every already-applied step is rolled back
        // before returning an error" — and until now nothing in the suite ran a recipe of more
        // than one step, so the promise was carried by no test at all.
        RecipeEngine.Result r = RecipeEngine.run("gate probe", List.of(
            insert(b.target(), "// step one, which is valid and IS applied\n"),
            insert(b.target(), "}}} step two, which is not java {{{\n")), b.service());

        assertFalse(r.ok(), "the recipe must fail on its second step");
        assertTrue(r.error().contains("step 2"),
            "and it must say WHICH step, so a caller can find it: " + r.error());
        assertEquals(b.before(), Files.readString(b.file(), StandardCharsets.UTF_8),
            "STEP ONE's write must be gone too. It applied cleanly and nothing about it was"
                + " wrong; it is undone because the recipe it belonged to could not finish,"
                + " which is the whole difference between a recipe and a sequence of edits");
    }

    @Test
    @DisplayName("the control: a step that writes valid code is accepted and applied")
    void aStepWritingValidCodeIsAccepted() throws Exception {
        Bench b = load();

        RecipeEngine.Result r = RecipeEngine.run("gate probe",
            List.of(insert(b.target(), "// a comment, which is valid Java here\n")),
            b.service());

        // WITHOUT THIS the test above passes against an engine that refuses every step, which
        // is a different defect wearing the same green.
        assertTrue(r.ok(), "a step writing valid Java must go through; got: " + r.error());
        assertNotEquals(b.before(), Files.readString(b.file(), StandardCharsets.UTF_8),
            "and it must actually have been applied, or the case above is about an engine"
                + " that writes nothing rather than about the gate");
        assertNotNull(r.compositeUndo(), "a successful recipe hands back its composite undo");
    }

    // ---- mcp#80: the state after the LAST step, which nothing verified ------------------

    @Test
    @DisplayName("mcp#80: a recipe that ENDS red reports what it introduced, and is not compile-verified")
    void aRecipeEndingRedReportsWhatItIntroduced() throws Exception {
        Bench b = load();

        // VALID JAVA THAT DOES NOT RESOLVE — the one shape the per-step gate is DESIGNED to
        // let through. Mode.REPORT undoes a syntax error and nothing else, deliberately,
        // because a recipe's intermediate state is often legitimately red. So this step
        // applies, the recipe finishes, and before mcp#80 the caller was told applied:true
        // over a file that no longer compiles.
        RecipeEngine.Result r = RecipeEngine.run("gate probe",
            List.of(insertAt(b.target(), insideClassBody(b.before()),
                "    void probe80() { NoSuchType80 x = null; }\n")),
            b.service());

        assertTrue(r.ok(), "PROOF OF LIFE: the step must go THROUGH — this case is not about"
            + " refusing it. If the gate refused here the assertions below would hold for a"
            + " completely different reason: " + r.error());
        assertFalse(r.compileVerified(),
            "the recipe left the file uncompilable and must SAY so; without the final"
                + " verification this reads compile-verified, which is the whole defect");
        assertFalse(r.introducedErrors().isEmpty(),
            "and it must name what it introduced rather than a bare boolean");
        assertTrue(String.join(" | ", r.introducedErrors()).contains("NoSuchType80"),
            "the error must be the one THIS recipe caused, named: " + r.introducedErrors());
    }

    @Test
    @DisplayName("mcp#80: the control — a recipe that ends clean is compile-verified and carries its diff")
    void aRecipeEndingCleanIsCompileVerifiedAndCarriesItsDiff() throws Exception {
        Bench b = load();

        RecipeEngine.Result r = RecipeEngine.run("gate probe",
            List.of(insertAt(b.target(), insideClassBody(b.before()),
                "    void probe80() { int ok = 1; }\n")),
            b.service());

        assertTrue(r.ok(), "the control must succeed; got: " + r.error());
        // WITHOUT THIS the case above passes against a verification that reports every recipe
        // red — which is a different defect wearing the same failure.
        assertTrue(r.compileVerified(),
            "a recipe that compiles must be reported compile-verified; got: "
                + r.introducedErrors());
        // NOTE WHAT THIS DOES **NOT** PROVE. The fixture compiles to begin with, so an empty
        // list here is equally consistent with a measurement that reports every error PRESENT
        // rather than every error INTRODUCED — there are none of either. That distinction is
        // the case below, on a file that is already red.
        assertTrue(r.introducedErrors().isEmpty(),
            "and it must have introduced nothing: " + r.introducedErrors());

        assertNotNull(r.diff(), "mcp#80: the response carried applied:true and NO diff — a"
            + " caller could not see what the recipe had done to their file");
        assertTrue(r.diff().contains("probe80"),
            "and the diff must show the composite's own change: " + r.diff());
    }

    @Test
    @DisplayName("mcp#80: a recipe on an ALREADY-RED file is not charged with errors it did not cause")
    void aRecipeIsNotChargedWithErrorsItDidNotCause() throws Exception {
        Bench b = load();

        // Recipe ONE leaves the file red, and is the SETUP rather than the subject. Its own
        // verdict is asserted only as proof of life: if it did not actually break the file,
        // the case below degenerates into the clean control and measures nothing.
        RecipeEngine.Result setup = RecipeEngine.run("gate probe",
            List.of(insertAt(b.target(), insideClassBody(b.before()),
                "    void alreadyBroken80() { PreExisting80 x = null; }\n")),
            b.service());
        assertTrue(setup.ok(), "PROOF OF LIFE: the setup recipe must apply; got: " + setup.error());
        assertFalse(setup.compileVerified(),
            "PROOF OF LIFE: the file must actually BE red before the recipe under test runs,"
                + " or this case is indistinguishable from the clean control");

        String red = Files.readString(b.file(), StandardCharsets.UTF_8);

        // Recipe TWO is the subject: it adds something perfectly valid to a file that is
        // already broken. The error is PRESENT throughout and was INTRODUCED by neither step
        // of this recipe, so this recipe must not be charged with it.
        RecipeEngine.Result r = RecipeEngine.run("gate probe",
            List.of(insertAt(b.target(), insideClassBody(red),
                "    void innocent80() { int ok = 1; }\n")),
            b.service());

        assertTrue(r.ok(), "the second recipe must apply; got: " + r.error());
        assertTrue(r.introducedErrors().isEmpty(),
            "the pre-existing error is not this recipe's: measuring what is PRESENT rather"
                + " than what was INTRODUCED charges every later recipe with the first one's"
                + " damage, and every correct recipe on a red file then reads as the culprit."
                + " Got: " + r.introducedErrors());
        assertTrue(r.compileVerified(),
            "so it is compile-verified even though the FILE does not compile — which is the"
                + " distinction this case exists for, and the one the clean control cannot make");
    }
}
