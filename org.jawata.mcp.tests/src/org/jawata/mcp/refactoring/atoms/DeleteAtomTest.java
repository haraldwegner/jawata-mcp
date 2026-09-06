package org.jawata.mcp.refactoring.atoms;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue, Stage 1 — the delete atom.
 *
 * <p>The claim that needs proving is the NEGATIVE one. Deleting a member is easy to
 * verify; not deleting the things the engine offers to take along is the property the
 * composed rows depend on, and it is invisible unless a test asks for it.</p>
 */
class DeleteAtomTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path target;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        target = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/DeleteAtomTargets.java");
    }

    private IType targets() throws Exception {
        ICompilationUnit unit = service.getCompilationUnit(target);
        assertNotNull(unit, "the fixture must be in the model, or nothing below is about it");
        IType type = unit.getType("DeleteAtomTargets");
        assertTrue(type.exists(), "PROOF OF LIFE: the fixture type must resolve");
        return type;
    }

    private String applyDeleteOf(IJavaElement... elements) throws Exception {
        CheckedChange checked =
            DeleteAtom.delete(elements, "test delete", new JdtRefactoringEngine());
        assertFalse(checked.isRefused(),
            "the engine must accept this deletion; it said: " + checked.messages());
        ChangeEngine.perform(checked.change(), service);
        return Files.readString(target, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("REFUSES a compilation unit declaring more than one top-level type")
    void refusesAFileThatDeclaresMoreThanOneType() throws Exception {
        Path paired = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example/PairedInOneFile.java");
        ICompilationUnit unit = service.getCompilationUnit(paired);
        assertNotNull(unit, "the fixture must be in the model");
        assertTrue(unit.getTypes().length == 2,
            "PROOF OF LIFE: the fixture must declare TWO top-level types, or this asserts"
                + " nothing — it had " + unit.getTypes().length);

        CheckedChange checked =
            DeleteAtom.delete(new IJavaElement[] { unit }, "test delete", new JdtRefactoringEngine());

        assertTrue(checked.isRefused(),
            "this class promises 'nothing else is touched' and deleting the FILE takes every"
                + " type in it. The engine never asks about that widening — the caller named"
                + " the unit — so the promise was false at two of three production callers"
                + " until this check existed");
        assertTrue(String.valueOf(checked.messages()).contains("PairedSurvivor"),
            "and the refusal must NAME the bystander it is protecting, or a caller cannot tell"
                + " what the objection is: " + checked.messages());
        // NO "and the file still exists" ASSERTION HERE, deliberately. `delete` ends at
        // engine.propose — it STAGES and never performs — so the file is on disk down every
        // path, refusal or not, and asserting it would pass with this whole check deleted.
        // A round-2 audit found that assertion in the first version of this test: an
        // assertion that cannot fail, inside the repair whose subject is assertions that
        // cannot fail. The three above carry the claim.
    }

    @Test
    @DisplayName("the named method goes, and nothing else does")
    void theNamedElementIsDeleted() throws Exception {
        IType type = targets();
        String after = applyDeleteOf(type.getMethod("obsolete", new String[0]));
        assertFalse(after.contains("obsolete()"), "the named method must be gone:\n" + after);
        assertTrue(after.contains("public String note()"),
            "and the neighbour it never mentioned must survive:\n" + after);
    }

    @Test
    @DisplayName("deleting a field does NOT take its accessors — the atom never widens its own set")
    void theAccessorsSurvive() throws Exception {
        IType type = targets();
        String after = applyDeleteOf(type.getField("tally"));

        assertFalse(after.contains("private int tally"),
            "the named field must be gone:\n" + after);
        // THE POINT OF THE ATOM. The Eclipse engine offers to delete the accessors of a
        // deleted field, and in an IDE that offer is answered by a human looking at a
        // preview. Here it is answered NO, so a composed row that wants them gone has to
        // name them — its diff and its undo then describe the same set.
        //
        // Flip setSuggestGetterSetterDeletion to true and these two go red.
        assertTrue(after.contains("getTally"),
            "the getter was never named, so it must still be here:\n" + after);
        assertTrue(after.contains("setTally"),
            "and the setter likewise — a widened deletion makes every composition's undo"
                + " bigger than its diff:\n" + after);
    }
}
