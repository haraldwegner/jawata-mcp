package org.goja.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.goja.core.IJdtService;
import org.goja.mcp.domain.Detector;
import org.goja.mcp.domain.Finding;
import org.goja.mcp.domain.Findings;
import org.goja.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Sprint 17 — base for Fowler smell {@link Detector}s. Handles the uniform
 * boilerplate every AST-walk detector shares: read the optional {@code filePath}
 * + {@code threshold} args, iterate the project's Java files (or just one), parse
 * each with binding resolution, collect {@link Finding}s, sort by severity, and
 * render via {@link Findings#toResponse}. Subclasses implement only
 * {@link #analyze} — the visitor + heuristic for one smell.
 *
 * <p>Findings use 1-based line/column (JDT convention), matching {@link Finding}.</p>
 */
public abstract class AbstractAstDetector implements Detector {

    private static final Map<String, Integer> SEVERITY_RANK = Map.of(
        "error", 3, "warning", 2, "info", 1);

    private final String kind;
    private final String description;
    private final int defaultThreshold;

    protected AbstractAstDetector(String kind, String description, int defaultThreshold) {
        this.kind = kind;
        this.description = description;
        this.defaultThreshold = defaultThreshold;
    }

    @Override
    public final String kind() {
        return kind;
    }

    @Override
    public final String description() {
        return description;
    }

    /** Default threshold for this smell when the caller omits {@code threshold}. */
    protected final int defaultThreshold() {
        return defaultThreshold;
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        int threshold = readInt(arguments, "threshold", defaultThreshold);
        String filePath = readString(arguments, "filePath");
        List<Finding> findings = new ArrayList<>();
        try {
            List<Path> files = (filePath != null && !filePath.isBlank())
                ? List.of(Path.of(filePath))
                : service.getAllJavaFiles();
            for (Path path : files) {
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    continue;
                }
                CompilationUnit ast = parse(cu);
                if (ast == null) {
                    continue;
                }
                String formatted = service.getPathUtils().formatPath(path);
                analyze(ast, formatted, service, threshold, findings);
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
        findings.sort(Comparator.comparingInt(
            (Finding f) -> SEVERITY_RANK.getOrDefault(f.severity(), 0)).reversed());
        return Findings.toResponse(findings);
    }

    /**
     * Analyse one parsed compilation unit and append findings.
     *
     * @param ast       the parsed unit (bindings resolved)
     * @param filePath  the formatted path for {@link Finding#filePath()}
     * @param service   the project-scoped JDT service (for cross-file queries)
     * @param threshold the effective threshold (caller value or {@link #defaultThreshold()})
     * @param out       collect findings here
     */
    protected abstract void analyze(CompilationUnit ast, String filePath,
                                    IJdtService service, int threshold, List<Finding> out);

    /** Parse a compilation unit with binding resolution; null on failure. */
    protected CompilationUnit parse(ICompilationUnit cu) {
        try {
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            return (CompilationUnit) parser.createAST(null);
        } catch (Exception e) {
            return null;
        }
    }

    protected static int readInt(JsonNode args, String name, int def) {
        if (args == null || !args.has(name) || args.get(name).isNull()) {
            return def;
        }
        return args.get(name).asInt(def);
    }

    protected static String readString(JsonNode args, String name) {
        if (args == null || !args.has(name) || args.get(name).isNull()) {
            return null;
        }
        return args.get(name).asText();
    }
}
