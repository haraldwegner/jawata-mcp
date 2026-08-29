package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 16b/A — parametric front door for the four LTK extract refactorings.
 * Each delegate self-validates its required params in {@code executeWithService},
 * so a flat schema (only {@code kind} + {@code filePath} required) is safe; the
 * per-kind params are documented and validated by the delegate.
 *
 * <p>Replaces {@code extract_method} / {@code extract_variable} /
 * {@code extract_constant} / {@code extract_interface}. Carries the apply/undo
 * contract unchanged (returns filesModified/diff/undoChangeId/summary).</p>
 */
public class ExtractTool extends AbstractTool {

    /**
     * ONE map, and it is the single source of truth for three things that used to be
     * three lists: which kinds exist, which delegate runs each, and which parameters
     * the published schema declares.
     *
     * <p><b>Why it is a map and not six fields plus a switch.</b> Stage 7 added
     * {@code kind=class} to the enum and to the switch, and its five parameters —
     * {@code newTypeName}, {@code fields} (which the delegate marks REQUIRED),
     * {@code fieldName}, {@code createTopLevel}, {@code createGetterSetter} — never
     * reached {@link #getInputSchema()}. The operation was wired for EXECUTION and
     * unwired for CONTRACT: it ran correctly for anyone who already knew the argument
     * names, and was undiscoverable to a client reading {@code tools/list}. Nothing
     * went red, because the schema sets no {@code additionalProperties: false} and no
     * test compared the two lists.</p>
     *
     * <p>The cause is that a hand-written schema beside a dispatch switch is a COPY of
     * the delegates' contracts, and a copy of a changing surface is wrong from the
     * first unmirrored change with no moment at which it announces itself. Adding the
     * five by hand would have fixed this instance and left the seventh kind to repeat
     * it. Deriving from the map means a kind cannot be half-added.</p>
     */
    private final Map<String, AbstractApplyingRefactoringTool> delegates;

    public ExtractTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        Map<String, AbstractApplyingRefactoringTool> d = new LinkedHashMap<>();
        d.put("method", new ExtractMethodTool(serviceSupplier, cache));
        d.put("variable", new ExtractVariableTool(serviceSupplier, cache));
        d.put("constant", new ExtractConstantTool(serviceSupplier, cache));
        d.put("interface", new ExtractInterfaceTool(serviceSupplier, cache));
        d.put("superclass", new ExtractSuperclassTool(serviceSupplier, cache));
        d.put("class", new ExtractClassTool(serviceSupplier, cache));
        this.delegates = java.util.Collections.unmodifiableMap(d);
    }

    /** The kinds, derived from the dispatch map so the two can never disagree. */
    private List<String> kinds() {
        return List.copyOf(delegates.keySet());
    }

    @Override
    public String getName() {
        return "extract";
    }

    @Override
    public String getDescription() {
        return """
            Extract a method, variable, constant, or interface (behaviour-preserving, reversible).

            USAGE: extract(kind="<kind>", filePath=..., ...)

            Kinds and their params (all ZERO-BASED coordinates):
            - method   — extract a statement range into a new method.
                         Needs: startLine, startColumn, endLine, endColumn, methodName.
            - variable — extract an expression range into a local variable.
                         Needs: startLine, startColumn, endLine, endColumn (optional variableName).
            - constant — extract an expression range into a static final constant.
                         Needs: startLine, startColumn, endLine, endColumn, constantName.
            - interface— extract an interface from the type at a caret.
                         Needs: line, column, interfaceName (optional methodNames[] to pull up).
            - superclass— extract a common superclass from the caret class and its same-package
                         siblings. Needs: line, column, superclassName, siblings[] (optional
                         members[], mode). Default mode=jdt (the JDT engine: fields,
                         non-identical members, constructors); mode=identical is the
                         conservative byte-identical + self-contained contract.
            - class    — extract a group of FIELDS into a new class; the original keeps a
                         reference and every access is rewritten through it. Needs: line,
                         column, newTypeName, fields[] (optional fieldName, createTopLevel,
                         createGetterSetter). fields[] has no default on purpose — WHICH
                         state travels together is the design decision this carries out.
                         Move Field ships INSIDE this rather than beside it: it is the
                         constituent atom, and moving a field needs a target that already
                         owns state, so the move and the class creation must be atomic.

            Applies by default; returns filesModified/diff/undoChangeId/summary. Pass
            auto_apply=false to stage without applying.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", kinds());
        kind.put("description", "Which extract refactoring to run. See the tool description for per-kind params.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("startLine", Map.of("type", "integer", "description", "method/variable/constant: zero-based start line of the selection."));
        properties.put("startColumn", Map.of("type", "integer", "description", "method/variable/constant: zero-based start column."));
        properties.put("endLine", Map.of("type", "integer", "description", "method/variable/constant: zero-based end line."));
        properties.put("endColumn", Map.of("type", "integer", "description", "method/variable/constant: zero-based end column."));
        properties.put("line", Map.of("type", "integer", "description", "interface: zero-based line of a caret in the type."));
        properties.put("column", Map.of("type", "integer", "description", "interface: zero-based column."));
        properties.put("methodName", Map.of("type", "string", "description", "method: name for the extracted method."));
        properties.put("variableName", Map.of("type", "string", "description", "variable: optional name for the extracted variable."));
        properties.put("constantName", Map.of("type", "string", "description", "constant: name for the extracted constant."));
        properties.put("interfaceName", Map.of("type", "string", "description", "interface: name for the extracted interface."));
        properties.put("methodNames", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "interface: optional method names to declare in the new interface."));
        properties.put("superclassName", Map.of("type", "string", "description", "superclass: name for the generated abstract parent."));
        properties.put("siblings", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "superclass: sibling class simple names in the same package to reparent + pull from."));
        properties.put("members", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "superclass: optional member names to pull up (default: auto-discover the identical methods; mode=jdt also accepts fields and non-identical members)."));
        properties.put("mode", Map.of("type", "string", "enum", List.of("jdt", "identical"),
            "description", "superclass: jdt (default) = the general JDT engine; identical = the conservative byte-identical + self-contained contract."));

        properties.put("typeName", org.jawata.mcp.tools.shared.FqnTarget.typeNameSchemaProperty(
            "type to extract from (kinds interface/superclass; the range kinds "
                + "method/variable/constant need their coordinates)"));

        // THE BACKSTOP: every parameter any delegate declares reaches the published
        // contract, whether or not someone remembered to curate it above.
        //
        // putIfAbsent, deliberately, and in this order. The curated entries above win
        // and keep their positions, because they carry something a delegate's own
        // schema cannot: which KIND each parameter belongs to. The front door says
        // "method/variable/constant: zero-based start line"; ExtractMethodTool says
        // "Path to source file." Overlaying the delegates on top would publish the
        // poorer description for five kinds in order to fix the sixth.
        //
        // So this loop adds only what is MISSING — which today is exactly the five
        // parameters of kind=class, and tomorrow is whatever the next kind brings.
        // Curating an entry above is now an improvement to the wording, never the
        // difference between a documented parameter and an invisible one.
        for (AbstractApplyingRefactoringTool delegate : delegates.values()) {
            Object declared = delegate.getInputSchema().get("properties");
            if (declared instanceof Map<?, ?> declaredProps) {
                declaredProps.forEach((k, v) -> properties.putIfAbsent(String.valueOf(k), v));
            }
        }
        schema.put("properties", properties);
        // Sprint 24 (D1): filePath OR typeName (the type-targeted kinds).
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Sprint 24 (D1): the TYPE-targeted kinds (interface, superclass) accept
        // typeName=pkg.Type; the range kinds (method/variable/constant) keep
        // their coordinates — a statement range has no name.
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + kinds());
        }
        AbstractApplyingRefactoringTool delegate = delegates.get(kind);
        if (delegate == null) {
            return ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + kinds());
        }
        return delegate.executeWithService(service, arguments);
    }
}
