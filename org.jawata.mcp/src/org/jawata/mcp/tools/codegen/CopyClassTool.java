package org.jawata.mcp.tools.codegen;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CreateCompilationUnitChange;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 18 — <b>Copy Class</b> (the cost-tool primitive behind
 * {@code generate(kind=copy_class)}). Clones the top-level type at a caret into a
 * new compilation unit in the <em>same package</em>, renaming the type, its
 * constructors, and every self-reference (binding-checked) to the new name. This
 * is the token-cheap "compiler writes the code" move: the IDE-style Copy/Paste +
 * rename a human does before Extract Superclass, instead of the agent re-authoring
 * a near-duplicate class from scratch.
 *
 * <p>Conservative-or-refuse (v1.5): the source file must contain exactly one
 * top-level {@link TypeDeclaration} (class/interface — not enum/record), the type
 * binding must resolve (so self-references can be renamed safely, not left
 * dangling), and the target file must not already exist. Same-package only;
 * cross-package copy is a later extension. The new file is created atomically and
 * reverts via {@code undo_refactoring} (which deletes it).</p>
 *
 * <p>A delegate of the {@code generate} front door — lives in the {@code codegen}
 * package (like the other generators) so the front door can reach its overridden
 * {@link AbstractTool#executeWithService}. It creates a NEW file rather than
 * replacing one, so it drives {@link ChangeEngine} + the change cache directly
 * (the {@code SourceCommit} full-replace helper does not fit a create).</p>
 */
public class CopyClassTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(CopyClassTool.class);

    private final RefactoringChangeCache changeCache;

    public CopyClassTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    @Override
    public String getName() {
        return "copy_class";
    }

    @Override
    public String getDescription() {
        return "Copy the top-level class at a caret into a new same-package file under a new name "
            + "(type + constructors + self-references renamed). Delegate of generate(kind=copy_class).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file of the class to copy."),
            "line", Map.of("type", "integer", "description", "Zero-based line on the class."),
            "column", Map.of("type", "integer", "description", "Zero-based column on the class."),
            "newTypeName", Map.of("type", "string", "description", "Name for the copied class.")));
        schema.put("required", List.of("filePath", "line", "column", "newTypeName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "filePath");
        if (missing != null) return missing;

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return ToolResponse.invalidParameter("line/column", "Must be >= 0 (zero-based).");
        }
        String newName = getStringParam(arguments, "newTypeName");
        if (newName == null || !isIdentifier(newName)) {
            return ToolResponse.invalidParameter("newTypeName", "A valid Java type name is required.");
        }

        try {
            java.nio.file.Path path = java.nio.file.Path.of(getStringParam(arguments, "filePath"));
            IType type = service.getTypeAtPosition(path, line, column);
            if (type == null) {
                return ToolResponse.invalidParameter("position", "No type at " + line + ":" + column + ".");
            }
            if (newName.equals(type.getElementName())) {
                return ToolResponse.invalidParameter("newTypeName",
                    "New name must differ from the source type '" + type.getElementName() + "'.");
            }
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return ToolResponse.invalidParameter("type", "Source not available (binary or unresolved).");
            }

            CompilationUnit ast = parse(cu);
            if (ast.types().size() != 1) {
                return ToolResponse.invalidParameter("filePath",
                    "copy_class is conservative: the file must contain exactly one top-level type (found "
                        + ast.types().size() + ").");
            }
            if (!(ast.types().get(0) instanceof TypeDeclaration td)
                || !td.getName().getIdentifier().equals(type.getElementName())) {
                return ToolResponse.invalidParameter("type",
                    "Target must be a top-level class or interface (enums/records/annotations not supported).");
            }
            ITypeBinding binding = td.resolveBinding();
            if (binding == null) {
                return ToolResponse.invalidParameter("type",
                    "Could not resolve the type; refusing to copy without bindings (a self-reference could be left dangling).");
            }

            AST a = ast.getAST();
            ASTRewrite rewrite = ASTRewrite.create(a);
            Set<SimpleName> toRename = Collections.newSetFromMap(new IdentityHashMap<>());
            toRename.add(td.getName());
            for (MethodDeclaration m : td.getMethods()) {
                if (m.isConstructor()) {
                    toRename.add(m.getName());
                }
            }
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(SimpleName node) {
                    IBinding b = node.resolveBinding();
                    if (b instanceof ITypeBinding tb && tb.isEqualTo(binding)) {
                        toRename.add(node);
                    }
                    return true;
                }
            });
            for (SimpleName n : toRename) {
                rewrite.replace(n, a.newSimpleName(newName), null);
            }
            Document doc = new Document(cu.getSource());
            TextEdit edits = rewrite.rewriteAST(doc,
                org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(
                    cu, getStringParam(arguments, "indentChar", null)));
            edits.apply(doc);
            String newSource = doc.get();

            IContainer parent = (IContainer) cu.getResource().getParent();
            IFile newFile = parent.getFile(new Path(newName + ".java"));
            if (newFile.exists()) {
                return ToolResponse.invalidParameter("newTypeName",
                    "A file named " + newName + ".java already exists in this package.");
            }
            Change change = new CreateCompilationUnitChange(newFile, newSource);
            String summary = "copy class " + type.getElementName() + " -> " + newName + " (same package)";

            boolean autoApply = getBooleanParam(arguments, "auto_apply", true);
            String diff = ChangeEngine.previewDiff(change, service);
            if (!autoApply) {
                List<String> files = ChangeEngine.affectedFilePaths(change, service);
                String changeId = changeCache.put(
                    RefactoringChangeCache.Kind.STAGED, change, summary, diff, files);
                Map<String, Object> data = new LinkedHashMap<>();
                data.put("operation", "copy_class");
                data.put("applied", false);
                data.put("changeId", changeId);
                data.put("diff", diff);
                data.put("summary", summary);
                data.put("generatedSource", newSource);
                return ToolResponse.success(data, ResponseMeta.builder()
                    .suggestedNextTools(List.of(
                        "apply_refactoring with this changeId to create the file",
                        "inspect_refactoring with this changeId to re-examine the copy"))
                    .build());
            }

            ChangeEngine.ApplyOutcome outcome = ChangeEngine.perform(change, service);
            if (outcome.validationError() != null) {
                return ToolResponse.error("REFACTORING_FAILED",
                    "copy_class failed: " + outcome.validationError(),
                    "No file was created. Check that " + newName + ".java does not already exist.");
            }
            String undoChangeId = null;
            if (outcome.undoChange() != null) {
                undoChangeId = changeCache.put(RefactoringChangeCache.Kind.UNDO, outcome.undoChange(),
                    "undo: " + summary, "", outcome.modifiedFilePaths());
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", "copy_class");
            data.put("applied", true);
            data.put("filesModified", outcome.modifiedFilePaths());
            data.put("diff", diff);
            data.put("undoChangeId", undoChangeId);
            data.put("summary", summary);
            data.put("newType", newName);
            data.put("generatedSource", newSource);
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(outcome.modifiedFilePaths().size())
                .returnedCount(outcome.modifiedFilePaths().size())
                .suggestedNextTools(List.of(
                    "compile_workspace to verify the copy",
                    "extract(kind=superclass) to lift shared members into a common parent",
                    "undo_refactoring with the undoChangeId to remove the copy"))
                .build());
        } catch (Exception e) {
            log.warn("copy_class failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    private static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
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
