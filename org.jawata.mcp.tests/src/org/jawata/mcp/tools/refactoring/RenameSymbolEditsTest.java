package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindReferencesTool;
import org.jawata.mcp.tools.RenameSymbolTool;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The editor's edit set (Sprint 28h, S16c): a refactoring staged with {@code edits: true} answers the edits the change would make,
 * file by file with their positions, and keeps the change out of the cache, because the editor applies them and the engine writes
 * nothing. The edits come from the change the engine computes, not from a second rename.
 */
class RenameSymbolEditsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    private JdtServiceImpl service;
    private RenameSymbolTool tool;
    private RefactoringChangeCache cache;
    private Path project;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        tool = new RenameSymbolTool(() -> service, cache, () -> null);
        project = helper.getTempDirectory().resolve("simple-maven");
    }

    private Path source(String name) {
        return project.resolve("src/main/java/com/example/" + name);
    }

    private ToolResponse stage(Path file, int line, int column, String newName) {
        ObjectNode args = JSON.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", line);
        args.put("column", column);
        args.put("newName", newName);
        args.put("auto_apply", false);
        args.put("edits", true);
        return tool.execute(args);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse response) {
        return (Map<String, Object>) response.getData();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    /** The text of a file with its edits applied, last position first so the earlier ones keep theirs. */
    private static String applied(String text, List<Map<String, Object>> edits) {
        String[] lines = text.split("\n", -1);
        int[] starts = new int[lines.length];
        for (int i = 1; i < lines.length; i++) {
            starts[i] = starts[i - 1] + lines[i - 1].length() + 1;
        }
        List<Map<String, Object>> ordered = new ArrayList<>(edits);
        ordered.sort(Comparator.comparingInt((Map<String, Object> e) -> (int) e.get("line")).thenComparingInt(e -> (int) e.get("column")).reversed());
        StringBuilder result = new StringBuilder(text);
        for (Map<String, Object> edit : ordered) {
            int from = starts[(int) edit.get("line")] + (int) edit.get("column");
            int to = starts[(int) edit.get("endLine")] + (int) edit.get("endColumn");
            result.replace(from, to, (String) edit.get("newText"));
        }
        return result.toString();
    }

    @Test
    @DisplayName("staged with edits, a method rename answers one edit per place the method is named, in each file, and writes and caches nothing")
    void aMethodRenameAnswersItsEditsAndWritesNothing() throws Exception {
        String helperBefore = Files.readString(source("SharedHelper.java"));
        String oneBefore = Files.readString(source("SharedUserOne.java"));
        String twoBefore = Files.readString(source("SharedUserTwo.java"));

        // SharedHelper#value is declared on line 5 (zero-based) and called once in each of the two users.
        ToolResponse response = stage(source("SharedHelper.java"), 5, 15, "magnitude");

        assertTrue(response.isSuccess(), "the rename must stage; got: " + response.getError());
        Map<String, Object> data = dataOf(response);
        assertEquals(Boolean.FALSE, data.get("applied"));
        assertNull(data.get("changeId"), "the editor applies the edits, so the change is not kept for a later apply");
        assertEquals(0, cache.size(), "nothing staged is cached");

        List<Map<String, Object>> files = list(data.get("edits"));
        assertNotNull(files, "the response carries the edits");
        assertEquals(3, files.size(), "the declaration and the two users are three files");
        int count = 0;
        for (Map<String, Object> file : files) {
            count += list(file.get("edits")).size();
        }
        assertEquals(3, count, "one edit per place: the declaration and one call in each user");

        // The exit clause of S16c: the edit count equals the engine's own reference count for the symbol, plus its declaration.
        ObjectNode find = JSON.createObjectNode();
        find.put("filePath", source("SharedHelper.java").toString());
        find.put("line", 5);
        find.put("column", 15);
        int references = ((Number) dataOf(new FindReferencesTool(() -> service).execute(find)).get("totalReferences")).intValue();
        assertEquals(references + 1, count, "the edits are the references the engine finds, and the declaration");

        String helperAfter = applied(helperBefore, editsOf(files, "SharedHelper.java"));
        String oneAfter = applied(oneBefore, editsOf(files, "SharedUserOne.java"));
        String twoAfter = applied(twoBefore, editsOf(files, "SharedUserTwo.java"));
        assertTrue(helperAfter.contains("public int magnitude()"), helperAfter);
        assertTrue(oneAfter.contains("helper.magnitude()") && !oneAfter.contains("helper.value()"), oneAfter);
        assertTrue(twoAfter.contains("helper.magnitude()") && !twoAfter.contains("helper.value()"), twoAfter);

        assertEquals(helperBefore, Files.readString(source("SharedHelper.java")), "the engine wrote nothing");
        assertEquals(oneBefore, Files.readString(source("SharedUserOne.java")));
        assertEquals(twoBefore, Files.readString(source("SharedUserTwo.java")));
    }

    @Test
    @DisplayName("staged with edits, a type rename also answers the rename of the type's file")
    void aTypeRenameAnswersTheFileRenameToo() {
        ToolResponse response = stage(source("HelloWorld.java"), 5, 13, "Greeting");

        assertTrue(response.isSuccess(), "the rename must stage; got: " + response.getError());
        Map<String, Object> data = dataOf(response);
        assertFalse(list(data.get("edits")).isEmpty(), "the class and its constructors are edited");
        List<Map<String, Object>> renames = list(data.get("fileRenames"));
        assertNotNull(renames, "the type's file is renamed with it, and the editor must be told");
        assertEquals(1, renames.size());
        assertTrue(((String) renames.get(0).get("from")).endsWith("HelloWorld.java"), renames.toString());
        assertTrue(((String) renames.get(0).get("to")).endsWith("Greeting.java"), renames.toString());
    }

    @Test
    @DisplayName("without edits, a staged rename is as it was: a change id, a diff and no edit list")
    void withoutEditsTheStagedAnswerIsUnchanged() {
        ObjectNode args = JSON.createObjectNode();
        args.put("filePath", source("SharedHelper.java").toString());
        args.put("line", 5);
        args.put("column", 15);
        args.put("newName", "magnitude");
        args.put("auto_apply", false);

        Map<String, Object> data = dataOf(tool.execute(args));

        assertNotNull(data.get("changeId"));
        assertNull(data.get("edits"));
    }

    private static List<Map<String, Object>> editsOf(List<Map<String, Object>> files, String fileName) {
        for (Map<String, Object> file : files) {
            if (((String) file.get("filePath")).endsWith(fileName)) {
                return list(file.get("edits"));
            }
        }
        throw new AssertionError("no edits for " + fileName + " in " + files);
    }
}
