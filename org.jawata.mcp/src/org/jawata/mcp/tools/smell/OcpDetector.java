package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.CompilationUnit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.ArrayList;
import java.util.List;

/**
 * Sprint 28d — <b>Open/Closed</b> (Meyer/Martin) as a first-class kind. Software
 * should be open for extension and closed for modification: adding a case should
 * add code, not edit existing code.
 *
 * <h2>This is an AGGREGATION, and says so</h2>
 * <p>It contains no new analysis. Sprint 20 deliberately shipped no {@code ocp}
 * detector, reasoning (in {@link OcpCure}) that an OCP violation is only
 * meaningful relative to the extension axis you intend to keep open — design
 * intent, not a code property. That reasoning still holds and this kind does not
 * overturn it. What it fixes is a <em>reachability</em> gap: the two observable
 * traces of a closed-for-extension design were already detected
 * ({@code switch_statements}, {@code type_code}) and {@link OcpCure} already
 * answered with recipes, but nothing named {@code ocp} existed, so a reader
 * sweeping a family for the principle found nothing and concluded the principle
 * was unmeasured. This kind is the name, over the existing measurements.</p>
 *
 * <p>Concretely: it runs {@link SwitchStatementsDetector} and
 * {@link TypeCodeDetector} over the same compilation unit and the same
 * {@code threshold} (both default to 3 — the same number means the same thing in
 * both: how many cases/constants make a group), then re-labels each finding
 * {@code ocp} and appends the cure — the {@code refactor_to_pattern} recipes
 * {@link RecipeCatalog} maps that trace to. Nothing is re-implemented, so the two
 * kinds can never drift apart from this one.</p>
 *
 * <h2>What is deliberately NOT collapsed</h2>
 * <p>A class that declares a type-code constant group AND switches on it yields
 * <b>two</b> {@code ocp} findings, not one. That is the measurement, not a
 * duplicate: each is a separate place the source must be EDITED when a new case
 * arrives, and the count of such places is exactly the cost OCP is about. The two
 * findings carry different symbols (the class for the constant group, the
 * enclosing method for the switch) and different lines, so they are separately
 * addressable.</p>
 *
 * <h2>The exclusions, inherited from the traces</h2>
 * <ul>
 *   <li><b>Enum switches</b> — not flagged ({@code switch_statements}' rule). A
 *       switch over an enum is already closed over a named, compiler-checked set;
 *       adding a constant is a deliberate, visible edit.</li>
 *   <li><b>Switches with fewer than {@code threshold} cases</b> and
 *       <b>constant groups smaller than {@code threshold}</b> — below the group
 *       size there is no axis yet, only a conditional.</li>
 *   <li><b>The churn traces</b> {@code divergent_change} /
 *       {@code shotgun_surgery} are NOT folded in, even though {@link OcpCure}
 *       serves them the same recipes. They are git-history detectors: they answer
 *       from commit churn, not from the AST, and merging a history verdict into a
 *       source verdict under one kind would make it impossible to tell which
 *       evidence a finding rests on. Sweep them by their own names.</li>
 * </ul>
 */
public final class OcpDetector extends AbstractAstDetector {

    /**
     * The traces. Held as instances, not re-implemented — a change to either
     * detector's rule reaches this kind automatically, which is the only way two
     * names over one measurement stay honest.
     */
    private final SwitchStatementsDetector switchStatements = new SwitchStatementsDetector();
    private final TypeCodeDetector typeCodes = new TypeCodeDetector();

    public OcpDetector() {
        super("ocp",
            "Open/Closed — the source traces of a design that must be EDITED to extend: a switch "
                + "on a type code, and a type-code constant group (>= `threshold`, default 3). An "
                + "aggregation of switch_statements + type_code re-labelled `ocp` and carrying the "
                + "refactor_to_pattern recipes that close the axis; no new analysis. Enum switches "
                + "are not flagged, and the git-history OCP traces (divergent_change, "
                + "shotgun_surgery) keep their own kinds.",
            3);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        List<Finding> traces = new ArrayList<>();
        switchStatements.analyze(ast, filePath, service, threshold, traces);
        typeCodes.analyze(ast, filePath, service, threshold, traces);
        for (Finding trace : traces) {
            out.add(relabel(trace));
        }
    }

    /** The same finding, named for the principle and carrying the principle's cure. */
    private static Finding relabel(Finding trace) {
        List<String> recipes = RecipeCatalog.recipesFor(trace.kind());
        String cure = recipes.isEmpty()
            ? OcpCure.HINT
            : " OCP cure: introduce an abstraction at the modification axis — refactor_to_pattern "
                + "kind=" + String.join(" / ", recipes)
                + " (or refactoring(action=plan, kind=<same>) then apply_plan for a parity-gated "
                + "run).";
        return new Finding("ocp", trace.filePath(), trace.line(), trace.column(), trace.severity(),
            "Open/Closed: this code must be MODIFIED to extend it. " + trace.message()
                + " [trace: " + trace.kind() + "]" + cure,
            trace.symbol());
    }
}
