package org.jawata.mcp.tools.refactoring;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.refactoring.code.IntroduceFactoryRefactoring;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d Stage 8 / S8.0 — THE SPIKE for rank 3, and it runs before anything
 * else in the stage.
 *
 * <h2>The one question this answers</h2>
 *
 * <p>D1 ranks <b>Replace Constructor with Factory Method</b> third and names the
 * blocker precisely: {@code change_method_signature(retargetCallsTo)} rewrites
 * METHOD call sites and not constructor calls, so the whole creational family
 * (abstract-factory, factory-method, builder, null-object) is blocked without a
 * constructor-call-site rewrite.</p>
 *
 * <p>JDT ships one. {@link IntroduceFactoryRefactoring} is on the classpath with a
 * public constructor, the LTK triple our engine already accepts, and a private
 * {@code replaceConstructorCalls} in its member list. <b>So the stage is planned as
 * a DELEGATION, and the first thing built is the thing that would invalidate that
 * plan.</b></p>
 *
 * <p><b>Presence on a classpath is not behaviour</b>, and Stage 7 paid for the
 * difference in the cheapest possible place: its own spike found
 * {@code createChange} throwing {@code NullPointerException: "newContents" is null}
 * with EVERY precondition green, because the headless config registered no
 * whole-file code template. That was a production defect in shared config, found
 * before the front door, the gates and the E2E promise were written against it.
 * This spike exists for the same reason and is deliberately the same shape.</p>
 *
 * <h2>What it does NOT claim, stated rather than left to assumption</h2>
 *
 * <p><b>It does not exercise the call-site rewrite</b> — which is the atom rank 3
 * exists for. The before-case here is the {@code fork-circuit-breaker} slice, and
 * NO file in that slice constructs {@code MonitoringService}: the demo entry point
 * that did is {@code App.java}, deliberately omitted from the slice because it is
 * the only Lombok file and JDT runs no annotation processor in this workspace (see
 * that fixture's PROVENANCE.md). So a green here says the engine RUNS and produces
 * a change; it says nothing about how many call sites were rewritten.</p>
 *
 * <p>That gap is not hand-waved away: S8.4's "code we did not author" case takes a
 * slice of the fork's own {@code factory-method} module, where
 * {@code ElfBlacksmith} constructs its weapons internally — real call sites, in
 * the pattern rank 3 exists to unblock.</p>
 *
 * <p>Nor does this go through the front door (S8.1), assert the done-definition
 * (S8.2), or check parity and undo (S8.3).</p>
 */
class IntroduceFactorySpikeTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("fork-circuit-breaker");
        targetFile = helper.getTempDirectory()
            .resolve("fork-circuit-breaker/src/main/java/com/iluwatar/circuitbreaker/"
                + "MonitoringService.java");
    }

    @Test
    @DisplayName("S8.0: JDT's Introduce Factory engine runs headlessly and yields a change")
    void theJdtIntroduceFactoryEngineRunsHeadlessly() throws Exception {
        HeadlessJdtConfig.ensureInitialized();

        String source = Files.readString(targetFile);
        ICompilationUnit cu = service.getCompilationUnit(targetFile);
        assertNotNull(cu, "the fixture compilation unit must resolve before anything else"
            + " here means anything");
        IType type = cu.getType("MonitoringService");
        assertTrue(type.exists(),
            "PROOF OF LIFE: MonitoringService must resolve, or this spike measures nothing");

        // The engine takes a SELECTION, not a descriptor — the caret sits on the
        // constructor's own name. Derived from the source rather than hard-coded, so
        // an edited fixture fails loudly here instead of silently selecting the wrong
        // node and reporting an engine that works.
        String ctor = "public MonitoringService(";
        int declStart = source.indexOf(ctor);
        assertTrue(declStart >= 0,
            "the fixture must still declare an explicit constructor; without one there is"
                + " nothing for Introduce Factory to act on");
        int nameStart = declStart + "public ".length();
        int nameLength = "MonitoringService".length();

        IntroduceFactoryRefactoring refactoring =
            new IntroduceFactoryRefactoring(cu, nameStart, nameLength);

        RefactoringStatus initial = refactoring.checkInitialConditions(new NullProgressMonitor());
        assertFalse(initial.hasFatalError(),
            () -> "checkInitialConditions refused headlessly — this is the failure mode the"
                + " spike exists to find, and it would mean the delegation design for rank 3"
                + " is wrong: " + initial.getMessageMatchingSeverity(RefactoringStatus.FATAL));

        RefactoringStatus named = refactoring.setNewMethodName("createMonitoringService");
        assertFalse(named.hasFatalError(),
            () -> "the engine rejected the factory method name: "
                + named.getMessageMatchingSeverity(RefactoringStatus.FATAL));

        RefactoringStatus fin = refactoring.checkFinalConditions(new NullProgressMonitor());
        assertFalse(fin.hasFatalError(),
            () -> "checkFinalConditions refused headlessly: "
                + fin.getMessageMatchingSeverity(RefactoringStatus.FATAL));

        Change change = refactoring.createChange(new NullProgressMonitor());
        assertNotNull(change,
            "the engine produced NO change. A null here with clean statuses is the worst"
                + " outcome to discover late — every precondition green and nothing to"
                + " apply — and it is exactly what Stage 7's spike caught");
    }

    /**
     * The flag that makes rank 3's done-definition a fact about the code rather than
     * a reading of the diff: with the constructor protected, a caller CANNOT bypass
     * the factory. That is the architect seat's standing rule — an encapsulation
     * refactor is done only when the old path is IMPOSSIBLE — available here as an
     * engine capability instead of machinery we would have to build.
     *
     * <p>Asserted as a QUESTION rather than assumed: {@code canProtectConstructor}
     * is the engine's own answer for this target, and if it says no, the
     * done-definition for S8.2 has to be built differently. Learning that now costs
     * one assertion; learning it at the gate costs the stage's shape.</p>
     */
    @Test
    @DisplayName("S8.0: the engine can protect the constructor, which is the done-definition")
    void theEngineCanMakeTheOldPathImpossible() throws Exception {
        HeadlessJdtConfig.ensureInitialized();

        String source = Files.readString(targetFile);
        ICompilationUnit cu = service.getCompilationUnit(targetFile);
        int declStart = source.indexOf("public MonitoringService(");
        assertTrue(declStart >= 0, "PROOF OF LIFE: the constructor must still be there");

        IntroduceFactoryRefactoring refactoring = new IntroduceFactoryRefactoring(
            cu, declStart + "public ".length(), "MonitoringService".length());
        RefactoringStatus initial = refactoring.checkInitialConditions(new NullProgressMonitor());
        assertFalse(initial.hasFatalError(),
            () -> "precondition: " + initial.getMessageMatchingSeverity(RefactoringStatus.FATAL));

        assertTrue(refactoring.canProtectConstructor(),
            "the engine reports it cannot protect this constructor. That is not a failure of"
                + " the spike — it is the finding: rank 3's done-definition would then need"
                + " its own mechanism rather than the engine's flag");
    }
}
