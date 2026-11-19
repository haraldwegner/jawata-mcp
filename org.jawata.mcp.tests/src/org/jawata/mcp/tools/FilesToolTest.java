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
    @DisplayName("C27-24, his word 2026-11-19 (\"pictures, csv, websites\"): a text document of the kinds the main seat and a lead may write is written here as well as a .md, the same kinds OwnWrites names, while a picture, which a text cannot be, and code are refused")
    void dataAndWebDocumentsAreWrittenAndCodeAndPicturesAreNot(@TempDir Path folder) throws Exception {
        for (String name : List.of("table.csv", "notes.txt", "page.html", "style.css", "figure.svg")) {
            data(call("write", Map.of("path", folder.resolve(name).toString(), "content", "x")));
            assertTrue(Files.exists(folder.resolve(name)), name + " was refused though a lead may write it");
            assertTrue(call("edit", Map.of("path", folder.resolve(name).toString(), "oldText", "x", "newText", "y")).isSuccess(), name + " was written but not edited");
        }
        for (String name : List.of("shot.png", "paper.pdf", "Foo.java", "build.gradle", "run.sh")) {
            assertFalse(call("write", Map.of("path", folder.resolve(name).toString(), "content", "x")).isSuccess(), name + " was written");
            assertFalse(Files.exists(folder.resolve(name)), name + " exists after the refusal");
        }
    }

    @Test
    @DisplayName("28h C26 (architect finding 20): a document that tells an agent what it may do is refused, though it is a .md, and an ordinary one beside it is written")
    void aDocumentThatConfiguresAnAgentIsRefused(@TempDir Path folder) throws Exception {
        for (String name : List.of("CLAUDE.md", "AGENTS.md", "SKILL.md", ".claude/skills/x/skill.md", ".cursor/rules/r.md", "seats/main.md",
            ".github/copilot-instructions.md", "jawata-studio/seats/pm.md", ".config/jawata-studio/seats/pm.md")) {
            Path target = folder.resolve(name);
            ToolResponse answer = call("write", Map.of("path", target.toString(), "content", "x"));

            assertFalse(answer.isSuccess(), name + " was written");
            assertFalse(Files.exists(target), name + " exists after the refusal");
        }
        Path plan = folder.resolve("docs").resolve("plan.md");
        data(call("write", Map.of("path", plan.toString(), "content", "ok")));
        assertTrue(Files.exists(plan), "an ordinary document was refused with the others");
    }

    @Test
    @DisplayName("Stage 27 (q): one passage of a document is replaced and everything else in it is left exactly as it was, which a whole-document write cannot promise for a long plan")
    void aPassageOfADocumentIsReplaced(@TempDir Path folder) throws Exception {
        Path plan = folder.resolve("plan.md");
        String before = "# Plan\n\n" + "A long stage that must not be retyped.\n".repeat(500) + "\nStage 2: build X.\n\nEnd.\n";
        Files.writeString(plan, before);

        Map<String, Object> answer = data(call("edit", Map.of("path", plan.toString(), "oldText", "Stage 2: build X.", "newText", "Stage 2: build X and Y.")));

        assertEquals(before.replace("Stage 2: build X.", "Stage 2: build X and Y."), Files.readString(plan), "the passage was not the only thing that changed");
        assertEquals(1, answer.get("replaced"), "the answer does not say one passage was replaced");
    }

    @Test
    @DisplayName("Stage 27 (q): a passage found twice or not at all is refused and the document is not touched, since a replacement that cannot say where is a guess")
    void aPassageThatIsNotOneIsRefused(@TempDir Path folder) throws Exception {
        Path plan = folder.resolve("plan.md");
        Files.writeString(plan, "same\nsame\nonce\n");

        ToolResponse twice = call("edit", Map.of("path", plan.toString(), "oldText", "same", "newText", "x"));
        ToolResponse never = call("edit", Map.of("path", plan.toString(), "oldText", "absent", "newText", "x"));
        ToolResponse empty = call("edit", Map.of("path", plan.toString(), "oldText", "", "newText", "x"));

        assertFalse(twice.isSuccess(), "a passage found twice was replaced");
        assertFalse(never.isSuccess(), "a passage found nowhere was reported as replaced");
        assertFalse(empty.isSuccess(), "an empty passage was accepted");
        assertEquals("same\nsame\nonce\n", Files.readString(plan), "a refused edit changed the document");
        assertTrue(call("edit", Map.of("path", plan.toString(), "oldText", "once", "newText", "twice")).isSuccess(), "CONTROL: a passage that occurs once was refused");
    }

    @Test
    @DisplayName("Stage 27 (q): an edit is held to the writer's refusals: any file that is not a .md, a document that tells an agent what it may do, and a file that is not there")
    void anEditIsHeldToTheWritersRefusals(@TempDir Path folder) throws Exception {
        for (String name : List.of("Foo.java", "main.rs", "view.tsx", "CLAUDE.md", "seats/main.md", ".claude/skills/x/skill.md")) {
            Path target = folder.resolve(name);
            Files.createDirectories(target.getParent());
            Files.writeString(target, "alpha\n");

            ToolResponse answer = call("edit", Map.of("path", target.toString(), "oldText", "alpha", "newText", "beta"));

            assertFalse(answer.isSuccess(), name + " was edited");
            assertEquals("alpha\n", Files.readString(target), name + " changed after the refusal");
        }
        assertFalse(call("edit", Map.of("path", folder.resolve("missing.md").toString(), "oldText", "a", "newText", "b")).isSuccess(), "an edit made a file");
        assertFalse(Files.exists(folder.resolve("missing.md")), "the missing document exists after the refusal");
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
