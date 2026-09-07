package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.OrganizeImportsTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jawata-mcp#16 — <b>organize_imports can ADD a missing import.</b>
 *
 * <p>It could not, for a reason nobody found by reading. The add path is the only one that
 * runs a type-name SEARCH, and JDT's search collector reads
 * {@code org.eclipse.jdt.ui.typefilter.enabled} and passes it straight to
 * {@code new StringTokenizer(str, ";")} — so headless, where that preference is unset, the
 * add path and only the add path died on an NPE while remove-and-sort worked perfectly.</p>
 *
 * <p>The recorded divergence says it was hunted in {@code OrganizeImportsOperation},
 * {@code JavaPreferencesSettings} and {@code CodeStyleConfiguration}. It is in none of them:
 * it is a type FILTER, an IDE convenience for hiding names from a search, which the import
 * machinery touches only by going through the search engine. Found by RUNNING the failing
 * path and reading the stack, after reading had already failed.</p>
 */
class OrganizeImportsAddsMissingTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private Path write(JdtServiceImpl service, String name, String body) throws Exception {
        Path file = service.getProjectRoot()
            .resolve("src/main/java/com/example/" + name + ".java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, body);
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();
        return file;
    }

    private ToolResponse organize(JdtServiceImpl service, Path file) {
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("filePath", file.toString());
        return new OrganizeImportsTool(() -> service, new RefactoringChangeCache())
            .execute(args);
    }

    @Test
    @DisplayName("mcp#16: the missing imports are ADDED — the path that used to NPE")
    void addsTheMissingImports() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        // TWO types, both UNAMBIGUOUS across the JDK. Ambiguity is a separate policy with
        // its own case below, and mixing the two would leave a failure meaning either.
        Path file = write(service, "NeedsImports", """
            package com.example;

            public class NeedsImports {
                public ArrayList<String> make() {
                    ArrayList<String> out = new ArrayList<>();
                    out.add(String.valueOf(new AtomicInteger(1).get()));
                    return out;
                }
            }
            """);

        ToolResponse response = organize(service, file);

        assertTrue(response.isSuccess(),
            "the add path used to die on an NPE here: " + response.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        String after = Files.readString(file);
        assertAll(
            () -> assertEquals(2, data.get("importsAdded"), "got: " + data),
            () -> assertTrue(after.contains("import java.util.ArrayList;"), after),
            () -> assertTrue(
                after.contains("import java.util.concurrent.atomic.AtomicInteger;"), after),
            // THE CONTROL. Without it an "add" that imported the world would pass every
            // clause above — and the preference this fix seeds is precisely the knob that
            // decides what a type search may return.
            () -> assertEquals(2, after.lines().filter(l -> l.startsWith("import ")).count(),
                "exactly the two needed and nothing else: " + after));
    }

    @Test
    @DisplayName("mcp#16: an AMBIGUOUS type is skipped rather than guessed")
    void ambiguityIsNeverGuessed() throws Exception {
        // `List` is java.util.List AND java.awt.List. The tool hands JDT a query that
        // returns no choice, which is what jdt.ls does with no UI to ask — so the import is
        // left out rather than picked. Documented in the tool and, until now, asserted
        // nowhere; the fix above is what first made this path reachable at all.
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path file = write(service, "NeedsAmbiguous", """
            package com.example;

            public class NeedsAmbiguous {
                public List<String> make() {
                    return null;
                }
            }
            """);

        ToolResponse response = organize(service, file);

        assertTrue(response.isSuccess(), "got: " + response.getError());
        String after = Files.readString(file);
        assertAll(
            () -> assertFalse(after.contains("import java.util.List;"),
                "a guess between two candidates is worse than no import: " + after),
            () -> assertFalse(after.contains("import java.awt.List;"), after));
    }
}
