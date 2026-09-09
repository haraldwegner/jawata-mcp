package org.jawata.mcp.refactoring;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.ltk.core.refactoring.TextFileChange;
import org.jawata.core.IJdtService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Sprint 19 (Kerievsky) — runs a <b>dependent</b> refactoring recipe: a sequence
 * of steps where each step's edit shifts the ground under the next, so the steps
 * cannot be pre-computed as one {@link org.eclipse.ltk.core.refactoring.Change}.
 * Each {@link Step} is evaluated lazily against the <em>current</em> workspace
 * (apply-reparse): the engine performs it, captures its undo-{@link Change}, and
 * moves on. All the per-step undos are wrapped, reverse-order, into one
 * {@link CompositeChange} so the whole recipe reverts with a single undo handle.
 *
 * <p>Atomic: if any step fails to build or validate, every already-applied step is
 * rolled back before returning an error — the workspace is left as it was found.
 * (Single-shot recipes — independent edits computable against the original AST —
 * do NOT need this; they build one {@link ChangeEngine#fromFileEdits} change.)</p>
 */
public final class RecipeEngine {

    private static final Logger log = LoggerFactory.getLogger(RecipeEngine.class);

    private RecipeEngine() {
    }

    /** One recipe step, built against the current (post-previous-step) workspace state. */
    @FunctionalInterface
    public interface Step {
        /** @return the change to perform, or {@code null} to abort the recipe. */
        Change build() throws Exception;
    }

    /**
     * Outcome of a recipe run. On failure {@code compositeUndo} is null and the workspace
     * is restored.
     *
     * <p>mcp#80: {@code introducedErrors} and {@code diff} exist because the composite had
     * NEITHER. The comment below used to say "the FINAL state is verified by the whole
     * composite the caller applies" — and no caller applies one. The engine has performed
     * every step by the time it returns, so nothing verified the end state, and
     * {@code applied: true} was asserted over it with no diff to read.</p>
     *
     * @param introducedErrors errors the recipe ADDED, measured per file against the state
     *                         before this recipe first touched that file — empty when it
     *                         added none. Pre-existing errors do not count against it.
     * @param diff             unified diff of the whole composite, or null when no file's
     *                         before-text could be read (see {@code textBefore} below)
     */
    public record Result(boolean ok, List<String> modifiedFilePaths, Change compositeUndo,
                         String error, List<String> introducedErrors, String diff) {

        /** The failure shape, keeping every caller's construction short. */
        static Result failed(String error) {
            return new Result(false, List.of(), null, error, List.of(), null);
        }

        /** Nothing was added that was not already broken. */
        public boolean compileVerified() {
            return introducedErrors.isEmpty();
        }
    }

    public static Result run(String name, List<Step> steps, IJdtService service) {
        List<Change> undos = new ArrayList<>();
        LinkedHashSet<String> modified = new LinkedHashSet<>();
        // mcp#80: THE BEFORE-STATE, CAPTURED AT FIRST TOUCH. It cannot be read up front —
        // which files a recipe ends up modifying is only known once its steps have been
        // built — and it cannot be read at the end, because by then every step has applied.
        // Recording each file the first time a step declares it affected gives exactly the
        // state as of before THIS recipe reached it, which is what "introduced" has to mean.
        Map<String, Set<String>> errorsBefore = new LinkedHashMap<>();
        Map<String, String> textBefore = new LinkedHashMap<>();
        for (int i = 0; i < steps.size(); i++) {
            Change change;
            try {
                change = steps.get(i).build();
            } catch (Exception e) {
                // THE CAUSE IS KEPT, and both halves earned their place in Stage 5. A step's
                // failure used to reach the caller as getMessage() alone: no type, no stack,
                // nothing logged. A recipe's steps run inside engines nobody here wrote, so
                // that message is often all JDT says — and on its own a line like "X is not
                // an instance of Y" names neither which engine raised it nor where. The type
                // goes into the message a caller reads; the stack goes to the log, which is
                // where a diagnosis has to start.
                log.warn("recipe '{}' step {} failed", name, i + 1, e);
                rollback(undos, service);
                return Result.failed("step " + (i + 1) + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
            if (change == null) {
                rollback(undos, service);
                return Result.failed("step " + (i + 1) + ": could not build change");
            }
            // THROUGH THE GATE, PER STEP, since C6. This was a bare ChangeEngine.perform,
            // so a recipe was the way around the compile verification every direct
            // refactoring performs — and the recipe path is where it matters MOST, because
            // an intermediate step's output is the next step's input. A step that produced
            // code the next step then reparsed wrongly would compound silently. A C6 audit
            // found this one round after the gate reached both refactoring bases, and it
            // was right that GatedApply's "one way to apply a change" javadoc was false
            // while this stood.
            //
            // MODE.REPORT, and the choice matters. A recipe's INTERMEDIATE state is often
            // legitimately red — Replace Temp with Query extracts a method that nothing
            // calls yet, and the temp it will replace is still there — so undoing on any
            // introduced error would refuse every correct recipe. REPORT still undoes a
            // SYNTAX error, which is the step writing something that is not Java, and no
            // intermediate state excuses that.
            //
            // THIS COMMENT USED TO END "the FINAL state is verified by the whole composite
            // the caller applies", and that sentence was FALSE — mcp#80. No caller applies
            // one: the engine has performed every step by the time it returns, so the
            // composite it hands back is an UNDO, and the state after the last step was
            // verified by nothing. What verifies it now is the block after this loop, and
            // that block exists because of this sentence rather than in spite of it.
            for (String affected : ChangeEngine.affectedFilePaths(change, service)) {
                errorsBefore.computeIfAbsent(affected, path ->
                    CompileVerify.errorMessagesByFile(service, List.of(path))
                        .getOrDefault(path, Set.of()));
                textBefore.computeIfAbsent(affected, path -> readOrNull(path, service));
            }
            GatedApply.Result gated =
                GatedApply.perform(change, service, GatedApply.Mode.REPORT);
            ChangeEngine.ApplyOutcome outcome = gated.outcome();
            if (outcome.validationError() != null) {
                rollback(undos, service);
                return Result.failed("step " + (i + 1) + ": " + outcome.validationError());
            }
            if (gated.refused()) {
                rollback(undos, service);
                return Result.failed("step " + (i + 1)
                    + " wrote code that does not parse: " + gated.failure());
            }
            if (outcome.undoChange() != null) {
                undos.add(outcome.undoChange());
            }
            modified.addAll(outcome.modifiedFilePaths());
            // Stale-buffer race (Sprint 23 Stage 14): perform() writes through the
            // LTK file buffer, but the JDT Openable buffer of the modified unit is
            // only invalidated when the workspace delta is processed — which can
            // lose the race against the NEXT step's prepare. A stale parse then
            // computes insertion offsets against the pre-step document and lands
            // mid-token in the new one (~1-in-3 on compose_method). Close the
            // modified units so the next step re-reads current content.
            closeModifiedUnits(change);
        }
        CompositeChange compositeUndo = new CompositeChange(name + " (undo)");
        for (int i = undos.size() - 1; i >= 0; i--) {
            compositeUndo.add(undos.get(i));
        }
        // mcp#80: VERIFY THE COMPOSITE. Every step ran under Mode.REPORT, which tolerates an
        // intermediate red state on purpose — Replace Temp with Query extracts a method
        // nothing calls yet. What no one checked was the state after the LAST step, which is
        // the only one a caller ever sees. Settle first, for the same reason GatedApply does:
        // a model that has not caught up with the disk reports errors the recipe did not cause.
        List<String> modifiedPaths = new ArrayList<>(modified);
        CompileVerify.settle(service, modifiedPaths);
        List<String> introduced = CompileVerify.introducedErrors(errorsBefore,
            CompileVerify.errorMessagesByFile(service, modifiedPaths));

        // And the diff, which the response simply did not carry. Rendered from the text as it
        // was at first touch, so it shows the WHOLE composite rather than the last step.
        List<DiffRenderer.FileDiff> diffs = new ArrayList<>();
        for (String path : modifiedPaths) {
            String was = textBefore.get(path);
            String now = readOrNull(path, service);
            if (was != null && now != null && !was.equals(now)) {
                diffs.add(new DiffRenderer.FileDiff(path, was, now));
            }
        }
        return new Result(true, modifiedPaths, compositeUndo, null, introduced,
            diffs.isEmpty() ? null : DiffRenderer.unifiedDiff(diffs));
    }

    /** Discard the JDT buffers of every compilation unit the change touched. */
    private static void closeModifiedUnits(Change change) {
        IFile file = null;
        if (change instanceof TextFileChange tfc) {
            file = tfc.getFile();
        } else if (change.getModifiedElement() instanceof IFile f) {
            file = f;
        } else if (change.getModifiedElement() instanceof ICompilationUnit cu
                && cu.getResource() instanceof IFile f) {
            file = f;
        }
        if (file != null) {
            try {
                ICompilationUnit cu = JavaCore.createCompilationUnitFrom(file);
                if (cu != null && cu.isOpen() && !cu.isWorkingCopy()) {
                    cu.close();
                }
            } catch (Exception e) {
                log.debug("Buffer close after recipe step failed for {}: {}", file, e.getMessage());
            }
        }
        if (change instanceof CompositeChange composite) {
            for (Change child : composite.getChildren()) {
                closeModifiedUnits(child);
            }
        }
    }

    /**
     * A file's text, or null when it cannot be read — mcp#80.
     *
     * <p>Null rather than an exception, and null rather than an empty string: a file whose
     * before-text is unavailable is simply left out of the diff, which makes the diff
     * PARTIAL. Substituting "" would render the whole file as an insertion, which is a
     * worse answer than an absent one. Verification is unaffected either way — it reads
     * the compiler's error sets, not the text.</p>
     *
     * <p>THE PATH IS RESOLVED THROUGH THE HOST, and the first version of this method did
     * not do that. {@code ChangeEngine.affectedFilePaths} spells its paths with
     * {@code HostPaths.formatPath}, which returns a PROJECT-RELATIVE path unless the host
     * is configured for absolute ones — so reading it directly resolves against the process
     * working directory, fails, and every file is silently dropped from the diff. The
     * symptom was a successful recipe reporting a null diff, which is the very shape mcp#80
     * exists to remove. {@code HostPaths.resolve} is the documented inverse of
     * {@code formatPath} and returns an absolute path unchanged, so it is correct for both
     * spellings.</p>
     */
    private static String readOrNull(String path, IJdtService service) {
        try {
            java.nio.file.Path absolute = service != null
                ? service.getPathUtils().resolve(path)
                : java.nio.file.Path.of(path);
            return java.nio.file.Files.readString(absolute);
        } catch (Exception e) {
            return null;
        }
    }

    /** Best-effort restore: perform the captured undos in reverse application order. */
    private static void rollback(List<Change> undos, IJdtService service) {
        for (int i = undos.size() - 1; i >= 0; i--) {
            try {
                ChangeEngine.perform(undos.get(i), service);
            } catch (RuntimeException e) {
                log.warn("Recipe rollback step failed: {}", e.getMessage());
            }
        }
    }
}
