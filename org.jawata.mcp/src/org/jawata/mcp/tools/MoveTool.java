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
 * Sprint 16b/A — parametric front door for the move family.
 *
 * <p>Sprint 28d-rescue (stage 1) folded {@code move_method} in as {@code kind=method}
 * and, in doing so, replaced the fields-plus-switch shape this tool used to have. That
 * shape is the one {@link ExtractTool} carries a long note about: it keeps the kind
 * list, the dispatch and the published parameters in three places, and stage 7 of the
 * previous sprint proved what that costs — {@code extract kind=class} reached the enum
 * and the switch while its five parameters never reached the schema, so the operation
 * ran for anyone who already knew the argument names and was invisible to everyone
 * else. Nothing went red, because nothing compared the three lists.</p>
 *
 * <p>This tool is about to take three more kinds (rows 23, 25, 26), so it moves to the
 * map now rather than repeating that arithmetic four times. The kind enum is derived
 * from the dispatch map, and every delegate's own parameters reach the published schema
 * through the backstop below.</p>
 *
 * <p>Replaces {@code move_class}, {@code move_package} and {@code move_method}; the
 * apply/undo contract is unchanged.</p>
 */
public class MoveTool extends AbstractTool {

    /** The single source of truth for which kinds exist and what runs each. */
    private final Map<String, AbstractRefactoringTool> delegates;

    public MoveTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        Map<String, AbstractRefactoringTool> d = new LinkedHashMap<>();
        d.put("class", new MoveClassTool(serviceSupplier, cache));
        d.put("package", new MovePackageTool(serviceSupplier, cache));
        // Sprint 28d-rescue: the folded `move_method`. Fowler names ONE refactoring here
        // — Move Function — and a caller should not have to know whether the method is
        // static before choosing a tool, so the static half (row 24) lands inside this
        // same kind rather than beside it.
        d.put("method", new MoveMethodTool(serviceSupplier, cache));
        this.delegates = java.util.Collections.unmodifiableMap(d);
    }

    /** The kinds, derived from the dispatch map so the two can never disagree. */
    private List<String> kinds() {
        return List.copyOf(delegates.keySet());
    }

    @Override
    public String getName() {
        return "move";
    }

    @Override
    public String getDescription() {
        return """
            Move a class, a package, or a method, updating references
            (behaviour-preserving, reversible).

            USAGE: move(kind="<class|package|method>", ...)

            - class   — move the type at a caret to another package.
                        Needs: filePath, line, column, targetPackage (optional targetProjectKey).
            - package — move/rename a whole package.
                        Needs: packageName, newPackageName.
            - method  — move an instance method onto the type of one of its parameters
                        or fields, rewriting every call site to invoke it on the new
                        receiver. Needs: the method's position (filePath, line, column)
                        or its symbol, plus `target` — the parameter/field whose type
                        receives it. `target` may be omitted when exactly one candidate
                        exists; with several, the call is refused and lists them.
                        Optional keepDelegate leaves a forwarder behind.

            Common: updateReferences (default true). IMPORTANT: ZERO-BASED coordinates.
            Applies by default; returns filesModified/diff/undoChangeId/summary.

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
        kind.put("description",
            "Move a class (by caret), a package (by name), or a method onto another type.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "class/method: path to the source file."));
        properties.put("line", Map.of("type", "integer", "description", "class/method: zero-based line of a caret in the type or method."));
        properties.put("column", Map.of("type", "integer", "description", "class/method: zero-based column."));
        properties.put("targetPackage", Map.of("type", "string", "description", "class: destination package name."));
        properties.put("targetProjectKey", Map.of("type", "string", "description", "class: optional destination project (cross-project move)."));
        properties.put("packageName", Map.of("type", "string", "description", "package: the package to move/rename."));
        properties.put("newPackageName", Map.of("type", "string", "description", "package: the new package name."));
        properties.put("updateReferences", Map.of("type", "boolean", "description", "Update all references (default true)."));

        properties.put("typeName", org.jawata.mcp.tools.shared.FqnTarget.typeNameSchemaProperty(
            "class to move (kind=class; kind=package uses packageName)"));

        // THE BACKSTOP — the same one ExtractTool carries, and for the same reason: a
        // parameter a delegate declares must reach the published contract whether or not
        // anyone remembered to curate it above. putIfAbsent, so the curated entries keep
        // their per-kind wording and only what is MISSING is added — today that is
        // kind=method's `target`, `keepDelegate` and `symbol`.
        for (AbstractRefactoringTool delegate : delegates.values()) {
            Object declared = delegate.getInputSchema().get("properties");
            if (declared instanceof Map<?, ?> declaredProps) {
                declaredProps.forEach((k, v) -> {
                    String name = String.valueOf(k);
                    if (!"projectKey".equals(name) && !"auto_apply".equals(name)) {
                        properties.putIfAbsent(name, v);
                    }
                });
            }
        }
        schema.put("properties", properties);
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Sprint 24 (D1): kind=class accepts typeName=pkg.Type (kind=package is
        // already name-based via packageName).
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return ToolResponse.invalidParameter("kind", "kind is required; one of " + kinds());
        }
        AbstractRefactoringTool delegate = delegates.get(kind);
        if (delegate == null) {
            return ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + kinds());
        }
        return delegate.executeWithService(service, arguments);
    }
    /**
     * Moving a METHOD onto another type rewrites every call site, which is structural.
     * Moving a class or a package relocates a type without changing any signature, and
     * was not treated as structural before the fold; that stays true.
     *
     * <p>This is the declaration the architect gate lost when `move_method` folded in:
     * its list held the retired tool name, so a method move stopped being reviewed.</p>
     */
    @Override
    public java.util.Set<String> structuralKinds() {
        return java.util.Set.of("method");
    }

}
