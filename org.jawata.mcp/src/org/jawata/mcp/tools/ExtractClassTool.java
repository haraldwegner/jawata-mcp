package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.refactoring.descriptors.ExtractClassDescriptor;
import org.eclipse.jdt.internal.corext.refactoring.structure.ExtractClassRefactoring;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * <b>Extract Class</b> — the {@code extract(kind=class)} delegate.
 *
 * <p>Moves a group of fields out of an over-full class into a new one, leaving
 * the original holding a reference to it and every access rewritten to go
 * through that reference. Sprint 28d's operation survey ranked this FIRST of the
 * missing operations: 9 of 19 sampled catalogue patterns route through it,
 * because every "introduce a new collaborator" pattern — adapter, facade,
 * command, visitor, repository, service-layer, DAO, abstract-factory,
 * circuit-breaker — needs exactly this move and no engine we shipped could make
 * it.</p>
 *
 * <h2>Delegated, not hand-rolled</h2>
 *
 * <p>JDT ships {@link ExtractClassRefactoring}, and it is the same engine the
 * IDE's Refactor &gt; Extract Class runs. Reimplementing it would mean
 * re-deriving field-access rewriting, constructor threading and reference
 * migration that already exist and are already correct. So this tool's job is
 * narrow: resolve the caret, validate the request, describe it, and refuse
 * clearly when JDT refuses.</p>
 *
 * <h2>Why Move Field is not a separate operation</h2>
 *
 * <p>The survey measured standalone Move Field demand at 2 paths, against 9 as
 * the constituent atom INSIDE this one — and Move Function cannot substitute,
 * because it needs a target that already owns the state, so the field move and
 * the class creation have to be atomic with each other. Move Field therefore
 * ships inside Extract Class rather than beside it.</p>
 *
 * <h2>The headless prerequisite, learned the hard way</h2>
 *
 * <p>{@link HeadlessJdtConfig#ensureInitialized()} must run first, and at Stage 7
 * it had to be TAUGHT to register the {@code NEWTYPE} code template. Without it
 * {@code CodeGeneration.getCompilationUnitContent} returns null and JDT passes
 * that null to {@code Buffer.setContents} — this refactoring then throws inside
 * {@code createChange} <b>after both precondition checks return clean</b>. Every
 * precondition green and nothing to apply.</p>
 */
public class ExtractClassTool extends AbstractApplyingRefactoringTool {

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    public ExtractClassTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "extract_class";
    }

    @Override
    public String getDescription() {
        return "Extract Class — move a group of fields out of a class into a new one, leaving "
            + "the original holding a reference and every access rewritten through it. Uses "
            + "JDT's own Extract Class engine. Delegate of extract(kind=class).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file of the class to extract FROM."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line on that class."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that class."));
        properties.put("newTypeName", Map.of("type", "string",
            "description", "Name for the extracted class."));
        properties.put("fields", Map.of("type", "array", "items", Map.of("type", "string"),
            "description", "Names of the fields to MOVE into the new class. Required: the "
                + "operation's whole point is which state travels together, and guessing that "
                + "is a design decision no default can make."));
        properties.put("fieldName", Map.of("type", "string",
            "description", "Optional: name of the reference field the original class keeps. "
                + "Defaults to the new type's name, first letter lowercased."));
        properties.put("createTopLevel", Map.of("type", "boolean",
            "description", "Optional, default true: create the extracted class as its own "
                + "top-level file. False nests it inside the original."));
        properties.put("createGetterSetter", Map.of("type", "boolean",
            "description", "Optional, default true: generate accessors on the extracted class "
                + "rather than exposing its fields."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "newTypeName", "fields"));
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
        String newTypeName = getStringParam(arguments, "newTypeName");
        if (newTypeName == null || !isIdentifier(newTypeName)) {
            return Preparation.fail(ToolResponse.invalidParameter("newTypeName",
                "A valid Java type name is required."));
        }
        List<String> wanted = stringArray(arguments, "fields");
        if (wanted.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("fields",
                "Name at least one field to move. WHICH state travels together is the design "
                    + "decision this operation exists to carry out; there is no safe default."));
        }

        IType caretType = service.getTypeAtPosition(java.nio.file.Path.of(filePath), line, column);
        if (caretType == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No type at " + line + ":" + column + "."));
        }
        ICompilationUnit cu = caretType.getCompilationUnit();
        if (cu == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type", "Source not available."));
        }
        if (caretType.isInterface() || caretType.isAnnotation()) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                "Caret must be on a class or enum — an interface has no instance state to move."));
        }

        // Every named field must EXIST on the caret type. A silently-ignored name
        // would extract a smaller class than asked for and report success.
        Set<String> present = new LinkedHashSet<>();
        for (IField f : caretType.getFields()) {
            present.add(f.getElementName());
        }
        List<String> missing = new ArrayList<>();
        for (String w : wanted) {
            if (!present.contains(w)) {
                missing.add(w);
            }
        }
        if (!missing.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("fields",
                "Not a field of " + caretType.getElementName() + ": " + missing
                    + ". Declared fields: " + present));
        }

        HeadlessJdtConfig.ensureInitialized();

        String refName = getStringParam(arguments, "fieldName");
        if (refName == null || refName.isBlank()) {
            refName = Character.toLowerCase(newTypeName.charAt(0)) + newTypeName.substring(1);
        }
        boolean topLevel = getBooleanParam(arguments, "createTopLevel", true);
        boolean accessors = getBooleanParam(arguments, "createGetterSetter", true);

        ExtractClassDescriptor descriptor = new ExtractClassDescriptor();
        descriptor.setType(caretType);
        descriptor.setClassName(newTypeName);
        descriptor.setPackage(caretType.getPackageFragment().getElementName());
        descriptor.setCreateTopLevel(topLevel);
        descriptor.setFieldName(refName);
        descriptor.setCreateGetterSetter(accessors);

        // Field instances are not constructible (private ctor): they come from the
        // descriptor's own reader, and the ones to move are FLAGGED.
        ExtractClassDescriptor.Field[] all = ExtractClassDescriptor.getFields(caretType);
        Set<String> wantedSet = new LinkedHashSet<>(wanted);
        for (ExtractClassDescriptor.Field f : all) {
            f.setCreateField(wantedSet.contains(f.getFieldName()));
        }
        descriptor.setFields(all);

        RefactoringStatus descriptorStatus = descriptor.validateDescriptor();
        if (descriptorStatus.hasFatalError()) {
            return Preparation.fail(ToolResponse.error(
                "EXTRACT_REFUSED",
                "extract_class refused: "
                    + descriptorStatus.getMessageMatchingSeverity(RefactoringStatus.FATAL),
                "JDT rejected the request before touching anything — usually a name that "
                    + "already exists in the package or the type. No files were modified."));
        }

        CheckedChange checked =
            engine.propose(new ExtractClassRefactoring(descriptor), "extract class " + newTypeName);
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error(
                "EXTRACT_REFUSED",
                "extract_class refused: " + checked.messages(),
                "JDT's Extract Class engine rejected it — a precondition failed (a name "
                    + "collision, or a field reference it cannot preserve). No files were "
                    + "modified."));
        }

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("newClass", newTypeName);
        extras.put("package", caretType.getPackageFragment().getElementName());
        extras.put("extractedFrom", caretType.getElementName());
        extras.put("movedFields", wanted);
        extras.put("referenceField", refName);
        extras.put("createTopLevel", topLevel);
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }
        String summary = "extract class " + newTypeName + " from " + caretType.getElementName()
            + "; moved " + wanted;
        return Preparation.of(checked.change(), summary, extras);
    }

    private List<String> stringArray(JsonNode arguments, String name) {
        List<String> out = new ArrayList<>();
        JsonNode node = arguments == null ? null : arguments.get(name);
        if (node != null && node.isArray()) {
            node.forEach(n -> {
                String s = n.asText("");
                if (!s.isBlank()) {
                    out.add(s);
                }
            });
        }
        return out;
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
}
