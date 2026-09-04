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
            - replace_inline_code — replace a group of duplicated method bodies with calls
                         to one canonical method (Fowler: Replace Inline Code with
                         Function Call). Needs: cloneGroupId from find_duplicate_code,
                         passed with the SAME minTokens/projectKey/crossProject the
                         detection used — group ids are hashes of the clone shape, not
                         session state. Optional canonicalMethodName picks which
                         instance survives. Clones in OTHER types are skipped and
                         listed with the reason: cross-type delegation is not
                         automatically safe.
                         The same behaviour reaches extract kind=method as
                         replaceDuplicates, DEFAULT FALSE — that operation predates the
                         parameter and its shipped behaviour is to extract the selection
                         only. Every extract reports otherOccurrences whether or not the
                         flag is set, so a non-zero count is the signal to re-run with it.
            - combine_functions — gather loose STATIC functions that all take the same type
                         as their FIRST parameter into a new class holding it; each becomes
                         an instance method taking whatever came after, and every call site
                         becomes `new Type(data).fn(rest)` (Fowler: Combine Functions into
                         Class). Needs: filePath, functions[], newTypeName (optional
                         fieldName). functions[] has no default for the same reason
                         kind=class's fields[] does not — WHICH functions belong together is
                         the decision this carries out. Refuses a non-static function
                         (it already has a receiver — that is move kind=method) and a
                         function whose first parameter is a different type, naming both
                         sides, since a guess at "the data" produces code that compiles.
            - function_to_command — turn a STATIC function into an object: its parameters
                         become final fields set by a constructor, its body becomes
                         execute(), and every call site becomes `new Type(args).execute()`
                         (Fowler: Replace Function with Command). Needs: filePath, line,
                         column on the function, newTypeName. The reason to want it is that
                         the parameters are FIELDS afterwards, so the body can be broken
                         into named steps that share them — which compose_method cannot do
                         while they are parameters. Refuses an instance method (its receiver
                         would be a field nobody named) and a function something else
                         redeclares (a command has no dispatch).
            - split_phase — split a function that does two jobs in sequence into two
                         functions joined by a generated record (Fowler: Split Phase).
                         Needs: filePath, line, column on the function, boundaryLine —
                         the line of the SECOND phase's first statement. boundaryLine has
                         no default because where one job ends is a judgement about
                         meaning that nothing in the syntax marks. What the record carries
                         IS derived: every local declared before the boundary and read
                         after it, which is usually few — the rest stay in phase one, and
                         that shrinkage is the readability this buys. Optional firstName,
                         secondName, intermediateName. Refuses a return before the
                         boundary (an early exit, not a phase) and a second phase that
                         ASSIGNS to a first-phase local (the carrier's components are
                         final; a mutable carrier is a design decision).
            - temp_to_query — extract a temp's initializer into a method, then inline the
                         temp, so every use reads the query instead (Fowler: Replace Temp
                         with Query). Needs: filePath, line, column on the declaration
                         (optional methodName, default the variable's own name). COMPOSED
                         from two operations that already ship, so it adds one undo handle
                         for the pair and rolls the first back if the second declines.
                         Refuses a temp that is assigned more than once — it is then not a
                         name for one value, and Split Variable comes first.

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
        properties.put("line", Map.of("type", "integer", "description", "interface/superclass/class: zero-based line of a caret in the type."));
        properties.put("column", Map.of("type", "integer", "description", "interface/superclass/class: zero-based column."));
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
            "type to extract from (kinds interface/superclass/class; the range kinds "
                + "method/variable/constant need their coordinates)"));
        // ACCEPTED and not PUBLISHED until C6 — the front door's materializer reads either
        // key, so a member FQN has always worked; only typeName was declared. Stage 6 added
        // a kind whose target is a METHOD (function_to_command), which is what made the
        // omission reach a client rather than merely being untidy.
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "member to extract from: pkg.Type#method for kind=function_to_command. The RANGE"
                + " kinds (method, variable, constant, split_phase, temp_to_query) are"
                + " positional — a statement range and a local have no name"));

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
        // The two WRAPPER params are skipped: every delegate's schema carries them too,
        // and taking them from a delegate would publish one delegate's wording for a
        // parameter that belongs to the front door. withProjectKey/withAutoApply below
        // add them once, in this tool's own terms.
        for (AbstractTool delegate : delegates.values()) {
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
        AbstractTool delegate = delegates.get(kind);
        if (delegate == null) {
            return ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + kinds());
        }
        return delegate.executeWithService(service, arguments);
    }
    /**
     * Extracting a SUPERCLASS or an INTERFACE changes the hierarchy; extracting a
     * method, a variable or a constant does not.
     *
     * <p>The architect gate carried these two names itself, in a set beside its list of
     * tool names. Same defect one level down: a kind list in another package cannot know
     * when this tool's kinds change.</p>
     */
    @Override
    public java.util.Set<String> structuralKinds() {
        return java.util.Set.of("superclass", "interface");
    }

}
