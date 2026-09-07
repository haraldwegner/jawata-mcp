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

    /**
     * MAKE THE MODEL SEE WHAT THE CHANGE JUST WROTE, before anything reads errors from it.
     *
     * <p>jawata-mcp#69. {@link #introducedErrors} skips everything but syntax in a CREATED
     * file, and justifies that by saying the created type's semantic validity is "proven
     * transitively by the MODIFIED files that reference them, which do resolve them". <b>That
     * sentence is the one this method exists to make true.</b> A file is written to disk and
     * the Java model does not necessarily know it yet; a modified file re-parsed in that
     * window resolves the type it references to nothing, and the gate reports an error the
     * change did not cause.</p>
     *
     * <p>It surfaced as a platform difference, which is what a timing gap looks like from
     * outside: on the macOS matrix job at the v4.1.0 tag, {@code combine_functions} was
     * refused on upstream code with <i>"App.java: the method getGroupingOfCarsByCategory()
     * from the type CarQueries refers to the missing type Car"</i>, where {@code CarQueries}
     * is the class the operation had just created and {@code Car} sits in its own package.
     * The same operation on the same slice passed on Linux in the same run — measured: both
     * loaded it identically, as 7 source files in 1 package with 0 unresolved requirements.
     * So the input was equal and the model's readiness was not.</p>
     *
     * <p><b>It cannot turn a correct result wrong, which is why it is safe to ship against a
     * platform this machine cannot run.</b> Refreshing a resource and making a unit consistent
     * only ever gives the compiler MORE of what is genuinely on disk; a real error stays an
     * error, and the errors it removes are ones about a file that exists. The failure it
     * prevents is the expensive direction: a correct refactoring refused and undone.</p>
     *
     * <p>Settling failures are swallowed on purpose. This is preparation for the measurement,
     * not the measurement — a workspace that cannot be refreshed will produce its own honest
     * problems a line later, and failing here would replace a real finding with a plumbing
     * one.</p>
     */
    public static void settle(IJdtService service, List<String> filePaths) {
        for (String fp : filePaths) {
            if (fp == null || !fp.endsWith(".java")) {
                continue;
            }
            try {
                Path p = Path.of(fp);
                if (!p.isAbsolute() && service.getProjectRoot() != null) {
                    p = service.getProjectRoot().resolve(p).normalize();
                }
                if (!java.nio.file.Files.exists(p)) {
                    continue;
                }
                ICompilationUnit cu = service.getCompilationUnit(p);
                if (cu == null) {
                    continue;
                }
                // THE PARENT, NOT THE FILE. A created unit's own resource may not exist in
                // the model yet — that is the state being repaired — so refreshing it alone
                // can be a no-op on exactly the case this is for. Refreshing the package it
                // landed in is what makes the model notice a NEW member.
                org.eclipse.core.resources.IResource resource = cu.getResource();
                if (resource != null && resource.getParent() != null) {
                    resource.getParent().refreshLocal(
                        org.eclipse.core.resources.IResource.DEPTH_ONE, null);
                }
                cu.makeConsistent(null);
            } catch (Exception settling) {
                // Deliberately silent — see the javadoc's last paragraph.
            }
        }
    }

    /**
     * WHAT A CREATED FILE DECLARES ITSELF TO BE — the evidence a refusal about one needs.
     *
     * <p>jawata-mcp#69, the half that is not the timing. When the gate refuses because a
     * modified file cannot resolve a type the change just CREATED, the reader is told the
     * type is missing and nothing about the file that was supposed to provide it — so the
     * question "what package did it land in, and what did it import" costs another run on a
     * machine you may not have. The change is undone a few lines later and the file is gone,
     * so this is the last moment the answer exists.</p>
     *
     * <p>Bounded on purpose: the package declaration and the imports, never the body. Those
     * are what decide whether a same-package type resolves, and they are two or three lines
     * rather than a whole class in an error message.</p>
     */
    public static String createdFileContext(IJdtService service,
                                            Map<String, Set<String>> before,
                                            List<String> afterPaths) {
        StringBuilder out = new StringBuilder();
        for (String fp : afterPaths) {
            if (fp == null || !fp.endsWith(".java") || before.containsKey(fp)) {
                continue;
            }
            try {
                Path p = Path.of(fp);
                if (!p.isAbsolute() && service.getProjectRoot() != null) {
                    p = service.getProjectRoot().resolve(p).normalize();
                }
                if (!java.nio.file.Files.exists(p)) {
                    continue;
                }
                StringBuilder head = new StringBuilder();
                for (String line : java.nio.file.Files.readAllLines(p)) {
                    String t = line.strip();
                    if (t.startsWith("package ") || t.startsWith("import ")) {
                        head.append("\n    ").append(t);
                    }
                }
                out.append("\n  CREATED ").append(fp)
                    .append(head.isEmpty() ? "\n    (no package or import declarations)" : head);
            } catch (Exception unreadable) {
                out.append("\n  CREATED ").append(fp).append("\n    (could not be read back: ")
                    .append(unreadable.getClass().getSimpleName()).append(")");
            }
        }
        return out.toString();
    }

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
