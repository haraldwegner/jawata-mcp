package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ROW 23 performed on code we did not author, and ROW 38's measured ABSENCE.
 *
 * <p>Each test reloads the slice, so neither acts on what the other left behind.</p>
 *
 * <h2>Row 23 (Move Field) — performed</h2>
 *
 * <p>{@code RegisterWorker.LEGAL_AGE} is a rule about the DTO's date of birth, declared on
 * the class that checks it while every other validation constant in the module already
 * lives on the DTO. The reference is written BARE, because the constant was local to the
 * class reading it, so the move has to qualify it — the half a same-file fixture never
 * reaches.</p>
 *
 * <p>The canonical candidate here was {@code RegisterWorkerDto.MISSING_NAME}, which its own
 * class references ZERO times ({@code find_references} returns two, both outside it) — the
 * strongest possible form of Fowler's trigger. It is NOT what this test moves, because its
 * initializer calls {@code new NotificationError(...)}, a constructor Lombok generates and
 * JDT therefore cannot see, so the gate refuses a rewrite it cannot verify. That is worth
 * recording rather than quietly substituting: the best candidate in the corpus is out of
 * reach for a reason that is about the compiler, not about the row.</p>
 *
 * <h2>Row 38 (Remove Subclass) — no usable candidate, measured</h2>
 *
 * <p>{@code RegisterWorker extends ServerCommand} is upstream's own domain-layer command.
 * It overrides nothing, has no subtypes, and its constructor forwards its one parameter
 * through unchanged — the narrow, distinction-free case this row performs. Which candidate
 * fits and why the other five do not is measured in {@code PROVENANCE.md} beside the
 * slice.</p>
 *
 * <h2>What a fixture could not have put here</h2>
 *
 * <p>{@code ServerCommand} is annotated {@code @AllArgsConstructor} and
 * {@code RegisterWorker} {@code @Slf4j}, so the parent's constructor and the subclass's
 * logger are members JDT cannot see at all — Lombok's processor does not run. A fixture
 * would have had a parent whose every member the compiler knows about. This one exercises
 * the apply gate's actual contract: it compares errors BEFORE and AFTER on the files a
 * change modifies, so a reference that was already unresolved must not be reported as
 * something this row broke, and a reference this row does break still must be.</p>
 */
class NotificationForkSliceTest {

    private static final String PKG = "com/iluwatar";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ForkSliceSupport.Slice slice;

    @BeforeEach
    void setUp() throws Exception {
        slice = ForkSliceSupport.load(helper, "fork-notification", PKG,
            "RegisterWorker.java", "public class RegisterWorker extends ServerCommand");
    }

    @Test
    @DisplayName("row 23 on fork code: a constant nothing in its own class reads moves to the class that does")
    void moveFieldOnForkCode() throws Exception {
        String before = slice.read("RegisterWorker.java");
        assertTrue(before.contains("static final int LEGAL_AGE = 18;"),
            "PROOF OF LIFE: the constant must still be where upstream declared it:\n"
                + before);

        // LEGAL_AGE is a rule about the DTO's date of birth, declared on the class that
        // checks it rather than the class it constrains — and every OTHER validation
        // constant in this module (MISSING_NAME, MISSING_DOB, DOB_TOO_SOON) already lives
        // on the DTO. Moving it there puts the rule with the data, which is Move Field's
        // whole argument.
        ObjectNode args = slice.at("field", "RegisterWorker.java",
            "static final int LEGAL_AGE = 18;", 19);
        args.put("targetType", "com.iluwatar.RegisterWorkerDto");

        ToolResponse r = slice.door("move").execute(args);
        assertTrue(r.isSuccess(), () -> "row 23 refused real upstream code: " + r.getError());

        assertTrue(slice.read("RegisterWorkerDto.java").contains("LEGAL_AGE"),
            "the constant arrives beside the data it constrains:\n"
                + slice.read("RegisterWorkerDto.java"));
        String after = slice.read("RegisterWorker.java");
        assertFalse(after.contains("static final int LEGAL_AGE"),
            "and leaves its old owner — moved, not copied:\n" + after);
        // Upstream reads it UNQUALIFIED, three lines inside a private method, because it
        // was a member of the reading class. After the move a bare name resolves to
        // nothing, so the reference has to gain the new owner's name — which is the half a
        // same-file fixture never has to do.
        assertTrue(after.contains("RegisterWorkerDto.LEGAL_AGE"),
            "and the use, written bare because it was local, is now qualified:\n" + after);
    }

    @Test
    @DisplayName("row 38 has no usable candidate in this corpus, and this is the closest one")
    void removeSubclassHasNoCandidateAndThisIsWhy() throws Exception {
        // THE CORPUS WAS MEASURED, NOT SAMPLED. find_quality_issue(kind=
        // composition_over_inheritance) over all 1336 main files returns NINE findings;
        // SEVEN name a subclass overriding NONE of its inherited members, which is row 38's
        // precondition. SIX of those seven are disqualified by a DIFFERENT precondition each
        // — CustomerRole is abstract with subtypes, SimpleProbableThreat does override,
        // UserConverter and the three Mma*Fighters extend generic parents and pass method
        // references rather than forwarding their own parameters. RegisterWorker is the
        // seventh and the only structural fit. PROVENANCE.md carries that table. (The
        // remaining two findings — FlamingAsteroid and SpaceStationIss — are flagged on the
        // touch-ratio clause, not on overriding nothing, so they were never candidates.)
        String before = slice.read("RegisterWorkerService.java");
        ToolResponse r = slice.door("inline").execute(slice.at("subclass",
            "RegisterWorker.java", "public class RegisterWorker extends ServerCommand", 13));

        // AND IT IS BLOCKED FOR A REASON THAT IS NOT A DEFECT. ServerCommand's only
        // constructor is generated by Lombok's @AllArgsConstructor, and JDT does not run
        // Lombok's processor, so `new ServerCommand(registration)` — the correct output of
        // this row — cannot be shown to compile. The gate refuses and undoes, which is the
        // gate working: it will not certify a rewrite it cannot verify.
        assertFalse(r.isSuccess(),
            "if this ever succeeds, Lombok's members became visible and row 38 has gained a"
                + " real fork demonstration — update Stage6ForkDemonstrationStatusTest");
        String error = String.valueOf(r.getError());
        assertTrue(error.contains("ServerCommand") && error.contains("undefined"),
            "and the refusal names the constructor JDT cannot see, rather than blaming the"
                + " rewrite: " + error);
        assertTrue(before.equals(slice.read("RegisterWorkerService.java")),
            "the caller is byte-identical — a refused refactoring modifies nothing");
        assertTrue(Files.exists(slice.pkg().resolve("RegisterWorker.java")),
            "and so is the subclass it would have deleted");
    }
}
