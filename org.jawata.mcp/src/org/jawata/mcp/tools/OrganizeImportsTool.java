package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Organize imports in a Java file.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}. A file whose imports are already
 * organized short-circuits with {@code hasChanges: false} and no edit.</p>
 *
 * <p>Sprint 25 (spec D1a item 3): the work is done by JDT's own
 * {@link OrganizeImportsOperation} — a PUBLIC manipulation API, the same engine
 * behind the IDE's Source → Organize Imports and the jdt.ls language server.
 * The original implementation removed-and-sorted only — and its unused-import
 * detection had a proven defect: the reference walker visited the import
 * declarations themselves, so every import marked itself "used" and nothing was
 * ever removed. The JDT operation removes genuinely unused imports (including
 * unused static imports) and honors the project's configured import order and
 * on-demand thresholds.</p>
 *
 * <p>KNOWN LIMIT (v2.14.1, filed): the engine's ADD-missing-imports half is not
 * usable headless yet — a file that needs an import added fails loudly with an
 * NPE deep in JDT's headless import rewrite ({@code StringTokenizer(null)} on
 * an unlocated preference; pre-existing, proven independent of our changes).
 * Nothing is corrupted — the call errors before any edit. Ranked follow-up;
 * record: {@code test-resources/parity/organize-imports/DIVERGENCES.md}.</p>
 */
public class OrganizeImportsTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(OrganizeImportsTool.class);

    public OrganizeImportsTool(Supplier<IJdtService> serviceSupplier,
                               RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "organize_imports";
    }

    @Override
    public String getDescription() {
        return """
            Organize imports in a Java file (JDT's own Organize Imports engine).

            Removes unused imports (including unused static imports) and sorts
            per the project's import-order configuration.
            KNOWN LIMIT (filed): adding MISSING imports is not available yet — a
            file that needs an import added fails loudly (nothing is modified);
            use quick_fix(action=suggest_imports) for adds until this is fixed.
            Applies the change directly (default) and returns
            { filesModified, diff, undoChangeId, summary }; when imports are
            already organized, returns hasChanges: false without touching the
            file. Pass auto_apply: false to stage instead.

            USAGE: organize_imports(filePath="path/to/File.java")
            OUTPUT: Modified file + unified diff + undo handle

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
                "description", "Path to source file"
            )
        ));
        schema.put("required", List.of("filePath"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }

        HeadlessJdtConfig.ensureInitialized();

        // Parse with bindings — the operation resolves references to decide
        // used/unused and to find candidates for missing imports.
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        int totalImports = ast.imports().size();

        // Headless ambiguity policy: never guess between candidates — skip them
        // (returning an empty choice), exactly what jdt.ls does without a UI.
        OrganizeImportsOperation.IChooseImportQuery skipAmbiguous =
            (openChoices, ranges) -> new TypeNameMatch[0];
        OrganizeImportsOperation operation = new OrganizeImportsOperation(
            cu, ast, /* ignoreLowerCaseNames */ true, /* save */ false,
            /* allowSyntaxErrors */ true, skipAmbiguous);

        TextEdit edit = operation.createTextEdit(new NullProgressMonitor());
        int importsAdded = operation.getNumberOfImportsAdded();
        int importsRemoved = operation.getNumberOfImportsRemoved();
        boolean hasChanges = edit != null
            && (edit.hasChildren() || edit.getLength() > 0 || importsAdded > 0 || importsRemoved > 0);

        if (!hasChanges) {
            // Already organized (or nothing to do) — success no-op, same shape as before.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", getName());
            data.put("applied", false);
            data.put("hasChanges", false);
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("totalImports", totalImports);
            data.put("importsAdded", 0);
            data.put("importsRemoved", 0);
            return Preparation.fail(ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "get_diagnostics to check for remaining issues"))
                .build()));
        }

        // The organized import block, for the response: apply the edit to a copy
        // of the source and collect the import lines.
        String organizedImportBlock = "";
        try {
            Document preview = new Document(cu.getSource());
            edit.copy().apply(preview);
            List<String> importLines = new ArrayList<>();
            for (String lineText : preview.get().split("\n", -1)) {
                String trimmed = lineText.trim();
                if (trimmed.startsWith("import ")) {
                    importLines.add(trimmed);
                }
            }
            organizedImportBlock = String.join("\n", importLines);
        } catch (Exception e) {
            log.debug("organized-import preview failed: {}", e.getMessage());
        }

        IFile file = (IFile) cu.getResource();
        List<TextEdit> edits = new ArrayList<>();
        edits.add(edit);
        Change change = ChangeEngine.fromFileEdits("organize imports", Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("hasChanges", true);
        extras.put("totalImports", totalImports);
        extras.put("importsAdded", importsAdded);
        extras.put("importsRemoved", importsRemoved);
        extras.put("organizedImportBlock", organizedImportBlock);

        String summary = "organize imports (" + importsAdded + " added, "
            + importsRemoved + " removed)";
        log.debug("organize_imports via JDT OrganizeImportsOperation: {}", summary);
        return Preparation.of(change, summary, extras);
    }
}
