package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.domain.NoOpAdvisor;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.PlanStore;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * 28k ledger C2-18: a plan step whose tool THROWS (a program error, not a refusal) used to leave the steps already applied in
 * place, because nothing in the plan loop caught it. Now a thrown error is a failed step, and the whole plan rolls back.
 *
 * <p>The second step's tool is replaced by one that throws; the first runs the real extraction.</p>
 */
class AStepThatThrowsRollsThePlanBackTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("a step whose tool throws rolls back the steps already applied, byte for byte, and says why")
    void aThrowingStepRollsThePlanBack() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("compose-clean");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        PlanRefactoringTool plan = new PlanRefactoringTool(() -> service, cache, new PlanStore(), new NoOpAdvisor());
        ExtractTool real = new ExtractTool(() -> service, cache);
        int[] calls = {0};
        plan.registerStepTool("extract", (svc, args) -> {
            if (++calls[0] == 2) {
                throw new IllegalStateException("the second step's tool blew up");
            }
            return real.executeWithService(svc, args);
        });
        Path target = helper.getTempDirectory().resolve("compose-clean/src/main/java/com/example/ComposeMethodTargets.java");
        String original = Files.readString(target);
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode planArgs = mapper.createObjectNode();
        planArgs.put("action", "plan");
        planArgs.put("kind", "compose_method");
        planArgs.put("filePath", target.toString());
        ArrayNode sections = planArgs.putArray("sections");
        int[] lines = {13, 18};
        String[] names = {"writeHeader", "writeFooter"};
        for (int i = 0; i < lines.length; i++) {
            ObjectNode section = sections.addObject();
            section.put("startLine", lines[i]);
            section.put("startColumn", 8);
            section.put("endLine", lines[i]);
            section.put("endColumn", 44);
            section.put("methodName", names[i]);
        }
        ToolResponse planned = plan.execute(planArgs);
        assertTrue(planned.isSuccess(), () -> "plan failed: " + planned.getError());
        String planId = (String) ((Map<?, ?>) planned.getData()).get("planId");

        ToolResponse applied = plan.execute(mapper.createObjectNode().put("action", "apply_plan").put("planId", planId));

        assertEquals(2, calls[0], "both steps were attempted: the first applied, the second threw");
        Map<?, ?> data = (Map<?, ?>) applied.getData();
        assertEquals(Boolean.TRUE, data.get("rolledBack"), () -> "the plan rolls back: " + applied.getError() + " " + data);
        assertTrue(String.valueOf(data.get("reason")).contains("blew up"), "the reason names what threw: " + data.get("reason"));
        assertEquals(original, Files.readString(target), "the first step's extraction is undone: the file is byte-identical");
    }
}
