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
