package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.jawata.core.IJdtService;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.models.ToolResponse;

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
public class MoveTool extends AbstractTool implements KindedTool {

    @Override
    public String discriminator() {
        return "kind";
    }

    /** The routing map IS the kind list (Stage 6a, M3b). */
    @Override
    public java.util.Map<String, KindDelegate> delegates() {
        java.util.Map<String, KindDelegate> published = new LinkedHashMap<>();
        delegates.forEach((kind, delegate) -> published.put(kind, (KindDelegate) delegate));
        return java.util.Collections.unmodifiableMap(published);
    }


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
        // Sprint 28d-rescue row 23. Fowler names Move Field beside Move Function for the
        // same reason: state in the wrong class is the same defect as behaviour in the
        // wrong class, and `shotgun_surgery` reports both.
        d.put("field", new MoveFieldTool(serviceSupplier, cache));
        // Rows 25 and 26: exact inverses, so they ship together. Each is the other read
        // backwards, and the pair is only safe because both enumerate every call site
        // rather than trusting that the statement "always" runs with the call.
        d.put("statements_into_function",
            new MoveStatementsIntoFunctionTool(serviceSupplier, cache));
        d.put("statements_to_callers",
            new MoveStatementsToCallersTool(serviceSupplier, cache));
        this.delegates = java.util.Collections.unmodifiableMap(d);
    }

    /*
     * kinds() WAS HERE — `List.copyOf(delegates.keySet())`, the same derivation
     * KindedTool#publishedKinds() performs, written a second time inside this door. Its two
     * callers now ask the interface instead (C6a); see ExtractTool for the measurement that
     * found all three private readers.
     */

    @Override
    public String getName() {
        return "move";
    }

    /**
     * ASSEMBLED, not written (Stage 6a, M4).
     *
     * <p><b>ONE DECLARED CHANGE TO THE PUBLISHED TEXT, and it is the reason this is written
     * down rather than absorbed.</b> This door's {@code USAGE:} line was hand-wrapped across
     * two source lines because its six kind names are long. A GENERATED line is not wrapped,
     * so the published text now carries it on one line. Nothing else differs; the wrap was
     * presentation, and a generated line cannot inherit a hand's choice about where to break
     * it without the width becoming a constant somebody has to maintain.</p>
     *
     * <p>The alternative was to keep the line hand-written, which is exactly the copy the
     * seam removes: this door has gained kinds three times and the line has to be edited by
     * hand each time or silently stop naming them.</p>
     */
    @Override
    public String getDescription() {
        return FrontDoorDescription.ASSEMBLER.describe(this);
    }

    @Override
    public String preamble() {
        return "Move code, updating references (behaviour-preserving, undoable). ZERO-BASED coordinates.";
    }

    @Override
    public String usageTail() {
        return ", ...";
    }

    @Override
    public String footer() {
        return "";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", publishedKinds());
        kind.put("description", "What to move.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "Source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the target."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));
        properties.put("targetPackage", Map.of("type", "string", "description", "class: destination package."));
        properties.put("targetProjectKey", Map.of("type", "string", "description", "class: destination project (optional)."));
        properties.put("packageName", Map.of("type", "string", "description", "package: current name."));
        properties.put("newPackageName", Map.of("type", "string", "description", "package: new name."));
        // NAMED PER KIND, because it is not common and calling it common was a lie a C6
        // audit caught. Only class and package read it; the other four rewrite references
        // as the whole point of what they do — a moved field whose readers are not
        // repointed does not compile, so there is no meaningful `false` for them. The old
        // text said "Common: updateReferences (default true)" at the front door and four
        // kinds ignored it silently, which is worse than not offering it.
        properties.put("updateReferences", Map.of("type", "boolean",
            "description", "class/package ONLY: update references (default true)."));

        properties.put("typeName", org.jawata.mcp.tools.shared.FqnTarget.typeNameSchemaProperty(
            "class (kind=class)"));
        // `symbol` was ACCEPTED here and not PUBLISHED. The front door's name-form
        // materializer reads either key, so a member FQN has always worked for kind=method;
        // only typeName was declared, so a client reading tools/list could not know. A C6
        // audit found the same shape on kind=middle_man's accessorName, which is why both
        // are written out now rather than left to the backstop below.
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "method or field (the statement kinds are positional)"));

        // THE BACKSTOP now lives on KindedTool. This door and three others each wrote it
        // out; `inline` did not, which is how row 36's accessorName shipped unpublished.
        schema.put("properties", withDelegateParameters(properties));
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
            return ToolResponse.invalidParameter("kind",
                "kind is required; one of " + publishedKinds());
        }
        AbstractRefactoringTool delegate = delegates.get(kind);
        if (delegate == null) {
            return ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + publishedKinds());
        }
        ToolResponse unresolved = org.jawata.mcp.tools.shared.UnresolvedFile.ofTheFileNamed(service, arguments);
        if (unresolved != null) {
            return unresolved;
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
     *
     * <p>A member or a type changing owner is a hierarchy change; a class or package move
     * rewrites every import that named it. Moving STATEMENTS is deliberately NOT structural:
     * it rewrites call SITES but changes no signature and no hierarchy, which is the
     * criterion. A C6 audit asked why the two were treated alike, and this is the answer
     * written down rather than left to be re-derived.</p>
     *
     * <p><b>Stage 6a (M3a) derived it from the delegates; C8 moved that derivation to
     * {@link KindedTool}, so the override here is gone.</b> The criterion above stays, because
     * it is what a delegate answers against. What is gone is the third copy of one loop —
     * and with it the possibility of a door having no copy at all, which is the state six
     * doors were in while these three were correct.</p>
     */

}
