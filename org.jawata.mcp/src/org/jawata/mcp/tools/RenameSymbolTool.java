package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.ILocalVariable;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.ITypeParameter;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.internal.corext.refactoring.rename.JavaRenameProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.MethodChecks;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameEnumConstProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameFieldProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameLocalVariableProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameNonVirtualMethodProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeParameterProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameTypeProcessor;
import org.eclipse.jdt.internal.corext.refactoring.rename.RenameVirtualMethodProcessor;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextChange;
import org.eclipse.ltk.core.refactoring.participants.RenameRefactoring;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Rename a symbol across the project.
 *
 * <p>Sprint 14b: auto-applies by default (contract base
 * {@link AbstractApplyingRefactoringTool}) — returns
 * {@code { filesModified, diff, undoChangeId, summary }}; with
 * {@code auto_apply: false} stages the change and returns
 * {@code { changeId, diff, summary }}.</p>
 *
 * <p>Sprint 25 (D1a): the rename transformation is computed by JDT's own
 * rename processors (the engines behind the IDE's Refactor → Rename), driven
 * through the {@link RefactoringEngine} seam. The original implementation was a
 * hand-rolled AST walker that matched occurrences by binding key — it worked,
 * but re-derived reference resolution the JDT engine already does correctly,
 * and did not run the engine's precondition analysis (name collisions,
 * overridden-method ripples, shadowing). The JDT engine resolves references
 * exhaustively, renames a type's constructors and file with it, and REFUSES a
 * rename its preconditions reject — with its reasons in the response. The
 * v2.12.1 compile-verify gate in the contract base stays wrapped around the
 * applied change.</p>
 */
public class RenameSymbolTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(RenameSymbolTool.class);

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null", "var", "yield",
        "record", "sealed", "permits", "non-sealed"
    );

    public RenameSymbolTool(Supplier<IJdtService> serviceSupplier,
                            RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "rename_symbol";
    }

    @Override
    public String getDescription() {
        return """
            Rename a symbol (variable, method, field, class, etc.) across the project.

            Applies the rename directly (default) and returns
            { filesModified, diff, undoChangeId, summary }. Verify with
            compile_workspace; revert with undo_refactoring(undoChangeId) if needed.
            Pass auto_apply: false to stage instead — returns { changeId, diff } for
            inspect_refactoring / apply_refactoring.

            USAGE: Position on symbol, provide new name
            OUTPUT: Modified files + unified diff + undo handle

            IMPORTANT: Uses ZERO-BASED coordinates.
            The rename runs through JDT's own rename engine: it rewrites a type's
            constructors and renames the type's file with it, and REFUSES a rename
            its preconditions reject (name collision, unsupported target) — the
            refusal names the reason.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file containing the symbol"
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            ),
            "newName", Map.of(
                "type", "string",
                "description", "New name for the symbol"
            ),
            "symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
                "type, method or field to rename (a LOCAL variable has no name form — "
                    + "use the position)")
        ));
        // Sprint 24 (D1): position OR name form; only newName is always required.
        schema.put("required", List.of("newName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String newName = getStringParam(arguments, "newName");

        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0"));
        }

        if (newName == null || newName.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("newName", "Required"));
        }

        if (!isValidJavaIdentifier(newName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("newName", "Not a valid Java identifier"));
        }

        Path path = Path.of(filePath);

        // Same reliable position resolution the other tools use.
        IJavaElement element = service.getElementAtPosition(path, line, column);
        if (element == null) {
            return Preparation.fail(ToolResponse.symbolNotFound("No symbol at position"));
        }

        // A caret on a constructor means "rename the class": JDT renames a type's
        // constructors as part of the type rename, so redirect to the declaring
        // type (the hand-rolled tool special-cased this the other way; JDT makes
        // it free once the target is the type).
        if (element instanceof IMethod method && method.isConstructor()) {
            IType declaring = method.getDeclaringType();
            if (declaring != null) {
                element = declaring;
            }
        }

        String oldName = element.getElementName();
        String symbolKind = getElementKind(element);

        if (oldName.equals(newName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("newName", "Same as current name"));
        }

        JavaRenameProcessor processor = renameProcessorFor(element);
        if (processor == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "Cannot rename a " + element.getClass().getSimpleName() + " at this position"));
        }
        processor.setNewElementName(newName);
        enableReferenceUpdates(processor);

        RenameRefactoring refactoring = new RenameRefactoring(processor);
        CheckedChange checked = engine.propose(refactoring,
            "rename " + oldName + " -> " + newName);
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error(
                "RENAME_REFUSED",
                "rename_symbol refused '" + oldName + "' -> '" + newName + "': "
                    + checked.messages(),
                "JDT's rename engine rejected this rename — a name collision, an "
                    + "unresolvable reference, or an unsupported target. Adjust the new "
                    + "name or the target and retry. No files were modified."));
        }

        Change change = checked.change();

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("oldName", oldName);
        extras.put("newName", newName);
        extras.put("symbolKind", symbolKind);
        extras.put("filesAffected", ChangeEngine.affectedFilePaths(change, service).size());
        extras.put("totalEdits", countTextEdits(change));
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }

        // Report the file rename JDT performs for a primary type. The change
        // ALREADY includes the compilation-unit rename (RenameTypeProcessor renames
        // the CU when the type is the primary type) — this only names it in the
        // response; no extra change is added.
        if (element instanceof IType type
                && type.getCompilationUnit() != null
                && (oldName + ".java").equals(type.getCompilationUnit().getElementName())) {
            extras.put("fileRenamed", oldName + ".java -> " + newName + ".java");
        }

        String summary = "rename " + symbolKind + " '" + oldName + "' -> '" + newName + "'";
        log.debug("rename_symbol via JDT rename processor: {}", summary);
        return Preparation.of(change, summary, extras);
    }

    /** Map a resolved Java element to the JDT rename processor for its kind. */
    private static JavaRenameProcessor renameProcessorFor(IJavaElement element)
            throws JavaModelException {
        if (element instanceof IType type) {
            return new RenameTypeProcessor(type);
        }
        if (element instanceof IField field) {
            return field.isEnumConstant()
                ? new RenameEnumConstProcessor(field)
                : new RenameFieldProcessor(field);
        }
        if (element instanceof IMethod method) {
            // Virtual (rippling) vs non-virtual: JDT's own selection logic, so an
            // overridden/overriding method renames across its whole hierarchy.
            return MethodChecks.isVirtual(method)
                ? new RenameVirtualMethodProcessor(method)
                : new RenameNonVirtualMethodProcessor(method);
        }
        if (element instanceof ILocalVariable local) {
            return new RenameLocalVariableProcessor(local);
        }
        if (element instanceof ITypeParameter typeParam) {
            return new RenameTypeParameterProcessor(typeParam);
        }
        return null;
    }

    /**
     * Turn reference updating ON. The processors expose the setter but do not
     * all default it on; a rename that skipped references is exactly the fault
     * this migration removes, so set it explicitly for every kind.
     */
    private static void enableReferenceUpdates(JavaRenameProcessor processor) {
        if (processor instanceof RenameTypeProcessor p) {
            p.setUpdateReferences(true);
        } else if (processor instanceof RenameFieldProcessor p) {
            p.setUpdateReferences(true);
        } else if (processor instanceof RenameMethodProcessor p) {
            p.setUpdateReferences(true);
        } else if (processor instanceof RenameLocalVariableProcessor p) {
            p.setUpdateReferences(true);
        } else if (processor instanceof RenameTypeParameterProcessor p) {
            p.setUpdateReferences(true);
        }
    }

    /** Count the leaf text edits across a change tree — reported as {@code totalEdits}. */
    private static int countTextEdits(Change change) {
        if (change instanceof CompositeChange composite) {
            int total = 0;
            for (Change child : composite.getChildren()) {
                total += countTextEdits(child);
            }
            return total;
        }
        if (change instanceof TextChange textChange) {
            TextEdit root = textChange.getEdit();
            return root == null ? 0 : countLeafEdits(root);
        }
        return 0;
    }

    private static int countLeafEdits(TextEdit edit) {
        if (!edit.hasChildren()) {
            // A leaf that actually rewrites text (a MultiTextEdit container with
            // no children rewrites nothing).
            return 1;
        }
        int total = 0;
        for (TextEdit child : edit.getChildren()) {
            total += countLeafEdits(child);
        }
        return total;
    }

    private boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return !RESERVED_WORDS.contains(name);
    }

    private String getElementKind(IJavaElement element) {
        if (element instanceof IType type) {
            try {
                if (type.isInterface()) return "Interface";
                if (type.isEnum()) return "Enum";
                if (type.isAnnotation()) return "Annotation";
            } catch (JavaModelException e) {
                log.debug("Error checking type kind: {}", e.getMessage());
            }
            return "Class";
        }
        if (element instanceof IMethod method) {
            try {
                return method.isConstructor() ? "Constructor" : "Method";
            } catch (JavaModelException e) {
                return "Method";
            }
        }
        if (element instanceof IField) return "Field";
        if (element instanceof ILocalVariable) return "LocalVariable";
        if (element instanceof ITypeParameter) return "TypeParameter";
        return "Unknown";
    }
}
