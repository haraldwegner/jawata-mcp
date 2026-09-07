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
 * Sprint 19 — refactor_to_visitor via refactor_to_pattern. Fixture VisitorTargets:
 * abstract Shape at 0-based 10:15 + Circle/Square/Triangle. Structure across both
 * files + real compile check + undo.
 */
class RefactorToVisitorToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactorToPatternTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper mapper;
    private Path pkgDir;
    private Path hierarchyFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new RefactorToPatternTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        pkgDir = helper.getTempDirectory().resolve("simple-maven/src/main/java/com/example");
        hierarchyFile = pkgDir.resolve("VisitorTargets.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode args(int line, int column) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "refactor_to_visitor");
        n.put("filePath", hierarchyFile.toString());
        n.put("line", line);
        n.put("column", column);
        return n;
    }

    private long compileErrors(Path file) throws Exception {
        ICompilationUnit cu = service.getCompilationUnit(file);
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        return Arrays.stream(ast.getProblems()).filter(IProblem::isError).count();
    }

    /**
     * A NESTED HIERARCHY, END TO END — the guard five repointed doors did not have.
     *
     * <p>C8b round 3 repointed five doors at {@code tools.shared.TypeLookup} so a NESTED type
     * resolves. A round-3 mutation then reverted one of them to its own top-level walk and the
     * suite stayed GREEN, so none of those repairs had anything watching it. Round 4 measured
     * why the first attempt at a guard was not one: a unit test of the shared lookup passes
     * whether or not a door still calls it, and the commit's claim that "a door that stopped
     * using it would stop compiling" is simply false — a door can reintroduce its own walk,
     * compile, and pass everything.</p>
     *
     * <p><b>Only an END-TO-END nested case fails in that situation</b>, which is why this test
     * drives the real door at a real nested hierarchy rather than asserting anything about
     * which method the door calls.</p>
     *
     * <p>It also pins the SECOND half of that repair, which round 3 left undone and round 4
     * found: resolving a nested base is useless while the SUBTYPE collection still reads the
     * unit's top-level types, because the row then refuses with "found 0" — a count that is
     * factually wrong about the file it just read. Both halves have to hold for this to pass,
     * and the refusal text is asserted absent by name so a regression cannot hide behind a
     * different one.</p>
     *
     * <p>The fixture is written into the COPY this test already works on, not into the shared
     * sample project — adding a file to the shared one has moved a counted population four
     * times in this sprint.</p>
     */
    @Test
    @DisplayName("a NESTED base with nested subtypes is resolved and accepted, end to end")
    void refactorToVisitor_findsANestedHierarchy() throws Exception {
        String source = """
            package com.example;

            /** Written by the test, into its own project copy. */
            public class NestedVisitorTargets {
                public abstract static class Node {
                    public abstract int size();
                }

                public static class Leaf extends Node {
                    @Override public int size() { return 1; }
                }

                public static class Branch extends Node {
                    @Override public int size() { return 2; }
                }
            }
            """;
        Path nested = pkgDir.resolve("NestedVisitorTargets.java");
        Files.writeString(nested, source);
        // A file written straight to disk is invisible to JDT until the project is refreshed —
        // the same two lines `WatchEngineIntegrationTest` uses for the same reason.
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());

        // The caret, derived from the text rather than hard-coded, so editing the fixture
        // above cannot silently point this somewhere else.
        String[] lines = source.split("\n", -1);
        int baseLine = -1;
        int baseColumn = -1;
        for (int i = 0; i < lines.length; i++) {
            int at = lines[i].indexOf("class Node");
            if (at >= 0) {
                baseLine = i;
                baseColumn = at + "class ".length();
                break;
            }
        }
        assertTrue(baseLine >= 0, "the fixture must declare the nested base");

        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "refactor_to_visitor");
        n.put("filePath", nested.toString());
        n.put("line", baseLine);
        n.put("column", baseColumn);
        n.put("auto_apply", false);
        ToolResponse r = tool.execute(n);

        String failure = String.valueOf(r.getError());
        assertFalse(failure.contains("Cannot locate"),
            "the door must RESOLVE a nested base — this is the lookup five doors were"
                + " repointed for, and a unit test of the shared lookup cannot see a door"
                + " that stopped calling it: " + failure);
        assertFalse(failure.contains("found 0"),
            "the door must see the subtypes declared BESIDE the nested base. Refusing with"
                + " 'found 0' means the base resolved and the subtype scan still read the"
                + " unit's top-level types — half a repair, refusing with a count that is"
                + " wrong about the file: " + failure);
        assertTrue(r.isSuccess(),
            "a nested abstract base with two nested subtypes is this row's own stated"
                + " precondition, so it must be accepted: " + failure);
    }

    @Test
    @DisplayName("generates the Visitor interface + accept double-dispatch; compiles; undo restores")
    void refactorToVisitor_appliesCompilesAndUndoRestores() throws Exception {
        String original = Files.readString(hierarchyFile);
        Path visitorFile = pkgDir.resolve("ShapeVisitor.java");
        assertFalse(Files.exists(visitorFile), "precondition: visitor file absent");

        ToolResponse response = tool.execute(args(10, 15)); // abstract class Shape
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("ShapeVisitor", data.get("visitorInterface"));
        assertNotNull(data.get("undoChangeId"));

        assertTrue(Files.exists(visitorFile), "the visitor interface file should be created");
        String iface = Files.readString(visitorFile);
        assertTrue(iface.contains("interface ShapeVisitor<R>"), "generic visitor:\n" + iface);
        assertTrue(iface.contains("R visitCircle(Circle node);"), "visitCircle:\n" + iface);
        assertTrue(iface.contains("R visitTriangle(Triangle node);"), "visitTriangle:\n" + iface);

        String onDisk = Files.readString(hierarchyFile);
        assertTrue(onDisk.contains("public abstract <R> R accept(ShapeVisitor<R> visitor);"),
            "abstract accept on Shape:\n" + onDisk);
        assertTrue(onDisk.contains("return visitor.visitCircle(this);"), "Circle accept:\n" + onDisk);

        // The hierarchy compiling (0 errors) with `visitor.visitCircle(this)` + `ShapeVisitor<R> visitor`
        // transitively proves the generated interface resolves and is usable. (Re-parsing the just-created
        // ShapeVisitor.java in isolation can't resolve its project siblings for bindings — a harness artifact,
        // not a defect; a real build compiles it.)
        assertEquals(0, compileErrors(hierarchyFile), "hierarchy must compile:\n" + onDisk);

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertEquals(original, Files.readString(hierarchyFile), "undo must restore the hierarchy file");
        assertFalse(Files.exists(visitorFile), "undo must delete the generated visitor file");
    }

    @Test
    @DisplayName("a non-abstract / no-subtype base is rejected without touching disk")
    void rejectsNonAbstractBase() throws Exception {
        String original = Files.readString(hierarchyFile);
        ToolResponse response = tool.execute(args(12, 6)); // class Circle (concrete, no subtypes)
        assertFalse(response.isSuccess());
        assertEquals(original, Files.readString(hierarchyFile));
    }
}
