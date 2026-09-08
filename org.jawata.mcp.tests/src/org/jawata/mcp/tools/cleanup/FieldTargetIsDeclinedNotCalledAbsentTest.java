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
 * Sprint 28e, mcp#76 — <b>a case the rule cannot handle is DECLINED, never reported as a real
 * absence.</b>
 *
 * <p>{@code apply_cleanup kind=remove_dead_code} pointed at a FIELD accepted the address,
 * changed nothing, and answered {@code hasChanges: false} carrying the scan's own sentence:
 * <i>"the scan was COMPLETE (1 file(s) examined), so this is a real absence, not a failure to
 * look."</i> Reproduced against v4.1.3 on {@code FindLargeClassesTool#log}, a field the
 * COMPILER itself reports as unused.</p>
 *
 * <p><b>Why that is worse than a refusal.</b> A refusal tells the caller their case was
 * declined. This asserted the opposite — and the sentence exists precisely to separate <i>found
 * nothing</i> from <i>could not look</i>, so applying it to a third case that is neither
 * (<i>looked, and cannot act on this kind of target</i>) actively rules out the explanation
 * that turns out to be true.</p>
 *
 * <p><b>Both directions, because a fix that refused every narrowing would pass a one-sided
 * test.</b> The declining case has two siblings that must still behave exactly as before: a
 * METHOD narrowing with no dead statements, which is a genuine absence and must still say so;
 * and {@code redundant_modifiers}, the one kind that legitimately acts on a field, which must
 * not be declined. Without those, "declines everything" and "declines the right thing" are the
 * same result.</p>
 */
class FieldTargetIsDeclinedNotCalledAbsentTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;

    private static final String TARGET = """
        package com.example;

        public class CleanupTargets {

            /** A field. remove_dead_code rewrites statements and can never act on one. */
            private static final String unusedLabel = "x";

            /** A method with nothing unreachable in it — a GENUINE absence for this rule. */
            public int liveMethod(int n) {
                return n + 1;
            }
        }
        """;

    /** An interface, so redundant_modifiers has an implicit modifier on a FIELD to strip. */
    private static final String IFACE = """
        package com.example;

        public interface CleanupIface {
            public static final int REDUNDANT = 1;
        }
        """;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("CleanupTargets.java"), TARGET);
        Files.writeString(pkg.resolve("CleanupIface.java"), IFACE);
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();
        // A REAL cache, not null: redundant_modifiers genuinely acts on the interface field, so
        // that control reaches STAGING — which is itself the proof the case is not declined.
        // With a null cache it failed there, which is a test defect wearing a product failure's
        // clothes.
        tool = new ApplyCleanupTool(() -> service,
            new org.jawata.mcp.refactoring.RefactoringChangeCache());
        mapper = new ObjectMapper();
    }

    private ToolResponse cleanup(String kind, String symbol, String file) {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", kind);
        args.put("symbol", symbol);
        args.put("filePath", "src/main/java/com/example/" + file);
        args.put("auto_apply", false);
        return tool.execute(args);
    }

    /**
     * The response as a reader meets it, STEERING INCLUDED.
     *
     * <p>The sentence this issue is about lives in {@code meta.steering}, which the meta's own
     * {@code toString} does not carry — so a first version of this helper asserted over text
     * that could never contain the needle, and the two control cases failed for that reason
     * rather than for anything the product did. Read the accessor.</p>
     */
    private static String render(ToolResponse r) {
        String steering = r.getMeta() == null ? "" : String.valueOf(r.getMeta().getSteering());
        return String.valueOf(r.getData()) + " | " + String.valueOf(r.getError())
            + " | steering=" + steering;
    }

    @Test
    @DisplayName("mcp#76: a field target is declined by name, not reported as a real absence")
    void aFieldTargetIsDeclined() {
        ToolResponse resp = cleanup("remove_dead_code",
            "com.example.CleanupTargets#unusedLabel", "CleanupTargets.java");
        String text = render(resp);

        assertAll(
            () -> assertFalse(resp.isSuccess(),
                "a case the rule cannot handle is a REFUSAL, not a success: " + text),
            () -> assertTrue(text.contains("FIELD"),
                "the refusal names what was wrong with the target: " + text),
            // The defect verbatim. Without this clause the test would pass against a refusal
            // that ALSO carried the absence sentence, which is the state being fixed.
            () -> assertFalse(text.contains("real absence"),
                "and it must not also assert the absence is real — that sentence is what made"
                    + " this worse than a plain refusal: " + text));
    }

    @Test
    @DisplayName("mcp#76 control: a method with nothing unreachable is still an honest absence")
    void aGenuineAbsenceIsStillReportedAsOne() {
        ToolResponse resp = cleanup("remove_dead_code",
            "com.example.CleanupTargets#liveMethod", "CleanupTargets.java");
        String text = render(resp);

        assertAll(
            () -> assertTrue(resp.isSuccess(),
                "a method the rule CAN act on, with nothing to do, is not a refusal: " + text),
            () -> assertTrue(text.contains("real absence"),
                "and it still says the absence is real, because here it is: " + text));
    }

    @Test
    @DisplayName("mcp#76 control: the one kind that acts on fields is not declined")
    void theRuleThatActsOnFieldsIsNotDeclined() {
        ToolResponse resp = cleanup("redundant_modifiers",
            "com.example.CleanupIface#REDUNDANT", "CleanupIface.java");
        String text = render(resp);

        assertFalse(text.contains("can never act"),
            "redundant_modifiers strips implicit modifiers on interface FIELDS, so a field is a"
                + " real target for it — the exemption is per rule, never blanket: " + text);
    }
}
