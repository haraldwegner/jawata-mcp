package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.internal.corext.fix.CleanUpConstants;
import org.eclipse.jdt.internal.corext.fix.VariableDeclarationFixCore;
import org.eclipse.jdt.internal.ui.fix.RedundantModifiersCleanUp;
import org.eclipse.jdt.ui.cleanup.ICleanUpFix;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.SourceScan;

import java.util.Optional;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 15 — parametric headless clean-up catalog (upstream v1.4.2 harvest).
 * Applies safe, mechanical source clean-ups via the standard apply/undo
 * contract ({@link AbstractApplyingRefactoringTool}).
 *
 * <p>Deliberately scoped to clean-ups the existing surface does NOT already
 * cover: {@code organize_imports} (imports), {@code format} (whitespace),
 * {@code apply_quick_fix} (compiler quick-fixes) and {@code find_modernization}
 * (language-idiom upgrades — find-only). The two kinds here are the canonical
 * non-overlapping ones:</p>
 *
 * <ul>
 *   <li>{@code add_final} — mark method/constructor parameters and local
 *       variable declarations {@code final} when they are never reassigned.</li>
 *   <li>{@code redundant_modifiers} — strip modifiers that are implicit on
 *       interface members ({@code public}/{@code abstract} on methods,
 *       {@code public}/{@code static}/{@code final} on fields,
 *       {@code public}/{@code static} on nested types).</li>
 * </ul>
 *
 * <p>Sprint 25 (spec D1a item 5): the per-file rewrites are computed by JDT's
 * own clean-up engines — {@link VariableDeclarationFixCore} for add_final and
 * {@link RedundantModifiersCleanUp} for redundant_modifiers, the same classes
 * behind the IDE's Source → Clean Up (headless by design; jdt.ls runs them).
 * They cover declaration forms the hand-rolled rewrite missed (catch and
 * enhanced-for parameters; nested enums/records in interfaces). The
 * {@code SourceScan} sweep shell with its honest missed-file reporting is
 * unchanged.</p>
 *
 * <p>{@code filePath} scopes to one file; omit it to sweep the whole project.
 * A no-op (nothing to clean) returns {@code hasChanges: false} without touching
 * anything.</p>
 */
public class ApplyCleanupTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ApplyCleanupTool.class);

    static final Set<String> KINDS = Set.of("add_final", "redundant_modifiers");

    public ApplyCleanupTool(Supplier<IJdtService> serviceSupplier,
                            RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "apply_cleanup";
    }

    @Override
    public String getDescription() {
        return """
            Apply a safe, mechanical source clean-up across a file or project.
            Auto-applies by default and returns
            { filesModified, diff, undoChangeId, summary }; pass auto_apply:false
            to stage instead. A no-op returns hasChanges:false.

            USAGE: apply_cleanup(kind="<kind>")  — whole default project
                   apply_cleanup(kind="<kind>", filePath="path/to/File.java")

            KINDS:
            - add_final           — mark parameters and local variables `final`
                                    when never reassigned (binding-checked, so it
                                    never breaks compilation).
            - redundant_modifiers — remove modifiers that are implicit on interface
                                    members (public/abstract methods, public/static/
                                    final fields, public/static nested types).

            This catalog is intentionally non-overlapping with organize_imports,
            format, apply_quick_fix and find_modernization. Optional: projectKey
            to scope a project-wide sweep. Requires load_project first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> kind = new LinkedHashMap<>();
        kind.put("type", "string");
        kind.put("enum", KINDS);
        kind.put("description", "Which clean-up to apply. See the tool description.");
        properties.put("kind", kind);
        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Optional. Restrict to one file; omit to sweep the whole project.");
        properties.put("filePath", filePath);
        schema.put("properties", properties);
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("kind",
                "kind is required; one of " + KINDS));
        }
        if (!KINDS.contains(kind)) {
            return Preparation.fail(ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + KINDS));
        }

        List<Path> targets = new ArrayList<>();
        String filePath = getStringParam(arguments, "filePath");
        if (filePath != null && !filePath.isBlank()) {
            Path path = Path.of(filePath);
            if (service.getCompilationUnit(path) == null) {
                return Preparation.fail(ToolResponse.fileNotFound(filePath));
            }
            targets.add(path);
        } else {
            targets.addAll(service.getAllJavaFiles());
        }

        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        int totalEdits = 0;
        // A cleanup SWEEP that silently skips the files it cannot read, and then reports
        // "filesScanned: <every file we listed>", tells you the project is clean when it has
        // not looked at parts of it. For a tool that CHANGES your code, that is the worst
        // version of this bug: you believe the sweep is done.
        SourceScan scan = SourceScan.of(targets);
        for (Path path : scan.files()) {
            ICompilationUnit cu = scan.resolve(service, path);
            if (cu == null) {
                continue;   // RECORDED — reported below, never silently dropped
            }
            CompilationUnit ast = scan.parse(cu, path, true);
            if (ast == null) {
                continue;
            }
            scan.examined();

            TextEdit edit = "add_final".equals(kind)
                ? addFinalEdit(ast)
                : redundantModifiersEdit(ast);
            if (edit == null || (!edit.hasChildren() && edit.getLength() == 0)) {
                continue;
            }
            List<TextEdit> list = new ArrayList<>();
            list.add(edit);
            editsByFile.put((IFile) cu.getResource(), list);
            totalEdits += countLeafEdits(edit);
        }

        // "Nothing to clean up" is only sayable if we managed to read the code.
        Optional<ToolResponse> blind = scan.refuseIfBlind("code to clean up");
        if (blind.isPresent()) {
            return Preparation.fail(blind.get());
        }

        if (editsByFile.isEmpty()) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", getName());
            data.put("applied", false);
            data.put("hasChanges", false);
            data.put("kind", kind);
            // filesScanned USED TO BE targets.size() — the number of files we LISTED, which
            // claimed we had scanned files we skipped. It is now the number we actually read.
            data.put("filesScanned", scan.examinedCount());
            data.putAll(scan.describe());
            return Preparation.fail(ToolResponse.success(data, ResponseMeta.builder()
                .steering(scan.steering(0, "code to clean up"))
                .suggestedNextTools(List.of("get_diagnostics to check for remaining issues"))
                .build()));
        }

        Change change = ChangeEngine.fromFileEdits("apply_cleanup " + kind, editsByFile);
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("kind", kind);
        extras.put("hasChanges", true);
        extras.put("filesChanged", editsByFile.size());
        extras.put("editCount", totalEdits);
        extras.putAll(scan.describe());
        if (scan.incomplete()) {
            // The sweep is going ahead — the edits it DID find are real and safe. But the
            // caller must not walk away believing the project has been swept.
            extras.put("sweepIncomplete", true);
            extras.put("warning", scan.missed() + " file(s) could not be read and were NOT "
                + "cleaned. This cleanup is PARTIAL — run refresh_workspace and repeat to "
                + "finish the job.");
        }
        String summary = "apply_cleanup " + kind + " (" + totalEdits + " edit(s) across "
            + editsByFile.size() + " file(s))";
        return Preparation.of(change, summary, extras);
    }

    /**
     * add_final via JDT's {@link VariableDeclarationFixCore} — parameters and
     * locals only (fields stay out of this kind's contract). Returns the file's
     * rewrite edit, or {@code null} when nothing changes.
     */
    private static TextEdit addFinalEdit(CompilationUnit ast) throws CoreException {
        ICleanUpFix fix = VariableDeclarationFixCore.createCleanUp(
            ast, /* addFinalFields */ false, /* addFinalParameters */ true,
            /* addFinalLocals */ true);
        return fix == null ? null : fix.createChange(new NullProgressMonitor()).getEdit();
    }

    /**
     * redundant_modifiers via JDT's {@link RedundantModifiersCleanUp}. The
     * engine's {@code createFix(CompilationUnit)} is protected — the private
     * subclass below is the access path (same package-independent mechanism the
     * IDE's clean-up runner uses through its public context API).
     */
    private static TextEdit redundantModifiersEdit(CompilationUnit ast) throws CoreException {
        ICleanUpFix fix = REDUNDANT_MODIFIERS.fixFor(ast);
        return fix == null ? null : fix.createChange(new NullProgressMonitor()).getEdit();
    }

    private static final RedundantModifiersFixAccess REDUNDANT_MODIFIERS =
        new RedundantModifiersFixAccess();

    private static final class RedundantModifiersFixAccess extends RedundantModifiersCleanUp {
        RedundantModifiersFixAccess() {
            super(Map.of(CleanUpConstants.REMOVE_REDUNDANT_MODIFIERS, "true"));
        }

        ICleanUpFix fixFor(CompilationUnit unit) throws CoreException {
            return createFix(unit);
        }
    }

    /** Leaf text-edit count across an edit tree — reported as {@code editCount}. */
    private static int countLeafEdits(TextEdit edit) {
        if (!edit.hasChildren()) {
            return 1;
        }
        int total = 0;
        for (TextEdit child : edit.getChildren()) {
            total += countLeafEdits(child);
        }
        return total;
    }
}
