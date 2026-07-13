package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AnalyzeTool;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.jawata.mcp.tools.EncapsulateFieldTool;
import org.jawata.mcp.tools.ExtractTool;
import org.jawata.mcp.tools.GetCallHierarchyTool;
import org.jawata.mcp.tools.MoveTool;
import org.jawata.mcp.tools.RenameSymbolTool;
import org.jawata.mcp.tools.codegen.GenerateTool;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 24 (D1) — navigate by NAME, everywhere. "Knowing is better than
 * searching": an agent that knows a symbol addresses it directly, the way a
 * human hits Open Type instead of grepping. The FQN is the stable memory key;
 * a file position is not.
 *
 * <p>Every tool in the recorded entry-point audit (see the Sprint-24 plan)
 * answers a fixture query by symbol name ALONE — no filePath/line/column. The
 * range-targeted refactorings are deliberately absent: a statement range has
 * no name.</p>
 */
class FqnEverywhereTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private RefactoringChangeCache cache;
    private ObjectMapper om;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        cache = new RefactoringChangeCache();
        om = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> data(ToolResponse r) {
        return (Map<String, Object>) r.getData();
    }

    /** Name-form args: never a filePath, line or column. */
    private ObjectNode byName(String field, String fqn) {
        ObjectNode args = om.createObjectNode();
        args.put(field, fqn);
        assertFalse(args.has("filePath"), "the name form supplies NO position");
        return args;
    }

    // ------------------------------------------------ the helper's own contract

    @Test
    @DisplayName("helper: a name materializes the element's exact position; explicit position wins")
    void helperContract() {
        // A type, a method and a field each resolve to their own name range.
        ObjectNode type = byName("typeName", "com.example.Calculator");
        assertTrue(FqnTarget.materializePosition(service, type).isEmpty(), "type resolves");
        assertTrue(type.get("filePath").asText().endsWith("Calculator.java"), "got: " + type);
        int typeLine = type.get("line").asInt();

        ObjectNode method = byName("symbol", "com.example.Calculator#add");
        assertTrue(FqnTarget.materializePosition(service, method).isEmpty(), "method resolves");
        int methodLine = method.get("line").asInt();
        assertTrue(methodLine > typeLine,
            "the method's name range sits below the type's: " + methodLine + " vs " + typeLine);

        ObjectNode field = byName("symbol", "com.example.Calculator#lastResult");
        assertTrue(FqnTarget.materializePosition(service, field).isEmpty(), "field resolves");
        assertTrue(field.get("line").asInt() > typeLine, "got: " + field);

        // An explicit position WINS — the caller asked for that exact spot.
        ObjectNode explicit = om.createObjectNode();
        explicit.put("symbol", "com.example.Calculator#add");
        explicit.put("filePath", "/somewhere/Else.java");
        explicit.put("line", 99);
        explicit.put("column", 7);
        assertTrue(FqnTarget.materializePosition(service, explicit).isEmpty());
        assertEquals("/somewhere/Else.java", explicit.get("filePath").asText(),
            "an explicit position is never overwritten");
        assertEquals(99, explicit.get("line").asInt());

        // An unknown name is an honest miss, not a crash.
        Optional<ToolResponse> miss =
            FqnTarget.materializePosition(service, byName("symbol", "com.example.NoSuchType"));
        assertTrue(miss.isPresent(), "an unresolvable name must fail honestly");
        assertFalse(miss.get().isSuccess());

        // A BINARY type has no source position — say so, never invent one.
        Optional<ToolResponse> binary =
            FqnTarget.materializePosition(service, byName("typeName", "java.util.ArrayList"));
        assertTrue(binary.isPresent(), "a binary element cannot be a position target");
        assertFalse(binary.get().isSuccess());
    }

    // ------------------------------------------------ reading tools

    @Test
    @DisplayName("get_call_hierarchy(outgoing) answers by symbol name alone")
    void outgoingCallHierarchy() {
        GetCallHierarchyTool tool = new GetCallHierarchyTool(() -> service);
        ObjectNode args = byName("symbol", "com.example.DocLinked#realCaller");
        args.put("direction", "outgoing");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertEquals("realCaller", data(r).get("method"), "resolved the right method: " + data(r));
        assertTrue(String.valueOf(data(r).get("callees")).contains("callee"),
            "realCaller calls callee(): " + data(r));
    }

    @Test
    @DisplayName("analyze: every position-based kind answers by symbol name alone")
    void analyzeKinds() {
        AnalyzeTool tool = new AnalyzeTool(() -> service);
        for (String kind : new String[] {"method", "control_flow", "data_flow",
                                         "change_impact", "symbol"}) {
            ObjectNode args = byName("symbol", "com.example.Calculator#add");
            args.put("kind", kind);
            ToolResponse r = tool.execute(args);
            assertTrue(r.isSuccess(), "analyze kind=" + kind + " by name: " + r.getError());
            assertNotNull(r.getData(), "kind=" + kind);
        }
    }

    @Test
    @DisplayName("analyze(kind=type) answers for a BINARY type — the library types an agent knows")
    void analyzeTypeAnswersForBinaries() {
        // Sprint 24 (D1) found this crashing in the RELEASED v2.10.0: a null
        // compilation unit (every library type has one) NPE'd through
        // getLineNumber, so analyze(kind=type) was unusable for java.util.* and
        // every dependency class — exactly the types a memory-first agent
        // addresses by name. Members and hierarchy exist for a binary; only the
        // source lines do not.
        AnalyzeTool tool = new AnalyzeTool(() -> service);
        ObjectNode args = om.createObjectNode();
        args.put("kind", "type");
        args.put("typeName", "java.util.ArrayList");

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "a binary type must ANSWER, not crash: " + r.getError());
        assertTrue(String.valueOf(data(r).get("members")).contains("add"),
            "the binary type's members are reported: " + data(r).get("members"));
    }

    // ------------------------------------------------ whole-symbol refactorings

    @Test
    @DisplayName("rename_symbol renames by symbol name alone")
    void renameByName() {
        RenameSymbolTool tool = new RenameSymbolTool(() -> service, cache);
        ObjectNode args = byName("symbol", "com.example.Calculator#multiply");
        args.put("newName", "times");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(String.valueOf(data(r).get("diff")).contains("times"),
            "the staged diff renames multiply -> times: " + data(r));
    }

    @Test
    @DisplayName("change_method_signature targets the method by name alone")
    void changeSignatureByName() {
        ChangeMethodSignatureTool tool = new ChangeMethodSignatureTool(() -> service, cache);
        ObjectNode args = byName("symbol", "com.example.Calculator#subtract");
        args.put("newName", "minus");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(String.valueOf(data(r).get("diff")).contains("minus"), "got: " + data(r));
    }

    @Test
    @DisplayName("encapsulate_field targets the field by name alone")
    void encapsulateByName() {
        EncapsulateFieldTool tool = new EncapsulateFieldTool(() -> service, cache);
        ObjectNode args = byName("symbol", "com.example.Calculator#lastResult");
        // Calculator already declares getLastResult() — ask for names that don't clash.
        args.put("getterName", "fetchLast");
        args.put("setterName", "storeLast");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(String.valueOf(data(r).get("diff")).contains("fetchLast"),
            "accessors generated for the named field: " + data(r));
    }

    @Test
    @DisplayName("extract(kind=interface) targets the type by name alone")
    void extractInterfaceByName() {
        ExtractTool tool = new ExtractTool(() -> service, cache);
        ObjectNode args = byName("typeName", "com.example.Calculator");
        args.put("kind", "interface");
        args.put("interfaceName", "ICalculator");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(String.valueOf(data(r).get("diff")).contains("ICalculator"), "got: " + data(r));
    }

    @Test
    @DisplayName("generate targets the type by name alone")
    void generateByName() {
        GenerateTool tool = new GenerateTool(() -> service, cache);
        ObjectNode args = byName("typeName", "com.example.Calculator");
        args.put("kind", "tostring");
        args.putArray("fields").add("lastResult");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(String.valueOf(data(r).get("diff")).contains("toString"), "got: " + data(r));
    }

    @Test
    @DisplayName("move(kind=class) targets the class by name alone")
    void moveClassByName() {
        MoveTool tool = new MoveTool(() -> service, cache);
        ObjectNode args = byName("typeName", "com.example.Calculator");
        args.put("kind", "class");
        args.put("targetPackage", "com.example.math");
        args.put("auto_apply", false);

        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        assertTrue(String.valueOf(data(r).get("diff")).contains("com.example.math"),
            "got: " + data(r));
    }
}
