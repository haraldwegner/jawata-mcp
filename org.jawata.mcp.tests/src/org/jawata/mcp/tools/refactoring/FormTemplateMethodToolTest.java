package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.RefactorToPatternTool;
import org.jawata.mcp.tools.UndoRefactoringTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 19 — form_template_method via refactor_to_pattern. Fixture
 * TemplateMethodTargets: HtmlReport.build() at 0-based 15:11, sibling TextReport.
 * Structure + real compile check + undo.
 */
class FormTemplateMethodToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper mapper;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new RefactorToPatternTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        targetFile = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/TemplateMethodTargets.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode args(int line, int column) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "form_template_method");
        n.put("filePath", targetFile.toString());
        n.put("line", line);
        n.put("column", column);
        return n;
    }

    /**
     * A NESTED HIERARCHY, END TO END — the guard this door's repair shipped without.
     *
     * <p>C8b round 4 repaired TWO doors the same way: the superclass lookup was repointed at
     * {@code tools.shared.TypeLookup} so a nested superclass resolves, and the sibling-subclass
     * collection beside it was moved off {@code CompilationUnit#types()} so the siblings
     * declared next to it are visible too. Only the visitor door got an end-to-end case; round
     * 5 reverted this one's repair in full and every test still passed, so the commit's "5/5
     * over both doors" counted tests executed rather than guards — a distinction worth the
     * sentence, because the two read identically in a summary.</p>
     *
     * <p>The fixture is written into the project COPY this class already works on, so nothing
     * is added to the shared sample project — which has moved a counted population four times
     * in this sprint. Both halves of the repair are asserted, and the refusal each must not
     * produce is named, so a regression cannot hide behind a different one.</p>
     */
    @Test
    @DisplayName("a NESTED superclass and its nested sibling are both found, end to end")
    void formTemplateMethod_findsANestedHierarchy() throws Exception {
        String source = """
            package com.example;

            /** Written by the test, into its own project copy. */
            public class NestedTemplateTargets {
                abstract static class Report {
                    abstract String build();
                }

                static class HtmlDoc extends Report {
                    String build() {
                        String head = "<h1>";
                        String body = "html body";
                        return head + body;
                    }
                }

                static class TextDoc extends Report {
                    String build() {
                        String head = "==";
                        String body = "text body";
                        return head + body;
                    }
                }
            }
            """;
        Path nested = helper.getTempDirectory()
            .resolve("simple-maven/src/main/java/com/example/NestedTemplateTargets.java");
        Files.writeString(nested, source);
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());

        // The caret is derived from the text rather than hard-coded, so editing the fixture
        // above cannot silently point this somewhere else.
        String[] lines = source.split("\n", -1);
        int line = -1;
        int column = -1;
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].indexOf("String build()");
            if (at >= 0 && lines[i].contains("    String build()")) {
                line = i;
                column = at + "String ".length();
                break;
            }
        }
        assertTrue(line >= 0, "the fixture must declare a nested subclass's build()");

        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "form_template_method");
        n.put("filePath", nested.toString());
        n.put("line", line);
        n.put("column", column);
        n.put("auto_apply", false);
        ToolResponse r = tool.execute(n);

        String failure = String.valueOf(r.getError());
        assertFalse(failure.contains("requires the superclass to be in the same file"),
            "the door must RESOLVE a nested superclass — that lookup was repointed at the"
                + " shared type lookup and nothing was watching it: " + failure);
        assertFalse(failure.contains("sibling"),
            "the door must see the sibling subclass declared BESIDE the nested one. Refusing"
                + " about siblings means the superclass resolved and the sibling scan still"
                + " read the unit's top-level types — the same half-repair the visitor door"
                + " had: " + failure);
        assertTrue(r.isSuccess(),
            "a nested abstract superclass with two nested subclasses sharing a no-arg method"
                + " is this row's own stated precondition, so it must be accepted: " + failure);
    }

    private long compileErrors() throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(targetFile);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return Arrays.stream(ast.getProblems()).filter(IProblem::isError).count();
    }

    @Test
    @DisplayName("pulls the template into the superclass + abstract step; compiles; undo restores")
    void formTemplate_appliesCompilesAndUndoRestores() throws Exception {
        String original = Files.readString(targetFile);

        ToolResponse response = tool.execute(args(15, 11)); // HtmlReport.build
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("Report", data.get("superclass"));
        assertEquals(1, data.get("abstractSteps"));
        assertNotNull(data.get("undoChangeId"));

        String onDisk = Files.readString(targetFile);
        assertTrue(onDisk.contains("abstract String body();"), "abstract step in Report:\n" + onDisk);
        assertTrue(onDisk.contains("String body = body();"), "template uses the step:\n" + onDisk);
        assertTrue(onDisk.contains("return renderHtml();"), "HtmlReport implements the step:\n" + onDisk);
        assertTrue(onDisk.contains("return renderText();"), "TextReport implements the step:\n" + onDisk);

        assertEquals(0, compileErrors(), "refactored code must compile (0 ERROR problems):\n" + onDisk);

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(targetFile), "undo must restore the original byte-for-byte");
    }

    @Test
    @DisplayName("a method with no sibling is rejected without touching disk")
    void rejectsNoSibling() throws Exception {
        String original = Files.readString(targetFile);
        // renderHtml() has no matching sibling method -> refuse (0-based line 22)
        ToolResponse response = tool.execute(args(22, 11));
        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(targetFile));
    }
}
