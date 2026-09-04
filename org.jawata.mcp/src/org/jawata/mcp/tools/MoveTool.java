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

    /** The kinds, derived from the dispatch map so the two can never disagree. */
    private List<String> kinds() {
        return List.copyOf(delegates.keySet());
    }

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
        return """
            Move a class, a package, a method, a field, or statements across a call,
            updating references (behaviour-preserving, reversible).""";
    }

    @Override
    public String usageTail() {
        return ", ...";
    }

    @Override
    public String kindBlock() {
        return """
            - class   — move the type at a caret to another package.
                        Needs: filePath, line, column, targetPackage (optional targetProjectKey).
            - package — move/rename a whole package.
                        Needs: packageName, newPackageName.
            - method  — move a method to another type. Which engine runs is read off the
                        method's own modifiers, not asked of you.
                        An INSTANCE method moves onto a RECEIVER — one of its parameters
                        or fields — and every call site is rewritten to invoke it there.
                        Needs: the method's position (filePath, line, column) or its
                        symbol, plus `target`, the parameter/field whose type receives it.
                        `target` may be omitted when exactly one candidate exists; with
                        several, the call is refused and lists them.
                        A STATIC method has no receiver — its call sites name the owning
                        TYPE — so it needs `targetType` instead, the destination's
                        fully-qualified name, and JDT's Move Static Members repoints every
                        qualified reference across the workspace.
                        Optional keepDelegate leaves a forwarder behind on either path.

            - field   — move a field to an EXISTING class, updating every reference.
                        Needs: filePath, line, column on the declaration, plus targetType
                        (the destination's fully-qualified name).
                        A STATIC field needs no receiver: JDT's engine rewrites the
                        qualified references. An INSTANCE field needs `target` — the
                        field of the source class holding the destination instance —
                        because every access becomes `receiver.name` and nothing in the
                        code says which field that is. Instance moves are scoped to a
                        PRIVATE field, whose accesses all live in one file; a non-private
                        one is refused, since each outside reader needs its own receiver
                        derived there (encapsulate it first). To move fields into a NEW
                        class, use extract kind=class, which needs no receiver.

            - statements_into_function
                      — move a statement that sits beside a call INTO the function being
                        called. Needs: filePath, line, column on the statement; the call
                        is its neighbour, and which side it is on decides whether the
                        statement lands at the top or the bottom of the callee.
                        EVERY call site is checked for the same statement first: with
                        three of four, moving it in would ADD behaviour at the fourth, so
                        the call is refused and says how many matched. The statement may
                        mention only static bindings and literals — a caller's local is
                        not in scope inside the callee and differs per call anyway.

            - statements_to_callers
                      — the inverse: move a function's FIRST or LAST statement out to
                        every call site. Needs: filePath, line, column on the statement.
                        Refuses a statement in the middle (no call-site position
                        reproduces running after part of the body), one mentioning the
                        method's own parameters or locals, a method that is overridden or
                        overrides (dispatch makes "the callers" unanswerable), a call
                        buried in a larger expression, and a method with no callers at
                        all — moving something to nobody is a deletion.""";
    }

    @Override
    public String footer() {
        return """
            updateReferences (default true) applies to kind=class and kind=package ONLY.
            For the other four, repointing the references IS the refactoring.
            IMPORTANT: ZERO-BASED coordinates.
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
            "Move a class or a method (by caret or by name), a package (by name), a FIELD onto another type, or STATEMENTS across a call boundary in either direction "
                + "(statements_into_function / statements_to_callers).");
        properties.put("kind", kind);

        properties.put("filePath", Map.of("type", "string", "description", "class/method: path to the source file."));
        properties.put("line", Map.of("type", "integer", "description", "class/method: zero-based line of a caret in the type or method."));
        properties.put("column", Map.of("type", "integer", "description", "class/method: zero-based column."));
        properties.put("targetPackage", Map.of("type", "string", "description", "class: destination package name."));
        properties.put("targetProjectKey", Map.of("type", "string", "description", "class: optional destination project (cross-project move)."));
        properties.put("packageName", Map.of("type", "string", "description", "package: the package to move/rename."));
        properties.put("newPackageName", Map.of("type", "string", "description", "package: the new package name."));
        // NAMED PER KIND, because it is not common and calling it common was a lie a C6
        // audit caught. Only class and package read it; the other four rewrite references
        // as the whole point of what they do — a moved field whose readers are not
        // repointed does not compile, so there is no meaningful `false` for them. The old
        // text said "Common: updateReferences (default true)" at the front door and four
        // kinds ignored it silently, which is worse than not offering it.
        properties.put("updateReferences", Map.of("type", "boolean",
            "description", "kind=class and kind=package ONLY: update all references "
                + "(default true). The other kinds do not accept it — for them repointing "
                + "the references IS the refactoring, and leaving them behind produces code "
                + "that does not compile."));

        properties.put("typeName", org.jawata.mcp.tools.shared.FqnTarget.typeNameSchemaProperty(
            "class to move (kind=class; kind=package uses packageName)"));
        // `symbol` was ACCEPTED here and not PUBLISHED. The front door's name-form
        // materializer reads either key, so a member FQN has always worked for kind=method;
        // only typeName was declared, so a client reading tools/list could not know. A C6
        // audit found the same shape on kind=middle_man's accessorName, which is why both
        // are written out now rather than left to the backstop below.
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "member to move: pkg.Type#method for kind=method, pkg.Type#field for kind=field."
                + " The statement kinds are POSITIONAL — a statement has no name"));

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
        // A member or a type changing owner is a hierarchy change; a class or package move
        // rewrites every import that named it. Moving STATEMENTS is not on this list: it
        // rewrites call SITES but changes no signature and no hierarchy, which is the
        // criterion. A C6 audit asked why the two were treated alike, and this is the
        // answer written down rather than left to be re-derived.
        //
        // Stage 6a (M3a): DERIVED from the delegates, which each declare it. The reasoning
        // above stays because it is the CRITERION, and the criterion is what a delegate
        // answers against; what is gone is the second list of names beside the routing map.
        java.util.Set<String> structural = new java.util.LinkedHashSet<>();
        delegates.forEach((kind, delegate) -> {
            if (delegate instanceof KindDelegate d && d.isStructural()) {
                structural.add(kind);
            }
        });
        return java.util.Set.copyOf(structural);
    }

}
