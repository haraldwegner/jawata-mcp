package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.jawata.core.IJdtService;
import org.jawata.core.project.SourceRootClassifier;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.domain.Findings;
import org.jawata.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Sprint 28 Stage 4 (D-UNWIRED) — <b>called_only_by_tests</b>: a public member
 * of PRODUCTION code whose every caller is test code.
 *
 * <p>The founding failure: v3.4.0 released its central deliverable INERT — the
 * enabling overload had 15 callers, and all 15 were tests. The suite was
 * 1591/1591, coverage ROSE, and the capability was dead in production. Zero
 * callers is the ordinary unused check and is NOT this; "called, but only by
 * tests" is the shape that catches hollowness, because the tests are what keep
 * such a member looking alive.</p>
 *
 * <p>ONE classifier: test-ness of the referencing file comes from
 * {@link SourceRootClassifier} — the model attribute the importer records —
 * never from a path or name heuristic of this detector's own. A second place
 * to know test-ness is how mcp#9 was born.</p>
 *
 * <p>Mechanics: a single AST pass over every source file, main and test.
 * Phase 1 per file: record every public method/constructor/field DECLARED in a
 * MAIN root. Phase 2 (same pass): resolve every reference and mark whether a
 * production (MAIN or cross-cutting) or a TEST file made it. A member with
 * test references and no production references is the finding. Bindings are
 * normalized through {@code getMethodDeclaration()}/{@code
 * getVariableDeclaration()} so parameterized references meet their generic
 * declaration.</p>
 *
 * <p>Deliberately skipped as declarations: {@code @Override}-annotated members
 * (dispatched polymorphically — the production caller calls the supertype),
 * {@code main(String[])} entry points (called by the JVM), and members of
 * annotation/interface types (implemented, not called).</p>
 */
public final class TestOnlyCallerDetector implements Detector {

    @Override
    public String kind() {
        return "called_only_by_tests";
    }

    @Override
    public String description() {
        return "Public production members whose EVERY caller is test code — the hollow-capability "
            + "shape (v3.4.0 shipped its central deliverable with 15 callers, all tests). Zero "
            + "callers is the ordinary unused check, not this. Test-ness comes from the model's "
            + "source-root attribute, never a path heuristic.";
    }

    /**
     * How one reference is attributed. Package-private so the truth table can
     * be asserted directly: the end-to-end {@code CROSS_CUTTING} path is
     * reachable only through a {@link org.eclipse.jdt.core.JavaModelException}
     * inside the classifier — a file outside every source root is not listed
     * at all — so no fixture can seed it, and this seam is where the fix is
     * falsifiable (C4 audit, finding 3).
     */
    enum Attribution { PRODUCTION, TEST, UNKNOWN }

    /**
     * THREE outcomes, not two. Reading this as {@code verdict != TEST ?
     * PRODUCTION : TEST} is the defect: it turns "we could not tell" into
     * "production", which silently deletes a finding.
     */
    static Attribution attribute(SourceRootClassifier.Verdict verdict) {
        return switch (verdict) {
            case MAIN -> Attribution.PRODUCTION;
            case TEST -> Attribution.TEST;
            case CROSS_CUTTING -> Attribution.UNKNOWN;
        };
    }

    /**
     * Whether the evidence entitles this scan to say "every caller is a test".
     * One unplaceable reference is enough to forfeit that claim.
     */
    static boolean reportable(int testRefs, boolean productionRef, boolean unknownRef) {
        return testRefs > 0 && !productionRef && !unknownRef;
    }

    /** What one pass learns about a declared member. */
    private static final class MemberRecord {
        String filePath;
        int line;
        String symbol;
        int testRefs;
        boolean productionRef;
        /**
         * A reference from a file whose main-vs-test nature could NOT be
         * determined. Neither a production nor a test reference — and the
         * difference is the whole finding, so a member carrying one is not
         * reported at all (C4 audit, finding 3).
         */
        boolean unknownRef;
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        long started = System.nanoTime();
        Map<String, MemberRecord> declared = new LinkedHashMap<>();
        List<String> unreadable = new ArrayList<>();
        List<String> bindingsDead = new ArrayList<>();
        List<String> unclassified = new ArrayList<>();
        int listed = 0;
        int examined = 0;

        try {
            List<Path> files = service.getAllJavaFiles();
            listed = files.size();

            // One parse per file; two logical phases share it. Declarations are
            // only harvested from MAIN files, references from every file — and
            // a reference seen before its declaration is kept until the
            // declaration arrives (the map entry is created lazily either way).
            List<ParsedUnit> units = new ArrayList<>();
            for (Path path : files) {
                ICompilationUnit cu = service.getCompilationUnit(path);
                if (cu == null) {
                    unreadable.add(String.valueOf(path));
                    continue;
                }
                CompilationUnit ast = AbstractAstDetector.parse(cu);
                if (ast == null || !ast.getAST().hasResolvedBindings()) {
                    bindingsDead.add(String.valueOf(path));
                    continue;
                }
                examined++;
                SourceRootClassifier.Verdict verdict =
                    SourceRootClassifier.classify(cu.getResource());
                units.add(new ParsedUnit(ast,
                    service.getPathUtils().formatPath(path), verdict));
            }

            for (ParsedUnit unit : units) {
                if (unit.verdict == SourceRootClassifier.Verdict.MAIN) {
                    harvestDeclarations(unit, declared);
                } else if (unit.verdict == SourceRootClassifier.Verdict.CROSS_CUTTING) {
                    // Not harvested — and that omission is a hole in the answer,
                    // not a fact about the code. Counted so it is visible.
                    unclassified.add(unit.filePath);
                }
            }
            for (ParsedUnit unit : units) {
                markReferences(unit, declared);
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        if (examined == 0 && listed > 0) {
            return ToolResponse.error("SCAN_EXAMINED_NOTHING",
                "This scan listed " + listed + " source file(s) and could not read any of them"
                    + " — 'no test-only callers' would be a claim about code never opened.",
                org.jawata.mcp.tools.shared.SourceScan.AGENT_CONTRACT);
        }

        List<Finding> findings = new ArrayList<>();
        int withheld = 0;
        for (MemberRecord r : declared.values()) {
            if (r.testRefs > 0 && !r.productionRef && r.unknownRef) {
                // Every caller MIGHT be a test — but one of them came from a
                // file we could not place, so "every caller is test code" is
                // not something this scan is entitled to say.
                withheld++;
                continue;
            }
            if (reportable(r.testRefs, r.productionRef, r.unknownRef)) {
                findings.add(new Finding(kind(), r.filePath, r.line, -1, "warning",
                    "public " + r.symbol + " is called ONLY by tests (" + r.testRefs
                        + " test reference(s), zero production callers) — a capability kept"
                        + " alive by its tests is the hollow-wiring shape; wire it or"
                        + " delete it.",
                    r.symbol));
            }
        }

        long elapsedMs = (System.nanoTime() - started) / 1_000_000;
        Map<String, Object> scan = new LinkedHashMap<>();
        scan.put("filesListed", listed);
        scan.put("filesExamined", examined);
        scan.put("publicMainMembersTracked", declared.size());
        scan.put("elapsedMs", elapsedMs);
        int missed = unreadable.size() + bindingsDead.size();
        if (missed > 0) {
            scan.put("filesMissed", missed);
        }
        // An unplaceable file is its own kind of hole: the file WAS read, so it
        // is not "missed", but its half of the main-vs-test question went
        // unanswered — which is the only question this detector asks.
        if (!unclassified.isEmpty()) {
            scan.put("filesUnclassified", unclassified.size());
        }
        if (withheld > 0) {
            scan.put("findingsWithheld", withheld);
        }
        boolean incomplete = missed > 0 || !unclassified.isEmpty();
        if (incomplete) {
            scan.put("scanIncomplete", true);
        }
        return Findings.toResponse(findings, scan,
            incomplete
                ? "PARTIAL SCAN: " + missed + " file(s) unread and " + unclassified.size()
                    + " file(s) whose main-vs-test nature could not be determined"
                    + (withheld > 0 ? " (" + withheld + " member(s) withheld — every caller"
                        + " MAY be a test, but one came from a file we could not place)" : "")
                    + ". These findings are what survived, not what exists."
                : findings.isEmpty()
                    ? "None found — and the scan was COMPLETE (" + examined + " files, "
                        + declared.size() + " public production members tracked)."
                    : null);
    }

    private record ParsedUnit(CompilationUnit ast, String filePath,
                              SourceRootClassifier.Verdict verdict) {
    }

    private static void harvestDeclarations(ParsedUnit unit, Map<String, MemberRecord> declared) {
        unit.ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                IMethodBinding binding = node.resolveBinding();
                if (binding == null) {
                    return true;
                }
                int flags = node.getModifiers();
                boolean isPublic = Modifier.isPublic(flags);
                boolean overrides = ((List<?>) node.modifiers()).stream().anyMatch(
                    m -> m instanceof org.eclipse.jdt.core.dom.Annotation a
                        && "Override".equals(a.getTypeName().getFullyQualifiedName()));
                boolean isMain = "main".equals(node.getName().getIdentifier())
                    && Modifier.isStatic(flags);
                boolean inInterface = binding.getDeclaringClass() != null
                    && binding.getDeclaringClass().isInterface();
                if (isPublic && !overrides && !isMain && !inInterface) {
                    record(declared, binding.getMethodDeclaration().getKey(), unit,
                        unit.ast.getLineNumber(node.getName().getStartPosition()),
                        describe(binding));
                }
                return true;
            }

            @Override
            public boolean visit(FieldDeclaration node) {
                if (!Modifier.isPublic(node.getModifiers())) {
                    return true;
                }
                for (Object f : node.fragments()) {
                    VariableDeclarationFragment frag = (VariableDeclarationFragment) f;
                    IVariableBinding binding = frag.resolveBinding();
                    if (binding != null) {
                        record(declared, binding.getVariableDeclaration().getKey(), unit,
                            unit.ast.getLineNumber(frag.getName().getStartPosition()),
                            describe(binding));
                    }
                }
                return true;
            }
        });
    }

    private static void record(Map<String, MemberRecord> declared, String key, ParsedUnit unit,
            int line, String symbol) {
        MemberRecord r = declared.computeIfAbsent(key, k -> new MemberRecord());
        r.filePath = unit.filePath;
        r.line = line;
        r.symbol = symbol;
    }

    /**
     * Name the member — and for a method, name the OVERLOAD.
     *
     * <p>Found live on jawata's own repository: the bare form reported
     * {@code PurityCheck#check is called only by tests}, which reads as "the
     * parity gate is hollow". It is not — the two-argument convenience
     * overload is test-only while the three-argument one the plan pipeline
     * calls is wired. A finding about one overload must not be readable as a
     * claim about its sibling, so the parameter list is part of the name.</p>
     */
    private static String describe(IBinding binding) {
        if (binding instanceof IMethodBinding m) {
            String type = m.getDeclaringClass() != null
                ? m.getDeclaringClass().getQualifiedName() : "?";
            StringBuilder params = new StringBuilder();
            for (org.eclipse.jdt.core.dom.ITypeBinding p : m.getParameterTypes()) {
                if (params.length() > 0) {
                    params.append(',');
                }
                params.append(p.getName());
            }
            return type + "#" + m.getName() + "(" + params + ")";
        }
        if (binding instanceof IVariableBinding v) {
            String type = v.getDeclaringClass() != null
                ? v.getDeclaringClass().getQualifiedName() : "?";
            return type + "#" + v.getName();
        }
        return binding.getName();
    }

    /**
     * Attribute this unit's references.
     *
     * <p>C4 audit, finding 3 — the three-way split matters. This read
     * {@code verdict != TEST} as "production", so a compilation unit the
     * classifier could not place (a {@code JavaModelException} mid-scan, a
     * source file not on the build path) turned every reference it made into a
     * production reference, and the finding it should have supported vanished
     * while the scan still called itself complete. That is this sprint's own
     * defect class — a failed lookup returned as an answer — inside the
     * detector written to catch it.</p>
     *
     * <p>A .java compilation unit inside a loaded project is MAIN or TEST;
     * {@code CROSS_CUTTING} here means "could not tell", so its references
     * mark the member UNKNOWN. Unknown is not "clean" and not "hollow": the
     * member is withheld and the scan declares itself incomplete.</p>
     */
    private static void markReferences(ParsedUnit unit, Map<String, MemberRecord> declared) {
        SourceRootClassifier.Verdict v = unit.verdict;
        unit.ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.isDeclaration()) {
                    return true;
                }
                IBinding binding = node.resolveBinding();
                String key = null;
                if (binding instanceof IMethodBinding m) {
                    key = m.getMethodDeclaration().getKey();
                } else if (binding instanceof IVariableBinding v && v.isField()) {
                    key = v.getVariableDeclaration().getKey();
                }
                mark(key);
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                IMethodBinding ctor = node.resolveConstructorBinding();
                if (ctor != null) {
                    mark(ctor.getMethodDeclaration().getKey());
                }
                return true;
            }

            private void mark(String key) {
                if (key == null) {
                    return;
                }
                MemberRecord r = declared.get(key);
                if (r == null) {
                    return;
                }
                switch (attribute(v)) {
                    case PRODUCTION -> r.productionRef = true;
                    case TEST -> r.testRefs++;
                    case UNKNOWN -> r.unknownRef = true;
                }
            }
        });
    }
}
