package org.jawata.mcp.tools.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.refactoring.sef.SelfEncapsulateFieldRefactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.Recipe;
import org.jawata.mcp.refactoring.RecipeEngine;
import org.jawata.mcp.refactoring.RecipeStep;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FqnTarget;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * {@code data kind=encapsulate_record} — Fowler row 2's sibling, row 10, Encapsulate Record.
 *
 * <p>A class whose fields are public is a bare record: every caller reaches its insides by
 * name, so the class can never learn that a value changed, validate one, or rename a field
 * without breaking everybody. The cure is the one the product already performs one field at a
 * time — {@code data kind=encapsulate_field} — applied to every public field the class has.
 * </p>
 *
 * <h2>COMPOSED, and it is a real recipe rather than written code</h2>
 *
 * <p>Each step is an operation that already ships, each leaves the class compiling (a class
 * with one field encapsulated and three still public is ordinary Java), and the whole
 * sequence reverts through one undo handle. That is precisely what {@link RecipeEngine}
 * exists for, and it is why this row adds no rewriting logic of its own: the accessors, the
 * cross-file rewriting of every direct access, and the visibility change are JDT's
 * self-encapsulate engine doing what it already does.</p>
 *
 * <h2>Every step addresses its field BY NAME, resolved when the step runs</h2>
 *
 * <p>A recipe's steps are decided before the first one runs, and encapsulating a field
 * INSERTS two accessor methods into the same file — so a position captured up front points at
 * different text by the second step. The field names are captured instead, and each step
 * re-resolves its {@link IField} against the type as it stands. Row 58 recorded this hazard
 * first; this row is its second instance, on a different axis (a member list rather than a
 * statement's line).</p>
 *
 * <h2>What it refuses, and what it merely SKIPS</h2>
 *
 * <ul>
 *   <li><b>A Java {@code record}</b> is refused: its components are already private and final
 *       with accessors, so the refactoring's whole output is what a record already is.</li>
 *   <li><b>A class with no public instance field</b> is refused, because there is nothing
 *       here to do — that is the state this operation produces.</li>
 *   <li><b>A {@code public static final} constant is SKIPPED, not refused.</b> A constant is
 *       not the state Fowler's Encapsulate Record is about, and encapsulating one would put a
 *       getter in front of a compile-time constant for nothing. The response names what was
 *       skipped, because a caller who asked about a class and got fewer fields than it has
 *       should be told which and why rather than left to diff.</li>
 *   <li><b>A non-final {@code public static} field is also skipped</b>, and for a sharper
 *       reason: static mutable state is {@code global_data}'s subject, which
 *       {@code encapsulate_collection} refuses for the same reason — curing it here would fix
 *       it under one name and leave it reported under another.</li>
 * </ul>
 *
 * <p>An accessor name that already exists is NOT checked here. JDT's own engine refuses that
 * conflict with a precise message, the recipe rolls back every step that had already run, and
 * the caller reads the engine's reason — a second, vaguer check invented here would only be
 * able to say something less exact.</p>
 */
public class EncapsulateRecordTool extends AbstractRefactoringTool implements ToolKindDelegate {

    private final RefactoringChangeCache cache;

    public EncapsulateRecordTool(Supplier<IJdtService> serviceSupplier,
                                 RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
        this.cache = cache;
    }

    @Override
    public String kindName() {
        return "encapsulate_record";
    }

    @Override
    public String kindSummary() {
        return """
            put every public field of a class behind accessors, so the object
            owns its own state instead of handing it out by name (Fowler:
            Encapsulate Record). Point at the CLASS — position, or
            typeName=pkg.Type. COMPOSED from encapsulate_field per field, so it
            adds one undo handle for the whole set and rolls the earlier fields
            back if a later one declines. Each field is addressed by NAME and
            resolved when its step runs, because encapsulating one field moves
            the others. Refuses a Java record (already encapsulated) and a class
            with no public instance field. Skips static fields — a constant is
            not this refactoring's subject, and static mutable state is
            global_data's — and names what it skipped.""";
    }

    /** Structural: it changes the class's published surface, field by field. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public String getName() {
        return "encapsulate_record";
    }

    @Override
    public String getDescription() {
        return "Encapsulate Record — put every public field of a class behind accessors. "
            + "Delegate of data.";
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
        properties.put("typeName", FqnTarget.typeNameSchemaProperty("class to encapsulate"));
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
                        + ". Encapsulate Record acts on the CLASS whose fields are public.");
            }
            return encapsulate(service, declaring, arguments);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(EncapsulateRecordTool.class)
                .warn("encapsulate_record failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse encapsulate(IJdtService service, IType declaring, JsonNode arguments)
            throws Exception {
        if (declaring.isRecord()) {
            return ToolResponse.invalidParameter("position",
                "'" + declaring.getElementName() + "' is a RECORD: its components are already"
                    + " private and final with accessors, which is exactly the state this"
                    + " operation produces. There is nothing here to encapsulate.");
        }

        List<String> encapsulate = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        for (IField field : declaring.getFields()) {
            int flags = field.getFlags();
            if (!Flags.isPublic(flags)) {
                continue;
            }
            if (Flags.isStatic(flags)) {
                skipped.add(field.getElementName() + " (static — "
                    + (Flags.isFinal(flags) ? "a constant, not the state this row is about"
                        : "static mutable state is global_data's subject") + ")");
                continue;
            }
            encapsulate.add(field.getElementName());
        }

        if (encapsulate.isEmpty()) {
            return ToolResponse.invalidParameter("position",
                "'" + declaring.getElementName() + "' has no public instance field, so it is"
                    + " not handing its state out by name — that is the state this operation"
                    + " produces rather than one it acts on."
                    + (skipped.isEmpty() ? "" : " Skipped: " + skipped + "."));
        }

        // A RECIPE CANNOT STAGE, and the reason is the same one row 58 records: each step is
        // built against the workspace the previous step produced, so there is no single change
        // to show before the first one has been applied.
        if (!getBooleanParam(arguments, "auto_apply", true)) {
            return ToolResponse.invalidParameter("auto_apply",
                "encapsulate_record is COMPOSED — it runs encapsulate_field once per public"
                    + " field, and each step is built against the file the previous step"
                    + " rewrote, so there is no single staged change to preview. Run it (it"
                    + " reverts through one undo handle), or stage the fields yourself with"
                    + " data kind=encapsulate_field, one at a time.");
        }

        ObjectMapper mapper = new ObjectMapper();
        String unitPath = declaring.getCompilationUnit().getResource().getLocation()
            .toOSString();
        List<RecipeStep> steps = new ArrayList<>();
        for (String fieldName : encapsulate) {
            ObjectNode args = mapper.createObjectNode();
            // THE FILE AND THE SIMPLE NAME, not the fully-qualified one. Row 54 recorded the
            // hazard: IType spells a nested type's qualified name with '$' and the binding
            // spells it with '.', so a name-keyed lookup is one of two spellings and silently
            // finds nothing for a member class. Walking this file's own types has no spelling.
            args.put("filePath", unitPath);
            args.put("typeName", declaring.getElementName());
            args.put("field", fieldName);
            steps.add(new RecipeStep("data", args));
        }
        Recipe recipe = new Recipe("encapsulate record " + declaring.getElementName(), steps);

        RecipeEngine.Result result = recipe.run((operation, args) -> {
            // BY NAME, resolved NOW: the previous step inserted accessor methods into this
            // same file, so any position captured before the recipe started is stale. See the
            // class javadoc.
            String fieldName = args.path("field").asText();
            IType current = TypeInFile.find(service, args.path("filePath").asText(),
                args.path("typeName").asText());
            if (current == null) {
                throw new IllegalStateException("could not re-resolve "
                    + args.path("typeName").asText() + " after the previous step.");
            }
            IField field = current.getField(fieldName);
            if (field == null || !field.exists()) {
                throw new IllegalStateException("could not find field '" + fieldName
                    + "' after the previous step — it may have been renamed or removed.");
            }
            // PUBLIC accessors, and the visibility flag is about the ACCESSORS rather than
            // the field — JDT's engine makes the field private either way. Measured here, by
            // this row: the first run passed the default and produced PRIVATE getters and
            // setters on a class whose fields a second file reads, which is an encapsulation
            // nobody outside can get through. Encapsulate Record's whole subject is the
            // callers that reached the state by name, so the way through has to stay open to
            // them. (data kind=encapsulate_field's own newFieldVisibility parameter is
            // documented as the FIELD's visibility and defaults to private; that reading is
            // recorded as a Stage 5 finding rather than changed here, since the parameter is
            // published and a caller may be relying on it.)
            SelfEncapsulateFieldRefactoring refactoring = EncapsulateFieldTool.refactoringFor(
                field, null, null, Flags.AccPublic, false);
            RefactoringStatus status =
                refactoring.checkAllConditions(new NullProgressMonitor());
            if (status.hasFatalError()) {
                throw new IllegalStateException("encapsulate '" + fieldName + "': "
                    + status.getMessageMatchingSeverity(RefactoringStatus.FATAL));
            }
            return refactoring.createChange(new NullProgressMonitor());
        }, service);

        if (!result.ok()) {
            return ToolResponse.error("REFACTORING_FAILED",
                "encapsulate_record failed: " + result.error(),
                "No changes were applied (the recipe rolled back every field it had already"
                    + " encapsulated).");
        }
        String undoChangeId = cache.put(RefactoringChangeCache.Kind.UNDO, result.compositeUndo(),
            "undo: encapsulate record " + declaring.getElementName(), "",
            result.modifiedFilePaths());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", getName());
        data.put("applied", true);
        data.put("filesModified", result.modifiedFilePaths());
        data.put("undoChangeId", undoChangeId);
        data.put("encapsulated", encapsulate);
        data.put("skipped", skipped);
        data.put("summary", "encapsulate record: " + declaring.getElementName() + " — "
            + encapsulate.size() + " field(s) now private with accessors"
            + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped"));
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(result.modifiedFilePaths().size())
            .returnedCount(result.modifiedFilePaths().size())
            .suggestedNextTools(List.of(
                "compile_workspace to verify the refactoring",
                "undo_refactoring with the undoChangeId if verification fails"))
            .build());
    }
}
