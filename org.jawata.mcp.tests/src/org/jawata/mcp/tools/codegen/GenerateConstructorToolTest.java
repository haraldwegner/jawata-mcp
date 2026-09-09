package org.jawata.mcp.tools.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ErrorInfo;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.codegen.GenerateConstructorTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 13 (v1.7.0) — {@code generate_constructor} contract tests.
 *
 * <p>Loads the {@code simple-maven} fixture, points the tool at
 * {@code RefactoringTarget} (which has {@code userName} and {@code count}
 * fields), and asserts the generated constructor body initializes both.</p>
 *
 * <p>Validation path: requesting a field not declared on the class returns
 * {@code INVALID_PARAMETER}.</p>
 */
class GenerateConstructorToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateConstructorTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new GenerateConstructorTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: constructor for two fields generates initializers")
    void happy_constructorForFields_generatesInitializers() throws Exception {
        IFile target = findFile("RefactoringTarget.java");
        assertNotNull(target, "RefactoringTarget.java must be present in fixture");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        // RefactoringTarget class declaration sits around line 13; pick a caret
        // inside the class body by aiming at line 16 (the userName field decl).
        args.put("line", 16);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("userName");
        fields.add("count");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "generate_constructor must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("generate_constructor", data.get("operation"));
        assertEquals("RefactoringTarget", data.get("methodName"));

        String generated = (String) data.get("generatedSource");
        assertNotNull(generated, "generatedSource must be populated");
        // The constructor must take both params and assign each via this.field = field.
        assertTrue(generated.contains("public RefactoringTarget(String userName, int count)"),
            "generated source must declare new ctor signature; got:\n" + generated);
        assertTrue(generated.contains("this.userName = userName"),
            "generated source must assign userName; got:\n" + generated);
        assertTrue(generated.contains("this.count = count"),
            "generated source must assign count; got:\n" + generated);
    }

    @Test
    @DisplayName("mcp#79: a generic field keeps its TYPE ARGUMENTS, nesting included")
    void genericFieldKeepsItsTypeArguments() throws Exception {
        // SearchPatterns already declares List<String>, Map<String, Integer> and
        // List<Calculator> — chosen over adding a field to RefactoringTarget because
        // simple-maven is shared and growing, and adding to it has moved a counted
        // population four times in this sprint.
        IFile target = findFile("SearchPatterns.java");
        assertNotNull(target, "SearchPatterns.java must be present in fixture");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 24);      // the `private List<String> stringList;` declaration
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("stringList");
        fields.add("stringIntMap");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "generate_constructor must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String generated = (String) data.get("generatedSource");
        assertNotNull(generated, "generatedSource must be populated");

        // THE WHOLE POINT. Before mcp#79 this read `SearchPatterns(List stringList,
        // Map stringIntMap)` — raw, legal, compiling, and silently wider than the fields
        // it was built from. `applied: true` and `fieldsInitialized` reported the NAMES,
        // which are unaffected by the loss, so nothing in the response could show it.
        assertTrue(generated.contains(
                "public SearchPatterns(List<String> stringList, Map<String, Integer> stringIntMap)"),
            "the generated constructor must carry the field's type arguments, NESTED ones"
                + " included; got:\n" + generated);

        // And the negative, because a `contains` on the correct text would also pass over
        // a signature that carried BOTH the raw and the parameterised forms somewhere.
        assertFalse(generated.contains("SearchPatterns(List stringList"),
            "no raw List may appear in the generated signature:\n" + generated);
    }

    @Test
    @DisplayName("mcp#79: a BLANK field entry is refused, not silently dropped")
    void blankFieldEntryIsRefused() throws Exception {
        IFile target = findFile("RefactoringTarget.java");
        assertNotNull(target, "RefactoringTarget.java must be present in fixture");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 16);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("userName");
        fields.add("   ");          // a mistyped entry
        fields.add("count");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(),
            "asking for three fields and getting two under applied:true is the defect");
        ErrorInfo error = r.getError();
        assertEquals("INVALID_PARAMETER", error.getCode());
        assertTrue(String.valueOf(error.getMessage()).contains("fields[1]"),
            "the refusal must name WHICH entry was empty: " + error.getMessage());
    }

    @Test
    @DisplayName("validation: unknown field returns INVALID_PARAMETER")
    void validation_unknownField_returnsInvalidParameter() throws Exception {
        IFile target = findFile("RefactoringTarget.java");
        assertNotNull(target, "RefactoringTarget.java must be present in fixture");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 16);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("doesNotExist");

        ToolResponse r = tool.execute(args);
        assertFalse(r.isSuccess(), "unknown field name must be rejected");
        ErrorInfo err = r.getError();
        assertNotNull(err);
        assertEquals(ErrorInfo.INVALID_PARAMETER, err.getCode(),
            "expected INVALID_PARAMETER; got: " + err);
    }

    private IFile findFile(String simpleName) throws Exception {
        AtomicReference<IFile> found = new AtomicReference<>();
        service.getJavaProject().getProject().accept(resource -> {
            if (resource instanceof IFile f && simpleName.equals(f.getName())) {
                found.compareAndSet(null, f);
            }
            return true;
        });
        return found.get();
    }
}
