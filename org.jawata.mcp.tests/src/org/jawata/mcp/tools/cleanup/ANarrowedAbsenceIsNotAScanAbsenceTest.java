package org.jawata.mcp.tools.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#76 — <b>a narrowed question gets a narrowed answer.</b>
 *
 * <p>{@code apply_cleanup} answered a MEMBER-scoped call with the sweep's own sentence:
 * <i>"No code to clean up — and the scan was COMPLETE (1 file(s) examined), so this is a real
 * absence, not a failure to look."</i> That is a claim about the FILE, true of a sweep that
 * found nothing, and the wrong scope for a question about one member. The rule may have looked
 * at that member and declined it; the caller was told the absence was real.</p>
 *
 * <p>Because the address was ACCEPTED, nothing signalled a decline — and the sentence exists to
 * separate <i>found nothing</i> from <i>could not look</i>, so applying it here ruled out the
 * explanation that was true.</p>
 *
 * <p><b>A WRONG FIX SHIPPED FIRST, and it is worth knowing why.</b> The premise was that
 * {@code remove_dead_code} rewrites statements and can never act on a field, so a field target
 * should be declined outright. Three controls passed. A mutation disabling the guard then
 * produced a DIFF deleting the field — JDT's unused-code fix removes unused private fields too
 * — so the premise was false and the guard would have declined cases the rule handles. Reverted
 * in {@code ba0c4678}. The difference between the reported case and that one is not the member
 * kind: {@code FindLargeClassesTool#log} has a {@code LoggerFactory.getLogger(...)} initializer,
 * and the fix will not delete a field whose initializer may have side effects. So the rule makes
 * a PER-MEMBER judgement, and this branch was discarding it.</p>
 *
 * <p>Both directions, in one fixture: the same rule, on the same file, asked two ways.</p>
 */
class ANarrowedAbsenceIsNotAScanAbsenceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;

    /** A method with nothing unreachable in it, so the rule looks and finds nothing to do. */
    private static final String TARGET = """
        package com.example;

        public class NarrowedTargets {

            public int liveMethod(int n) {
                return n + 1;
            }
        }
        """;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("NarrowedTargets.java"), TARGET);
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();
        tool = new ApplyCleanupTool(() -> service,
            new org.jawata.mcp.refactoring.RefactoringChangeCache());
        mapper = new ObjectMapper();
    }

    /** The steering lives in the meta and its toString does not carry it — read the accessor. */
    private String steeringOf(ObjectNode args) {
        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess(), "the call answers: " + resp.getError());
        return resp.getMeta() == null ? "" : String.valueOf(resp.getMeta().getSteering());
    }

    private ObjectNode base() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "remove_dead_code");
        args.put("filePath", "src/main/java/com/example/NarrowedTargets.java");
        args.put("auto_apply", false);
        return args;
    }

    @Test
    @DisplayName("mcp#76: asked about ONE member, it does not claim a real absence over the file")
    void aMemberScopedAbsenceIsScopedToTheMember() {
        ObjectNode args = base();
        args.put("symbol", "com.example.NarrowedTargets#liveMethod");
        String steering = steeringOf(args);

        assertAll(
            () -> assertFalse(steering.contains("real absence"),
                "a member-scoped question must not be answered with the SCAN's absence claim,"
                    + " which is about the file: " + steering),
            () -> assertTrue(steering.contains("member you named"),
                "and it says what it actually knows — that nothing changed in that member: "
                    + steering));
    }

    /**
     * The control, and without it the fix is indistinguishable from deleting the sentence.
     * The same rule over the same file, asked as a SWEEP, must still make the scan-level claim
     * — it is true there, and it is the honesty the sentence was written for.
     */
    @Test
    @DisplayName("mcp#76 control: a whole-file sweep still states the real absence")
    void aSweepStillStatesTheScanAbsence() {
        String steering = steeringOf(base());

        assertTrue(steering.contains("real absence"),
            "a sweep that read the file and found nothing SHOULD say the absence is real —"
                + " that distinction is the point, and only its scope was wrong: " + steering);
    }
}
