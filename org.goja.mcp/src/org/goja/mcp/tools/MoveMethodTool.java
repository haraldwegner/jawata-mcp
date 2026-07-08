package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.MoveInstanceMethodProcessor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Sprint 22a P1-a.1 — {@code move_method}: move an instance method onto the
 * type of one of its parameters or fields (JDT "Move Instance Method"),
 * rewriting every call site to invoke it on the new receiver.
 *
 * <p>The composition-axis primitive: a method that lives on an owner class but
 * really operates on a collaborator ({@code Owner.reset(Cell c) { c.set(0); }})
 * moves onto the collaborator ({@code Cell.reset() { set(0); }}). Like
 * {@code pull_up} drives {@code PullUpRefactoringProcessor}, this drives the
 * internal {@link MoveInstanceMethodProcessor} directly — the 2024-09
 * descriptor exposes no public target setter. The target must be chosen
 * BETWEEN initial and final conditions (its {@link IVariableBinding} is bound
 * to the initial-conditions parse), so this uses
 * {@link AbstractRefactoringTool#runPreCheckedRefactoring}.</p>
 */
public class MoveMethodTool extends AbstractRefactoringTool {

    public MoveMethodTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "move_method";
    }

    @Override
    public String getDescription() {
        return """
            Move an instance method onto the type of one of its parameters or
            fields (JDT "Move Instance Method"), updating every call site to
            invoke it on the new receiver.

            USAGE:
              move_method(filePath="src/main/java/com/example/Owner.java",
                          line=12, column=17, target="c")

            Inputs:
            - filePath / line / column — position inside the method to move
              (zero-based line/column).
            - target — name of the parameter or field whose type receives the
              method. Optional when the method has exactly one possible target;
              when there are several, the call is rejected with the list of
              options.
            - keepDelegate (default false) — leave a forwarding method on the
              original type instead of removing it and inlining the call sites.

            The target's type must be a source class you can add a method to.
            No valid target, or a conflict on the destination, →
            REFACTORING_FAILED with no files modified.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the method."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line number inside the method."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column number on the line."));
        properties.put("target", Map.of("type", "string",
            "description", "Name of the parameter/field whose type receives the method "
                + "(optional when there is exactly one possible target)."));
        properties.put("keepDelegate", Map.of("type", "boolean",
            "description", "Leave a forwarding method on the original type (default false)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String targetName = getStringParam(arguments, "target");
        boolean keepDelegate = getBooleanParam(arguments, "keepDelegate", false);

        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IMethod method)) {
                return ToolResponse.invalidParameter("position",
                    "Position does not resolve to a method; got "
                        + (element == null ? "null" : element.getClass().getSimpleName()));
            }

            CodeGenerationSettings settings = new CodeGenerationSettings();
            MoveInstanceMethodProcessor processor = new MoveInstanceMethodProcessor(method, settings);
            ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);

            // Initial conditions parse the method and compute the possible targets.
            RefactoringStatus initial = refactoring.checkInitialConditions(new NullProgressMonitor());
            if (initial.hasFatalError()) {
                return ToolResponse.invalidParameter("move_method", formatStatus(initial));
            }

            IVariableBinding[] targets = processor.getPossibleTargets();
            if (targets == null || targets.length == 0) {
                return ToolResponse.invalidParameter("target",
                    "no valid move target — the method has no parameter or field of a "
                        + "type that can receive it (the destination must be a source class)");
            }

            IVariableBinding chosen = selectTarget(targets, targetName);
            if (chosen == null) {
                String options = Arrays.stream(targets).map(IVariableBinding::getName)
                    .collect(Collectors.joining(", "));
                if (targetName == null || targetName.isBlank()) {
                    return ToolResponse.invalidParameter("target",
                        "several possible targets — specify one of: " + options);
                }
                return ToolResponse.invalidParameter("target",
                    "target '" + targetName + "' is not a possible target; choose one of: " + options);
            }
            processor.setTarget(chosen);
            // Clean move by default: inline the call sites onto the new receiver
            // and remove the leftover forwarding method from the original type.
            processor.setInlineDelegator(!keepDelegate);
            processor.setRemoveDelegator(!keepDelegate);

            return runPreCheckedRefactoring(service, refactoring, "move_method", arguments);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Pick the target variable: by name when {@code targetName} is given; else
     * the sole target when there is exactly one. Returns {@code null} when the
     * choice is ambiguous or the name doesn't match — the caller then reports
     * the available options.
     */
    private static IVariableBinding selectTarget(IVariableBinding[] targets, String targetName) {
        if (targetName != null && !targetName.isBlank()) {
            for (IVariableBinding t : targets) {
                if (targetName.equals(t.getName())) {
                    return t;
                }
            }
            return null;
        }
        return targets.length == 1 ? targets[0] : null;
    }
}
