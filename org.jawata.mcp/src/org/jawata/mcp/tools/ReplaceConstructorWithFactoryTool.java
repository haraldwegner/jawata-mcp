package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.internal.corext.refactoring.code.IntroduceFactoryRefactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * <b>Replace Constructor with Factory Method</b> — the
 * {@code refactor_to_pattern(kind=replace_constructor_with_factory)} delegate.
 *
 * <p>Rewrites every {@code new X(...)} call site to a factory call and adds the
 * factory method. Sprint 28d's operation survey ranked this THIRD, and named it as
 * an operation the sprint's own gap table had missed entirely: four catalogue
 * patterns route through it — abstract-factory, factory-method, builder,
 * null-object — and <b>the whole creational family is blocked without it</b>,
 * because {@code change_method_signature(retargetCallsTo)} rewrites METHOD call
 * sites and cannot touch a constructor call.</p>
 *
 * <h2>Delegated, not hand-rolled</h2>
 *
 * <p>JDT ships {@link IntroduceFactoryRefactoring} — the engine behind the IDE's
 * Refactor &gt; Introduce Factory — and its private {@code replaceConstructorCalls}
 * is exactly the atom the survey found missing. Reimplementing it would mean
 * re-deriving constructor-call search, type-argument copying, varargs handling and
 * javadoc reference rewriting that already exist and are already correct. This
 * tool's job is narrow: resolve the caret to a constructor, validate the request,
 * drive the engine, and refuse clearly when the engine refuses.</p>
 *
 * <h2>{@code protectConstructor} is the DONE-DEFINITION, not a convenience</h2>
 *
 * <p>An operation that adds a factory while leaving the constructor public has not
 * finished: the new path exists and the old habit survives, and the two disagree the
 * first time someone forgets. So this tool defaults {@code protectConstructor} to
 * TRUE — the constructor becomes private and a caller <b>cannot</b> bypass the
 * factory. That is the architect seat's standing rule stated as behaviour rather
 * than as advice: an encapsulation refactor is done only when the old path is
 * IMPOSSIBLE, and the check is a query rather than a reading.</p>
 *
 * <p>It is settable, because the engine itself may say no —
 * {@code canProtectConstructor()} is false where a call site lives outside the
 * constructor's own compilation unit in a way JDT will not narrow. When that
 * happens this tool reports it rather than silently shipping the weaker outcome.</p>
 */
public class ReplaceConstructorWithFactoryTool extends AbstractApplyingRefactoringTool {

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    public ReplaceConstructorWithFactoryTool(
        Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "replace_constructor_with_factory";
    }

    @Override
    public String getDescription() {
        return """
            Replace Constructor with Factory Method — rewrite every `new X(...)` call
            site to a factory call, and add the factory.

            The creational family (abstract-factory, factory-method, builder,
            null-object) is blocked without this: change_method_signature's
            retargetCallsTo rewrites METHOD calls and cannot touch a constructor call.

            Needs: filePath + line/column on the CONSTRUCTOR, and factoryMethodName.
            Optional: factoryClass (a fully-qualified type to host the factory;
            default is the constructor's own class), protectConstructor (default TRUE
            — makes the constructor private so callers cannot bypass the factory).
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the constructor."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the CONSTRUCTOR declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("factoryMethodName", Map.of("type", "string",
            "description", "Name for the generated factory method, e.g. 'create' or "
                + "'newInstance'. No default: the name is the call site every reader "
                + "will see, and it is the caller's decision."));
        properties.put("factoryClass", Map.of("type", "string",
            "description", "Optional fully-qualified type to HOST the factory method. "
                + "Default: the constructor's own class."));
        properties.put("protectConstructor", Map.of("type", "boolean",
            "description", "Optional, default TRUE: make the constructor private so "
                + "callers cannot bypass the factory. Setting it false leaves the old "
                + "path reachable — the operation then adds a factory rather than "
                + "replacing the constructor."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "factoryMethodName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required."));
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column",
                "Must be >= 0 (zero-based)."));
        }
        String factoryMethodName = getStringParam(arguments, "factoryMethodName");
        if (factoryMethodName == null || !isIdentifier(factoryMethodName)) {
            return Preparation.fail(ToolResponse.invalidParameter("factoryMethodName",
                "A valid Java method name is required. There is no default: the factory's "
                    + "name is what every call site will read, and naming it is the "
                    + "caller's decision, not this tool's."));
        }

        Path source = Path.of(filePath);
        IType caretType = service.getTypeAtPosition(source, line, column);
        if (caretType == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No type at " + line + ":" + column + "."));
        }
        ICompilationUnit cu = caretType.getCompilationUnit();
        if (cu == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Source not available."));
        }

        HeadlessJdtConfig.ensureInitialized();

        // The engine takes a SELECTION rather than a descriptor, so the caret must land
        // on the constructor. Resolved to a character offset from the file's own text:
        // the tool's contract is line/column, and converting here keeps that contract
        // while giving JDT what it needs.
        int offset = offsetOf(Files.readString(source), line, column);
        if (offset < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column",
                "Position " + line + ":" + column + " is past the end of " + filePath + "."));
        }

        IntroduceFactoryRefactoring refactoring =
            new IntroduceFactoryRefactoring(cu, offset, 0);

        RefactoringStatus initial = refactoring.checkInitialConditions(
            new org.eclipse.core.runtime.NullProgressMonitor());
        if (initial.hasFatalError()) {
            return Preparation.fail(ToolResponse.error(
                "FACTORY_REFUSED",
                "replace_constructor_with_factory refused: "
                    + initial.getMessageMatchingSeverity(RefactoringStatus.FATAL),
                "The caret must sit on a CONSTRUCTOR declaration or a constructor call. "
                    + "No files were modified."));
        }

        RefactoringStatus named = refactoring.setNewMethodName(factoryMethodName);
        if (named.hasFatalError()) {
            return Preparation.fail(ToolResponse.invalidParameter("factoryMethodName",
                named.getMessageMatchingSeverity(RefactoringStatus.FATAL)));
        }

        String factoryClass = getStringParam(arguments, "factoryClass");
        if (factoryClass != null && !factoryClass.isBlank()) {
            RefactoringStatus hosted = refactoring.setFactoryClass(factoryClass);
            if (hosted.hasFatalError()) {
                return Preparation.fail(ToolResponse.invalidParameter("factoryClass",
                    hosted.getMessageMatchingSeverity(RefactoringStatus.FATAL)));
            }
        }

        // THE DONE-DEFINITION. Default TRUE, and a refusal to honour it is REPORTED
        // rather than silently downgraded: shipping the weaker outcome under the same
        // response shape is how a caller ends up believing the old path is closed when
        // it is open.
        boolean protect = getBooleanParam(arguments, "protectConstructor", true);
        if (protect && !refactoring.canProtectConstructor()) {
            return Preparation.fail(ToolResponse.error(
                "FACTORY_CANNOT_PROTECT",
                "The factory can be introduced, but JDT cannot make this constructor "
                    + "private — so the old path would stay reachable.",
                "Pass protectConstructor=false to accept that outcome deliberately: the "
                    + "operation then ADDS a factory rather than replacing the "
                    + "constructor, and callers can still bypass it. No files modified."));
        }
        refactoring.setProtectConstructor(protect);

        CheckedChange checked = engine.propose(refactoring,
            "replace constructor with factory " + factoryMethodName);
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error(
                "FACTORY_REFUSED",
                "replace_constructor_with_factory refused: " + checked.messages(),
                "JDT declined before modifying anything. No files were modified."));
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("factoryMethod", factoryMethodName);
        extras.put("constructorOf", caretType.getElementName());
        extras.put("factoryClass", factoryClass == null || factoryClass.isBlank()
            ? caretType.getFullyQualifiedName() : factoryClass);
        // Reported explicitly, because it is the difference between "a factory exists"
        // and "the constructor is no longer reachable" — and a caller reading only a
        // success cannot otherwise tell which of the two they got.
        extras.put("constructorProtected", protect);
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }
        String summary = "replace constructor of " + caretType.getElementName()
            + " with factory " + factoryMethodName
            + (protect ? " (constructor made private)" : " (constructor left reachable)");
        return Preparation.of(checked.change(), summary, extras);
    }

    private static boolean isIdentifier(String s) {
        if (s == null || s.isEmpty() || !Character.isJavaIdentifierStart(s.charAt(0))) {
            return false;
        }
        for (int i = 1; i < s.length(); i++) {
            if (!Character.isJavaIdentifierPart(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Zero-based line/column to a character offset, counting the line terminators the
     * file actually uses.
     *
     * <p>Derived from the source rather than assumed, because an offset computed with
     * the wrong terminator length lands the caret somewhere else and JDT then reports
     * a refusal about a node nobody selected — a failure that reads like an engine
     * limitation and is arithmetic.</p>
     *
     * @return the offset, or -1 if the position is past the end of the text
     */
    static int offsetOf(String text, int line, int column) {
        int offset = 0;
        int currentLine = 0;
        while (currentLine < line) {
            int nl = text.indexOf('\n', offset);
            if (nl < 0) {
                return -1;
            }
            offset = nl + 1;
            currentLine++;
        }
        int lineEnd = text.indexOf('\n', offset);
        int limit = lineEnd < 0 ? text.length() : lineEnd;
        if (offset + column > limit) {
            return -1;
        }
        return offset + column;
    }
}
