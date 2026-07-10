package org.jawata.mcp.tools.codegen;

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
 * Sprint 18 — {@code generate(kind=copy_class)}: clone a class into a new
 * same-package file, renaming the type, its constructor, and every self-reference;
 * the copy compiles clean; undo deletes it. Fixture com.example.PizzaSalami (single
 * top-level type with self-references) at 0-based 3:13.
 */
class CopyClassToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateTool tool;
    private UndoRefactoringTool undoTool;
    private ObjectMapper mapper;
    private Path pkgDir;
    private Path salamiFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        RefactoringChangeCache cache = new RefactoringChangeCache();
        tool = new GenerateTool(() -> service, cache);
        undoTool = new UndoRefactoringTool(() -> service, cache);
        mapper = new ObjectMapper();
        pkgDir = helper.getTempDirectory().resolve("simple-maven/src/main/java/com/example");
        salamiFile = pkgDir.resolve("PizzaSalami.java");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> getData(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    private ObjectNode args(String newName) {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "copy_class");
        n.put("filePath", salamiFile.toString());
        n.put("line", 3);
        n.put("column", 13);
        n.put("newTypeName", newName);
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
    @DisplayName("clones the class with type/ctor/self-refs renamed; the copy compiles; undo deletes it")
    void copiesClass_compiles_andUndoDeletes() throws Exception {
        String original = Files.readString(salamiFile);
        Path fungiFile = pkgDir.resolve("PizzaFungi.java");
        assertFalse(Files.exists(fungiFile), "precondition: target absent");

        ToolResponse response = tool.execute(args("PizzaFungi"));
        assertTrue(response.isSuccess(), () -> String.valueOf(response.getError()));
        Map<String, Object> data = getData(response);
        assertEquals(Boolean.TRUE, data.get("applied"));
        assertEquals("PizzaFungi", data.get("newType"));
        assertNotNull(data.get("undoChangeId"));

        assertTrue(Files.exists(fungiFile), "the copy should be created");
        String copy = Files.readString(fungiFile);
        assertTrue(copy.contains("public class PizzaFungi {"), "renamed type:\n" + copy);
        assertTrue(copy.contains("public PizzaFungi(int slices)"), "renamed constructor:\n" + copy);
        assertTrue(copy.contains("public PizzaFungi withExtraSlice()"), "renamed self-referencing return type:\n" + copy);
        assertTrue(copy.contains("return new PizzaFungi(slices + 1);"), "renamed self-instantiation:\n" + copy);
        assertFalse(copy.contains("PizzaSalami"), "no stray reference to the source type:\n" + copy);
        assertTrue(copy.contains("package com.example;"), "same package:\n" + copy);

        // A self-contained file: a surviving `PizzaSalami` self-ref would be an unresolved type here.
        assertEquals(0, compileErrors(fungiFile), "the copy must compile:\n" + copy);

        // The source class must be untouched.
        assertEquals(original, Files.readString(salamiFile), "source class must be unchanged");

        ToolResponse undone = undoTool.execute(
            mapper.createObjectNode().put("undoChangeId", (String) data.get("undoChangeId")));
        assertTrue(undone.isSuccess(), () -> String.valueOf(undone.getError()));
        assertFalse(Files.exists(fungiFile), "undo must delete the created copy");
    }

    @Test
    @DisplayName("missing newTypeName is rejected")
    void rejectsMissingNewTypeName() {
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "copy_class");
        n.put("filePath", salamiFile.toString());
        n.put("line", 3);
        n.put("column", 13);
        assertFalse(tool.execute(n).isSuccess());
    }

    @Test
    @DisplayName("a file with more than one top-level type is rejected without touching disk")
    void rejectsMultiTypeFile() throws Exception {
        Path multi = pkgDir.resolve("TypeCodeTargets.java"); // Order + NoCodes
        String before = Files.readString(multi);
        ObjectNode n = mapper.createObjectNode();
        n.put("kind", "copy_class");
        n.put("filePath", multi.toString());
        n.put("line", 11);
        n.put("column", 6);
        n.put("newTypeName", "OrderCopy");
        ToolResponse r = tool.execute(n);
        assertFalse(r.isSuccess());
        assertEquals(before, Files.readString(multi), "rejection must not touch disk");
        assertFalse(Files.exists(pkgDir.resolve("OrderCopy.java")), "no file created on rejection");
    }
}
