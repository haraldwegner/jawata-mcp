package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h, D57 (j): the principal changes no code, so its brain's own editors and shell are withheld; what it still needs to read and
 * search any file and to write a document comes from this tool. It reads and searches every file, writes a {@code .md} file, and refuses to
 * write any other.
 */
class FilesToolTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final FilesTool files = new FilesTool();

    private ToolResponse call(String action, Map<String, String> arguments) {
        ObjectNode node = JSON.createObjectNode().put("action", action);
        arguments.forEach(node::put);
        return files.execute(node);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse response) {
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        return (Map<String, Object>) response.getData();
    }

    @Test
    @DisplayName("28h D57 (j): a document is written, and read back as it was written")
    void aDocumentIsWrittenAndReadBack(@TempDir Path folder) throws Exception {
        Path plan = folder.resolve("docs").resolve("plan.md");

        data(call("write", Map.of("path", plan.toString(), "content", "# Plan\n\nStep 1.\n")));

        assertEquals("# Plan\n\nStep 1.\n", Files.readString(plan), "the folder that did not exist was not made, or the text differs");
        assertEquals("# Plan\n\nStep 1.\n", data(call("read", Map.of("path", plan.toString()))).get("content"));
    }

    @Test
    @DisplayName("28h D57 (j): any file that is not a .md document is refused, and nothing is written")
    void anythingButADocumentIsRefused(@TempDir Path folder) throws Exception {
        for (String name : List.of("Foo.java", "main.rs", "view.tsx", "pom.xml", "notes.md.java", "README")) {
            Path target = folder.resolve(name);
            ToolResponse answer = call("write", Map.of("path", target.toString(), "content", "x"));

            assertFalse(answer.isSuccess(), name + " was written");
            assertFalse(Files.exists(target), name + " exists after the refusal");
        }
    }

    @Test
    @DisplayName("28h D57 (j): a source file can be read, though not written")
    void aSourceFileIsRead(@TempDir Path folder) throws Exception {
        Path source = folder.resolve("Foo.java");
        Files.writeString(source, "class Foo {}\n");

        assertEquals("class Foo {}\n", data(call("read", Map.of("path", source.toString()))).get("content"));
    }

    @Test
    @DisplayName("28h D57 (j): a search names each matching line by file and line number, in every file under the folder, and a control finds nothing")
    @SuppressWarnings("unchecked")
    void aSearchNamesFileAndLine(@TempDir Path folder) throws Exception {
        Files.createDirectories(folder.resolve("src"));
        Files.writeString(folder.resolve("src").resolve("A.java"), "class A {\n  int colour = 1;\n}\n");
        Files.writeString(folder.resolve("notes.md"), "colour: red\n");

        List<Map<String, Object>> hits = (List<Map<String, Object>>) data(call("search", Map.of("folder", folder.toString(), "pattern", "colour")))
            .get("matches");

        assertEquals(2, hits.size(), hits.toString());
        assertTrue(hits.stream().anyMatch(h -> String.valueOf(h.get("path")).endsWith("A.java") && Integer.valueOf(2).equals(h.get("line"))), hits.toString());
        assertTrue(((List<?>) data(call("search", Map.of("folder", folder.toString(), "pattern", "nowhere"))).get("matches")).isEmpty(),
            "CONTROL: a pattern in no file was found");
    }

    @Test
    @DisplayName("28h D57 (j): the tool says it changes no code, so a seat that changes none may carry it")
    void itChangesNoCode() {
        assertFalse(files.changesCode());
        assertEquals("files", files.getName());
    }
}
