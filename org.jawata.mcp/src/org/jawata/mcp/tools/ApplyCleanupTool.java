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
 * (language-idiom upgrades — find-only).</p>
 *
 * <p>WHICH kinds this publishes is no longer written here. This paragraph used to say
 * "the two kinds here", and stage 3 took it to eight — a count in a comment beside a
 * derived list is exactly the second home this class was restructured to remove. The
 * registry below is the one producer; the schema, the dispatch and the description all
 * read from it.</p>
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
public class ApplyCleanupTool extends AbstractApplyingRefactoringTool
        implements KindedTool {

    @Override
    public String discriminator() {
        return "kind";
    }

    /**
     * The rule registry IS the kind list (Stage 6a, M3b).
     *
     * <p>This door's delegates are {@code CleanupRule}s rather than tools, which is why the
     * role is not typed to {@code Tool} — and the map needs no adaptation, because a rule was
     * always required to say its own kind and the registry is already keyed by it.</p>
     */
    @Override
    public java.util.Map<String, KindDelegate> delegates() {
        java.util.Map<String, KindDelegate> published = new java.util.LinkedHashMap<>();
        RULES.forEach((kind, rule) -> published.put(kind, rule));
        return java.util.Collections.unmodifiableMap(published);
    }

    private static final Logger log = LoggerFactory.getLogger(ApplyCleanupTool.class);

    // A LIST, not a Set, and the widened honesty guard is what found it. A JSON
    // schema's `enum` is an ordered array; publishing a Set gave it whatever order
    // Set.of chose that run, so the tool's published contract was not stable between
    // JVMs. Worse for the guard: every check reading the enum tests `instanceof List`,
    // so this tool silently fell out of all of them — the "passes while never looking"
    // shape those guards exist to refuse, sitting inside one of them.
    /**
     * EVERY CLEANUP, and the single source of the kind list.
     *
     * <p>The kinds used to be a two-entry literal beside a ternary that chose between two
     * private methods. This tool takes nine more rows, and that is the shape ExtractTool
     * documents at length: the kind list, the dispatch and the published contract in
     * three places, one of which is always the one that was forgotten.</p>
     *
     * <p>Derived from the registry now, so a rule that exists is dispatched, listed and
     * described, and adding one is a class and a line.</p>
     */
    private static final java.util.LinkedHashMap<String,
            org.jawata.mcp.tools.statements.CleanupRule> RULES = rules();

    private static java.util.LinkedHashMap<String,
            org.jawata.mcp.tools.statements.CleanupRule> rules() {
        java.util.LinkedHashMap<String, org.jawata.mcp.tools.statements.CleanupRule> m =
            new java.util.LinkedHashMap<>();
        for (org.jawata.mcp.tools.statements.CleanupRule rule : java.util.List.of(
                new org.jawata.mcp.tools.statements.JdtCleanupRule("add_final",
                    """
                    mark parameters and local variables `final` when never
                    reassigned (binding-checked, so it never breaks
                    compilation).""",
                    ApplyCleanupTool::addFinalEdit),
                new org.jawata.mcp.tools.statements.JdtCleanupRule("redundant_modifiers",
                    """
                    remove modifiers that are implicit on interface members
                    (public/abstract methods, public/static/final
                    fields, public/static nested types).""",
                    ApplyCleanupTool::redundantModifiersEdit),
                new org.jawata.mcp.tools.statements.GuardClausesRule(),
                new org.jawata.mcp.tools.statements.ConsolidateConditionalRule(),
                new org.jawata.mcp.tools.statements.ControlFlagToBreakRule(),
                new org.jawata.mcp.tools.statements.LoopToPipelineRule(),
                new org.jawata.mcp.tools.statements.SlideStatementsRule(),
                new org.jawata.mcp.tools.statements.SplitLoopRule(),
                new org.jawata.mcp.tools.statements.ReturnModifiedValueRule(),
                new org.jawata.mcp.tools.statements.RemoveDeadCodeRule())) {
            m.put(rule.kind(), rule);
        }
        return m;
    }

    /*
     * KINDS WAS HERE — `List.copyOf(RULES.keySet())`, and RULES is the same map delegates()
     * wraps, so it derived correctly. It went at C6a anyway, with extract's and move's
     * private kinds(), because the seam's own documentation claimed all six routing doors
     * put publishedKinds() into their schema enum and only three did. A private reader that
     * happens to derive today is one edit from being the hand-written list `generate` was
     * caught carrying; asking the interface removes the question.
     */

    /**
     * Kinds whose rewrite MOVES code, so a caller's position cannot narrow it to one
     * member — {@code MemberScope} refuses those with a reason rather than splitting a
     * linked pair of edits.
     *
     * <p>Hand-written, because whether a rewrite emits a move is a property of the EDIT
     * TREE it produces on a given file, not of the kind — nothing static derives it. So it
     * is bound to behaviour the only way it can be: {@code
     * EveryRowIsCallableFromItsFindingTest} measures every row and asserts that the set
     * which refuses equals this list. A kind that starts or stops moving code turns that
     * test red instead of leaving this sentence quietly wrong.</p>
     */
    public static final List<String> POSITION_REFUSING_KINDS = List.of(
        "guard_clauses", "consolidate_conditional", "loop_to_pipeline", "slide_declaration");

    public ApplyCleanupTool(Supplier<IJdtService> serviceSupplier,
                            RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "apply_cleanup";
    }

    /**
     * DERIVED, because this is the whole documentation surface.
     *
     * <p>The kind list here was written by hand beside a two-entry KINDS constant. Nine
     * rows are landing on this tool, and the front-door honesty test exists because a
     * kind reached the enum, the dispatch and the schema of a neighbouring tool while
     * being described nowhere. A description assembled from the rules cannot omit one.</p>
     */
    @Override
    public String getDescription() {
        return org.jawata.mcp.tools.FrontDoorDescription.ASSEMBLER.describe(this);
    }

    @Override
    public String preamble() {
        return """
            Apply a safe, mechanical source clean-up across a file or project.
            Auto-applies by default and returns
            { filesModified, diff, undoChangeId, summary }; pass auto_apply:false
            to stage instead. A no-op returns hasChanges:false.""";
    }

    /**
     * THE ONE DOOR WHOSE USAGE IS A SECTION, NOT A LINE.
     *
     * <p>Four call shapes and a paragraph about which narrowings a moving rewrite refuses.
     * The generated line above is the first shape — whole default project — and this
     * continues it, which is why it opens mid-line with that shape's own annotation.</p>
     */
    @Override
    public String usageNote() {
        // CONCATENATION, not a text block, and deliberately: every line here is aligned
        // against the generated `USAGE: ` prefix that precedes it, and a text block strips
        // the smallest indent its lines share — which would take the two spaces off the
        // first line and five off the rest, silently re-aligning a published block against
        // nothing.
        return "  — whole default project\n"
            + "       apply_cleanup(kind=\"<kind>\", filePath=\"path/to/File.java\")\n"
            + "       apply_cleanup(kind=\"<kind>\", filePath=..., line=N, column=M)\n"
            + "       apply_cleanup(kind=\"<kind>\", symbol=\"pkg.Type#member\")\n"
            + "              — just the member named, which is how a finding about ONE\n"
            + "                method is answered without rewriting the whole file around\n"
            + "                it. The symbol form resolves to the same position.\n"
            + "                REFUSED for a rewrite that MOVES code past that member —\n"
            + "                a move is a linked pair of edits and half of one is not a\n"
            + "                smaller change, so the refusal says so instead. The kinds\n"
            + "                that do: " + String.join(", ", POSITION_REFUSING_KINDS) + ".";
    }

    @Override
    public String kindBlockLeadIn() {
        return "KINDS:";
    }

    @Override
    public String footer() {
        return """
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
        kind.put("enum", publishedKinds());
        kind.put("description", "Which clean-up to apply. See the tool description.");
        properties.put("kind", kind);
        Map<String, Object> filePath = new LinkedHashMap<>();
        filePath.put("type", "string");
        filePath.put("description", "Optional. Restrict to one file; omit to sweep the whole project.");
        properties.put("filePath", filePath);
        properties.put("line", Map.of("type", "integer",
            "description", "Optional, ZERO-BASED. Restrict the rewrite to the member at this "
                + "position — the way a finding names one method. Needs filePath."));
        properties.put("column", Map.of("type", "integer",
            "description", "Optional, ZERO-BASED column of that position (default 0)."));
        properties.put("symbol", org.jawata.mcp.tools.shared.FqnTarget.symbolSchemaProperty(
            "the member to clean up — the same narrowing as line/column, addressed the way "
                + "a finding names it"));
        // The backstop, for uniformity rather than for an outstanding gap: a CleanupRule's
        // parameterSchema() is empty and truthfully so — apply_cleanup is a sweep and no
        // rule adds a parameter of its own, which is exactly why row 8 could not live here.
        // Calling it anyway means the next rule that DOES declare one is published without
        // anyone noticing it needed to be.
        schema.put("properties", withDelegateParameters(properties));
        schema.put("required", List.of("kind"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        // NAME FORM FIRST. The per-row contract says every row must be callable from the
        // finding that names it "by symbol name AND by file position", and a finding
        // carries the symbol. This resolves `symbol=pkg.Type#member` into the filePath and
        // position read below — the same helper refactor_to_pattern uses, so the two front
        // doors cannot drift on what a symbol means.
        java.util.Optional<ToolResponse> nameForm =
            org.jawata.mcp.tools.shared.FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return Preparation.fail(nameForm.get());
        }

        String kind = getStringParam(arguments, "kind");
        if (kind == null || kind.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("kind",
                "kind is required; one of " + publishedKinds()));
        }
        if (!delegates().containsKey(kind)) {
            return Preparation.fail(ToolResponse.invalidParameter("kind",
                "Unknown kind '" + kind + "'. Allowed: " + publishedKinds()));
        }

        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", 0);
        boolean scopedToMember = line >= 0;

        List<Path> targets = new ArrayList<>();
        String filePath = getStringParam(arguments, "filePath");
        if (scopedToMember && (filePath == null || filePath.isBlank())) {
            // A position with no file is not a narrower request, it is an ambiguous one.
            return Preparation.fail(ToolResponse.invalidParameter("line",
                "A position needs the file it is in — pass filePath with line/column. "
                    + "Without it there is no way to know which file the line belongs to."));
        }
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

            TextEdit edit = RULES.get(kind).edit(ast);
            if (scopedToMember) {
                // The caller pointed at one member, so answer about that member. THREE
                // outcomes, and the third is why this is not a null check: a rewrite that
                // moves code across the member's boundary cannot be narrowed at all, and
                // reporting that as "nothing to change here" would be a silent lie.
                org.jawata.mcp.tools.shared.MemberScope.Result scoped =
                    org.jawata.mcp.tools.shared.MemberScope.restrict(edit, ast, line, column);
                if (scoped.refusal() != null) {
                    return Preparation.fail(
                        ToolResponse.invalidParameter("line/column", scoped.refusal()));
                }
                edit = scoped.edit();
            }
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
        if (scopedToMember) {
            extras.put("scopedToMemberAt", line + ":" + column);
        }
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
    static TextEdit addFinalEdit(CompilationUnit ast) throws CoreException {
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
    static TextEdit redundantModifiersEdit(CompilationUnit ast) throws CoreException {
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
