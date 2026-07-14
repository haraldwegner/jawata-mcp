package org.jawata.mcp.refactoring;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.IJdtService;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The refactoring tools' self-check: compile the files a change touched and
 * say whether the change INTRODUCED errors.
 *
 * <p>Why this exists: a refactoring used to answer {@code applied: true} and
 * merely <em>advise</em> the caller to run {@code compile_workspace}. The
 * first live self-refactor (Stage 14, 2026-07-14) applied a hand-rolled
 * extract-method that produced non-compiling code — and reported success.
 * A tool that modified the code can check the code; making the caller do it
 * is how broken code gets left behind wearing a green checkmark.</p>
 *
 * <p>Scope, stated honestly: this verifies the files the change MODIFIED
 * (parse with bindings, collect ERROR-severity problems). Breakage in files
 * the change should have touched but did not (the subtle class) is outside
 * its reach — that is what {@code compile_workspace} and the parity-gated
 * plan path remain for.</p>
 *
 * <p>Comparison is by problem MESSAGE (not line): the edit legitimately
 * shifts lines, so positions cannot identify "the same pre-existing error".
 * A refactoring on a file with pre-existing errors stays possible; only NEW
 * messages count against it.</p>
 */
public final class CompileVerify {

    private CompileVerify() {
    }

    /**
     * ERROR-severity problem messages per file (file → set of messages).
     * Only {@code .java} files are verified — a change that triggers a build
     * sweeps {@code bin/**.class} outputs into the modified-file delta, and
     * "verifying" a binary is meaningless (and produced 171 false findings on
     * the first type-with-file rename).
     */
    public static Map<String, Set<String>> errorMessagesByFile(IJdtService service,
                                                               List<String> filePaths) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        for (String fp : filePaths) {
            if (fp != null && fp.endsWith(".java")) {
                Set<String> messages = errorMessages(service, fp);
                if (messages != null) {
                    out.put(fp, messages);
                }
            }
        }
        return out;
    }

    /**
     * Messages present per-file in {@code after} but not in {@code before} —
     * the errors the change introduced. Each rendered "file: message".
     *
     * <p>A file with NO entry in {@code before} was CREATED by the change. A
     * just-created compilation unit, re-parsed in isolation, cannot resolve its
     * project siblings yet (a persistent model-timing artifact, not a defect —
     * documented independently in RefactorToVisitorToolTest long before this
     * gate existed), so for created files only SYNTAX errors count: their
     * semantic validity is proven transitively by the MODIFIED files that
     * reference them, which do resolve them and are fully verified.</p>
     */
    public static List<String> introducedErrors(Map<String, Set<String>> before,
                                                Map<String, Set<String>> after) {
        List<String> introduced = new java.util.ArrayList<>();
        after.forEach((file, messages) -> {
            Set<String> pre = before.get(file);
            boolean createdByChange = pre == null;
            for (String m : messages) {
                if (createdByChange && !m.startsWith(SYNTAX_PREFIX)) {
                    continue;
                }
                if (pre == null || !pre.contains(m)) {
                    introduced.add(file + ": " + m);
                }
            }
        });
        return introduced;
    }

    /** Marker prefix for syntax-category problems — garbage output, never a legitimate intermediate state. */
    public static final String SYNTAX_PREFIX = "SYNTAX: ";

    /** Null = the file does not exist at this path (distinct from "exists, no errors"). */
    private static Set<String> errorMessages(IJdtService service, String filePath) {
        Set<String> messages = new LinkedHashSet<>();
        try {
            Path p = Path.of(filePath);
            if (!p.isAbsolute() && service.getProjectRoot() != null) {
                p = service.getProjectRoot().resolve(p).normalize();
            }
            if (!java.nio.file.Files.exists(p)) {
                // Before-pass: the file is yet to be created by the change.
                // After-pass: the change removed/renamed it. Either way there is
                // nothing to verify AT this path — and the null (vs empty set)
                // is how introducedErrors recognizes a created file.
                return null;
            }
            ICompilationUnit cu = service.getCompilationUnit(p);
            if (cu == null) {
                // The file EXISTS but the model cannot open it — that IS a finding
                // for the gate: report it rather than silently skipping (a skipped
                // file would make a broken result look verified).
                messages.add("(file could not be opened for compile verification)");
                return messages;
            }
            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setResolveBindings(true);
            CompilationUnit ast = (CompilationUnit) parser.createAST(null);
            for (IProblem problem : ast.getProblems()) {
                if (problem.isError()) {
                    boolean syntax = (problem.getID() & IProblem.Syntax) != 0;
                    messages.add((syntax ? SYNTAX_PREFIX : "") + problem.getMessage());
                }
            }
        } catch (Exception e) {
            messages.add("(compile verification itself failed: "
                + e.getClass().getSimpleName()
                + (e.getMessage() != null ? ": " + e.getMessage() : "") + ")");
        }
        return messages;
    }
}
