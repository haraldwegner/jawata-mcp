package org.jawata.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindUnusedCodeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#77 — <b>the detector reported live members as dead, and its cure offered to
 * delete them.</b>
 *
 * <p>{@code find_quality_issue(kind=unused)} used JDT binding OBJECTS as set members and asked
 * {@code usedBindings.contains(declaration)}. For a GENERIC member that is always false: a call
 * site resolves to a parameterized INSTANCE — {@code causeOf(Throwable, Class<BindException>)} —
 * which is not equal to the declaration {@code causeOf(Throwable, Class<T>)}. So every private
 * generic member read as unused however many callers it had.</p>
 *
 * <p><b>Measured on this repository before the fix</b>, each by call hierarchy:
 * {@code JawataApplication#causeOf} 1 caller, {@code H2ExperienceStore#withRead} 12,
 * {@code RefactorToStateTool#enclosing} 4 — all three reported unused, all three generic. Each
 * carried a rendered, door-accepted {@code apply_cleanup kind=remove_dead_code} address, so the
 * cure declining was the only thing between a false positive and a destructive edit.</p>
 *
 * <p><b>Both directions are asserted, and that is the point of the class.</b> A fix that simply
 * stopped reporting things would pass a test that only checks the false positives are gone. Each
 * case therefore has a sibling that must STILL be reported, and the pair is what discriminates a
 * detector that got more accurate from one that went quiet.</p>
 *
 * <p>The fixture is written into a COPY of the shared project rather than into the project
 * itself: adding a file to {@code simple-maven} has moved a counted population four times in
 * this sprint (a findings page, a clone-group page, a naming population, a search ranking).</p>
 */
class UnusedAgreesWithTheCompilerTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindUnusedCodeTool tool;
    private ObjectMapper mapper;

    /**
     * Four private members, in two matched pairs.
     *
     * <p>The two CALLED ones differ only in whether they are generic, and the two UNCALLED ones
     * only in whether they are the serialization contract — so each pair isolates exactly one
     * property and nothing else can explain a difference in how they are reported.</p>
     */
    private static final String TARGET = """
        package com.example;

        import java.io.Serializable;

        public class UnusedTargets implements Serializable {

            /** Read by ObjectStreamClass, referenced by no Java code: unused BY CONSTRUCTION. */
            private static final long serialVersionUID = 7L;

            /** Referenced by nothing at all — the control for the field above. */
            private static final long trulyDeadConstant = 3L;

            /** GENERIC and CALLED. Reported unused before mcp#77. */
            private <T> T firstOf(java.util.List<T> items) {
                return items.isEmpty() ? null : items.get(0);
            }

            /** NON-generic and CALLED — the control that proves the pair differs only by generics. */
            private String plainCalled(String s) {
                return s.trim();
            }

            /** NON-generic and UNCALLED — must STILL be reported, or the fix went quiet. */
            private String plainDead(String s) {
                return s.toUpperCase();
            }

            public String use(java.util.List<String> items) {
                return plainCalled(firstOf(items));
            }
        }
        """;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("UnusedTargets.java"), TARGET);
        // The production reconcile, the way a sibling test does it — a test-only refresh would
        // prove less about how the product notices a file written outside it.
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();
        tool = new FindUnusedCodeTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Set<String> reportedNames() {
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", "src/main/java/com/example/UnusedTargets.java");
        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess(), "the detector answers: " + resp);
        Map<String, Object> data = (Map<String, Object>) resp.getData();
        List<Map<String, Object>> items =
            (List<Map<String, Object>>) data.getOrDefault("unusedItems", List.of());
        Set<String> names = new LinkedHashSet<>();
        for (Map<String, Object> item : items) {
            names.add(String.valueOf(item.get("name")));
        }
        return names;
    }

    @Test
    @DisplayName("mcp#77: a called generic member is not dead, and an uncalled one still is")
    void genericCallersAreCountedWithoutSilencingTheDetector() {
        Set<String> reported = reportedNames();

        assertAll(
            // The fix.
            () -> assertFalse(reported.contains("firstOf"),
                "a GENERIC method with a caller is not unused — this is mcp#77: " + reported),
            // Its control: without this, a detector that reported nothing would pass the clause
            // above and look like a fix.
            () -> assertTrue(reported.contains("plainDead"),
                "a non-generic method with no caller must STILL be reported: " + reported),
            // The pair that isolates generic-ness: same call shape, one generic, one not.
            () -> assertFalse(reported.contains("plainCalled"),
                "a non-generic method with a caller was never affected: " + reported));
    }

    @Test
    @DisplayName("mcp#77: serialVersionUID is exempt, and an ordinary dead constant is not")
    void theSerializationContractIsExemptWithoutExemptingFields() {
        Set<String> reported = reportedNames();

        assertAll(
            () -> assertFalse(reported.contains("serialVersionUID"),
                "serialVersionUID is read by the serialization machinery, so a reference count"
                    + " can never find its reader and deleting it changes the class's"
                    + " serialization identity: " + reported),
            // Its control: the exemption is for ONE name, not for private static final fields.
            () -> assertTrue(reported.contains("trulyDeadConstant"),
                "an ordinary unused constant must still be reported: " + reported));
    }
}
