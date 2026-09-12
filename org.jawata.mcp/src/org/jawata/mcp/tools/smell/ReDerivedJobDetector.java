package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Sprint 28f Stage 8 D5 — TWO METHODS THAT DO THE SAME JOB, DERIVED INDEPENDENTLY.
 *
 * <p>The smell this whole sprint is aimed at. Somebody needed a job done, did not know the
 * codebase already did it, and wrote it again. The second copy is not a CLONE — it was
 * derived from scratch, so its structure differs and every token-based finder is blind to
 * it. What the two share is the WORK: the same shape of answer, computed from the same
 * collaborators.</p>
 *
 * <h2>Why it is not built on the clone finder, which would have been cheaper</h2>
 *
 * <p>{@code find_duplicate_code} groups methods by normalised token sequence, and
 * {@link org.jawata.mcp.tools.DuplicatedCodeSmell} adapts it rather than re-implementing
 * it — the right move there, because the question was the same. Here the question is the
 * OPPOSITE one: a re-derived job is precisely the duplicate whose tokens do NOT match. An
 * adapter over that finder would return the empty set on every real instance and look like
 * a working detector. That control is the test's job rather than an argument: the token
 * detector is run over the same tree and must name none of these.</p>
 *
 * <h2>The four conditions, and what each one is keeping out</h2>
 *
 * <ol>
 *   <li><b>The same signature shape</b> — the same parameter types and return type. Two
 *       methods that answer different questions are not two answers to one question.</li>
 *   <li><b>Overlapping collaborators</b> — both reach for the same foreign types. This is
 *       what separates a re-derivation from a coincidence: {@code String f(int)} occurs
 *       everywhere, and shape alone would pair half the workspace.</li>
 *   <li><b>Neither signature is IMPOSED</b> — see below. This is the condition the plan
 *       names "no common supertype", and measuring it against the population this
 *       deliverable exists to find is what corrected it.</li>
 *   <li><b>Neither calls the other</b> — a method that delegates is not a second
 *       implementation, it is the first one with a wrapper. This is also what keeps the
 *       five {@code findTypeDeclaration} copies out: each of them now forwards to
 *       {@code tools.shared.TypeLookup}, so they are one job reached through its owner.</li>
 * </ol>
 *
 * <h2>IMPOSED, not "shares a supertype" — and the difference is the whole detector</h2>
 *
 * <p>A shared supertype was the first rule and it is WRONG here, measured rather than
 * argued. The population this deliverable was written to find is ~34 private static
 * {@code parse(ICompilationUnit)} helpers, and their declaring classes very largely share
 * {@code AbstractApplyingRefactoringTool} — so a shared-supertype exclusion silences the
 * flagship case completely.</p>
 *
 * <p>What actually makes a match meaningless is that the signature was DICTATED: the method
 * overrides something, so the shape is the type system's doing and not a re-derivation.
 * That keeps every strategy hierarchy out — which is what the original clause was for —
 * without touching two siblings who each happened to write the same helper. A private or
 * static method can neither override nor be overridden, so it is never imposed; saying so
 * explicitly is what protects the parse-helper population from a same-named private method
 * further up the chain.</p>
 *
 * <h2>The cure is ADVICE, and the architect rules on it</h2>
 *
 * <p>There is no refactoring to run here. Whether two implementations should become one is
 * a design judgement — sometimes the answer is that the second is deliberate, which the
 * architect seat's report carries with its reason. So the finding names the group and says
 * what to weigh; it does not name an operation, and {@code CureCatalog} carries it as an
 * advisory row rather than a runnable one.</p>
 */
public final class ReDerivedJobDetector {

    /**
     * How many foreign types two methods must share before the shape means anything.
     *
     * <p>Two, not one: one shared collaborator is what every method touching a common
     * utility has, and pairing on it would report the workspace against itself. Two is the
     * smallest number that says "these reach for the same PARTS", and the flagship
     * population sits exactly on it — a {@code parse} helper touches {@code ASTParser} and
     * {@code AST} and nothing else — so the bar is measured rather than chosen. A corpus
     * with a wide shared vocabulary can raise it with {@code threshold}.</p>
     */
    private static final int DEFAULT_MIN_SHARED = 2;

    /**
     * A ceiling on the clustering, and it is stated in the response rather than silent.
     *
     * <p>Clustering compares every pair INSIDE a shape group, and a workspace with a
     * thousand {@code void f()} methods would compare half a million. A group past this is
     * reported as skipped with its size, because a sweep that quietly examined less than it
     * says is the defect this file's neighbours keep finding.</p>
     */
    private static final int MAX_GROUP = 400;

    /** How many siblings a finding names before it stops listing them. */
    private static final int SIBLINGS_NAMED = 3;

    private ReDerivedJobDetector() {
    }

    /**
     * What the scan SAW, so a zero can be told from a blindness.
     *
     * <p>A method whose binding does not resolve is invisible to every one of the four
     * conditions — they are all questions about resolved types — so it is dropped. Dropping
     * it quietly is the difference between "this tree has no re-derived jobs" and "this
     * scan could not see the tree", and those must never print the same.</p>
     */
    private static final class Tally {
        private int filesScanned;
        private int bindingsUnresolved;
    }

    /** One method, reduced to what the four conditions need. */
    private record Job(String path, int line, String type, String method, String shape,
                       boolean imposed, Set<String> foreign, Set<String> calls) {

        String symbol() {
            return type + "#" + method;
        }
    }

    public static Detector detector() {
        return new Detector() {
            @Override
            public String kind() {
                return "re_derived_job";
            }

            @Override
            public String description() {
                return "Re-derived job — two or more methods that answer the same question"
                    + " from the same collaborators, written independently. NOT a clone: the"
                    + " second was derived from scratch, so its tokens differ and"
                    + " find_duplicate_code cannot see it. Advisory: whether they should"
                    + " become one is a design judgement, and sometimes the second is"
                    + " deliberate.";
            }

            @Override
            public ToolResponse detect(IJdtService service, JsonNode arguments) {
                if (service == null) {
                    return ToolResponse.error("NO_PROJECT",
                        "re_derived_job needs a loaded project — it compares methods across"
                            + " files and reads their resolved types.",
                        "Call load_project first.");
                }
                int minShared = AbstractAstDetector.readInt(
                    arguments, "threshold", DEFAULT_MIN_SHARED);
                List<Job> jobs = new ArrayList<>();
                List<String> unreadable = new ArrayList<>();
                Tally tally = new Tally();
                for (Path file : AbstractAstDetector.scopedSourceFiles(service, arguments)) {
                    tally.filesScanned++;
                    try {
                        ICompilationUnit cu = service.getCompilationUnit(file);
                        if (cu == null) {
                            // NAMED rather than skipped. A file the model does not hold
                            // reads exactly like a file with nothing in it, and the first
                            // version of this loop dropped it in silence.
                            unreadable.add(file + " (no compilation unit in the model)");
                            continue;
                        }
                        CompilationUnit ast = AbstractAstDetector.parse(cu);
                        if (ast == null) {
                            unreadable.add(file + " (did not parse)");
                            continue;
                        }
                        collect(ast, file.toString(), jobs, tally);
                    } catch (Exception e) {
                        // NAMED. A file skipped in silence makes a sweep report a smaller
                        // world as a clean one, which is this project's own recorded
                        // deepest defect class.
                        unreadable.add(file + " (" + e.getMessage() + ")");
                    }
                }
                return report(cluster(jobs, minShared), jobs.size(), unreadable, tally);
            }
        };
    }

    /** Reduce every method declaration in one unit to a {@link Job}. */
    private static void collect(CompilationUnit ast, String path, List<Job> out,
                                Tally tally) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                // A constructor re-derived is a different question — two ways of building
                // one object is what a factory is for — so it is out of scope here rather
                // than reported as this smell. An abstract or interface method has no body
                // and so derives nothing.
                if (node.isConstructor() || node.getBody() == null) {
                    return false;
                }
                IMethodBinding binding = node.resolveBinding();
                if (binding == null || binding.getDeclaringClass() == null) {
                    // COUNTED, not dropped. Every condition below is a question about
                    // resolved types, so an unresolved method is invisible to all four —
                    // and a scan that could not resolve anything would otherwise report a
                    // clean tree.
                    tally.bindingsUnresolved++;
                    return false;
                }
                ITypeBinding owner = binding.getDeclaringClass();
                String ownerName = owner.getErasure().getQualifiedName();
                Set<String> foreign = new LinkedHashSet<>();
                Set<String> calls = new LinkedHashSet<>();
                node.accept(new ASTVisitor() {
                    @Override
                    public boolean visit(MethodInvocation call) {
                        IMethodBinding m = call.resolveMethodBinding();
                        if (m != null && m.getDeclaringClass() != null) {
                            String declaring =
                                m.getDeclaringClass().getErasure().getQualifiedName();
                            calls.add(declaring + "#" + m.getName());
                            if (!declaring.equals(ownerName)
                                    && !declaring.startsWith("java.lang.")) {
                                foreign.add(declaring);
                            }
                        }
                        return true;
                    }
                });
                out.add(new Job(path, ast.getLineNumber(node.getStartPosition()),
                    ownerName, node.getName().getIdentifier(),
                    shapeOf(binding), isImposed(binding), foreign, calls));
                return false;
            }
        });
    }

    /** The question a method answers, as its types: {@code (int,java.lang.String)->boolean}. */
    private static String shapeOf(IMethodBinding binding) {
        StringBuilder sb = new StringBuilder("(");
        ITypeBinding[] params = binding.getParameterTypes();
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sb.append(',');
            }
            sb.append(params[i].getErasure().getQualifiedName());
        }
        return sb.append(")->")
            .append(binding.getReturnType().getErasure().getQualifiedName())
            .toString();
    }

    /**
     * Whether a supertype DICTATED this signature.
     *
     * <p>A private or static method is exempt by the language: neither can override or be
     * overridden, so its shape is nobody else's doing. Saying that first is not a
     * micro-optimisation — it is what stops a same-named private helper somewhere up the
     * chain from marking the whole parse-helper population as imposed.</p>
     */
    private static boolean isImposed(IMethodBinding binding) {
        int mods = binding.getModifiers();
        if (Modifier.isPrivate(mods) || Modifier.isStatic(mods)) {
            return false;
        }
        return declaredAbove(binding, binding.getDeclaringClass(), new LinkedHashSet<>());
    }

    private static boolean declaredAbove(IMethodBinding binding, ITypeBinding type,
                                         Set<String> seen) {
        List<ITypeBinding> supers = new ArrayList<>(List.of(type.getInterfaces()));
        if (type.getSuperclass() != null) {
            supers.add(type.getSuperclass());
        }
        for (ITypeBinding parent : supers) {
            if (parent == null || !seen.add(parent.getErasure().getQualifiedName())) {
                continue;
            }
            for (IMethodBinding candidate : parent.getDeclaredMethods()) {
                if (binding.overrides(candidate) || sameShape(binding, candidate)) {
                    return true;
                }
            }
            if (declaredAbove(binding, parent, seen)) {
                return true;
            }
        }
        return false;
    }

    private static boolean sameShape(IMethodBinding a, IMethodBinding b) {
        return a.getName().equals(b.getName()) && shapeOf(a).equals(shapeOf(b));
    }

    /**
     * Group the methods that answer one question from the same parts.
     *
     * <p>GROUPS, not pairs, and the difference is the difference between a usable finding
     * and an unreadable one: the ~34 {@code parse} helpers are ONE re-derived job and
     * yield 34 findings naming one group — as pairs they would be 561 findings saying the
     * same thing 561 times.</p>
     */
    private static Map<String, Object> cluster(List<Job> jobs, int minShared) {
        Map<String, List<Job>> byShape = new LinkedHashMap<>();
        for (Job j : jobs) {
            byShape.computeIfAbsent(j.shape(), k -> new ArrayList<>()).add(j);
        }
        List<Map<String, Object>> findings = new ArrayList<>();
        List<String> skipped = new ArrayList<>();
        int groupNumber = 0;
        for (Map.Entry<String, List<Job>> e : byShape.entrySet()) {
            List<Job> group = e.getValue();
            if (group.size() > MAX_GROUP) {
                skipped.add(e.getKey() + " (" + group.size() + " methods)");
                continue;
            }
            for (List<Job> found : connected(group, minShared)) {
                groupNumber++;
                for (Job j : found) {
                    findings.add(finding(j, found, groupNumber));
                }
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("findings", findings);
        out.put("skipped", skipped);
        return out;
    }

    /** Union-find over the pair relation, returning every component of two or more. */
    private static List<List<Job>> connected(List<Job> group, int minShared) {
        int n = group.size();
        int[] parent = new int[n];
        for (int i = 0; i < n; i++) {
            parent[i] = i;
        }
        for (int i = 0; i < n; i++) {
            for (int k = i + 1; k < n; k++) {
                if (related(group.get(i), group.get(k), minShared)) {
                    union(parent, i, k);
                }
            }
        }
        Map<Integer, List<Job>> components = new LinkedHashMap<>();
        for (int i = 0; i < n; i++) {
            components.computeIfAbsent(find(parent, i), k -> new ArrayList<>())
                .add(group.get(i));
        }
        List<List<Job>> out = new ArrayList<>();
        for (List<Job> component : components.values()) {
            if (component.size() > 1) {
                out.add(component);
            }
        }
        return out;
    }

    private static int find(int[] parent, int i) {
        int root = i;
        while (parent[root] != root) {
            root = parent[root];
        }
        while (parent[i] != root) {
            int next = parent[i];
            parent[i] = root;
            i = next;
        }
        return root;
    }

    private static void union(int[] parent, int a, int b) {
        int ra = find(parent, a);
        int rb = find(parent, b);
        if (ra != rb) {
            parent[rb] = ra;
        }
    }

    /** The four conditions, on one pair. Shape is already equal inside a group. */
    private static boolean related(Job a, Job b, int minShared) {
        if (a.type().equals(b.type()) || a.imposed() || b.imposed()) {
            return false;
        }
        if (a.calls().contains(b.symbol()) || b.calls().contains(a.symbol())) {
            return false;
        }
        Set<String> shared = new LinkedHashSet<>(a.foreign());
        shared.retainAll(b.foreign());
        return shared.size() >= minShared;
    }

    private static Map<String, Object> finding(Job self, List<Job> group, int number) {
        List<String> others = new ArrayList<>();
        for (Job j : group) {
            if (!j.symbol().equals(self.symbol()) && others.size() < SIBLINGS_NAMED) {
                others.add(j.symbol());
            }
        }
        int remaining = group.size() - 1 - others.size();
        String more = remaining > 0 ? " and " + remaining + " more" : "";
        Map<String, Object> f = new LinkedHashMap<>();
        f.put("kind", "re_derived_job");
        f.put("filePath", self.path());
        f.put("line", self.line());
        f.put("severity", "warning");
        f.put("symbol", self.symbol());
        f.put("group", number);
        f.put("groupSize", group.size());
        // The SHAPE is a field and not only a sentence, because it is the fact that put
        // these methods in one group and a caller filtering by it is the difference between
        // a population and a set of methods sharing a name. This file's own live probe
        // selected on the name first and counted three unrelated signatures as members.
        f.put("shape", self.shape());
        f.put("message", "'" + self.symbol() + "' answers the same question as "
            + (group.size() - 1) + " other method(s) — " + String.join(", ", others) + more
            + ". Same shape " + self.shape() + ", reached through the same collaborators;"
            + " none of them calls another and none of the signatures is imposed by a"
            + " supertype, so this is not delegation and not a dictated shape: it looks"
            + " like one job derived " + group.size() + " times. WEIGH it — if the"
            + " duplication is deliberate, the reason belongs in the record; if it is not,"
            + " one of them should become the owner and the rest should call it.");
        return f;
    }

    private static ToolResponse report(Map<String, Object> clustered, int examined,
                                       List<String> unreadable, Tally tally) {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings =
            (List<Map<String, Object>>) clustered.get("findings");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("operation", "find_quality_issue");
        out.put("kind", "re_derived_job");
        out.put("count", findings.size());
        // WHAT WAS LOOKED AT travels with the count, so a zero can be read. A detector
        // reporting nothing over four methods and one reporting nothing over four thousand
        // are different answers, and only this tells them apart.
        out.put("methodsExamined", examined);
        out.put("filesScanned", tally.filesScanned);
        if (tally.bindingsUnresolved > 0) {
            out.put("bindingsUnresolved", tally.bindingsUnresolved);
        }
        @SuppressWarnings("unchecked")
        List<String> skipped = (List<String>) clustered.get("skipped");
        if (!skipped.isEmpty()) {
            out.put("groupsTooLarge", skipped);
        }
        if (!unreadable.isEmpty()) {
            out.put("unreadable", unreadable);
        }
        out.put("findings", findings);
        return ToolResponse.success(out, ResponseMeta.builder()
            .totalCount(findings.size())
            .returnedCount(findings.size())
            .build());
    }
}
