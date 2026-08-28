package org.jawata.mcp.tools.refactoring;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.refactoring.descriptors.ExtractClassDescriptor;
import org.eclipse.jdt.internal.corext.refactoring.structure.ExtractClassRefactoring;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d Stage 7 / S7.0 — THE SPIKE, and it is deliberately first.
 *
 * <h2>The one question this answers</h2>
 *
 * <p>Extract Class is planned as a DELEGATION to JDT's own
 * {@link ExtractClassRefactoring} rather than a hand-rolled operation, because
 * that class is on the classpath, extends LTK's {@code Refactoring}, and
 * {@code JdtRefactoringEngine.propose} already accepts one. Every later step of
 * Stage 7 assumes that. <b>So the first thing built is the thing that would
 * invalidate the plan.</b></p>
 *
 * <p><b>Presence on a classpath is NOT behaviour</b>, and this sprint has already
 * paid for that confusion once: a capability was confirmed to exist and reported
 * as working while its method body was a bare {@code return}. A refactoring
 * engine designed for a running IDE can fail headlessly for reasons that have
 * nothing to do with whether its jar resolves — missing UI plugin state, an
 * unset {@code CodeGenerationSettings}, a preferences lookup with no workbench
 * behind it. If any of that bites, the delegation design is wrong and the rest
 * of Stage 7 changes shape. Better to learn it in one small test than after the
 * front door, the gates and the E2E promise are all written against it.</p>
 *
 * <h2>What it does NOT claim</h2>
 *
 * <p>This is a spike, not the operation. It does not go through the
 * {@code extract} front door (S7.1), does not assert the done-definition that
 * the old shape is GONE (S7.2), and its before-case is a FIXTURE — which C7
 * explicitly rules insufficient: "each op's before-case is pre-existing code (a
 * fork slice or equivalent) … a fixture written for the test does not satisfy
 * the clause". That case is S7.4. What is asserted here is exactly what the name
 * says: the engine runs, and it produces a change.</p>
 */
class ExtractClassSpikeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        targetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/SrpCohesionTargets.java");
    }

    /**
     * {@code PointBuilder} carries {@code label} and {@code unit} — a field pair
     * disjoint from the rest of its state, which is the shape Extract Class
     * exists to move out. The fixture is a COPY (loadProjectCopy), so the
     * sample project on disk is never touched.
     */
    @Test
    @DisplayName("S7.0: JDT's Extract Class engine runs headlessly and yields a change")
    void theJdtExtractClassEngineRunsHeadlessly() throws Exception {
        HeadlessJdtConfig.ensureInitialized();

        ICompilationUnit cu = service.getCompilationUnit(targetFile);
        assertNotNull(cu, "the fixture compilation unit must resolve before anything else means anything");
        IType pointBuilder = cu.getType("PointBuilder");
        assertTrue(pointBuilder.exists(),
            "PROOF OF LIFE: PointBuilder must be resolvable as a secondary type in"
                + " SrpCohesionTargets.java, or this test is measuring nothing");

        ExtractClassDescriptor descriptor = new ExtractClassDescriptor();
        descriptor.setType(pointBuilder);
        descriptor.setClassName("PointMeta");
        descriptor.setPackage("com.example");
        descriptor.setCreateTopLevel(true);
        descriptor.setFieldName("meta");
        descriptor.setCreateGetterSetter(true);

        // Field instances are not constructible directly (private ctor) — they come
        // from the descriptor's own static reader, and the ones to move are flagged.
        ExtractClassDescriptor.Field[] fields = ExtractClassDescriptor.getFields(pointBuilder);
        assertTrue(fields.length >= 2,
            "the fixture type must expose fields to move; found " + fields.length);
        int selected = 0;
        for (ExtractClassDescriptor.Field f : fields) {
            boolean wanted = "label".equals(f.getFieldName()) || "unit".equals(f.getFieldName());
            f.setCreateField(wanted);
            if (wanted) {
                selected++;
            }
        }
        assertEquals(2, selected,
            "both label and unit must have been found by name — if the fixture is edited"
                + " so they no longer exist, this spike would silently extract NOTHING and"
                + " still report an engine that works");
        descriptor.setFields(fields);

        RefactoringStatus descriptorStatus = descriptor.validateDescriptor();
        assertFalse(descriptorStatus.hasFatalError(),
            () -> "the descriptor itself was rejected: " + descriptorStatus.getMessageMatchingSeverity(
                RefactoringStatus.FATAL));

        ExtractClassRefactoring refactoring = new ExtractClassRefactoring(descriptor);

        RefactoringStatus initial = refactoring.checkInitialConditions(new NullProgressMonitor());
        assertFalse(initial.hasFatalError(),
            () -> "checkInitialConditions refused headlessly — this is the failure mode the"
                + " spike exists to find, and it would mean the delegation design is wrong: "
                + initial.getMessageMatchingSeverity(RefactoringStatus.FATAL));

        RefactoringStatus fin = refactoring.checkFinalConditions(new NullProgressMonitor());
        assertFalse(fin.hasFatalError(),
            () -> "checkFinalConditions refused headlessly: "
                + fin.getMessageMatchingSeverity(RefactoringStatus.FATAL));

        Change change = refactoring.createChange(new NullProgressMonitor());
        assertNotNull(change,
            "the engine produced no change. A null here with clean statuses would be the"
                + " worst outcome to discover later: every precondition green and nothing"
                + " to apply");
    }
}
