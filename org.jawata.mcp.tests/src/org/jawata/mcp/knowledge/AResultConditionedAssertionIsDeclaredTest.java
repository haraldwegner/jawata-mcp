package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Annotation;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.jawata.mcp.catalogue.RepoFiles;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * REL44-22 — a test does not check its subject only IF the result happened to contain it.
 *
 * <p>The shape: {@code if (!fixes.isEmpty()) { assertNotNull(fixes.get(0).get("fixId")); }}. On
 * the day the tool returns nothing, the check is skipped and the test is green. That is how
 * GetQuickFixesToolTest stayed green while the tool answered {@code problemCount=0} for every
 * file (REL44-26). The census behind this guard found eighteen such assertions; twelve were
 * converted to unconditional ones, one redundant guard was removed, and the five whose
 * condition is a genuine part of the claim are declared below with the reason.</p>
 *
 * <p>The guard reads SOURCE: an assertion inside an {@code if} with no {@code else}, in a test
 * method, WHATEVER the condition. It first read a fixed vocabulary — empty, sized, null,
 * contains, present — and the C20 audit showed that was narrower than the finding: a guard
 * phrased {@code if (a.equals(b))} hid REL44-21's subject assertion from it. With the condition
 * left open the census named fifteen more; four were converted and eleven declared. A new site
 * fails it. If the condition really is part of what is being claimed, declare it here with the
 * reason — a declaration nobody can read the reason for is the bypass this exists to refuse.</p>
 */
class AResultConditionedAssertionIsDeclaredTest {

    private static final String TEST_SOURCES = "org.jawata.mcp.tests/src";

    /** Declared sites, as {@code <file>#<method>}, each with why its condition is the claim. */
    private static final Map<String, String> DECLARED = Map.ofEntries(
        Map.entry("org/jawata/mcp/models/WorkspaceIdentityTest.java#uninstalled_isSilent",
            "a null hint IS the silent outcome under test; the check applies only when a hint exists"),
        Map.entry("org/jawata/mcp/tools/navigation/FindReferencesToolTest.java#everyReferenceRowIsUsableOrDeclaresItselfUnresolved",
            "per-row: an unresolved row carries no path by design, and the row's own clause covers it"),
        Map.entry("org/jawata/mcp/tools/verification/PdeExternalPoolTest.java#externalPool_compilesAndRunsPdeTests",
            "the if only builds a richer failure message; the assertNull inside always fails when entered"),
        Map.entry("org/jawata/mcp/tools/verification/RunTestsToolTest.java#schema_methodScopeDescription_doesNotImplyFilePathMethodName",
            "the phrase is allowed to be absent; the claim is about the context IF it appears"),
        Map.entry("org/jawata/mcp/tools/refactoring/OrganizeImportsToolTest.java#organizeImports_appliesOrShortCircuits",
            "an ordering between two import groups is only defined when both groups are present"),
        // The census with the condition LEFT OPEN (C20 audit, REL44-21/22) named fifteen more;
        // four were converted, and these eleven are the claim itself.
        Map.entry("org/jawata/mcp/EveryCrossBundlePackageIsImportedTest.java#everyCrossBundlePackageIsDeclared",
            "per-bundle: the proof of life is claimed of the one bundle known to import across"
                + " bundles, and the loop visits every bundle"),
        Map.entry("org/jawata/mcp/knowledge/CatalogueOriginContractTest.java#no_two_origins_can_claim_the_same_row",
            "pairwise: one prefix swallowing another is defined only between two different prefixes"),
        Map.entry("org/jawata/mcp/knowledge/EmbeddingStoreTest.java#a_written_entry_carries_a_vector_and_the_identity_that_made_it",
            "both branches assert: the degraded answer with no embedder, the vector with one"),
        Map.entry("org/jawata/mcp/tools/DeclaredShapeHonestyTest.java#everyDeclaredRunTestsActionIsRouted",
            "the claim is about refusals: an action that succeeds has been routed, which is the claim"),
        Map.entry("org/jawata/mcp/tools/VectoriseVerbTest.java#the_verb_reports_what_it_did_and_never_claims_a_remainder_it_could_not_count",
            "both branches assert: the honest degradation with no embedder, convergence with one"),
        Map.entry("org/jawata/mcp/tools/analysis/FindNamingViolationsToolTest.java#theReportedLineIsOneBasedForFieldRows",
            "per-row: the clause holds of field rows only, and a count after the loop asserts at"
                + " least one was read"),
        Map.entry("org/jawata/mcp/tools/quickfix/SuggestImportsToolTest.java#findsJdkTypesWithCompleteStructure",
            "an ordering between two candidates is defined only when both are offered; a JDK"
                + " without java.desktop offers no java.awt type"),
        Map.entry("org/jawata/mcp/tools/verification/LatencySeamTest.java#aHotSeamThatOverflowsTheRingReportsTheLoss",
            "the flag is claimed of a run that dropped events; zero loss is honest where the ring"
                + " keeps up, and the bound on loss is asserted unconditionally"),
        Map.entry("org/jawata/mcp/tools/verification/LibSourceAndHierarchyTest.java#sourceByFqn_allOrigins",
            "the label is claimed of a stub; a JDK shipping src.zip answers attached source, which"
                + " the unconditional assertions above cover"),
        Map.entry("org/jawata/mcp/tools/verification/RunTestsToolTest.java#zeroDiscovery_isNotReportedAsAPassingRun",
            "the tool may refuse, which is honest; the claim is about the wording of an empty run"
                + " that succeeds"),
        Map.entry("org/jawata/mcp/transport/ResolvedTokenTest.java#absentFileIsWrittenOwnerOnly",
            "file permissions exist only on a POSIX filesystem; the token's existence and shape are"
                + " asserted on every platform"));

    @Test
    @DisplayName("REL44-22: no test checks its subject only if the result happened to contain it")
    void everyResultConditionedAssertionIsDeclared() throws IOException {
        Path root = RepoFiles.sourceOf(TEST_SOURCES);
        assertNotNull(root, "looked for " + TEST_SOURCES + " above " + Path.of("").toAbsolutePath());

        TreeSet<String> found = new TreeSet<>();
        List<String> lines = new ArrayList<>();
        int parsed = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                parsed++;
                String rel = root.relativize(file).toString().replace('\\', '/');
                for (String[] hit : sites(Files.readString(file))) {
                    found.add(rel + "#" + hit[0]);
                    lines.add(rel + ":" + hit[1] + " " + hit[0] + " if (" + hit[2] + ")");
                }
            }
        }
        assertTrue(parsed > 500, "only " + parsed + " test sources parsed — the walk is wrong");

        TreeSet<String> undeclared = new TreeSet<>(found);
        undeclared.removeAll(DECLARED.keySet());
        assertTrue(undeclared.isEmpty(), () -> undeclared.size() + " test(s) check their subject"
            + " only if the result happened to contain it, so an empty answer passes them."
            + " Assert the condition instead, or declare it here with why it is the claim:\n  "
            + String.join("\n  ", lines.stream()
                .filter(l -> undeclared.stream().anyMatch(u -> l.startsWith(u.substring(0,
                    u.indexOf('#'))) && l.contains(" " + u.substring(u.indexOf('#') + 1) + " ")))
                .toList()));

        // A declaration whose site has gone is a stale exemption, waiting to cover a new one.
        TreeSet<String> stale = new TreeSet<>(DECLARED.keySet());
        stale.removeAll(found);
        assertEquals(List.of(), List.copyOf(stale),
            "these declared sites no longer exist; remove them from DECLARED");
    }

    /**
     * Test methods allowed to have a path that checks nothing, as {@code <file>#<method>}, each
     * with why. The census's first run named four; two were false positives of a rule that read
     * where an assertion sits rather than which paths reach one, one was converted to an
     * assumption (UsageLedgerTest), and this one is the claim itself.
     */
    private static final Map<String, String> DECLARED_UNCHECKED = Map.of(
        "org/jawata/mcp/tools/verification/RunTestsToolTest.java#schema_methodScopeDescription_doesNotImplyFilePathMethodName",
        "the claim is that the description does not imply a pairing; a description without the"
            + " phrase satisfies it, so checking nothing when it is absent IS the claim");

    /**
     * REL44-22, widened after the C20 audit: the census with the condition LEFT OPEN.
     *
     * <p>The guard above reads a fixed vocabulary of conditions — empty, sized, null, contains,
     * present — so a guard phrased any other way passed it. {@code NominationHonestyTest} had one
     * (REL44-21): its only assertion sat inside {@code if (coverage.get("embedded").equals(
     * coverage.get("entries")))}, which no vocabulary of result questions names. What makes the
     * shape dangerous is not the condition but that NOTHING in the method always runs: on the day
     * the condition is false the method checks nothing and JUnit reports a pass.</p>
     *
     * <p>So this reads every test method that has at least one assertion and asks whether ANY of
     * them always runs — one not inside an {@code if} with no {@code else}, and not after an
     * early {@code return}. A method where none does is refused unless declared. What it does not
     * see, so a green run is not read as more: an assertion inside a loop over a collection that
     * may be empty, and a check made by a helper whose name does not start with assert or
     * fail — such a method has no assertion this reads and is not judged.</p>
     */
    @Test
    @DisplayName("REL44-22: no test method can pass without one of its assertions having run")
    void everyTestHasAnAssertionThatAlwaysRuns() throws IOException {
        Path root = RepoFiles.sourceOf(TEST_SOURCES);
        assertNotNull(root, "looked for " + TEST_SOURCES + " above " + Path.of("").toAbsolutePath());

        TreeSet<String> found = new TreeSet<>();
        List<String> lines = new ArrayList<>();
        int parsed = 0;
        try (Stream<Path> walk = Files.walk(root)) {
            for (Path file : walk.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                parsed++;
                String rel = root.relativize(file).toString().replace('\\', '/');
                for (String[] hit : uncheckedMethods(Files.readString(file))) {
                    found.add(rel + "#" + hit[0]);
                    lines.add(rel + ":" + hit[1] + " " + hit[0]);
                }
            }
        }
        assertTrue(parsed > 500, "only " + parsed + " test sources parsed — the walk is wrong");

        TreeSet<String> undeclared = new TreeSet<>(found);
        undeclared.removeAll(DECLARED_UNCHECKED.keySet());
        assertTrue(undeclared.isEmpty(), () -> undeclared.size() + " test method(s) have no"
            + " assertion that always runs, so a run in which the condition is false checks"
            + " nothing and passes. Make one unconditional, turn the condition into an assumption,"
            + " or declare it here with why:\n  " + String.join("\n  ", lines.stream()
                .filter(l -> undeclared.stream().anyMatch(u -> l.startsWith(u.substring(0,
                    u.indexOf('#'))) && l.endsWith(" " + u.substring(u.indexOf('#') + 1))))
                .toList()));

        TreeSet<String> stale = new TreeSet<>(DECLARED_UNCHECKED.keySet());
        stale.removeAll(found);
        assertEquals(List.of(), List.copyOf(stale),
            "these declared methods no longer have the shape; remove them from DECLARED_UNCHECKED");
    }

    /** Each test method with assertions but none that always runs, as {method, line}. */
    static List<String[]> uncheckedMethods(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        List<String[]> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() == null || !isTest(method)) {
                    return true;
                }
                List<ReturnStatement> returns = new ArrayList<>();
                List<MethodInvocation> checks = new ArrayList<>();
                int[] nested = {0};
                method.getBody().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(LambdaExpression lambda) {
                        nested[0]++;
                        return true;
                    }

                    @Override
                    public void endVisit(LambdaExpression lambda) {
                        nested[0]--;
                    }

                    @Override
                    public boolean visit(AnonymousClassDeclaration body) {
                        nested[0]++;
                        return true;
                    }

                    @Override
                    public void endVisit(AnonymousClassDeclaration body) {
                        nested[0]--;
                    }

                    @Override
                    public boolean visit(ReturnStatement ret) {
                        if (nested[0] == 0) {
                            returns.add(ret);
                        }
                        return true;
                    }

                    @Override
                    public boolean visit(MethodInvocation call) {
                        String name = call.getName().getIdentifier();
                        if (name.startsWith("assert") || name.startsWith("fail")) {
                            checks.add(call);
                        }
                        return true;
                    }
                });
                // A PATH that checks nothing, not an assertion that sits somewhere: a guard that
                // asserts the degraded answer and then returns checks something on its own path.
                // The path that runs to the end needs an assertion outside every guard; each early
                // return needs one that runs before it on the way there.
                boolean endChecks = checks.stream().anyMatch(
                    check -> guardsOf(check, method).isEmpty());
                boolean everyReturnChecks = returns.stream().allMatch(ret -> checks.stream()
                    .anyMatch(check -> check.getStartPosition() < ret.getStartPosition()
                        && guardsOf(check, method).stream().allMatch(
                            guard -> within(ret, guard.getThenStatement()))));
                if (!checks.isEmpty() && !(endChecks && everyReturnChecks)) {
                    out.add(new String[] {method.getName().getIdentifier(),
                        String.valueOf(unit.getLineNumber(method.getStartPosition()))});
                }
                return false;
            }
        });
        return out;
    }

    /** Every {@code if} with no else whose then-branch holds {@code node}, within {@code method}. */
    private static List<IfStatement> guardsOf(ASTNode node, MethodDeclaration method) {
        List<IfStatement> guards = new ArrayList<>();
        for (ASTNode at = node, parent = node.getParent(); parent != null && at != method;
                at = parent, parent = parent.getParent()) {
            if (parent instanceof IfStatement guard && guard.getElseStatement() == null
                    && guard.getThenStatement() == at) {
                guards.add(guard);
            }
        }
        return guards;
    }

    /** Whether {@code node} lies inside {@code container}. */
    private static boolean within(ASTNode node, ASTNode container) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at == container) {
                return true;
            }
        }
        return false;
    }

    /** Each site as {method, line, condition}. */
    private static List<String[]> sites(String source) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        Map<String, String> options = JavaCore.getOptions();
        JavaCore.setComplianceOptions(JavaCore.latestSupportedJavaVersion(), options);
        parser.setCompilerOptions(options);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(source.toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        List<String[]> out = new ArrayList<>();
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration method) {
                if (method.getBody() == null || !isTest(method)) {
                    return true;
                }
                method.getBody().accept(new ASTVisitor() {
                    @Override
                    public boolean visit(IfStatement guard) {
                        String condition = guard.getExpression().toString();
                        if (guard.getElseStatement() == null && asserts(guard)) {
                            out.add(new String[] {method.getName().getIdentifier(),
                                String.valueOf(unit.getLineNumber(guard.getStartPosition())),
                                condition.replace("\n", " ")});
                        }
                        return true;
                    }
                });
                return false;
            }
        });
        return out;
    }

    private static boolean asserts(IfStatement guard) {
        boolean[] found = {false};
        guard.getThenStatement().accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation call) {
                String name = call.getName().getIdentifier();
                if (name.startsWith("assert") || name.startsWith("fail")) {
                    found[0] = true;
                }
                return true;
            }
        });
        return found[0];
    }

    private static boolean isTest(MethodDeclaration method) {
        for (Object modifier : method.modifiers()) {
            if (modifier instanceof Annotation a) {
                String name = a.getTypeName().getFullyQualifiedName();
                String simple = name.substring(name.lastIndexOf('.') + 1);
                if (List.of("Test", "ParameterizedTest", "RepeatedTest").contains(simple)) {
                    return true;
                }
            }
        }
        return false;
    }
}
