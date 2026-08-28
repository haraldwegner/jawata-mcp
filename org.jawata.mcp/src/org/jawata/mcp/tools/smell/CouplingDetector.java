package org.jawata.mcp.tools.smell;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CharacterLiteral;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MarkerAnnotation;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NormalAnnotation;
import org.eclipse.jdt.core.dom.NullLiteral;
import org.eclipse.jdt.core.dom.NumberLiteral;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.ParameterizedType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleType;
import org.eclipse.jdt.core.dom.SingleMemberAnnotation;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.Type;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.domain.Findings;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.SourceScan;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 28d — <b>coupling, reported as connascence</b> (Meilir Page-Jones). Two
 * pieces of code are <em>connascent</em> when changing one forces a matching change
 * in the other. This detector measures that per <b>package</b> and reports it on
 * connascence's three axes:
 *
 * <ul>
 *   <li><b>STRENGTH</b> — how hard the agreement is to change, as a <em>named
 *       form</em>, never a bare number. Weakest first: Connascence of
 *       <b>Name</b> (the parties agree on an identifier), of <b>Type</b> (they
 *       agree on a type), of <b>Meaning</b> (they agree on what a bare literal
 *       signifies — nothing in the type system carries that agreement), of
 *       <b>Position</b> (they agree on argument ORDER, which no compiler checks
 *       once the types match).</li>
 *   <li><b>DEGREE</b> — how many components a change would reach: the number of
 *       other packages in the corpus that depend on this one (its dependents).
 *       Reported alongside the breadth of what it itself depends on.</li>
 *   <li><b>LOCALITY</b> — how far apart the coupled parties are: how many of the
 *       package's connascence sites cross its own package boundary versus how
 *       many stay inside it.</li>
 * </ul>
 *
 * <p>The cure sentence is <b>Page-Jones's rule</b>: minimise connascence ACROSS
 * encapsulation boundaries, maximise it WITHIN. High connascence inside one
 * package is cohesion; the same connascence spread across packages is the
 * expensive kind, because every party to it must be found and changed together.</p>
 *
 * <h2>The metric</h2>
 * <p>For each package P, {@code crossWeight(P)} = the sum, over every connascence
 * <em>site</em> in P whose counterpart lives in a <em>different package of the
 * scanned corpus</em>, of that site's strength rank (Name 1, Type 2, Meaning 3,
 * Position 4). The ranks are the published ordering positions of the forms — not
 * numbers tuned on any repository. References to types outside the corpus (the
 * JDK, third-party jars) are deliberately NOT counted: Page-Jones's rule is about
 * <em>your</em> encapsulation boundaries, and counting {@code java.lang.String}
 * would turn the measure into "how much JDK does this package use".</p>
 *
 * <h2>The flag is a QUANTILE, never a constant</h2>
 * <p>A package is flagged when its {@code crossWeight} is <b>at or above the
 * q-th percentile of the corpus's own distribution</b> AND <b>strictly above that
 * corpus's median</b>. {@code threshold} <em>is</em> q, expressed as a percentile
 * and defaulting to 90 — the top decile of efferent connascence among the
 * packages actually scanned. Both cuts are order statistics of the population
 * being measured, so the instrument does not have to be re-tuned when the corpus
 * grows, shrinks, or changes character; a number fitted to one repository would
 * be a fit, not a rule.</p>
 *
 * <p>The median clause is what makes the quantile safe on a degenerate corpus. A
 * scan of one package (or of a corpus where every package couples equally) has no
 * outlier by construction: nothing is strictly above the median, so nothing is
 * flagged. A percentile alone would have flagged the single package in a one-file
 * scan at 100%, which is not a finding but an arithmetic artefact.</p>
 *
 * <h2>What this cannot see, and does not pretend to</h2>
 * <p>Only the <em>static</em> connascence forms are resolvable from source.
 * Connascence of Algorithm (two parties independently implementing the same rule)
 * and the dynamic forms (Execution, Timing, Value, Identity) need whole-program
 * or runtime reasoning, so they are neither detected nor reported as absent — a
 * package whose only connascence is dynamic scores zero here. Other deliberate
 * limits: an {@code import} is not counted (it is not a use; the use in the body
 * is), a bare statically-imported constant is missed (only qualified field reads
 * and {@code FieldAccess} are counted), and a site is classified by the
 * <em>strongest</em> form it exhibits, so a multi-argument call carrying a magic
 * literal is scored Position rather than counted twice.</p>
 *
 * <p>Aggregating per package over the whole corpus is what a quantile requires, so
 * — like {@link DataClumpsDetector} — this implements {@link Detector} directly
 * and reuses {@link AbstractAstDetector}'s argument helpers and scan-honesty
 * contract rather than its per-file {@code analyze} loop.</p>
 */
public final class CouplingDetector implements Detector {

    /** Percentile used when the caller omits {@code threshold}: the top decile. */
    private static final int DEFAULT_PERCENTILE = 90;

    /** What this scan is looking for, in the sentence-fragment form {@link SourceScan} wants. */
    private static final String NOUN = "over-coupled packages";

    /** Page-Jones's rule — the cure sentence every finding carries. */
    private static final String PAGE_JONES_RULE =
        "Page-Jones's rule: minimise connascence ACROSS encapsulation boundaries, maximise it "
            + "WITHIN — move the coupled parties into one package, or narrow the interface they "
            + "agree on so the agreement is weaker (Position -> Type -> Name).";

    /**
     * The static connascence forms, weakest first. {@link #rank()} is the form's
     * position in that published ordering — an ordinal, not a tuned constant.
     */
    enum Form {
        NAME("Connascence of Name"),
        TYPE("Connascence of Type"),
        MEANING("Connascence of Meaning"),
        POSITION("Connascence of Position");

        private final String label;

        Form(String label) {
            this.label = label;
        }

        String label() {
            return label;
        }

        int rank() {
            return ordinal() + 1;
        }
    }

    /** One scanned compilation unit: where it lives, and its sites per target package. */
    private record Unit(String packageName, String filePath, int line, Map<String, int[]> byTarget) {
    }

    @Override
    public String kind() {
        return "coupling";
    }

    @Override
    public String description() {
        return "Coupling as connascence (Page-Jones), per package: STRENGTH as a named form "
            + "(Name/Type/Meaning/Position), DEGREE as the count of dependent packages, LOCALITY "
            + "as sites crossing the package boundary vs staying within. Flags packages whose "
            + "cross-boundary connascence weight is at or above the `threshold` PERCENTILE "
            + "(default 90 = the top decile) of the scanned corpus AND above its median — a "
            + "quantile over the population measured, never an absolute cutoff.";
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        int percentile = clampPercentile(
            AbstractAstDetector.readInt(arguments, "threshold", DEFAULT_PERCENTILE));
        SourceScan scan;
        List<Unit> units = new ArrayList<>();
        try {
            scan = SourceScan.of(AbstractAstDetector.scopedSourceFiles(service, arguments));
            for (Path path : scan.files()) {
                ICompilationUnit cu = scan.resolve(service, path);
                if (cu == null) {
                    continue;   // RECORDED, not swallowed — see SourceScan
                }
                CompilationUnit ast = scan.parse(cu, path, false);
                if (ast == null) {
                    continue;
                }
                scan.examined();
                units.add(collect(ast, service.getPathUtils().formatPath(path)));
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        // The distribution IS the instrument here: a quantile computed over files we
        // failed to open is a cut through a population we never saw.
        Optional<ToolResponse> blind = scan.refuseIfBlind(NOUN);
        if (blind.isPresent()) {
            return blind.get();
        }
        return report(units, scan, percentile);
    }

    // ------------------------------------------------------------------ collect

    /** Walk one unit and tally its connascence sites by target package and form. */
    private static Unit collect(CompilationUnit ast, String filePath) {
        PackageDeclaration pkg = ast.getPackage();
        String owner = pkg != null ? pkg.getName().getFullyQualifiedName() : "";
        int line = pkg != null ? ast.getLineNumber(pkg.getStartPosition()) : 1;
        Map<String, int[]> byTarget = new LinkedHashMap<>();
        ast.accept(new SiteVisitor(byTarget));
        return new Unit(owner, filePath, Math.max(line, 1), byTarget);
    }

    /**
     * Classifies every connascence site in one compilation unit. Each site is one
     * AST occurrence, scored by the strongest form it exhibits; the visits are
     * chosen so no occurrence is counted twice (in particular the type name of a
     * {@code new Foo(a, b)} is scored by the creation, not again as a type use).
     */
    private static final class SiteVisitor extends ASTVisitor {

        private final Map<String, int[]> byTarget;

        SiteVisitor(Map<String, int[]> byTarget) {
            this.byTarget = byTarget;
        }

        /** An import is not a use — the use in the body is, and counting both double-counts. */
        @Override
        public boolean visit(ImportDeclaration node) {
            return false;
        }

        @Override
        public boolean visit(PackageDeclaration node) {
            return false;
        }

        /** A type in a declaration/cast/instanceof position: the parties agree on a TYPE. */
        @Override
        public boolean visit(SimpleType node) {
            if (!isCreationTypeName(node)) {
                record(node.resolveBinding(), Form.TYPE);
            }
            return true;
        }

        @Override
        public boolean visit(ClassInstanceCreation node) {
            record(node.getType().resolveBinding(), formOf(node.arguments()));
            return true;
        }

        @Override
        public boolean visit(MethodInvocation node) {
            IMethodBinding binding = node.resolveMethodBinding();
            if (binding != null) {
                record(binding.getDeclaringClass(), formOf(node.arguments()));
            }
            return true;
        }

        @Override
        public boolean visit(SuperMethodInvocation node) {
            IMethodBinding binding = node.resolveMethodBinding();
            if (binding != null) {
                record(binding.getDeclaringClass(), formOf(node.arguments()));
            }
            return true;
        }

        /** {@code obj.field} — the parties agree on a NAME. */
        @Override
        public boolean visit(FieldAccess node) {
            IVariableBinding field = node.resolveFieldBinding();
            if (field != null && field.isField()) {
                record(field.getDeclaringClass(), Form.NAME);
            }
            return true;
        }

        /** {@code Constants.MAX}, {@code Colour.RED} — again an agreement on a NAME. */
        @Override
        public boolean visit(QualifiedName node) {
            if (node.resolveBinding() instanceof IVariableBinding field && field.isField()) {
                record(field.getDeclaringClass(), Form.NAME);
            }
            return true;
        }

        @Override
        public boolean visit(MarkerAnnotation node) {
            record(node.resolveTypeBinding(), Form.NAME);
            return true;
        }

        @Override
        public boolean visit(NormalAnnotation node) {
            record(node.resolveTypeBinding(), Form.NAME);
            return true;
        }

        @Override
        public boolean visit(SingleMemberAnnotation node) {
            record(node.resolveTypeBinding(), Form.NAME);
            return true;
        }

        private void record(ITypeBinding type, Form form) {
            if (type == null) {
                return;
            }
            ITypeBinding resolved = type.getErasure() != null ? type.getErasure() : type;
            while (resolved != null && resolved.isArray()) {
                resolved = resolved.getElementType();
            }
            if (resolved == null || resolved.isPrimitive() || resolved.isTypeVariable()
                || resolved.isWildcardType() || resolved.isNullType()
                || resolved.getPackage() == null) {
                return;
            }
            byTarget
                .computeIfAbsent(resolved.getPackage().getName(), k -> new int[Form.values().length])
                [form.ordinal()]++;
        }
    }

    /**
     * The form a call site exhibits — the STRONGEST one, so a two-argument call
     * carrying a magic literal counts once, as Position.
     */
    private static Form formOf(List<?> arguments) {
        if (arguments.size() >= 2) {
            return Form.POSITION;         // the parties must agree on ORDER
        }
        if (arguments.size() == 1 && isLiteral(arguments.get(0))) {
            return Form.MEANING;          // ... and on what a bare literal signifies
        }
        return Form.NAME;                 // ... otherwise only on the member's name
    }

    private static boolean isLiteral(Object argument) {
        return argument instanceof NumberLiteral || argument instanceof StringLiteral
            || argument instanceof CharacterLiteral || argument instanceof BooleanLiteral
            || argument instanceof NullLiteral;
    }

    /**
     * True when this type node is the type being instantiated by a
     * {@code new ...()} — that occurrence is scored by the creation itself, so
     * counting it here as well would double-count one site. A type ARGUMENT of a
     * parameterized creation is NOT this node, and stays counted.
     */
    private static boolean isCreationTypeName(Type node) {
        ASTNode child = node;
        ASTNode parent = node.getParent();
        if (parent instanceof ParameterizedType parameterized && parameterized.getType() == child) {
            child = parent;
            parent = parent.getParent();
        }
        return parent instanceof ClassInstanceCreation creation && creation.getType() == child;
    }

    // ------------------------------------------------------------------- report

    private ToolResponse report(List<Unit> units, SourceScan scan, int percentile) {
        Set<String> corpus = new LinkedHashSet<>();
        for (Unit unit : units) {
            corpus.add(unit.packageName());
        }

        Map<String, int[]> crossForms = new LinkedHashMap<>();
        Map<String, Integer> withinSites = new LinkedHashMap<>();
        Map<String, Set<String>> dependsOn = new LinkedHashMap<>();
        Map<String, Set<String>> dependents = new LinkedHashMap<>();
        for (String pkg : corpus) {
            crossForms.put(pkg, new int[Form.values().length]);
            withinSites.put(pkg, 0);
            dependsOn.put(pkg, new LinkedHashSet<>());
            dependents.put(pkg, new LinkedHashSet<>());
        }

        for (Unit unit : units) {
            String owner = unit.packageName();
            for (Map.Entry<String, int[]> entry : unit.byTarget().entrySet()) {
                String target = entry.getKey();
                int[] counts = entry.getValue();
                if (target.equals(owner)) {
                    withinSites.merge(owner, sites(counts), Integer::sum);
                } else if (corpus.contains(target) && sites(counts) > 0) {
                    int[] accumulated = crossForms.get(owner);
                    for (int i = 0; i < counts.length; i++) {
                        accumulated[i] += counts[i];
                    }
                    dependsOn.get(owner).add(target);
                    dependents.get(target).add(owner);
                }
                // A target outside the corpus (JDK, a jar) is not OUR encapsulation
                // boundary, so it is not connascence this rule can ask anyone to fix.
            }
        }

        int[] distribution = corpus.stream()
            .mapToInt(pkg -> weight(crossForms.get(pkg))).sorted().toArray();
        int packages = distribution.length;
        int cut = percentileOf(distribution, percentile);
        double median = medianOf(distribution);

        List<String> flagged = corpus.stream()
            .filter(pkg -> {
                int w = weight(crossForms.get(pkg));
                return w >= cut && w > median;
            })
            .sorted(Comparator.comparingInt((String pkg) -> weight(crossForms.get(pkg))).reversed()
                .thenComparing(Comparator.naturalOrder()))
            .toList();

        List<Finding> out = new ArrayList<>();
        for (String pkg : flagged) {
            Unit anchor = anchorFor(units, pkg, corpus);
            out.add(new Finding("coupling",
                anchor != null ? anchor.filePath() : null,
                anchor != null ? anchor.line() : -1,
                -1, "warning",
                message(pkg, crossForms.get(pkg), withinSites.get(pkg), dependents.get(pkg),
                    dependsOn.get(pkg), packages, percentile, cut, median),
                pkg));
        }

        Map<String, Object> described = new LinkedHashMap<>(scan.describe());
        described.put("packagesScanned", packages);
        described.put("percentile", percentile);
        described.put("cutWeight", cut);
        described.put("medianWeight", median);
        return Findings.toResponse(out, described, steering(scan, out.size(), packages));
    }

    /** The file in {@code pkg} carrying the most cross-boundary sites — where to start reading. */
    private static Unit anchorFor(List<Unit> units, String pkg, Set<String> corpus) {
        Unit best = null;
        int bestSites = -1;
        for (Unit unit : units) {
            if (!unit.packageName().equals(pkg)) {
                continue;
            }
            int sites = 0;
            for (Map.Entry<String, int[]> entry : unit.byTarget().entrySet()) {
                if (!entry.getKey().equals(pkg) && corpus.contains(entry.getKey())) {
                    sites += sites(entry.getValue());
                }
            }
            if (sites > bestSites) {
                bestSites = sites;
                best = unit;
            }
        }
        return best;
    }

    private static String message(String pkg, int[] cross, int within, Set<String> dependents,
                                  Set<String> dependsOn, int packages, int percentile,
                                  int cut, double median) {
        int crossSites = sites(cross);
        return "Package '" + pkg + "' carries cross-boundary connascence weight " + weight(cross)
            + " — at or above the " + percentile + "th percentile of the " + packages
            + " package(s) scanned (cut " + cut + ", median " + median + "). "
            + "STRENGTH: " + strongest(cross).label() + " is the strongest form present ("
            + breakdown(cross) + "). "
            + "DEGREE: " + dependents.size() + " dependent package(s)"
            + (dependents.isEmpty() ? "" : " " + dependents)
            + ", and it depends on " + dependsOn.size() + " package(s) itself. "
            + "LOCALITY: " + crossSites + " of " + (crossSites + within)
            + " connascence site(s) cross the package boundary, " + within + " stay within. "
            + PAGE_JONES_RULE;
    }

    /** Per-form site counts, strongest first — the named forms, not one blended number. */
    private static String breakdown(int[] counts) {
        List<String> parts = new ArrayList<>();
        Form[] forms = Form.values();
        for (int i = forms.length - 1; i >= 0; i--) {
            if (counts[i] > 0) {
                parts.add(counts[i] + " x " + forms[i].label());
            }
        }
        return String.join(", ", parts);
    }

    private static Form strongest(int[] counts) {
        Form found = Form.NAME;
        for (Form form : Form.values()) {
            if (counts[form.ordinal()] > 0) {
                found = form;
            }
        }
        return found;
    }

    /** Sum of {@code rank(form) * sites(form)} — the strength-weighted site count. */
    private static int weight(int[] counts) {
        int total = 0;
        for (Form form : Form.values()) {
            total += counts[form.ordinal()] * form.rank();
        }
        return total;
    }

    private static int sites(int[] counts) {
        int total = 0;
        for (int count : counts) {
            total += count;
        }
        return total;
    }

    /**
     * Nearest-rank percentile of an ascending distribution: the smallest value at
     * or above which {@code (100 - p)%} of the population sits. An order statistic
     * of the scanned corpus — it moves with the corpus, which is the point.
     */
    private static int percentileOf(int[] ascending, int percentile) {
        if (ascending.length == 0) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile / 100.0 * ascending.length);
        return ascending[Math.min(ascending.length - 1, Math.max(0, rank - 1))];
    }

    private static double medianOf(int[] ascending) {
        int n = ascending.length;
        if (n == 0) {
            return 0;
        }
        return n % 2 == 1
            ? ascending[n / 2]
            : (ascending[n / 2 - 1] + ascending[n / 2]) / 2.0;
    }

    /** A percentile is only defined on 0..100; this is arithmetic domain, not tuning. */
    private static int clampPercentile(int requested) {
        return Math.min(100, Math.max(1, requested));
    }

    private static String steering(SourceScan scan, int found, int packages) {
        if (scan.incomplete()) {
            return scan.steering(found, NOUN);
        }
        if (packages < 2) {
            return "NO POPULATION TO RANK AGAINST: this scan covered " + packages + " package(s), "
                + "and an outlier is defined relative to its own corpus — with fewer than two "
                + "packages nothing can be above the median, so ZERO here means the question was "
                + "unanswerable, not that the coupling is fine. Drop `filePath` (or widen the "
                + "project scope) and re-run.";
        }
        return scan.steering(found, NOUN);
    }
}
