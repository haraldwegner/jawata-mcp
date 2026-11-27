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
 * Sprint 19 (Kerievsky "Refactoring to Patterns") — parametric front door for the
 * pattern-targeted transforms. Mirrors {@link ExtractTool}: a flat schema whose
 * {@code kind} selects a delegate that self-validates its per-kind params, carrying
 * the apply/undo contract unchanged (returns filesModified/diff/undoChangeId/summary).
 *
 * <p>The catalogue runs both directions — <em>toward</em> patterns (when complexity
 * warrants) and <em>away</em> from patterns (Kerievsky's distinguishing insight:
 * Inline Singleton, Replace Pattern with Idiom). Detection of candidates is via
 * {@code find_quality_issue} kinds (family {@code kerievsky} + reused Fowler kinds);
 * this tool applies the chosen transform at a caller-supplied target.</p>
 */
public class RefactorToPatternTool extends AbstractTool implements KindedTool {

    @Override
    public String discriminator() {
        return "kind";
    }

    /** Built from the typed fields, keyed by what each delegate calls itself (Stage 6a, M3b). */
    @Override
    public java.util.Map<String, KindDelegate> delegates() {
        java.util.Map<String, KindDelegate> published = new java.util.LinkedHashMap<>();
        for (KindDelegate delegate : List.of(
                inlineSingleton, composeMethod, replaceTypeCode, refactorToState,
                refactorToCommand, formTemplateMethod, refactorToVisitor,
                replacePatternWithIdiom, replaceConstructorWithFactory,
                replaceConditionalWithPolymorphism, decomposeConditional)) {
            published.put(delegate.kindName(), delegate);
        }
        return java.util.Collections.unmodifiableMap(published);
    }


    /** All kinds in the catalogue; delegates are wired in as each ships. */
    private static final List<String> KINDS = List.of(
        "inline_singleton",              // away  — Stage 0
        "compose_method",                // toward — Stage 1
        "replace_type_code_with_class",  // toward — Stage 2
        "refactor_to_state",             // toward — Stage 3
        "refactor_to_command_dispatcher",// toward — Stage 4
        "form_template_method",          // toward — Stage 5
        "refactor_to_visitor",           // toward — Stage 6
        "replace_pattern_with_idiom",    // away  — Stage 7
        // 28d Stage 8, rank 3 of the operation survey. The creational family
        // (abstract-factory, factory-method, builder, null-object) is blocked
        // without a constructor call-site rewrite, which retargetCallsTo cannot do.
        "replace_constructor_with_factory",
        // 28d Stage 8, rank 2 — the general dispatch-collapse the special-case
        // tools cannot reach: an ENUM discriminator, the arrow form, and a
        // selector that is a parameter rather than a private int field.
        "replace_conditional_with_polymorphism",
        // 28d row 8. The plan assigned it to apply_cleanup and it cannot live there:
        // that tool is a sweep and accepts no per-call input, while the whole value of
        // Decompose Conditional is the caller's names — `notSummer`, not `condition1`.
        // This front door already asks for names, so it is where the row belongs.
        "decompose_conditional");

    /**
     * The published kind list, for callers that must check a step EXISTS —
     * Stage 11a's tier derivation reads it as the operation registry.
     *
     * <p>An accessor rather than a copy: this list is the registration line's
     * own source, and a second spelling of it elsewhere would be the copy that
     * goes stale when a kind ships.</p>
     */
    public static List<String> patternKinds() {
        return KINDS;
    }

    private final InlineSingletonTool inlineSingleton;
    private final ComposeMethodTool composeMethod;
    private final ReplaceTypeCodeWithClassTool replaceTypeCode;
    private final RefactorToStateTool refactorToState;
    private final RefactorToCommandDispatcherTool refactorToCommand;
    private final FormTemplateMethodTool formTemplateMethod;
    private final RefactorToVisitorTool refactorToVisitor;
    private final ReplacePatternWithIdiomTool replacePatternWithIdiom;
    private final ReplaceConstructorWithFactoryTool replaceConstructorWithFactory;
    private final ReplaceConditionalWithPolymorphismTool replaceConditionalWithPolymorphism;
    private final DecomposeConditionalTool decomposeConditional;

    public RefactorToPatternTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.inlineSingleton = new InlineSingletonTool(serviceSupplier, cache);
        this.composeMethod = new ComposeMethodTool(serviceSupplier, cache);
        this.replaceTypeCode = new ReplaceTypeCodeWithClassTool(serviceSupplier, cache);
        this.refactorToState = new RefactorToStateTool(serviceSupplier, cache);
        this.refactorToCommand = new RefactorToCommandDispatcherTool(serviceSupplier, cache);
        this.formTemplateMethod = new FormTemplateMethodTool(serviceSupplier, cache);
        this.refactorToVisitor = new RefactorToVisitorTool(serviceSupplier, cache);
        this.replacePatternWithIdiom = new ReplacePatternWithIdiomTool(serviceSupplier, cache);
        this.replaceConstructorWithFactory =
            new ReplaceConstructorWithFactoryTool(serviceSupplier, cache);
        this.replaceConditionalWithPolymorphism =
            new ReplaceConditionalWithPolymorphismTool(serviceSupplier, cache);
        this.decomposeConditional = new DecomposeConditionalTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "refactor_to_pattern";
    }

    /**
     * ASSEMBLED, not written (Stage 6a, M5).
     *
     * <p><b>TWO declared changes to the published text, and the second is the interesting
     * one.</b> The {@code USAGE:} line said {@code kind="<kind>"} — a placeholder, the same
     * degeneration {@code extract} showed, and for the same reason: eleven names are too many
     * to keep listing by hand. A generated line lists them.</p>
     *
     * <p>And the BULLET ORDER changes. {@code decompose_conditional} was written third,
     * beside {@code compose_method}, because that reads well; it is eleventh in the routing
     * table and eleventh in the published {@code kind} enum. Projecting the block makes all
     * three agree — so a reader comparing the enum against the bullets now finds the same
     * sequence, which they did not before.</p>
     */
    @Override
    public String getDescription() {
        return FrontDoorDescription.ASSEMBLER.describe(this);
    }

    @Override
    public String preamble() {
        return "Refactor toward or away from a design pattern (behaviour-preserving, undoable).";
    }

    @Override
    public String usageTail() {
        return ", filePath=..., line=..., column=...";
    }

    @Override
    public String kindBlockLeadIn() {
        return "Kinds (ZERO-BASED coordinates):";
    }

    @Override
    public String footer() {
        return "compose_method and decompose_conditional refuse auto_apply=false.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");

        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        // DERIVED from the routing table (M6), so the published enum and the dispatch are
        // the same iteration. KINDS survives below because patternKinds() is STATIC and so
        // cannot read an instance's delegates, while six places across five classes read it —
        // CureLookup and CureTier in production, three test classes besides. (This sentence
        // said "four callers" until C6a measured it with find_references; the same wrong
        // number was in TheRoutingTableIsTheKindListTest and in commit 10082733.)
        kind.put("enum", publishedKinds());
        kind.put("description", "Which transform; see the tool description.");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "Source file."));
        properties.put("line", Map.of("type", "integer", "description", "Zero-based line of the target."));
        properties.put("column", Map.of("type", "integer", "description", "Zero-based column."));
        properties.put("sections", Map.of("type", "array",
            "items", Map.of("type", "object"),
            "description", "compose_method: 2+ disjoint {startLine, startColumn, endLine, endColumn, methodName}, zero-based."));
        properties.put("newTypeName", Map.of("type", "string",
            "description", "replace_type_code_with_class: enum name."));
        properties.put("prefix", Map.of("type", "string",
            "description", "replace_type_code_with_class: constant prefix (default largest group)."));
        properties.put("idiom", Map.of("type", "string",
            "description", "replace_pattern_with_idiom: default anonymous_to_lambda."));

        properties.put("typeName", org.jawata.mcp.tools.shared.FqnTarget.typeNameSchemaProperty(
            "type (type-targeted kinds)"));
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "method (form_template_method)"));

        // THE BACKSTOP — every parameter any delegate declares reaches the published
        // contract, whether or not someone curated it above.
        //
        // Added BEFORE this tool gains a kind, deliberately. This front door happened
        // to be complete when the guard was written, and that is exactly the state
        // `extract` and `generate` were in before their next kind was added: a
        // hand-written schema beside a dispatch switch is a COPY of the delegates'
        // contracts, correct until the first unmirrored change and silent about it
        // afterwards. Being complete today is not a property that survives the next
        // addition unless something derives it.
        //
        // THE BACKSTOP now lives on KindedTool, and this copy carried a second defect the
        // shared one cannot have: it iterated a hand-written List.of(...) of the eleven
        // fields rather than the routing table, so a twelfth delegate would have been
        // dispatched, published and left out of the loop that publishes its parameters.
        schema.put("properties", withDelegateParameters(properties));
        // Sprint 24 (D1): filePath OR the name form (the symbol-targeted kinds).
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        // Sprint 24 (D1): the type/method-targeted kinds (inline_singleton,
        // replace_type_code_with_class, refactor_to_visitor, form_template_method)
        // accept typeName=/symbol=; the statement-targeted kinds (refactor_to_state,
        // refactor_to_command_dispatcher, compose_method, replace_pattern_with_idiom)
        // keep their coordinates — a switch or a range has no name.
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            // publishedKinds(), not KINDS, even though this door's KINDS legitimately
            // survives: it is kept for the STATIC patternKinds() the cure table reads, and
            // a client-facing message has no reason to prefer the constant to the routing
            // table. Leaving it on KINDS made this the one door of six still answering a
            // caller from its own copy — noticed by a C6a audit as an unevenness rather
            // than a defect, which is exactly when it is cheap to remove.
            return ToolResponse.invalidParameter("kind",
                "kind is required; one of " + publishedKinds());
        }
        return switch (kind) {
            case "inline_singleton" -> inlineSingleton.executeWithService(service, arguments);
            case "compose_method" -> composeMethod.executeWithService(service, arguments);
            case "replace_type_code_with_class" -> replaceTypeCode.executeWithService(service, arguments);
            case "refactor_to_state" -> refactorToState.executeWithService(service, arguments);
            case "refactor_to_command_dispatcher" -> refactorToCommand.executeWithService(service, arguments);
            case "form_template_method" -> formTemplateMethod.executeWithService(service, arguments);
            case "refactor_to_visitor" -> refactorToVisitor.executeWithService(service, arguments);
            case "replace_pattern_with_idiom" -> replacePatternWithIdiom.executeWithService(service, arguments);
            case "replace_constructor_with_factory" ->
                replaceConstructorWithFactory.executeWithService(service, arguments);
            case "decompose_conditional" -> decomposeConditional.executeWithService(service, arguments);
            case "replace_conditional_with_polymorphism" ->
                replaceConditionalWithPolymorphism.executeWithService(service, arguments);
            default -> ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + publishedKinds());
        };
    }
    /**
     * STRUCTURAL. Every pattern transform reshapes types or their relationships.
     */
    @Override
    public boolean isStructural() {
        return true;
    }

}
