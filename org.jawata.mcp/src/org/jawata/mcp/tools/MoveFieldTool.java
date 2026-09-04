package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.MoveStaticMembersProcessor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fowler — <b>Move Field</b> (row 23), moving a field to an EXISTING class.
 *
 * <p>A delegate of {@link MoveTool} (kind {@code field}); not registered standalone.
 * Routed from {@code shotgun_surgery} and {@code inappropriate_intimacy}, which are the
 * smells that say state is living in the wrong class.</p>
 *
 * <h2>THIS SHIPS THE STATIC HALF ONLY, and says so rather than pretending otherwise</h2>
 *
 * <p>A STATIC field has a JDT engine behind it — {@link MoveStaticMembersProcessor}, the
 * IDE's own Move Static Members — which relocates the declaration and rewrites every
 * qualified reference across the workspace. That is the whole refactoring for a static
 * field, and it is what this does.</p>
 *
 * <p>An INSTANCE field has no engine, and it is not the same operation wearing a different
 * modifier. Moving {@code a.f} to another class means every read and write becomes
 * {@code a.<something>.f}, and NOTHING IN THE CODE SAYS WHAT {@code <something>} IS: the
 * source class may hold several fields of the destination's type, one, or none — and where
 * it holds none, the refactoring is impossible until somebody adds one. That choice is the
 * caller's, exactly as {@code move kind=method} takes a {@code target} naming the receiver.
 * Until this tool takes that parameter and rewrites the accesses, an instance field is
 * REFUSED with a message saying which part is missing.</p>
 *
 * <p>The refusal is deliberate and is not a silent gap: a tool that moved the declaration
 * and left the accesses would produce code that does not compile, and one that guessed the
 * receiver would produce code that compiles and is wrong.</p>
 */
public class MoveFieldTool extends AbstractRefactoringTool {

    public MoveFieldTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "move_field";
    }

    @Override
    public String getDescription() {
        return "Move Field — move a field to an existing class, updating every reference. "
            + "STATIC fields only for now; an instance field is refused with the reason. "
            + "Delegate of move.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the field."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the field declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of the field declaration."));
        properties.put("targetType", Map.of("type", "string",
            "description", "move kind=field: fully-qualified name of the EXISTING class "
                + "the field moves to."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "targetType"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String targetType = getStringParam(arguments, "targetType");

        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        if (targetType == null || targetType.isBlank()) {
            return ToolResponse.invalidParameter("targetType",
                "targetType is required — the fully-qualified name of the class the field "
                    + "moves to. This refactoring moves state to an EXISTING class; to move "
                    + "fields into a NEW one, use extract kind=class.");
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IField field)) {
                return ToolResponse.invalidParameter("position",
                    "Position does not resolve to a field; got "
                        + (element == null ? "null" : element.getClass().getSimpleName()));
            }

            if (!Flags.isStatic(field.getFlags())) {
                return ToolResponse.invalidParameter("position",
                    "'" + field.getElementName() + "' is an INSTANCE field, and moving one "
                        + "needs something this call does not carry: every access becomes "
                        + "`owner.<receiver>." + field.getElementName() + "`, and nothing in "
                        + "the code says which field of the source class is that receiver — "
                        + "there may be several, or none at all. Naming it is the caller's "
                        + "choice, as `move kind=method` takes `target`. Static fields move "
                        + "today; this half is not built. Moving fields into a NEW class is "
                        + "extract kind=class, which does not need a receiver.");
            }

            IType declaring = field.getDeclaringType();
            if (declaring != null && targetType.equals(declaring.getFullyQualifiedName())) {
                return ToolResponse.invalidParameter("targetType",
                    "The field already lives in " + targetType + "; nothing to move.");
            }

            MoveStaticMembersProcessor processor =
                new MoveStaticMembersProcessor(new IMember[] { field }, new CodeGenerationSettings());
            processor.setDestinationTypeFullyQualifiedName(targetType);
            // No forwarder: a moved field with a delegate left behind is two names for one
            // piece of state, which is the shotgun-surgery this row exists to remove.
            processor.setDelegateUpdating(false);
            ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);

            RefactoringStatus initial =
                refactoring.checkInitialConditions(new NullProgressMonitor());
            if (initial.hasFatalError()) {
                return ToolResponse.invalidParameter("move kind=field", formatStatus(initial));
            }
            return runPreCheckedRefactoring(service, refactoring, "move_field", arguments);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }
}
