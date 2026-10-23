package org.jawata.mcp.refactoring;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.jawata.spi.lang.BuildVerdict;

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
    public static Map<String,Set<String>> errorMessagesByFile(org.jawata.spi.lang.BuildVerdict verdict, List<String> filePaths) {
        Map<String, Set<String>> out = new LinkedHashMap<>();
        verdict.problemsIn(filePaths).forEach((file, problems) -> {
            Set<String> messages = new LinkedHashSet<>();
            for (BuildVerdict.SourceProblem problem : problems) {
                messages.add((problem.syntax() ? SYNTAX_PREFIX : "") + problem.message());
            }
            out.put(file, messages);
        });
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
    public static String createdFileContext(org.jawata.spi.lang.BuildVerdict verdict, Map<String,Set<String>> before, List<String> afterPaths) {
        StringBuilder out = new StringBuilder();
        for (String fp : afterPaths) {
            if (fp == null || before.containsKey(fp)) {
                continue;
            }
            verdict.describeCreated(fp).ifPresent(out::append);
        }
        return out.toString();
    }
}
