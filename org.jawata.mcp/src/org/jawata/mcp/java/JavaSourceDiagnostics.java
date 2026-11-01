package org.jawata.mcp.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IPackageFragment;
import org.eclipse.jdt.core.IPackageFragmentRoot;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.IJdtService;
import org.jawata.spi.lang.Diagnostic;
import org.jawata.spi.lang.LoadedWorkspace;
import org.jawata.spi.lang.SourceDiagnostics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Java's answer to {@code get_diagnostics} (Sprint 28k, Stage 9, moved here from the tool): the problems JDT's reconcile finds in a
 * compilation unit, and for a unit that is not a working copy, the problems the last build left in it. Errors and warnings only: newer
 * JDT reports other severities, which the contract of that tool does not surface or count (22c).
 *
 * <p>The workspace it is handed is Java's own service, possibly narrowed to one project.</p>
 */
public final class JavaSourceDiagnostics implements SourceDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(JavaSourceDiagnostics.class);

    @Override
    public Optional<List<Row>> inFile(LoadedWorkspace workspace, Path file) {
        IJdtService service = (IJdtService) workspace;
        ICompilationUnit unit = service.getCompilationUnit(file);
        if (unit == null) {
            return Optional.empty();
        }
        return Optional.of(rowsOf(service, unit, file));
    }

    @Override
    public void inEveryFile(LoadedWorkspace workspace, FileVisitor visitor) {
        IJdtService service = (IJdtService) workspace;
        try {
            for (IPackageFragmentRoot root : service.getJavaProject().getPackageFragmentRoots()) {
                if (root.getKind() != IPackageFragmentRoot.K_SOURCE) {
                    continue;
                }
                for (IJavaElement child : root.getChildren()) {
                    if (!(child instanceof IPackageFragment pkg)) {
                        continue;
                    }
                    for (ICompilationUnit unit : pkg.getCompilationUnits()) {
                        Path path = pathOf(unit);
                        if (!visitor.visit(path, rowsOf(service, unit, path))) {
                            return;
                        }
                    }
                }
            }
        } catch (JavaModelException e) {
            throw new IllegalStateException("the project's source roots could not be walked: " + e.getMessage(), e);
        }
    }

    private static Path pathOf(ICompilationUnit unit) {
        try {
            if (unit.getResource() != null && unit.getResource().getLocation() != null) {
                return Path.of(unit.getResource().getLocation().toOSString());
            }
        } catch (Exception e) {
            log.trace("Error getting CU path: {}", e.getMessage());
        }
        return Path.of(unit.getElementName());
    }

    private static List<Row> rowsOf(IJdtService service, ICompilationUnit unit, Path path) {
        List<Row> rows = new ArrayList<>();
        try {
            // Reconcile to get AST with problems
            CompilationUnit ast = unit.reconcile(AST.getJLSLatest(), ICompilationUnit.FORCE_PROBLEM_DETECTION, null, null);
            if (ast == null) {
                // Sprint 21e (item C): reconcile returns a null AST for NON-working-copies — in this headless server no file is ever a
                // working copy, so this tool NEVER reported problems for closed files (proven 2026-07-07). Post-build PROBLEM markers are
                // per-call fresh since 21d (build on change): read them instead, the same sweep compile_workspace uses.
                return rowsFromMarkers(service, path);
            }
            for (IProblem problem : ast.getProblems()) {
                boolean isError = problem.isError();
                boolean isWarning = problem.isWarning();
                // 22c (2026-07-11, ported from upstream 2119dd80): newer JDT reports problems at other severities (e.g. info). The
                // contract is errors and warnings only; anything else must not be surfaced or counted.
                if (!isError && !isWarning) {
                    continue;
                }
                rows.add(new Row(problem.getSourceLineNumber() - 1, problem.getSourceStart(), problem.getSourceEnd(),
                    isError ? "error" : "warning", problem.getMessage(), problem.getID(), categorize(problem)));
            }
        } catch (JavaModelException e) {
            log.debug("Error reconciling {}: {}", path, e.getMessage());
            rows.add(new Row(0, null, null, "error", "Failed to analyze file: " + e.getMessage(), null, "PARSE_ERROR"));
        }
        return rows;
    }

    /**
     * Sprint 21e (item C): the honest closed-file path — the file's post-build problems, taken from the language's build verdict (28k
     * E10b: the one place the host's markers are read). {@code problemId}/{@code category} are carried where the row has them.
     */
    private static List<Row> rowsFromMarkers(IJdtService service, Path path) {
        List<Row> rows = new ArrayList<>();
        for (Diagnostic row : new JavaBuildVerdict(service).diagnosticsIn(path.toString())) {
            boolean isError = row.severity() == Diagnostic.Severity.ERROR;
            boolean isWarning = row.severity() == Diagnostic.Severity.WARNING;
            // 22c (2026-07-11): same contract as the AST path: errors and warnings only; info rows neither surfaced nor counted.
            if (!isError && !isWarning) {
                continue;
            }
            rows.add(new Row(row.line() > 0 ? row.line() - 1 : 0, row.detail().charStart(), row.detail().charEnd(),
                isError ? "error" : "warning", row.message(),
                row.detail().problemId() >= 0 ? row.detail().problemId() : null,
                row.detail().categoryId() >= 0 ? String.valueOf(row.detail().categoryId()) : null));
        }
        return rows;
    }

    /** Categorize a problem using JDT's categorization system. */
    private static String categorize(IProblem problem) {
        // CategorizedProblem provides richer categorization
        if (problem instanceof CategorizedProblem categorized) {
            return categoryName(categorized.getCategoryID());
        }

        // Fall back to IProblem bit flag checking
        int id = problem.getID();

        if ((id & IProblem.Syntax) != 0) return "SYNTAX";
        if ((id & IProblem.ImportRelated) != 0) return "IMPORT";
        if ((id & IProblem.TypeRelated) != 0) return "TYPE";
        if ((id & IProblem.MethodRelated) != 0) return "METHOD";
        if ((id & IProblem.ConstructorRelated) != 0) return "CONSTRUCTOR";
        if ((id & IProblem.FieldRelated) != 0) return "FIELD";
        if ((id & IProblem.Internal) != 0) return "INTERNAL";

        return "OTHER";
    }

    private static String categoryName(int categoryId) {
        return switch (categoryId) {
            case CategorizedProblem.CAT_UNSPECIFIED -> "UNSPECIFIED";
            case CategorizedProblem.CAT_BUILDPATH -> "BUILDPATH";
            case CategorizedProblem.CAT_SYNTAX -> "SYNTAX";
            case CategorizedProblem.CAT_IMPORT -> "IMPORT";
            case CategorizedProblem.CAT_TYPE -> "TYPE";
            case CategorizedProblem.CAT_MEMBER -> "MEMBER";
            case CategorizedProblem.CAT_INTERNAL -> "INTERNAL";
            case CategorizedProblem.CAT_JAVADOC -> "JAVADOC";
            case CategorizedProblem.CAT_CODE_STYLE -> "CODE_STYLE";
            case CategorizedProblem.CAT_POTENTIAL_PROGRAMMING_PROBLEM -> "POTENTIAL_PROBLEM";
            case CategorizedProblem.CAT_NAME_SHADOWING_CONFLICT -> "NAME_SHADOWING";
            case CategorizedProblem.CAT_DEPRECATION -> "DEPRECATION";
            case CategorizedProblem.CAT_UNNECESSARY_CODE -> "UNNECESSARY_CODE";
            case CategorizedProblem.CAT_UNCHECKED_RAW -> "UNCHECKED_RAW";
            case CategorizedProblem.CAT_NLS -> "NLS";
            case CategorizedProblem.CAT_RESTRICTION -> "RESTRICTION";
            case CategorizedProblem.CAT_MODULE -> "MODULE";
            case CategorizedProblem.CAT_COMPLIANCE -> "COMPLIANCE";
            case CategorizedProblem.CAT_PREVIEW_RELATED -> "PREVIEW";
            default -> "OTHER";
        };
    }
}
