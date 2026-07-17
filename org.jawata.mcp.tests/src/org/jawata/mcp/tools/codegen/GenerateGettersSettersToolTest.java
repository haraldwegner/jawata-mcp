package org.jawata.mcp.tools.codegen;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IFile;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.codegen.GenerateGettersSettersTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 13 (v1.7.0) — {@code generate_getters_setters} contract tests.
 *
 * <p>Targets the {@code UnusedCode} fixture (has fields with no accessors)
 * for the happy path and {@code RefactoringTarget} (already has
 * {@code getUserName}/{@code setUserName}) for the conflict path.</p>
 */
class GenerateGettersSettersToolTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private GenerateGettersSettersTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new GenerateGettersSettersTool(() -> service, new org.jawata.mcp.refactoring.RefactoringChangeCache());
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("happy: both for two fields generates four methods")
    void happy_bothForTwoFields_generatesFourMethods() throws Exception {
        IFile target = findFile("UnusedCode.java");
        assertNotNull(target, "UnusedCode.java must be present in fixture");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        // Caret anywhere inside the class body is fine — UnusedCode has no
        // package-level decl quirks, line ~7 sits inside the class.
        args.put("line", 7);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("unusedField");
        fields.add("unusedStringField");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "generate_getters_setters must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) data.get("methodsAdded");
        assertEquals(4, added.size(),
            "expected 4 methods (2 getters + 2 setters); got: " + added);
        assertTrue(added.contains("getUnusedField"), "missing getUnusedField in " + added);
        assertTrue(added.contains("setUnusedField"), "missing setUnusedField in " + added);
        assertTrue(added.contains("getUnusedStringField"), "missing getUnusedStringField in " + added);
        assertTrue(added.contains("setUnusedStringField"), "missing setUnusedStringField in " + added);

        String generated = (String) data.get("generatedSource");
        assertNotNull(generated);
        assertTrue(generated.contains("public int getUnusedField()"),
            "generated source must declare getUnusedField; got:\n" + generated);
        assertTrue(generated.contains("public void setUnusedField(int unusedField)"),
            "generated source must declare setUnusedField; got:\n" + generated);

        // v2.14.1 finding #5, tier 4: the fixture project has no formatter
        // config, so the config-less default applies — SPACES, not JDT's
        // built-in tab default. No leading tab anywhere.
        assertFalse(generated.lines().anyMatch(l -> l.startsWith("\t")),
            "generated code must not indent with tabs (config-less default = spaces):\n"
                + generated);
        assertTrue(generated.contains("    public int getUnusedField()"),
            "the generated accessor must be space-indented:\n" + generated);
    }

    @Test
    @DisplayName("indentChar=tab override forces tab indentation (v2.14.1 #5 tier 1)")
    void indentCharOverride_forcesTabs() throws Exception {
        IFile target = findFile("UnusedCode.java");
        assertNotNull(target);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 7);
        args.put("column", 4);
        args.put("indentChar", "tab");
        args.putArray("fields").add("unusedField");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> "must succeed; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String generated = (String) data.get("generatedSource");
        // The explicit override wins over the config-less spaces default: the
        // generated accessor is tab-indented.
        assertTrue(generated.contains("\tpublic int getUnusedField()"),
            "indentChar=tab must produce a tab-indented accessor:\n" + generated);
    }

    @Test
    @DisplayName("conflict: existing accessor is skipped and reported in warnings")
    void conflict_existingAccessor_isSkipped() throws Exception {
        IFile target = findFile("RefactoringTarget.java");
        assertNotNull(target, "RefactoringTarget.java must be present");

        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 16);
        args.put("column", 4);
        ArrayNode fields = args.putArray("fields");
        fields.add("userName"); // RefactoringTarget already has getUserName/setUserName

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(),
            "tool itself succeeds even when both accessors are skipped; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) data.get("methodsAdded");
        @SuppressWarnings("unchecked")
        List<String> warnings = (List<String>) data.get("warnings");

        assertEquals(0, added.size(),
            "no methods should be added; both accessors already exist; got: " + added);
        assertEquals(2, warnings.size(),
            "exactly two warnings expected (one per skipped accessor); got: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("getUserName")),
            "expected getUserName skip warning; got: " + warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.contains("setUserName")),
            "expected setUserName skip warning; got: " + warnings);
    }

    // ========== Sprint 25 (spec D1a item 1): styles + gaps ==========

    @Test
    @DisplayName("record style: getter is the property name itself — unusedField()")
    void recordStyle_getterIsPropertyName() throws Exception {
        ObjectNode args = unusedCodeArgs();
        args.put("kind", "getters");
        args.put("getterStyle", "record");
        args.putArray("fields").add("unusedField");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) data.get("methodsAdded");
        assertTrue(added.contains("unusedField"), "record-style accessor name expected: " + added);
        String generated = (String) data.get("generatedSource");
        assertTrue(generated.contains("public int unusedField()"),
            "record-style accessor must be on disk:\n" + generated);
        assertTrue(!generated.contains("public int getUnusedField()"),
            "no classic getter may be generated in record style:\n" + generated);
    }

    @Test
    @DisplayName("fluent style: setter returns the declaring type and ends with `return this;`")
    void fluentStyle_setterReturnsThis() throws Exception {
        ObjectNode args = unusedCodeArgs();
        args.put("kind", "setters");
        args.put("setterStyle", "fluent");
        args.putArray("fields").add("unusedField");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String generated = (String) data.get("generatedSource");
        assertTrue(generated.contains("public UnusedCode setUnusedField(int unusedField)"),
            "fluent setter must return the declaring type:\n" + generated);
        assertTrue(generated.contains("return this;"),
            "fluent setter must end with `return this;` for chaining:\n" + generated);
    }

    @Test
    @DisplayName("generateJavadoc: accessors carry @return / @param doc")
    void javadoc_isEmittedOnDemand() throws Exception {
        ObjectNode args = unusedCodeArgs();
        args.put("generateJavadoc", true);
        args.putArray("fields").add("unusedField");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        String generated = (String) data.get("generatedSource");
        assertTrue(generated.contains("@return the unusedField"),
            "getter Javadoc expected:\n" + generated);
        assertTrue(generated.contains("@param unusedField the unusedField to set"),
            "setter Javadoc expected:\n" + generated);
    }

    @Test
    @DisplayName("prefix-aware naming: with a field prefix configured, unusedField -> getField (JDT NamingConventions)")
    void prefixAwareNaming_honorsProjectConventions() throws Exception {
        // Configure a field prefix on the PROJECT (not the workspace — keeps the
        // suite isolated): "unused" is treated as a prefix, so field unusedField
        // has base name "field" and the classic getter becomes getField().
        service.getJavaProject().setOption(
            org.eclipse.jdt.core.JavaCore.CODEASSIST_FIELD_PREFIXES, "unused");

        ObjectNode args = unusedCodeArgs();
        args.put("kind", "getters");
        args.putArray("fields").add("unusedField");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), () -> String.valueOf(r.getError()));
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<String> added = (List<String>) data.get("methodsAdded");
        assertTrue(added.contains("getField"),
            "prefix-stripped getter name expected (unusedField -> getField): " + added);
    }

    private ObjectNode unusedCodeArgs() throws Exception {
        IFile target = findFile("UnusedCode.java");
        assertNotNull(target, "UnusedCode.java must be present in fixture");
        ObjectNode args = objectMapper.createObjectNode();
        args.put("filePath", target.getLocation().toFile().toPath().toString());
        args.put("line", 7);
        args.put("column", 4);
        return args;
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
