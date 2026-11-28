package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.engine.LanguageAwareReconciler;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.java.JavaProvider;
import org.jawata.mcp.models.CallerIdentity;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.MoveTool;
import org.jawata.mcp.tools.ToolRegistry;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.resident.workspace.OwnedReconciler;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 28h z195 — a REPRODUCTION test. After a coupled signature change has been applied and the
 * body has been CORRECTED BY HAND on disk (the service is not told, as when someone edits in an
 * editor), does {@code move kind=class}, called through the {@link ToolRegistry} where the disk
 * sync happens, act on the corrected text rather than on a stale model?
 *
 * <p>Green means "not reproduced" and the test stays as a pin. Red means "reproduced", and this
 * test is then the reproduction on file.</p>
 */
class MoveClassAfterSignatureChangeTest {

    private static final String MARKER = "// z195-corrected-by-hand";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    @DisplayName("28h z195: move kind=class after a hand correction of the body moves the corrected text")
    void theMoveActsOnTheTextCorrectedByHand() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path root = service.getProjectRoot();
        Path file = root.resolve("src/main/java/com/example/RefactoringTarget.java");

        // The registry is wired as the engine wires it: the real tools over the loaded service
        // and the language-aware reconciler that syncs the disk before each call.
        RefactoringChangeCache cache = new RefactoringChangeCache();
        ToolRegistry registry = new ToolRegistry();
        registry.register(new ChangeMethodSignatureTool(() -> service, cache));
        registry.register(new MoveTool(() -> service, cache));
        registry.setReconciler(new LanguageAwareReconciler(new OwnedReconciler(() -> service),
            () -> List.of(new JavaProvider())));
        CallerIdentity caller = CallerIdentity.supplied("z195");

        // (1) the coupled signature change: String -> void, which applies and lists its errors.
        ObjectNode change = mapper.createObjectNode();
        change.put("kind", "change_signature");
        change.put("filePath", file.toString());
        change.put("line", 71);
        change.put("column", 18);
        change.put("newReturnType", "void");
        ToolResponse changed = registry.callTool("change_method_signature", change, caller);
        assertTrue(changed.isSuccess(), () -> "the signature change must apply: " + changed.getError());
        String afterChange = Files.readString(file);
        assertTrue(afterChange.contains("void formatMessage"),
            "the new return type is on disk:\n" + afterChange);

        // (2) the body is corrected BY HAND, behind the service's back.
        String corrected = afterChange
            .replace("return message + \": \" + count;", MARKER)
            .replace("String result = formatMessage(\"Items\", 5);\n        System.out.println(result);",
                "formatMessage(\"Items\", 5);")
            .replace("System.out.println(formatMessage(\"Total\", 10));", "formatMessage(\"Total\", 10);");
        assertNotEquals(afterChange, corrected, "the hand correction must change the text");
        assertTrue(corrected.contains(MARKER), "the marker is in the corrected text");
        assertFalse(corrected.contains("return message + \": \" + count;"), "the value-returning body is gone");
        Files.writeString(file, corrected);

        // (3) move the class, through the registry.
        ObjectNode move = mapper.createObjectNode();
        move.put("kind", "class");
        move.put("typeName", "com.example.RefactoringTarget");
        move.put("targetPackage", "com.example.z195moved");
        ToolResponse moved = registry.callTool("move", move, caller);
        assertTrue(moved.isSuccess(), () -> "the move after the hand correction must succeed: "
            + moved.getError() + " / data=" + moved.getData());

        // (4) the moved file holds the corrected text, not a stale copy.
        Path newFile = root.resolve("src/main/java/com/example/z195moved/RefactoringTarget.java");
        assertTrue(Files.exists(newFile), "the class lives under the new package directory");
        assertFalse(Files.exists(file), "the old file no longer exists");
        String movedText = Files.readString(newFile);
        assertTrue(movedText.contains(MARKER), "the move acted on the corrected text:\n" + movedText);
        assertTrue(movedText.contains("package com.example.z195moved;"),
            "the package line names the new package:\n" + movedText);
    }
}
