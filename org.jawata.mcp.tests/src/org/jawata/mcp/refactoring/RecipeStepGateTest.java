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
        return () -> ChangeEngine.fromFileEdits("gate probe",
            Map.of(target, List.<TextEdit>of(new InsertEdit(0, text))));
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
}
