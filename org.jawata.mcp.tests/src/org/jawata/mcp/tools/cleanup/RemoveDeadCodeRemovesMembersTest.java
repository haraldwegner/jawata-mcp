package org.jawata.mcp.tools.cleanup;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ApplyCleanupTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28e, mcp#15 — <b>what {@code remove_dead_code} actually removes.</b>
 *
 * <p>mcp#15 is filed as a capability gap: <i>"There is no refactoring that removes a member.
 * {@code find_quality_issue(kind=unused)} detects unused private methods and fields; nothing can
 * act on the finding."</i> It records a real cost — during the Sprint 28a host-boundary
 * migration an orphaned {@code ProjectImporter#isWindows} was deleted BY HAND inside a declared
 * authoring window, and the hand-edit left a dead import behind that survived twelve gates.</p>
 *
 * <p><b>The premise is measured here rather than taken.</b> While fixing mcp#76 a mutation
 * produced a diff in which {@code remove_dead_code} DELETED an unused private field — which
 * mcp#15 says nothing can do. That was incidental evidence from another issue's control, so it
 * is worth pinning deliberately and on both member kinds, because whether the issue is a missing
 * CAPABILITY or a missing ROUTE is a different piece of work either way.</p>
 *
 * <p>This class asserts only what it observes. It is a measurement, and it is written so that a
 * later change to what the rule removes turns it red rather than quietly moving the answer.</p>
 */
class RemoveDeadCodeRemovesMembersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private ApplyCleanupTool tool;
    private ObjectMapper mapper;
    private Path file;

    /**
     * One unused private METHOD and one unused private FIELD, with a live method beside them so
     * the class is not trivially empty. The field's initializer is a CONSTANT: mcp#76 measured
     * that the rule declines a field whose initializer is a method call, so a constant is the
     * shape that isolates "can it remove a field" from "will it remove this one".
     */
    private static final String TARGET = """
        package com.example;

        public class DeadMembers {

            private static final String deadField = "x";

            private String deadMethod(String s) {
                return s.trim();
            }

            public int live(int n) {
                return n + 1;
            }
        }
        """;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        file = pkg.resolve("DeadMembers.java");
        Files.writeString(file, TARGET);
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();
        tool = new ApplyCleanupTool(() -> service,
            new org.jawata.mcp.refactoring.RefactoringChangeCache());
        mapper = new ObjectMapper();
    }

    @Test
    @DisplayName("mcp#15: remove_dead_code DOES remove an unused private method and field")
    @SuppressWarnings("unchecked")
    void itRemovesBothMemberKinds() throws Exception {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "remove_dead_code");
        args.put("filePath", "src/main/java/com/example/DeadMembers.java");
        ToolResponse resp = tool.execute(args);
        assertTrue(resp.isSuccess(), "the sweep answers: " + resp.getError());

        Map<String, Object> data = (Map<String, Object>) resp.getData();
        assertTrue(Boolean.TRUE.equals(data.get("hasChanges")),
            "it found something to remove: " + data);

        // The FILE is what settles this. A diff in the response says an edit was computed; only
        // reading the source afterwards says what survived — and the answer to mcp#15 is about
        // what ends up on disk, not about what was proposed.
        String after = Files.readString(file);

        assertAll(
            () -> assertTrue(!after.contains("deadMethod"),
                "the unused private METHOD is gone — mcp#15 says nothing can do this:\n" + after),
            () -> assertTrue(!after.contains("deadField"),
                "and so is the unused private FIELD:\n" + after),
            // The control: it removed the dead members and not the live one. Without this the
            // assertions above are also satisfied by a rule that emptied the class.
            () -> assertTrue(after.contains("public int live(int n)"),
                "while the live method is untouched:\n" + after));
    }
}
