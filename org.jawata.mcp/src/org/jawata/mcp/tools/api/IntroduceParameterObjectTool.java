package org.jawata.mcp.tools.api;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.refactoring.descriptors.IntroduceParameterObjectDescriptor;
import org.eclipse.jdt.internal.corext.refactoring.structure.IntroduceParameterObjectProcessor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code change_method_signature kind=introduce_parameter_object} — Fowler row 21, Introduce
 * Parameter Object.
 *
 * <p>A group of parameters that travel together through signature after signature is a concept
 * with no name. Every method that carries them declares them again, every caller passes them
 * again, and adding one to the group means editing all of them. The cure is to give the group
 * a class: the methods take one argument, and the thing the arguments were always describing
 * finally exists somewhere it can gain behaviour.</p>
 *
 * <h2>WRAPPED, not written — this is JDT's own engine</h2>
 *
 * <p>{@link IntroduceParameterObjectProcessor} is what the IDE runs behind Refactor → Introduce
 * Parameter Object. It creates the class, moves each parameter to a field, rewrites the method
 * body's uses, and rewrites every call site to construct the object — across files. Writing
 * that here would be re-deriving a well-tested engine, and the plan's Stage 1 lists this row as
 * a wrap for exactly that reason.</p>
 *
 * <h2>{@code className} is REQUIRED, and it has no default on purpose</h2>
 *
 * <p>JDT will invent {@code <MethodName>Parameter} if asked to. Naming the concept IS the
 * refactoring — a group of arguments becomes a class because somebody recognised what they
 * are together — and a generated name delivers the mechanics while withholding the whole
 * point. Row 65 took the same decision for the same reason, and {@code compose_method} takes a
 * name per section on the same ground.</p>
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li><b>Fewer than two parameters.</b> One parameter is not a group; wrapping it produces a
 *       class whose only job is to hold what the caller already had, and Fowler's row is about
 *       the parameters that repeat TOGETHER.</li>
 *   <li><b>Anything JDT's own preconditions reject</b> — a name that collides, a parameter
 *       whose type is not visible where the class would live. The engine's message is returned
 *       as it stands, because a second check invented here could only say something vaguer.</li>
 * </ul>
 */
public class IntroduceParameterObjectTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a method. */
        public static final String NOT_A_METHOD = "NOT_A_METHOD";
        /** Fewer than two parameters, so there is no group to name. */
        public static final String NOT_A_PARAMETER_GROUP = "NOT_A_PARAMETER_GROUP";
        /** {@code className} was absent, and it has no default by design. */
        public static final String CLASS_NAME_REQUIRED = "CLASS_NAME_REQUIRED";

        private Refusal() {
        }
    }

    public IntroduceParameterObjectTool(Supplier<IJdtService> serviceSupplier,
                                        RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "introduce_parameter_object";
    }

    @Override
    public String getName() {
        return "introduce_parameter_object";
    }

    @Override
    public String getDescription() {
        return """
            Introduce Parameter Object — give a group of parameters that travel together a
            CLASS, so the methods take one argument and the concept they describe exists.
            Point at the METHOD — position, or symbol=pkg.Type#method. className is REQUIRED
            and has no default: naming the concept is the refactoring, and a generated name
            would deliver the mechanics and withhold the point. Wraps JDT's own engine, so
            the class, the body's uses and every call site across files are rewritten
            together. Optional: parameterName, topLevel, getters, setters. Refuses a method
            with fewer than two parameters — one parameter is not a group.""";
    }

    /** Structural: the signature changes and every call site with it. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the method."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the method's declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("symbol", FqnTarget.symbolSchemaProperty("method whose parameters group"));
        properties.put("className", Map.of("type", "string",
            "description", "Name for the new parameter class. REQUIRED — naming the concept "
                + "is the refactoring, so there is no default."));
        properties.put("parameterName", Map.of("type", "string",
            "description", "Name of the single parameter that replaces the group "
                + "(optional; JDT's default is the class name, first letter lowercased)."));
        properties.put("topLevel", Map.of("type", "boolean",
            "description", "Create the class as its own top-level file (default true); "
                + "false nests it inside the declaring type."));
        properties.put("getters", Map.of("type", "boolean",
            "description", "Generate a getter per field (default true)."));
        properties.put("setters", Map.of("type", "boolean",
            "description", "Generate a setter per field (default false) — a parameter object "
                + "is usually read-only."));
        schema.put("properties", properties);
        schema.put("required", List.of());
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        java.util.Optional<ToolResponse> nameForm =
            FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return Preparation.fail(nameForm.get());
        }

        String className = getStringParam(arguments, "className");
        if (className == null || className.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("className",
                "className is required and has no default: the new class's NAME is what turns"
                    + " a group of arguments into a concept, and a generated one would do the"
                    + " mechanics and withhold the point.",
                Refusal.CLASS_NAME_REQUIRED));
        }

        String filePath = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath",
                "filePath is required (or name the method with symbol=pkg.Type#method)"));
        }

        IJavaElement element = service.getElementAtPosition(Path.of(filePath), line, column);
        if (!(element instanceof IMethod method)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a method.", Refusal.NOT_A_METHOD));
        }

        int parameterCount = method.getNumberOfParameters();
        if (parameterCount < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' takes " + parameterCount + " parameter(s),"
                    + " and a parameter object names a group that TRAVELS TOGETHER. Wrapping a"
                    + " single argument produces a class that holds what the caller already"
                    + " had.",
                Refusal.NOT_A_PARAMETER_GROUP));
        }

        HeadlessJdtConfig.ensureInitialized();

        IntroduceParameterObjectDescriptor descriptor = new IntroduceParameterObjectDescriptor();
        descriptor.setMethod(method);
        descriptor.setClassName(className);
        descriptor.setParameters(IntroduceParameterObjectDescriptor.createParameters(method));
        String parameterName = getStringParam(arguments, "parameterName");
        if (parameterName != null && !parameterName.isBlank()) {
            descriptor.setParameterName(parameterName);
        }
        descriptor.setTopLevel(getBooleanParam(arguments, "topLevel", true));
        descriptor.setGetters(getBooleanParam(arguments, "getters", true));
        // A parameter object is usually read-only: it exists so the group can be passed as one
        // value, and a setter per field invites it to be changed after construction — which is
        // the state row 37 removes one door over.
        descriptor.setSetters(getBooleanParam(arguments, "setters", false));

        IntroduceParameterObjectProcessor processor =
            new IntroduceParameterObjectProcessor(descriptor);
        ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);
        CheckedChange checked = engine.propose(refactoring,
            "introduce parameter object " + className + " for " + method.getElementName());
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error("REFACTORING_FAILED",
                "introduce_parameter_object refused: " + checked.messages(),
                "JDT's own preconditions declined — a colliding name, or a parameter type not"
                    + " visible where the class would live. Nothing was modified."));
        }

        Change change = checked.change();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("className", className);
        extras.put("method", method.getElementName());
        extras.put("parametersGrouped", parameterCount);
        extras.put("topLevel", getBooleanParam(arguments, "topLevel", true));
        extras.put("filesAffected", ChangeEngine.affectedFilePaths(change, service).size());
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }

        String summary = "introduce parameter object " + className + " for "
            + method.getElementName() + " (" + parameterCount + " parameters grouped)";
        return Preparation.of(change, summary, extras);
    }
}
