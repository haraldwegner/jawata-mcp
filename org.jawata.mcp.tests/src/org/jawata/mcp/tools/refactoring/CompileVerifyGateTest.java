package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * C13-c / v2.12.1 — the compile-verify gate on {@link AbstractApplyingRefactoringTool}:
 * a refactoring that APPLIED broken code must not report success; it must undo
 * itself and refuse with the compiler's messages. Proven necessary live: the
 * Stage-14 self-refactor applied a non-compiling extract and answered
 * {@code applied: true}.
 */
class CompileVerifyGateTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private ObjectMapper om;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        om = new ObjectMapper();
        targetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/HelloWorld.java");
    }

    /** Minimal applying tool that injects the text it is given at file offset 0. */
    private static final class InjectingTool extends AbstractApplyingRefactoringTool {
        InjectingTool(Supplier<IJdtService> s, RefactoringChangeCache c) {
            super(s, c);
        }

        @Override
        public String getName() {
            return "test_injector";
        }

        @Override
        public String getDescription() {
            return "test-only";
        }

        @Override
        public Map<String, Object> getInputSchema() {
            return Map.of("type", "object");
        }

        @Override
        protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
            Path p = Path.of(arguments.get("filePath").asText());
            ICompilationUnit cu = service.getCompilationUnit(p);
            IFile file = (IFile) cu.getResource();
            List<TextEdit> edits = List.of(
                new InsertEdit(0, arguments.get("inject").asText()));
            Change change = ChangeEngine.fromFileEdits("inject", Map.of(file, edits));
            return Preparation.of(change, "inject");
        }
    }

    private ObjectNode args(String inject) {
        ObjectNode args = om.createObjectNode();
        args.put("filePath", targetFile.toString());
        args.put("inject", inject);
        return args;
    }

    @Test
    @DisplayName("a change that breaks the compile is UNDONE and refused with the compiler's message — never 'applied: true'")
    void breakingChange_isUndoneAndRefused() throws Exception {
        String original = Files.readString(targetFile);

        ToolResponse r = new InjectingTool(() -> service, new RefactoringChangeCache())
            .execute(args("%%% this is not java %%% "));

        assertFalse(r.isSuccess(), "broken code must not be a success");
        assertEquals("REFACTORING_BROKE_COMPILE", r.getError().getCode());
        assertTrue(r.getError().getMessage().contains("UNDONE"),
            "the refusal must say the change was rolled back: " + r.getError().getMessage());
        assertEquals(original, Files.readString(targetFile),
            "the broken change must not remain on disk");
    }

    @Test
    @DisplayName("a harmless change passes the gate and reports compileVerified")
    void harmlessChange_passesGate() throws Exception {
        ToolResponse r = new InjectingTool(() -> service, new RefactoringChangeCache())
            .execute(args("// harmless comment\n"));

        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals(Boolean.TRUE, data.get("compileVerified"));
        assertTrue(Files.readString(targetFile).startsWith("// harmless comment"),
            "the harmless change stays applied");
    }
}
