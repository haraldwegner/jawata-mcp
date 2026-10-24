package org.jawata.mcp.tools.refactoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.change.DiffRenderer;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * REL44-25 — a placeholder the engine writes into the code is SAID, not left to be found.
 *
 * <p>Adding a parameter to ExperienceRetrieval#fits wrote an {@code @param tally} tag whose
 * text was a to-do marker into its javadoc. The text is JDT's, not jawata's, and the answer's
 * summary named the signature change and nothing about the stub — it had to be found in the
 * diff and fixed by hand. Every apply-shaped answer now lists the marker lines a change ADDS
 * ({@code placeholdersAdded}), and not the ones it merely moves.</p>
 */
class AStubTheEngineWritesIsReportedTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("REL44-25: change_signature names the @param stub it writes into the javadoc")
    @SuppressWarnings("unchecked")
    void theStubIsNamed() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path file = service.getProjectRoot().resolve("src/main/java/com/example/Documented.java");
        Files.writeString(file, """
            package com.example;

            public class Documented {
                /**
                 * Adds a number.
                 *
                 * @param a the first
                 * @return the sum
                 */
                public int add(int a) {
                    return a;
                }

                int caller() {
                    return add(1);
                }
            }
            """);
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "change_signature");
        args.put("filePath", file.toString());
        args.put("line", 9);
        args.put("column", 15);
        args.put("auto_apply", false);
        ArrayNode params = args.putArray("newParameters");
        params.addObject().put("name", "a").put("type", "int");
        params.addObject().put("name", "tally").put("type", "int").put("defaultValue", "0");

        ToolResponse r = new ChangeMethodSignatureTool(() -> service, new RefactoringChangeCache())
            .execute(args);
        assertTrue(r.isSuccess(), () -> "staging failed: " + r.getError());
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String diff = String.valueOf(data.get("diff"));

        assertTrue(diff.lines().anyMatch(l -> l.startsWith("+") && l.contains("@param tally")
                && l.contains("TODO")),
            () -> "the precondition, as measured live: JDT writes a TODO @param tag:\n" + diff);
        Object placeholders = data.get("placeholdersAdded");
        assertTrue(placeholders instanceof List<?> l && l.stream()
                .anyMatch(p -> String.valueOf(p).contains("@param tally")),
            () -> "the answer must name the stub it wrote: " + data.keySet() + " " + placeholders);
    }

    @Test
    @DisplayName("REL44-25: a to-do marker the change only MOVES is not reported as written")
    void aMovedTodoIsNotReported() {
        String diff = DiffRenderer.unifiedDiff(List.of(
            new DiffRenderer.FileDiff("A.java",
                "class A {\n    // TODO keep this\n    int x;\n}\n",
                "class A {\n    int x;\n}\n"),
            new DiffRenderer.FileDiff("B.java",
                "class B {\n}\n",
                "class B {\n    // TODO keep this\n    // TODO new stub\n}\n")));

        assertEquals(List.of("B.java: // TODO new stub"), DiffRenderer.addedPlaceholders(diff),
            "the moved TODO went from A to B and is not new; only the new one is reported");
    }
}
