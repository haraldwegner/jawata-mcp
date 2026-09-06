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
 * Parametric front door for the FIVE inline refactorings: {@code method},
 * {@code variable}, {@code class} (row 17), {@code subclass} (row 38) and
 * {@code middle_man} (row 36).
 *
 * <p>It began as a uniform collapse over two delegates that took the identical
 * {@code {filePath, line, column}} input. That is no longer true and saying so was
 * misleading: {@code middle_man} takes {@code delegateField} and {@code accessorName},
 * and those parameters exist precisely BECAUSE the inputs are not identical — a class
 * forwarding to several fields needs the caller to name which one. The kind still
 * selects the delegate; the input shape is per-kind and documented per-kind below.</p>
 *
 * <p>Replaces {@code inline_method} / {@code inline_variable}; apply/undo
 * contract unchanged.</p>
 */
public class InlineTool extends AbstractTool implements KindedTool {

    @Override
    public String discriminator() {
        return "kind";
    }

    /**
     * Built from the typed fields, keyed by what each delegate calls itself (Stage 6a, M3b).
     *
     * <p>This door holds FIELDS rather than a map, so unlike {@code extract} and {@code move}
     * there is no existing key to read — which is where {@link KindDelegate#kindName()} earns
     * its place. On a map-holding door it looks like a second spelling of the key; here it is
     * the only spelling there is, and it lets one derivation serve both shapes.</p>
     */
    @Override
    public java.util.Map<String, KindDelegate> delegates() {
        java.util.Map<String, KindDelegate> published = new java.util.LinkedHashMap<>();
        for (KindDelegate delegate
                : List.of(method, variable, clazz, subclass, middleMan)) {
            published.put(delegate.kindName(), delegate);
        }
        return java.util.Collections.unmodifiableMap(published);
    }


    private final InlineMethodTool method;
    private final InlineVariableTool variable;
    private final InlineClassTool clazz;
    private final RemoveSubclassTool subclass;
    private final RemoveMiddleManTool middleMan;

    public InlineTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.method = new InlineMethodTool(serviceSupplier, cache);
        this.variable = new InlineVariableTool(serviceSupplier, cache);
        // Sprint 28d-rescue row 17. Fowler files Inline Class beside Inline Function:
        // both fold something back into its only user when it stopped earning its own
        // name.
        this.clazz = new InlineClassTool(serviceSupplier, cache);
        // Row 38. Remove Subclass is the same fold one level down: a class that is
        // not earning its name, except that its name is a place in a hierarchy.
        this.subclass = new RemoveSubclassTool(serviceSupplier, cache);
        // Row 36. The third fold on this door, and the one that folds a class's
        // METHODS away rather than the class: a middle man keeps its name and loses
        // the forwarding that was all it did.
        this.middleMan = new RemoveMiddleManTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "inline";
    }

    /**
     * Three of the five kinds DELETE A TYPE or change what a hierarchy contains.
     *
     * <p>{@code class} folds a class away, {@code subclass} removes one from a hierarchy
     * and reparents every reference, {@code middle_man} changes what a class exposes to
     * every caller it had. {@code method} and {@code variable} rewrite inside one body.</p>
     *
     * <p>THIS DOOR HAD NO DECLARATION AT ALL until a C6 audit looked, so the
     * architect-involvement gate had been silent on every one of them — including
     * {@code subclass}, whose entire job is to remove a class from a hierarchy. An absent
     * override is indistinguishable from a considered "nothing here is structural", which
     * is why it went unnoticed through the stage that added all three.</p>
     *
     * <p><b>Stage 6a (M3a): DERIVED.</b> Each delegate declares it, and this asks them. On a
     * door whose failure was an ABSENT declaration, that is the whole point: a delegate that
     * exists but says nothing is now the same as a kind that is not structural, and the only
     * way to be structural is to say so on the class that does the work.</p>
     *
     * <p><b>C8: the derivation moved to {@link KindedTool} and this override is gone.</b> The
     * paragraph above says an absent override is indistinguishable from a considered "nothing
     * here is structural" — which was still true of six OTHER doors while this one was fixed.
     * Deriving it on the interface makes the absent case impossible rather than noticed.</p>
     */

    /**
     * ASSEMBLED, not written (Stage 6a, M4).
     *
     * <p>The door supplies its four regions and the shared assembler joins them, generating
     * the {@code USAGE:} line from the discriminator and the routing table. What that closes
     * is the line's two failure modes: a kind that ships without reaching it, and a door
     * naming {@code kind} when it dispatches on something else.</p>
     *
     * <p><b>M5: the per-kind block is now PROJECTED from the delegates</b>, each of which
     * carries its own bullet. This door no longer writes one, and the byte-golden that
     * guarded M4's move is gone with it — it could not survive a step that deliberately
     * changes the layout, and keeping it would have meant asserting the old text while
     * publishing the new.</p>
     */
    @Override
    public String getDescription() {
        return FrontDoorDescription.ASSEMBLER.describe(this);
    }

    @Override
    public String preamble() {
        return """
            Inline a method, a local variable, a whole class, a subclass into its
            parent, or a middle man's forwarding (behaviour-preserving, reversible).""";
    }

    @Override
    public String usageTail() {
        return ", filePath=..., line=..., column=...";
    }

    /*
     * WHAT STOOD HERE (Stage 6a, M5): five bullets this door wrote about classes that each
     * already knew their own story, plus a LEGACY_KIND_BLOCK holding that text as the golden
     * for the equivalence test. The bullets now live on the delegates and the assembler
     * iterates the routing table to build the list, so a kind cannot be added to the dispatch
     * and left out of the description — the defect this stage was opened for.
     *
     * The golden went when the equivalence it guarded became structural rather than textual:
     * it had ZERO references, measured, and thirty-two lines of published prose that nothing
     * read is a second copy waiting to disagree with the first.
     */

    @Override
    public String footer() {
        return """
            IMPORTANT: ZERO-BASED coordinates. Applies by default; returns
            filesModified/diff/undoChangeId/summary. Pass auto_apply=false to stage only.

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
        kind.put("enum", publishedKinds());
        kind.put("description", "Inline a method, a local variable, a whole class, a"
            + " subclass into its parent, or a middle man's forwarding.");
        properties.put("kind", kind);
        properties.put("filePath", Map.of("type", "string", "description", "Path to source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the symbol to inline."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));

        // The name form reaches every kind whose target HAS a name, and after Stage 6 that
        // is four of the five. The old text said "kind=method only", which was true when
        // this door had two kinds and became false the moment it took three type-targeted
        // ones — a published sentence telling clients a form does not apply where it does.
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "target to inline: pkg.Type#method for kind=method, pkg.Type for kind=class, "
                + "subclass and middle_man. kind=variable is POSITIONAL only — a local has "
                + "no name to address it by"));
        properties.put("accessorName", Map.of("type", "string",
            "description", "kind=middle_man: name for the accessor that exposes the delegate "
                + "(default: the field's own name). Every rewritten call site reads it."));
        properties.put("delegateField", Map.of("type", "string",
            "description", "kind=middle_man: which field to stop forwarding to, when the "
                + "class forwards to more than one. Required only then, and the refusal "
                + "lists the candidates."));
        // THE BACKSTOP, which this door did NOT have. Four others each wrote the loop out
        // and this one did not, and the cost is on the record: row 36's accessorName above
        // reached the delegate's schema and never this one, so it ran for anyone who knew
        // the argument name and was invisible to everyone reading tools/list. It was
        // curated by hand afterwards; the backstop is what makes the next one not need to
        // be. See KindedTool#withDelegateParameters.
        schema.put("properties", withDelegateParameters(properties));
        // Sprint 24 (D1): position OR name form.
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Sprint 24 (D1): kind=method accepts symbol=pkg.Type#method (a LOCAL
        // variable has no name to address — kind=variable stays positional).
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
        return switch (kind) {
            case "method"   -> method.executeWithService(service, arguments);
            case "variable" -> variable.executeWithService(service, arguments);
            case "class"    -> clazz.executeWithService(service, arguments);
            case "subclass" -> subclass.executeWithService(service, arguments);
            case "middle_man" -> middleMan.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + publishedKinds());
        };
    }
}
