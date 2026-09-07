package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 14b Stage A2 — readOnlyHint annotations on detect tools
 * (MCP spec ≥ 2025-03-26; Cursor Ask-mode unblock).
 */
class ReadOnlyHintAnnotationTest {

    private static Tool stub(String name) {
        return new Tool() {
            @Override public String getName() { return name; }
            @Override public String getDescription() { return "stub"; }
            @Override public Map<String, Object> getInputSchema() {
                return Map.of("type", "object", "properties", Map.of());
            }
            @Override public ToolResponse execute(JsonNode arguments) {
                return ToolResponse.success(Map.of());
            }
        };
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> definitionOf(ToolRegistry registry, String name) {
        return registry.getToolDefinitions().stream()
            .filter(def -> name.equals(def.get("name")))
            .findFirst()
            .orElseThrow(() -> new AssertionError("definition missing for " + name));
    }

    @Test
    @DisplayName("detect tools carry annotations.readOnlyHint = true")
    void detectTools_carryReadOnlyHint() {
        ToolRegistry registry = new ToolRegistry();
        List<String> readOnly = List.of(
            // prefix-rule representatives across all four detect families
            "find_references", "find_duplicate_code",
            "get_diagnostics", "get_call_hierarchy_incoming",
            "analyze_type", "analyze_change_impact",
            "search_symbols",
            // read-only tools whose names match no detect prefix
            "go_to_definition", "health_check", "list_projects",
            "validate_syntax", "inspect_refactoring", "suggest_imports");
        readOnly.forEach(name -> registry.register(stub(name)));

        for (String name : readOnly) {
            Map<String, Object> def = definitionOf(registry, name);
            @SuppressWarnings("unchecked")
            Map<String, Object> annotations = (Map<String, Object>) def.get("annotations");
            assertNotNull(annotations, name + " must carry annotations");
            assertEquals(Boolean.TRUE, annotations.get("readOnlyHint"),
                name + " must be marked readOnlyHint");
        }
    }

    /**
     * REWRITTEN FOR mcp#36, and the old version of this test WAS the defect written down.
     *
     * <p>It asserted that a mutating tool "must NOT carry annotations (it mutates state)".
     * That is the reasoning the issue overturns: leaving a mutator unannotated does not
     * make a client cautious, it makes the client GUESS — and measured in the v3.10.0
     * Cursor dogfood, the guess was not even stable, gating three of eight identical calls
     * in one session and letting five through.</p>
     *
     * <p><b>THE FIRST REWRITE OVER-CORRECTED, and an architect watch caught it.</b> It
     * derived {@code destructiveHint} as the negation of the read-only name match — which
     * collapses a THREE-valued domain (read-only · rewrites source · neither) into two, so
     * every tool the classifier had no opinion about began affirmatively claiming to be
     * destructive. That is worse than the silence it replaced: {@code compile_workspace} is
     * the tool this product's own steering line tells every agent to run and it was being
     * published as destructive; so was {@code experience}, for its {@code recall} half,
     * which is the exact tool the dogfood measured being over-gated.</p>
     *
     * <p><b>And this test could not have caught either, which is why it now drives REAL
     * TOOLS.</b> It registered name-only stubs while the classifier read only the name — so
     * both sides of the comparison were one literal list written by one author, a guard over
     * a fact the class already owns. It asks the registry about the tools the application
     * actually registers instead.</p>
     */
    @Test
    @DisplayName("mcp#36: the REAL refactoring tools say they rewrite source")
    void realRefactoringToolsSayTheyRewriteSource() {
        // BORROW AND RETURN. ToolRegistry.register publishes into the PROCESS-WIDE
        // OperationRegistry, so registering the real doors here changes what every later
        // test in this JVM sees. The first version of this test omitted the restore and
        // the full suite went red in CureTierTest — composition_over_inheritance derived
        // RUN instead of CONSIDER, because this test had registered the very operation its
        // cure names. The class run passed in isolation; only the whole suite, in order,
        // could see it.
        org.jawata.mcp.refactoring.OperationRegistry ops =
            org.jawata.mcp.refactoring.OperationRegistry.theRegistry();
        org.jawata.mcp.refactoring.OperationRegistry.Snapshot borrowed = ops.snapshot();
        try {
            ToolRegistry registry = new ToolRegistry();
            org.jawata.mcp.refactoring.RefactoringChangeCache cache =
                new org.jawata.mcp.refactoring.RefactoringChangeCache();
            // The SAME call the application registers from, so this cannot drift from what
            // actually ships.
            List<AbstractTool> real = new java.util.ArrayList<>(
                RefactoringDoors.all(() -> null, cache));
            real.addAll(RefactoringDoors.standalone(() -> null, cache));
            real.forEach(registry::register);

            assertFalse(real.isEmpty(), "the derivation must yield tools, or this proves nothing");
            for (AbstractTool tool : real) {
                Map<String, Object> annotations = annotationsOf(registry, tool.getName());
                assertEquals(Boolean.FALSE, annotations.get("readOnlyHint"),
                    tool.getName() + " rewrites source, so it is not read-only");
                assertEquals(Boolean.TRUE, annotations.get("destructiveHint"),
                    tool.getName() + " rewrites existing files; reversible is not additive");
                assertEquals(Boolean.FALSE, annotations.get("openWorldHint"),
                    tool.getName() + " operates on the loaded workspace, a closed domain");
            }
        } finally {
            ops.restore(borrowed);
        }
    }

    @Test
    @DisplayName("mcp#36: a tool the product cannot vouch for OMITS destructiveHint — the third state")
    void anUnvouchedToolSaysNothingRatherThanSomethingFalse() {
        // compile_workspace makes this concrete: not read-only by name, does not rewrite
        // source, and this product's own steering line tells every agent to run it. Claiming
        // it destructive would have jawata telling clients to gate the tool jawata tells
        // agents to use.
        ToolRegistry registry = new ToolRegistry();
        registry.register(stub("compile_workspace"));

        Map<String, Object> annotations = annotationsOf(registry, "compile_workspace");

        assertEquals(Boolean.FALSE, annotations.get("readOnlyHint"),
            "it is still not a read-only tool, and says so");
        assertFalse(annotations.containsKey("destructiveHint"),
            "but the product cannot vouch that it rewrites anything, so it says NOTHING "
                + "rather than something false — a missing hint reads as unknown, which is "
                + "exactly what this is. got: " + annotations);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> annotationsOf(ToolRegistry registry, String name) {
        Map<String, Object> annotations =
            (Map<String, Object>) definitionOf(registry, name).get("annotations");
        assertNotNull(annotations, name + " must carry annotations — silence is what "
            + "made clients guess");
        return annotations;
    }
}
