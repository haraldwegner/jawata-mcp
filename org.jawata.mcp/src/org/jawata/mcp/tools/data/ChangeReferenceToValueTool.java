package org.jawata.mcp.tools.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.Recipe;
import org.jawata.mcp.refactoring.RecipeEngine;
import org.jawata.mcp.refactoring.RecipeStep;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.codegen.GenerateEqualsHashCodeTool;
import org.jawata.mcp.tools.shared.FqnTarget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code data kind=reference_to_value} — Fowler row 2, Change Reference to Value.
 *
 * <p>An object that everybody shares by reference has to be kept in step: change it in one
 * place and every holder sees it, which is either the point or a bug, and nothing about the
 * class says which. A VALUE says which. It cannot be changed after it is made, and two of them
 * that carry the same data ARE the same — so nobody has to share one, and copying is free.</p>
 *
 * <p>Those are exactly two properties, and this row installs both: every setter goes (row 37,
 * once per setter, which also makes each field final where that is provable), and then
 * {@code equals} and {@code hashCode} are generated over the class's own fields. Neither half
 * is a value object on its own — an immutable class still compared by identity is the worst of
 * both, since callers cannot change it AND cannot compare it.</p>
 *
 * <h2>COMPOSED, and a real recipe — the steps are DEPENDENT</h2>
 *
 * <p>{@link RecipeEngine}'s own javadoc draws the line: independent edits computable against
 * one AST should be a single change, and only dependent ones need the engine. These are
 * dependent. Removing a setter rewrites the file — its constructor callers become direct
 * assignments and a modifier appears on a field — so the second setter is no longer where the
 * first step saw it, and the generation at the end must run against the file all the removals
 * left. Every step is therefore addressed BY NAME and resolved when it runs, and the whole
 * sequence reverts through one undo handle.</p>
 *
 * <p>Row 10 next door is a recipe for a different reason (JDT's self-encapsulate engine
 * rewrites references across FILES, so each step must see the previous one's world), and row
 * 45 is written code because its edits genuinely are independent. The three sit side by side
 * so the distinction is readable rather than a matter of taste.</p>
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li><b>A class that already declares {@code equals} or {@code hashCode}.</b> Somebody
 *       chose that identity on purpose, and a value object's equality is the whole point of
 *       the refactoring — overwriting it silently would change what the class MEANS. The
 *       generator would skip them and report a warning; here that would leave the row's
 *       headline claim half-done while reporting success, so it refuses up front instead.</li>
 *   <li><b>A Java {@code record}</b>, which is already exactly what this produces.</li>
 *   <li><b>A class with no instance field</b>, which has no value to be equal by.</li>
 *   <li><b>Any setter whose own preconditions fail</b> — an outside caller, an override, a
 *       field written elsewhere. The recipe rolls back and the caller reads row 37's own
 *       refusal, naming the sites. A vaguer refusal invented here would say less.</li>
 * </ul>
 *
 * <p><b>It is the one row of the eight composed rows that carries no detector</b>, and the
 * plan says so in its own C2 clause: seven of the eight are callable from a finding and this
 * one is not. Nothing reports "this class should be a value" — that is a modelling decision
 * about the domain, not a shape in the code.</p>
 */
public class ChangeReferenceToValueTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    private final RemoveSettingMethodTool removeSettingMethod;
    private final RefactoringChangeCache cache;

    public ChangeReferenceToValueTool(Supplier<IJdtService> serviceSupplier,
                                      RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
        this.removeSettingMethod = new RemoveSettingMethodTool(serviceSupplier, cache);
        this.cache = cache;
    }

    @Override
    public String kindName() {
        return "reference_to_value";
    }

    @Override
    public String kindSummary() {
        return """
            make a class a VALUE: remove every setter so it cannot change after
            it is made, then generate equals and hashCode over its fields so two
            with the same data are the same (Fowler: Change Reference to Value).
            Point at the CLASS — position, or typeName=pkg.Type. COMPOSED from
            remove_setting_method per setter plus the equals/hashCode generator,
            so it reverts through one undo handle and rolls every removal back if
            a later step declines. Refuses a class that already declares equals
            or hashCode (that identity was somebody's decision), a record
            (already a value), and a class with no instance field. A setter with
            a caller outside the class's constructors refuses with row 37's own
            reason, naming the sites.""";
    }

    /** Structural: setters leave the published surface and two methods arrive. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public String getName() {
        return "reference_to_value";
    }

    @Override
    public String getDescription() {
        return "Change Reference to Value — remove every setter, then generate equals and "
            + "hashCode so the class is compared by its data. Delegate of data.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the class."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of a caret in the class."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("typeName",
            FqnTarget.typeNameSchemaProperty("class to turn into a value"));
        schema.put("properties", properties);
        schema.put("required", List.of());
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    public ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        java.util.Optional<ToolResponse> nameForm =
            FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return nameForm.get();
        }
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath",
                "filePath is required (or name the class with typeName=pkg.Type)");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IType declaring = service.getTypeAtPosition(filePath, line, column);
            if (declaring == null) {
                IJavaElement element = service.getElementAtPosition(filePath, line, column);
                return ToolResponse.invalidParameter("position",
                    "position does not resolve to a class; got "
                        + (element == null ? "nothing" : element.getClass().getSimpleName())
                        + ". Change Reference to Value acts on the CLASS.");
            }
            return toValue(service, declaring, arguments);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(ChangeReferenceToValueTool.class)
                .warn("reference_to_value failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse toValue(IJdtService service, IType declaring, JsonNode arguments)
            throws Exception {
        if (declaring.isRecord()) {
            return ToolResponse.invalidParameter("position",
                "'" + declaring.getElementName() + "' is a RECORD: it is already immutable and"
                    + " compared by its components, which is exactly what this operation"
                    + " produces.");
        }
        for (IMethod method : declaring.getMethods()) {
            if (("equals".equals(method.getElementName()) && method.getNumberOfParameters() == 1)
                    || ("hashCode".equals(method.getElementName())
                        && method.getNumberOfParameters() == 0)) {
                return ToolResponse.invalidParameter("position",
                    "'" + declaring.getElementName() + "' already declares "
                        + method.getElementName() + ", so its identity was decided on purpose."
                        + " A value object's equality IS the refactoring, and replacing a"
                        + " hand-written one would change what the class means. Remove or"
                        + " rename it first if that is what you want.");
            }
        }

        List<String> valueFields = new ArrayList<>();
        for (IField field : declaring.getFields()) {
            if (!Flags.isStatic(field.getFlags())) {
                valueFields.add(field.getElementName());
            }
        }
        if (valueFields.isEmpty()) {
            return ToolResponse.invalidParameter("position",
                "'" + declaring.getElementName() + "' declares no instance field, so there is"
                    + " no data for two of them to be equal BY. A class with no state is"
                    + " already interchangeable, and equals over nothing says nothing.");
        }

        ICompilationUnit unit = declaring.getCompilationUnit();
        if (unit == null) {
            return ToolResponse.symbolNotFound(
                "'" + declaring.getElementName() + "' has no source compilation unit here.");
        }
        List<String> setters = setterNames(declaring, unit);

        // A RECIPE CANNOT STAGE — the same answer rows 10 and 58 give, for the same reason.
        if (!getBooleanParam(arguments, "auto_apply", true)) {
            return ToolResponse.invalidParameter("auto_apply",
                "reference_to_value is COMPOSED — it removes each setter and then generates"
                    + " equals/hashCode against the file those removals left, so there is no"
                    + " single staged change to preview. Run it (it reverts through one undo"
                    + " handle), or stage the halves yourself: data kind=remove_setting_method"
                    + " per setter, then generate kind=equals_hashcode.");
        }

        ObjectMapper mapper = new ObjectMapper();
        // THE FILE AND THE SIMPLE NAME — see TypeInFile for why a fully-qualified name is the
        // wrong key between steps.
        String unitPath = unit.getResource().getLocation().toOSString();
        String simpleName = declaring.getElementName();
        List<RecipeStep> steps = new ArrayList<>();
        for (String setter : setters) {
            ObjectNode args = mapper.createObjectNode();
            args.put("filePath", unitPath);
            args.put("typeName", simpleName);
            args.put("setter", setter);
            steps.add(new RecipeStep("data", args));
        }
        ObjectNode generateArgs = mapper.createObjectNode();
        generateArgs.put("filePath", unitPath);
        generateArgs.put("typeName", simpleName);
        generateArgs.set("fields", mapper.valueToTree(valueFields));
        steps.add(new RecipeStep("generate", generateArgs));

        Recipe recipe = new Recipe("change reference to value " + declaring.getElementName(),
            steps);
        RecipeEngine.Result result = recipe.run((operation, args) -> {
            // BY NAME, resolved NOW: every previous step rewrote this file. See the javadoc.
            IType current = TypeInFile.find(service, args.path("filePath").asText(),
                args.path("typeName").asText());
            if (current == null) {
                throw new IllegalStateException("could not re-resolve "
                    + args.path("typeName").asText() + " after the previous step.");
            }
            if ("generate".equals(operation)) {
                return equalsHashCodeChange(current, valueFields);
            }
            String setterName = args.path("setter").asText();
            IMethod setter = methodNamed(current, setterName);
            if (setter == null) {
                throw new IllegalStateException("could not find setter '" + setterName
                    + "' after the previous step — it may have been renamed or removed.");
            }
            RemoveSettingMethodTool.Prepared prepared =
                removeSettingMethod.prepare(service, setter);
            if (prepared.refusal() != null) {
                throw new IllegalStateException("remove '" + setterName + "': "
                    + String.valueOf(prepared.refusal().getError()));
            }
            return prepared.change();
        }, service);

        if (!result.ok()) {
            return ToolResponse.error("REFACTORING_FAILED",
                "reference_to_value failed: " + result.error(),
                "No changes were applied (the recipe rolled back every setter it had already"
                    + " removed).");
        }
        String undoChangeId = cache.put(RefactoringChangeCache.Kind.UNDO, result.compositeUndo(),
            "undo: change reference to value " + declaring.getElementName(), "",
            result.modifiedFilePaths());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", getName());
        data.put("applied", true);
        data.put("filesModified", result.modifiedFilePaths());
        data.put("undoChangeId", undoChangeId);
        data.put("settersRemoved", setters);
        data.put("valueFields", valueFields);
        data.put("summary", "change reference to value: " + declaring.getElementName() + " — "
            + setters.size() + " setter(s) removed, equals/hashCode over " + valueFields);
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(result.modifiedFilePaths().size())
            .returnedCount(result.modifiedFilePaths().size())
            .suggestedNextTools(List.of(
                "compile_workspace to verify the refactoring",
                "find_modernization kind=class_to_record — an immutable data class with value "
                    + "equality is what a record already is",
                "undo_refactoring with the undoChangeId if verification fails"))
            .build());
    }

    /**
     * The equals/hashCode step as a {@link org.eclipse.ltk.core.refactoring.Change}.
     *
     * <p>A full-file replace, because the generator's own output is a SOURCE STRING rather
     * than an edit tree — it splices the {@code java.util.Objects} import textually so that
     * its staged and applied paths cannot diverge. Wrapping that string as one
     * {@link ReplaceEdit} is what lets the recipe engine own it, gate it and undo it like
     * every other step, rather than this row applying it out of band.</p>
     */
    private static org.eclipse.ltk.core.refactoring.Change equalsHashCodeChange(
            IType type, List<String> fields) throws Exception {
        ICompilationUnit unit = type.getCompilationUnit();
        String original = unit.getSource();
        GenerateEqualsHashCodeTool.Generated generated =
            GenerateEqualsHashCodeTool.generate(type, fields, null);
        if (generated.methodsAdded().isEmpty()) {
            throw new IllegalStateException("neither equals nor hashCode could be generated: "
                + generated.warnings());
        }
        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();
        byFile.put((IFile) unit.getResource(),
            List.of(new ReplaceEdit(0, original.length(), generated.source())));
        return ChangeEngine.fromFileEdits(
            "generate equals/hashCode on " + type.getElementName(), byFile);
    }

    /**
     * The class's setters, in declaration order, recognised by row 37's own shape check.
     *
     * <p>Sharing {@code assignedField} rather than re-deciding what a setter is means the set
     * this row removes is exactly the set row 37 accepts — a second definition here could
     * offer a method the step then refuses, and the recipe would roll back for a reason the
     * caller could not have predicted.</p>
     */
    private static List<String> setterNames(IType declaring, ICompilationUnit unit)
            throws Exception {
        CompilationUnit ast = RemoveSettingMethodTool.parse(unit);
        List<String> found = new ArrayList<>();
        for (IMethod method : declaring.getMethods()) {
            if (method.isConstructor() || method.getNumberOfParameters() != 1) {
                continue;
            }
            MethodDeclaration declaration = RemoveSettingMethodTool.methodNamed(
                ast, method.getElementName(), 1);
            if (declaration != null
                    && RemoveSettingMethodTool.assignedField(declaration) != null) {
                found.add(method.getElementName());
            }
        }
        return found;
    }

    private static IMethod methodNamed(IType type, String name) throws Exception {
        for (IMethod method : type.getMethods()) {
            if (method.getElementName().equals(name) && method.getNumberOfParameters() == 1) {
                return method;
            }
        }
        return null;
    }
}
