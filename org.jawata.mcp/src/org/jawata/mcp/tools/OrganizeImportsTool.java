package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.manipulation.OrganizeImportsOperation;
import org.eclipse.jdt.core.search.TypeNameMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.core.LoadedProject;
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
 * Organize imports in a Java file, a project, or the whole workspace.
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
 * <h2>Sprint 28d-rescue (stage 1): {@code optimize_imports_workspace} folded in here</h2>
 *
 * <p>The two tools did one job at two scopes, so the scope becomes a parameter. What
 * makes this more than a registration change is that the folded tool carried its OWN
 * import engine — a second hand-rolled walker, sorting into its own fixed
 * java/javax/other/static order rather than the project's configured one, and keeping
 * every static and on-demand import because it did not track their use. Two engines for
 * one job disagree, and this pair had already disagreed once: the defect described
 * above is the SAME reimplementation, and the folded copy carried a comment explaining
 * how it avoided that specific bug. Folding the scope in while leaving the engine
 * behind would have kept both.</p>
 *
 * <p>So a project or workspace run is now the same JDT operation, per file, gathered
 * into ONE change with ONE undo handle. That is also a capability the folded tool never
 * had: it wrote each file in place through a working copy, so a sweep across 800 files
 * had no way back. A file whose edit cannot be computed is SKIPPED and named in the
 * response rather than failing the sweep — a broad run must not be all-or-nothing on
 * one bad file, and a skip nobody can see is the silent-degradation class this product
 * exists to refuse.</p>
 *
 * <p>KNOWN LIMIT (v2.14.1, filed): the engine's ADD-missing-imports half is not
 * usable headless yet — a file that needs an import added fails loudly with an
 * NPE deep in JDT's headless import rewrite ({@code StringTokenizer(null)} on
 * an unlocated preference; pre-existing, proven independent of our changes).
 * Nothing is corrupted — the call errors before any edit. Ranked follow-up;
 * record: {@code test-resources/parity/organize-imports/DIVERGENCES.md}. At file
 * scope that is the loud failure; at project or workspace scope it is one of the
 * skips described above.</p>
 */
public class OrganizeImportsTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(OrganizeImportsTool.class);

    private static final List<String> SCOPES = List.of("file", "project", "workspace");

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
            Organize imports (JDT's own Organize Imports engine) in one file, one
            project, or the whole workspace.

            Removes unused imports (including unused static imports) and sorts
            per the project's import-order configuration.

            USAGE:
              organize_imports(filePath="path/to/File.java")     — the default, scope=file
              organize_imports(scope="project", projectKey="core")
              organize_imports(scope="workspace")

            Inputs:
            - scope — "file" (default) | "project" | "workspace".
            - filePath — required for scope=file; unused otherwise.
            - projectKey — for scope=project; the first loaded project when omitted.

            A project or workspace run is ONE change with ONE undo handle, not a
            per-file sweep. A file whose edit cannot be computed is skipped and NAMED
            in `skippedFiles` with its reason — a broad run is never all-or-nothing on
            one bad file, and never silently short.

            KNOWN LIMIT (filed): adding MISSING imports is not available yet — a
            file that needs an import added fails loudly (nothing is modified);
            use quick_fix(action=suggest_imports) for adds until this is fixed.
            Applies the change directly (default) and returns
            { filesModified, diff, undoChangeId, summary }; when imports are
            already organized, returns hasChanges: false without touching the
            file. Pass auto_apply: false to stage instead.

            OUTPUT: Modified file(s) + unified diff + undo handle

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("scope", Map.of(
            "type", "string",
            "enum", SCOPES,
            "description", "file (default) | project | workspace."));
        properties.put("filePath", Map.of(
            "type", "string",
            "description", "Path to source file. Required for scope=file; unused otherwise."));
        schema.put("properties", properties);
        // NO `required` list: filePath is required for one scope of three, and a JSON
        // schema cannot say that. The check lives in prepareChange, where it can name
        // the scope it is talking about.
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String scope = getStringParam(arguments, "scope", "file");
        if (!SCOPES.contains(scope)) {
            return Preparation.fail(ToolResponse.invalidParameter("scope",
                "Unknown scope '" + scope + "'. Allowed: " + SCOPES));
        }
        return "file".equals(scope)
            ? prepareOneFile(service, arguments)
            : prepareSweep(service, arguments, scope);
    }

    // ---------------------------------------------------------------- scope=file

    private Preparation prepareOneFile(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath",
                "Required for scope=file (the default). For a whole project or the whole"
                    + " workspace pass scope=project or scope=workspace instead, and no"
                    + " filePath."));
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }

        HeadlessJdtConfig.ensureInitialized();
        Organized organized = organize(cu);

        if (!organized.hasChanges()) {
            // Already organized (or nothing to do) — success no-op, same shape as before.
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", getName());
            data.put("scope", "file");
            data.put("applied", false);
            data.put("hasChanges", false);
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("totalImports", organized.totalImports());
            data.put("importsAdded", 0);
            data.put("importsRemoved", 0);
            return Preparation.fail(ToolResponse.success(data, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "get_diagnostics to check for remaining issues"))
                .build()));
        }

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits("organize imports",
            Map.of(file, List.of(organized.edit())));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("scope", "file");
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("hasChanges", true);
        extras.put("totalImports", organized.totalImports());
        extras.put("importsAdded", organized.added());
        extras.put("importsRemoved", organized.removed());
        extras.put("organizedImportBlock", previewImportBlock(cu, organized.edit()));

        String summary = "organize imports (" + organized.added() + " added, "
            + organized.removed() + " removed)";
        log.debug("organize_imports via JDT OrganizeImportsOperation: {}", summary);
        return Preparation.of(change, summary, extras);
    }

    // ------------------------------------------------- scope=project | workspace

    private Preparation prepareSweep(IJdtService service, JsonNode arguments, String scope)
            throws Exception {
        List<ICompilationUnit> targets = new ArrayList<>();
        if ("project".equals(scope)) {
            LoadedProject project = pickProject(service, arguments);
            if (project == null) {
                return Preparation.fail(ToolResponse.invalidParameter("projectKey",
                    "No such project, and no project is loaded to fall back to."
                        + " Use list_projects."));
            }
            targets.addAll(compilationUnitsOf(project.javaProject()));
        } else {
            for (LoadedProject project : service.allProjects()) {
                targets.addAll(compilationUnitsOf(project.javaProject()));
            }
        }

        HeadlessJdtConfig.ensureInitialized();

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        List<Map<String, Object>> modified = new ArrayList<>();
        List<Map<String, Object>> skipped = new ArrayList<>();
        int added = 0;
        int removed = 0;

        for (ICompilationUnit cu : targets) {
            String where = pathOf(service, cu);
            try {
                Organized organized = organize(cu);
                if (!organized.hasChanges()) {
                    continue;
                }
                if (!(cu.getResource() instanceof IFile file)) {
                    continue;
                }
                edits.put(file, List.of(organized.edit()));
                added += organized.added();
                removed += organized.removed();
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filePath", where);
                entry.put("importsAdded", organized.added());
                entry.put("importsRemoved", organized.removed());
                modified.add(entry);
            } catch (Exception | LinkageError e) {
                // NAMED, not swallowed. The known headless add-imports defect lands
                // here, and so would anything else one file can do; a sweep that
                // silently produced a short list would be indistinguishable from a
                // sweep that found nothing wrong.
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("filePath", where);
                entry.put("reason", e.toString());
                skipped.add(entry);
                log.debug("organize_imports skipped {}: {}", where, e.toString());
            }
        }

        Map<String, Object> counts = new LinkedHashMap<>();
        counts.put("scope", scope);
        counts.put("filesProcessed", targets.size());
        counts.put("filesChanged", modified.size());
        counts.put("importsAdded", added);
        counts.put("importsRemoved", removed);
        counts.put("changedFiles", modified);
        counts.put("skippedFiles", skipped);

        if (edits.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>(counts);
            data.put("operation", getName());
            data.put("applied", false);
            data.put("hasChanges", false);
            return Preparation.fail(ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(0)
                .returnedCount(0)
                .build()));
        }

        Change change = ChangeEngine.fromFileEdits("organize imports (" + scope + ")", edits);
        Map<String, Object> extras = new LinkedHashMap<>(counts);
        extras.put("hasChanges", true);
        String summary = "organize imports across " + modified.size() + " file(s) ("
            + added + " added, " + removed + " removed"
            + (skipped.isEmpty() ? "" : ", " + skipped.size() + " skipped") + ")";
        return Preparation.of(change, summary, extras);
    }

    // ------------------------------------------------------------------ the engine

    /** What one file's organize produced. A null edit means nothing to do. */
    private record Organized(TextEdit edit, int added, int removed, int totalImports) {
        boolean hasChanges() {
            return edit != null
                && (edit.hasChildren() || edit.getLength() > 0 || added > 0 || removed > 0);
        }
    }

    /**
     * ONE file through JDT's own operation — the single place the engine is named, so
     * every scope runs the same one. That is the point of the fold.
     */
    private static Organized organize(ICompilationUnit cu) throws Exception {
        // Parse with bindings — the operation resolves references to decide
        // used/unused and to find candidates for missing imports.
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        // Headless ambiguity policy: never guess between candidates — skip them
        // (returning an empty choice), exactly what jdt.ls does without a UI.
        OrganizeImportsOperation.IChooseImportQuery skipAmbiguous =
            (openChoices, ranges) -> new TypeNameMatch[0];
        OrganizeImportsOperation operation = new OrganizeImportsOperation(
            cu, ast, /* ignoreLowerCaseNames */ true, /* save */ false,
            /* allowSyntaxErrors */ true, skipAmbiguous);

        TextEdit edit = operation.createTextEdit(new NullProgressMonitor());
        return new Organized(edit, operation.getNumberOfImportsAdded(),
            operation.getNumberOfImportsRemoved(), ast.imports().size());
    }

    /** The organized import block, for the response: apply the edit to a copy. */
    private static String previewImportBlock(ICompilationUnit cu, TextEdit edit) {
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
            return String.join("\n", importLines);
        } catch (Exception e) {
            log.debug("organized-import preview failed: {}", e.getMessage());
            return "";
        }
    }

    private static String pathOf(IJdtService service, ICompilationUnit cu) {
        try {
            return service.getPathUtils().formatPath(
                cu.getResource().getLocation().toFile().toPath());
        } catch (Exception e) {
            return cu.getElementName();
        }
    }

    private static List<ICompilationUnit> compilationUnitsOf(IJavaProject project)
            throws Exception {
        List<ICompilationUnit> out = new ArrayList<>();
        for (IPackageFragmentRoot root : project.getPackageFragmentRoots()) {
            if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                continue;
            }
            for (IJavaElement child : root.getChildren()) {
                if (child instanceof IPackageFragment pkg) {
                    out.addAll(List.of(pkg.getCompilationUnits()));
                }
            }
        }
        return out;
    }

    private LoadedProject pickProject(IJdtService service, JsonNode arguments) {
        String projectKey = getStringParam(arguments, "projectKey");
        if (projectKey != null && !projectKey.isBlank()) {
            return service.getProject(projectKey).orElse(null);
        }
        return service.allProjects().stream().findFirst().orElse(null);
    }
}
