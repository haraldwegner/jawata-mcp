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
 * Parametric front door for ELEVEN extract refactorings. Four wrap an LTK engine
 * (method, variable, constant, interface); the rest are Sprint 28d-rescue rows built
 * here — superclass, class, and the Fowler rows 5, 48, 58, 64 and the replace-inline-code
 * path on {@code method}.
 *
 * Each delegate self-validates its required params in {@code executeWithService},
 * so a flat schema (only {@code kind} + {@code filePath} required) is safe; the
 * per-kind params are documented and validated by the delegate.
 *
 * <p>Replaces {@code extract_method} / {@code extract_variable} /
 * {@code extract_constant} / {@code extract_interface}. Carries the apply/undo
 * contract unchanged (returns filesModified/diff/undoChangeId/summary).</p>
 */
public class ExtractTool extends AbstractTool implements KindedTool {

    @Override
    public String discriminator() {
        return "kind";
    }

    /**
     * The routing map IS the kind list (Stage 6a, M3b).
     *
     * <p>Nothing is built here: the map below already keys every delegate by the kind that
     * reaches it, and every delegate now fills the role, so this is a re-typing of what the
     * door already held rather than a second structure.</p>
     */
    @Override
    public java.util.Map<String, KindDelegate> delegates() {
        java.util.Map<String, KindDelegate> published = new LinkedHashMap<>();
        delegates.forEach((kind, delegate) -> published.put(kind, (KindDelegate) delegate));
        return java.util.Collections.unmodifiableMap(published);
    }


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
    /**
     * THE ELEMENT TYPE IS {@code AbstractTool}, not {@code AbstractApplyingRefactoringTool},
     * and row 58 is why. jawata has TWO refactoring bases and they are SIBLINGS under this
     * one: {@code AbstractApplyingRefactoringTool} for an operation that prepares a single
     * change, {@code AbstractRefactoringTool} for one that drives a refactoring or a recipe
     * itself. Replace Temp with Query is the second kind — its second step cannot be built
     * until the first has been applied — so a map typed to either base excludes half the
     * operations a front door has to be able to dispatch.
     *
     * <p>What every delegate does share is exactly what this map uses: a published schema
     * and an {@code executeWithService}. Typing it to the common supertype says that, rather
     * than saying something narrower that is not true of the set.</p>
     */
    private final Map<String, AbstractTool> delegates;

    public ExtractTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        Map<String, AbstractTool> d = new LinkedHashMap<>();
        d.put("method", new ExtractMethodTool(serviceSupplier, cache));
        d.put("variable", new ExtractVariableTool(serviceSupplier, cache));
        d.put("constant", new ExtractConstantTool(serviceSupplier, cache));
        d.put("interface", new ExtractInterfaceTool(serviceSupplier, cache));
        d.put("superclass", new ExtractSuperclassTool(serviceSupplier, cache));
        d.put("class", new ExtractClassTool(serviceSupplier, cache));
        // Sprint 28d-rescue (stage 1): the folded `replace_duplicates`. It was already
        // Fowler's Replace Inline Code with Function Call under another name — it takes a
        // clone group the duplicate detector found and rewrites every member of it to
        // call one canonical method — so it lands ON that row's kind rather than beside
        // it. Folding it anywhere else would put `extract` at twelve kinds.
        d.put("replace_inline_code", new ReplaceDuplicatesTool(serviceSupplier, cache));
        // Row 5. The mirror of `class`: that one pulls FIELDS out of a type carrying too
        // much state, this gathers FUNCTIONS around state they were all passing to each
        // other. Same decision in both — WHICH members travel together — and the same
        // answer: the caller names them, because no detector can.
        d.put("combine_functions", new CombineFunctionsIntoClassTool(serviceSupplier, cache));
        // Row 48. It belongs here rather than on refactor_to_pattern because what it
        // actually does is EXTRACT a function into a type — the Command shape is the
        // consequence, and the reason to want it is that the parameters become fields the
        // body's steps can share.
        d.put("function_to_command",
            new ReplaceFunctionWithCommandTool(serviceSupplier, cache));
        // Row 64. Two extractions with a generated carrier between them, which is why it
        // lives here rather than beside compose_method: compose_method names sections of
        // one method, this splits one method into two that no longer share a scope.
        d.put("split_phase", new SplitPhaseTool(serviceSupplier, cache));
        // Row 58, the one COMPOSED kind here: extract the temp's initializer, then inline
        // the temp. Both halves already ship, so what this adds is one undo handle for the
        // pair and a rollback when the second declines after the first has run.
        d.put("temp_to_query", new ReplaceTempWithQueryTool(serviceSupplier, cache));
        this.delegates = java.util.Collections.unmodifiableMap(d);
    }

    /*
     * kinds() WAS HERE, and it was `List.copyOf(delegates.keySet())` — the same derivation
     * KindedTool#publishedKinds() performs, written a second time inside this door. Its two
     * callers now ask the interface instead (C6a).
     *
     * It was deleted because a C6a audit measured what the seam's own documentation claimed.
     * Three texts said every routing door puts publishedKinds() into its schema enum; three
     * of six did. This door and `move` read a private kinds(), and `apply_cleanup` a private
     * constant. All three happened to derive, so nothing was wrong with the published output
     * — but the sentence justifying the deletion of the enum guard was true only of the
     * three doors that called the shared method, and a private method a door's author can
     * freely change is exactly where `generate`'s hand-written list had been hiding.
     * Repointing all six makes the claim true rather than smaller.
     */

    @Override
    public String getName() {
        return "extract";
    }

    /**
     * ASSEMBLED, not written (Stage 6a, M4).
     *
     * <p><b>A DECLARED CHANGE TO THE PUBLISHED TEXT, and the more substantive of the two this
     * step makes.</b> This door's {@code USAGE:} line read {@code extract(kind="<kind>", ...)}
     * — a PLACEHOLDER where the other doors list their kinds. A generated line lists them, so
     * a reader of the usage line now sees all eleven.</p>
     *
     * <p>The placeholder is worth naming as a symptom rather than a style: a hand-written
     * usage line degenerates to {@code <kind>} exactly when there are too many kinds to keep
     * listing by hand, which is the same pressure that let this door's structural set name two
     * while five had been added. A derived line has no such pressure.</p>
     */
    @Override
    public String getDescription() {
        return FrontDoorDescription.ASSEMBLER.describe(this);
    }

    @Override
    public String preamble() {
        return "Extract a method, variable, constant or type, and related restructurings (behaviour-preserving, undoable).";
    }

    @Override
    public String usageTail() {
        return ", filePath=..., ...";
    }

    /**
     * The one line of this block that is NOT a bullet, and it stays with the door.
     *
     * <p>It is a fact about every kind at once — the coordinates are the door's convention,
     * not any delegate's story — so a pure projection over {@code delegates()} would drop it
     * silently. Most doors have no such line and inherit the empty default.</p>
     */
    @Override
    public String kindBlockLeadIn() {
        return "Kinds and their params (all ZERO-BASED coordinates):";
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
        kind.put("description", "What to extract; see the tool description.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "Source file."));
        properties.put("startLine", Map.of("type", "integer", "description", "Range kinds: zero-based."));
        properties.put("startColumn", Map.of("type", "integer", "description", "Range kinds: zero-based."));
        properties.put("endLine", Map.of("type", "integer", "description", "Range kinds: zero-based."));
        properties.put("endColumn", Map.of("type", "integer", "description", "Range kinds: zero-based."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based caret line."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based caret column."));
        properties.put("methodName", Map.of("type", "string", "description", "method/temp_to_query: new method name."));
        properties.put("variableName", Map.of("type", "string", "description", "variable: new name."));
        properties.put("constantName", Map.of("type", "string", "description", "constant: new name."));
        properties.put("interfaceName", Map.of("type", "string", "description", "interface: new name."));
        properties.put("methodNames", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "interface: methods to declare."));
        properties.put("superclassName", Map.of("type", "string", "description", "superclass: new name."));
        properties.put("siblings", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "superclass: sibling simple names in the same package."));
        properties.put("members", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "superclass: members to pull up (default: identical methods)."));
        properties.put("mode", Map.of("type", "string", "enum", List.of("jdt", "identical"),
            "description", "superclass: jdt (default, also fields and differing members) or identical (conservative)."));

        properties.put("typeName", org.jawata.mcp.tools.shared.FqnTarget.typeNameSchemaProperty(
            "type (interface, superclass, class)"));
        // ACCEPTED and not PUBLISHED until C6 — the front door's materializer reads either
        // key, so a member FQN has always worked; only typeName was declared. Stage 6 added
        // a kind whose target is a METHOD (function_to_command), which is what made the
        // omission reach a client rather than merely being untidy.
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "function (function_to_command; range kinds need coordinates)"));

        // THE BACKSTOP now lives on KindedTool — this door wrote it out, three others wrote
        // it out again, and `inline` did not write it at all, which is how row 36's
        // accessorName shipped unpublished. See KindedTool#withDelegateParameters.
        schema.put("properties", withDelegateParameters(properties));
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
            return ToolResponse.invalidParameter("kind",
                "kind is required; one of " + publishedKinds());
        }
        AbstractTool delegate = delegates.get(kind);
        if (delegate == null) {
            return ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + publishedKinds());
        }
        return delegate.executeWithService(service, arguments);
    }
    /**
     * The criterion is {@link Tool#structuralKinds()}'s own: does the kind change a
     * SIGNATURE or a HIERARCHY?
     *
     * <p>{@code superclass} and {@code interface} change the hierarchy. {@code class},
     * {@code combine_functions} and {@code function_to_command} all introduce a TYPE and
     * rewrite call sites into it — {@code new ScoreCommand(x).execute()} is not the
     * signature {@code score(x)} was. {@code split_phase} generates a carrier record and
     * two methods where one stood.</p>
     *
     * <p>{@code method}, {@code variable}, {@code constant} and {@code temp_to_query} add
     * a private member and change no caller's contract.</p>
     *
     * <p>This listed TWO until a C6 audit checked it against the criterion. Five Stage 6
     * kinds had been added under it without anyone re-reading the sentence, and the
     * architect gate was silent on every one — the same defect the javadoc below already
     * described one level up, repeated one level down: a kind list cannot know when the
     * kinds change.</p>
     *
     * <p><b>Stage 6a (M3a): DERIVED, and the paragraph above is why.</b> Each delegate now declares
     * for itself whether it is structural, and this asks them. The six names that used to be
     * written here are gone — not because a hand-written list is inelegant, but because this
     * one was measurably wrong: it named two while five had been added under it, and no
     * amount of care at the door can notice a kind arriving in the map beside it.</p>
     *
     * <p><b>C8: THE DERIVATION MOVED TO {@link KindedTool}, and the override here is gone.</b>
     * Three doors carried a near-identical copy of that loop and six carried none, so the gate
     * this feeds was silent on {@code data} and {@code hierarchy} and about to go silent on
     * eleven more. Deriving it once on the interface is the same move {@code publishedKinds()}
     * and {@code withDelegateParameters} already made there, for the reason the latter states:
     * a backstop each door opts into by remembering is not a backstop. This paragraph stays
     * because the criterion above is what a reader needs when deciding a NEW delegate's answer.</p>
     */

}
