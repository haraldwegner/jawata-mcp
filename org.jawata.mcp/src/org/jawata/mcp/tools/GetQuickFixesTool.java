package org.jawata.mcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.core.TypeLookupException;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.ImportCandidates;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Get available quick fixes for a problem at a specific position.
 *
 * Maps IProblem IDs to available fixes like add import, remove unused import,
 * add throws declaration, or surround with try-catch.
 */
public class GetQuickFixesTool extends AbstractTool {

    private static final Logger log = LoggerFactory.getLogger(GetQuickFixesTool.class);

    // Common IProblem IDs we can provide fixes for
    private static final int UNDEFINED_TYPE = 16777218;           // IProblem.UndefinedType
    private static final int UNDEFINED_NAME = 16777219;           // IProblem.UndefinedName
    private static final int UNUSED_IMPORT = 268435844;           // IProblem.UnusedImport
    private static final int UNHANDLED_EXCEPTION = 16777384;      // IProblem.UnhandledException
    private static final int IMPORT_NOT_FOUND = 268435846;        // IProblem.ImportNotFound

    private static final int MOST_IMPORT_FIXES = 10;

    private final ImportCandidates.Search typeSearch;

    public GetQuickFixesTool(Supplier<IJdtService> serviceSupplier) {
        this(serviceSupplier, ImportCandidates.inProject());
    }

    public GetQuickFixesTool(Supplier<IJdtService> serviceSupplier, ImportCandidates.Search typeSearch) {
        super(serviceSupplier);
        this.typeSearch = typeSearch;
    }

    @Override
    public String getName() {
        return "get_quick_fixes";
    }

    @Override
    public String getDescription() {
        return """
            List available fixes for a problem at position.

            USAGE: get_quick_fixes(filePath="...", line=10)
            OUTPUT: List of quick fixes with fixId, label, and category

            Supported fixes:
            - UndefinedType: Suggest imports for unresolved types
            - UnusedImport: Remove unused import
            - UnhandledException: Add throws or surround with try-catch

            IMPORTANT: Uses ZERO-BASED line numbers.

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
            ),
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number (optional, uses whole line if omitted)"
            )
        ));
        schema.put("required", List.of("filePath", "line"));
        return withProjectKey(schema);
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePath = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);

        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }
        if (line < 0) {
            return ToolResponse.invalidParameter("line", "Required and must be >= 0");
        }

        try {
            Path path = Path.of(filePath);
            ICompilationUnit cu = service.getCompilationUnit(path);
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }

            // REL44-26: NOT reconcile — it answers null for a unit no editor has open, which
            // in this server is every unit, and the null used to be reported as problemCount 0.
            CompilationUnit ast = org.jawata.mcp.tools.shared.CompilationProblems.parse(cu);

            if (ast == null) {
                return ToolResponse.error("PROBLEMS_UNKNOWN",
                    "The file could not be parsed, so its problems are unknown — this is not"
                        + " an answer of zero problems.",
                    "Run get_diagnostics on the file, which reads the build's own markers.");
            }

            IProblem[] problems = ast.getProblems();
            List<Map<String, Object>> fixes = new ArrayList<>();
            List<Map<String, Object>> problemsAtLine = new ArrayList<>();

            // Filter problems at the specified line
            for (IProblem problem : problems) {
                int problemLine = problem.getSourceLineNumber() - 1; // Convert to 0-based
                if (problemLine != line) continue;

                // If column specified, check if problem covers that column
                if (column >= 0) {
                    int startOffset = problem.getSourceStart();
                    int endOffset = problem.getSourceEnd();
                    int startCol = ast.getColumnNumber(startOffset);
                    int endCol = ast.getColumnNumber(endOffset);
                    if (column < startCol || column > endCol) continue;
                }

                Map<String, Object> problemInfo = new LinkedHashMap<>();
                problemInfo.put("problemId", problem.getID());
                problemInfo.put("message", problem.getMessage());
                problemInfo.put("severity", problem.isError() ? "error" : "warning");
                problemsAtLine.add(problemInfo);

                // Generate fixes based on problem type
                List<Map<String, Object>> problemFixes = generateFixes(
                    problem, ast, cu, service);
                fixes.addAll(problemFixes);
            }

            // Sort fixes by relevance
            fixes.sort(Comparator.comparingInt(
                (Map<String, Object> f) -> (int) f.get("relevance")).reversed());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("filePath", service.getPathUtils().formatPath(path));
            data.put("line", line);
            data.put("problemCount", problemsAtLine.size());
            data.put("problems", problemsAtLine);
            data.put("fixes", fixes);

            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(fixes.size())
                .returnedCount(fixes.size())
                .suggestedNextTools(List.of(
                    "quick_fix(action=apply) with fixId to apply a fix",
                    "get_diagnostics to see all problems"
                ))
                .build());

        } catch (TypeLookupException e) {
            log.warn("Quick fixes could not be listed: {}", e.getMessage());
            return ToolResponse.error("FIX_SEARCH_FAILED", e.getMessage(),
                "Ask again; if it repeats, run get_diagnostics on the file and refresh_workspace, because the project's type index is not answering.");
        } catch (Exception e) {
            log.error("Error getting quick fixes: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Generate fixes for a specific problem.
     */
    private List<Map<String, Object>> generateFixes(IProblem problem, CompilationUnit ast,
                                                     ICompilationUnit cu, IJdtService service) {
        List<Map<String, Object>> fixes = new ArrayList<>();
        int problemId = problem.getID();

        try {
            if (problemId == UNDEFINED_TYPE || problemId == UNDEFINED_NAME) {
                // Extract the unresolved type name from the problem arguments
                String[] args = problem.getArguments();
                String unresolvedName = null;

                if (args != null && args.length > 0) {
                    unresolvedName = args[0];
                } else {
                    // Try to extract from source
                    int start = problem.getSourceStart();
                    int end = problem.getSourceEnd();
                    if (start >= 0 && end >= start) {
                        unresolvedName = cu.getSource().substring(start, end + 1);
                    }
                }

                if (unresolvedName != null) {
                    List<Map<String, Object>> importFixes = suggestImportFixes(
                        unresolvedName, service, problem);
                    fixes.addAll(importFixes);
                }
            }

            if (problemId == UNUSED_IMPORT) {
                // Find the import index
                @SuppressWarnings("unchecked")
                List<ImportDeclaration> imports = ast.imports();
                int importIndex = -1;

                for (int i = 0; i < imports.size(); i++) {
                    ImportDeclaration imp = imports.get(i);
                    if (imp.getStartPosition() <= problem.getSourceStart() &&
                        imp.getStartPosition() + imp.getLength() >= problem.getSourceEnd()) {
                        importIndex = i;
                        break;
                    }
                }

                if (importIndex >= 0) {
                    Map<String, Object> fix = new LinkedHashMap<>();
                    fix.put("fixId", "remove_import:" + importIndex);
                    fix.put("label", "Remove unused import");
                    fix.put("category", "IMPORT");
                    fix.put("relevance", 90);
                    fix.put("problemId", problemId);
                    fixes.add(fix);
                }
            }

            if (problemId == UNHANDLED_EXCEPTION) {
                // Extract exception type from problem arguments
                String[] args = problem.getArguments();
                String exceptionType = args != null && args.length > 0 ? args[0] : "Exception";

                // Fix 1: Add throws declaration
                Map<String, Object> addThrowsFix = new LinkedHashMap<>();
                addThrowsFix.put("fixId", "add_throws:" + exceptionType);
                addThrowsFix.put("label", "Add throws declaration for " + exceptionType);
                addThrowsFix.put("category", "EXCEPTION");
                addThrowsFix.put("relevance", 80);
                addThrowsFix.put("problemId", problemId);
                fixes.add(addThrowsFix);

                // Fix 2: Surround with try-catch
                Map<String, Object> tryCatchFix = new LinkedHashMap<>();
                tryCatchFix.put("fixId", "surround_try_catch:" + exceptionType);
                tryCatchFix.put("label", "Surround with try-catch for " + exceptionType);
                tryCatchFix.put("category", "EXCEPTION");
                tryCatchFix.put("relevance", 75);
                tryCatchFix.put("problemId", problemId);
                fixes.add(tryCatchFix);
            }

            if (problemId == IMPORT_NOT_FOUND) {
                // Suggest removing the bad import
                @SuppressWarnings("unchecked")
                List<ImportDeclaration> imports = ast.imports();
                int importIndex = -1;

                for (int i = 0; i < imports.size(); i++) {
                    ImportDeclaration imp = imports.get(i);
                    if (imp.getStartPosition() <= problem.getSourceStart() &&
                        imp.getStartPosition() + imp.getLength() >= problem.getSourceEnd()) {
                        importIndex = i;
                        break;
                    }
                }

                if (importIndex >= 0) {
                    Map<String, Object> fix = new LinkedHashMap<>();
                    fix.put("fixId", "remove_import:" + importIndex);
                    fix.put("label", "Remove unresolved import");
                    fix.put("category", "IMPORT");
                    fix.put("relevance", 85);
                    fix.put("problemId", problemId);
                    fixes.add(fix);
                }
            }

        } catch (JavaModelException e) {
            log.debug("Error generating fixes for problem {}: {}", problemId, e.getMessage());
        }

        return fixes;
    }

    /**
     * Suggest import fixes for an unresolved type.
     */
    private List<Map<String, Object>> suggestImportFixes(String typeName, IJdtService service,
                                                          IProblem problem) {
        List<Map<String, Object>> fixes = new ArrayList<>();
        // A search that fails is a TypeLookupException and is not an empty list: "the search failed" and "no such type exists" are
        // different answers (S16e b).
        for (ImportCandidates.Candidate candidate : ImportCandidates.find(service, typeName, MOST_IMPORT_FIXES, typeSearch)) {
            Map<String, Object> fix = new LinkedHashMap<>();
            fix.put("fixId", "add_import:" + candidate.fullyQualifiedName());
            fix.put("label", "Import '" + typeName + "' (" + candidate.packageName() + ")");
            fix.put("category", "IMPORT");
            fix.put("relevance", candidate.relevance());
            fix.put("problemId", problem.getID());
            fixes.add(fix);
        }
        return fixes;
    }
}
