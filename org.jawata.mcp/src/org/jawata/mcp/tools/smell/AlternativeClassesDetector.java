package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Detector;
import org.jawata.mcp.domain.Finding;
import org.jawata.mcp.domain.Findings;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.shared.SourceScan;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Fowler (2nd ed. ch.3) — <b>Alternative Classes with Different Interfaces</b>.
 *
 * <p>Two classes do substantially the same job and cannot be substituted for one
 * another, because somebody named their methods differently. The cure is to make the
 * interfaces agree — Rename Function and Move Function until the two line up, then
 * Extract Superclass — and the finding is worth having because nothing else in the
 * catalogue looks for it: a duplicate-code check compares BODIES, and these two classes
 * may share no code at all.</p>
 *
 * <h2>THE ACCURACY PROBLEM IS THIS DETECTOR'S WHOLE DESIGN</h2>
 *
 * <p>A rule written as "two classes share method shapes" reports most of any codebase.
 * {@code String getName()} and {@code void run()} are everywhere, and a pair sharing two
 * such shapes has nothing in common. A check that reports a thousand pairs is worse than
 * no check: it buries the real ones and gets switched off. Four conditions must hold
 * together, and each removes a specific way of being wrong:</p>
 *
 * <ol>
 *   <li><b>Enough shared shapes</b> — at least {@code threshold} (default 3). Two
 *       coincidences are a coincidence.</li>
 *   <li><b>Shared shapes are most of the smaller class</b> — at least 60% of its
 *       methods. This is the condition that does the real work: it says the two classes
 *       are largely the same API rather than two large classes with an overlapping
 *       corner.</li>
 *   <li><b>No common supertype above Object</b> — two classes implementing one
 *       interface already have the SAME interface, which is not this smell. It is also
 *       the shape a detector without this condition reports most of, because siblings
 *       share shapes by construction.</li>
 *   <li><b>The names actually differ</b> — the smell is <i>different</i> interfaces. Two
 *       classes with identical method names and shapes are duplication, which
 *       {@code find_duplicate_code} and {@code refused_bequest} already speak about.</li>
 *   <li><b>The shared shapes carry a DOMAIN type</b> — at least one type in the shape
 *       comes from this workspace rather than from {@code java.*}. This condition was
 *       added after measuring: without it the detector paired
 *       {@code JdtServiceImpl} with {@code DetectorCatalog} on
 *       {@code Optional(String)}, {@code boolean(String)}, {@code Collection()} and
 *       {@code int()} — the shapes every lookup-flavoured class in every codebase has.
 *       Two classes that are genuinely alternatives for one job traffic in the same
 *       domain types; two that merely both look things up do not.</li>
 * </ol>
 *
 * <p>A shape is the erasure of the return type and the parameter types, with the name
 * dropped. Constructors, static methods, synthetic methods and the four inherited from
 * {@code Object} are excluded: every class has those, so counting them would make the
 * 60% condition meaningless.</p>
 */
public final class AlternativeClassesDetector implements Detector {

    /** Below this many methods a class has no API to compare. */
    private static final int MIN_METHODS = 3;

    /** Shared shapes must be at least this fraction of the smaller class's methods. */
    private static final double MIN_OVERLAP = 0.6;

    private static final Set<String> OBJECT_METHODS =
        Set.of("equals", "hashCode", "toString", "clone", "finalize");

    @Override
    public String kind() {
        return "alternative_classes";
    }

    @Override
    public String description() {
        return "Alternative Classes with Different Interfaces — two classes that do "
            + "substantially the same job and cannot substitute for each other because "
            + "their methods are named differently. Reported only when ALL of: they share "
            + ">= `threshold` method SHAPES (return type + parameter types, name dropped; "
            + "default 3) that each carry a type from this workspace rather than only "
            + "java.* ones, those shapes are >= 60% of the smaller class's domain-bearing "
            + "methods, the two "
            + "share no supertype above Object (siblings share shapes by construction), and "
            + "their method names actually differ (identical names are duplication, not this "
            + "smell). Points to Rename Function / Move Function, then Extract Superclass.";
    }

    /** What one class offers: where it is, its shapes, and the names it uses. */
    private record Api(String filePath, int line, String name, Set<String> shapes,
                       Set<String> methodNames, Set<String> supertypes) {
    }

    @Override
    public ToolResponse detect(IJdtService service, JsonNode arguments) {
        int threshold = AbstractAstDetector.readInt(arguments, "threshold", 3);
        boolean includeTests = AbstractAstDetector.includeTests(arguments);

        List<Path> inScope = new ArrayList<>();
        for (Path path : service.getAllJavaFiles()) {
            if (includeTests || !AbstractAstDetector.isTestSource(path, service)) {
                inScope.add(path);
            }
        }

        Map<String, Api> byType = new HashMap<>();
        SourceScan scan = SourceScan.of(inScope);
        try {
            for (Path path : scan.files()) {
                ICompilationUnit cu = scan.resolve(service, path);
                if (cu == null) {
                    continue;   // RECORDED, not swallowed — see SourceScan
                }
                CompilationUnit ast = scan.parse(cu, path, true);
                if (ast == null) {
                    continue;
                }
                scan.examined();
                collect(ast, service.getPathUtils().formatPath(path), byType);
            }
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }

        // A class we never read cannot pair with anything, so a missed file silently
        // REMOVES findings. "None" is not sayable without the whole set.
        Optional<ToolResponse> blind = scan.refuseIfBlind("alternative classes");
        if (blind.isPresent()) {
            return blind.get();
        }

        List<Finding> out = new ArrayList<>();
        List<String> types = new ArrayList<>(new TreeSet<>(byType.keySet()));
        for (int i = 0; i < types.size(); i++) {
            for (int j = i + 1; j < types.size(); j++) {
                Api a = byType.get(types.get(i));
                Api b = byType.get(types.get(j));
                pair(a, b, threshold, out);
            }
        }
        return Findings.toResponse(out, scan.describe(),
            scan.steering(out.size(), "alternative classes"));
    }

    /** Report the pair, or say nothing — every condition is a way of being wrong. */
    private static void pair(Api a, Api b, int threshold, List<Finding> out) {
        if (a.shapes().size() < MIN_METHODS || b.shapes().size() < MIN_METHODS) {
            return;
        }
        // Siblings share shapes by construction; that is not a finding, it is a hierarchy.
        if (!java.util.Collections.disjoint(a.supertypes(), b.supertypes())) {
            return;
        }
        Set<String> shared = new LinkedHashSet<>(a.shapes());
        shared.retainAll(b.shapes());
        // ONLY DOMAIN-BEARING SHAPES COUNT. A shape made entirely of JDK types is the
        // vocabulary every class shares; counting those is how two unrelated lookup
        // classes came out as alternatives when this was measured.
        shared.removeIf(shape -> !carriesDomainType(shape));
        if (shared.size() < threshold) {
            return;
        }
        int smaller = Math.min(domainShapes(a), domainShapes(b));
        if (smaller == 0 || shared.size() < MIN_OVERLAP * smaller) {
            return;
        }
        // Identical names are duplication, which other kinds already speak about.
        Set<String> sharedNames = new LinkedHashSet<>(a.methodNames());
        sharedNames.retainAll(b.methodNames());
        if (sharedNames.size() >= Math.min(a.methodNames().size(), b.methodNames().size())) {
            return;
        }

        String message = "Classes '" + a.name() + "' and '" + b.name() + "' share "
            + shared.size() + " of " + smaller + " method shapes but name them"
            + " differently, so neither can stand in for the other. Shared shapes: "
            + shared + ". '" + a.name() + "' calls them " + a.methodNames() + "; '"
            + b.name() + "' calls them " + b.methodNames() + ". Consider Rename Function"
            + " until the two agree, then Extract Superclass.";
        out.add(new Finding("alternative_classes", a.filePath(), a.line(), -1, "warning",
            message, a.name()));
        out.add(new Finding("alternative_classes", b.filePath(), b.line(), -1, "warning",
            message, b.name()));
    }

    /**
     * Does this shape mention a type this workspace declares?
     *
     * <p>A shape of nothing but {@code java.*} types is the shared vocabulary of every
     * Java class ever written, so it cannot distinguish two classes that do one job from
     * two that merely both take a String and return a boolean.</p>
     */
    private static boolean carriesDomainType(String shape) {
        for (String token : shape.split("[(),<>]")) {
            String type = token.trim();
            if (type.isEmpty() || type.equals("void") || type.equals("?")) {
                continue;
            }
            if (!type.startsWith("java.") && !type.startsWith("javax.") && type.contains(".")) {
                return true;
            }
        }
        return false;
    }

    /** How much of this class's API is domain-bearing — the denominator that matters. */
    private static int domainShapes(Api api) {
        int count = 0;
        for (String shape : api.shapes()) {
            if (carriesDomainType(shape)) {
                count++;
            }
        }
        return count;
    }

    private static void collect(CompilationUnit ast, String filePath, Map<String, Api> byType) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(TypeDeclaration node) {
                if (node.isInterface() || Modifier.isAbstract(node.getModifiers())) {
                    return true;
                }
                ITypeBinding binding = node.resolveBinding();
                if (binding == null) {
                    return true;
                }
                Set<String> shapes = new LinkedHashSet<>();
                Set<String> names = new LinkedHashSet<>();
                for (MethodDeclaration method : node.getMethods()) {
                    if (method.isConstructor()
                            || Modifier.isStatic(method.getModifiers())
                            || Modifier.isPrivate(method.getModifiers())
                            || OBJECT_METHODS.contains(method.getName().getIdentifier())) {
                        continue;
                    }
                    IMethodBinding mb = method.resolveBinding();
                    if (mb == null) {
                        continue;
                    }
                    shapes.add(shapeOf(mb));
                    names.add(method.getName().getIdentifier());
                }
                if (shapes.isEmpty()) {
                    return true;
                }
                byType.put(binding.getQualifiedName(), new Api(filePath,
                    ast.getLineNumber(node.getName().getStartPosition()),
                    binding.getQualifiedName(), shapes, names, supertypesOf(binding)));
                return true;
            }
        });
    }

    /** Return type and parameter types, with the NAME dropped — the shape. */
    private static String shapeOf(IMethodBinding method) {
        StringBuilder shape = new StringBuilder(typeOf(method.getReturnType())).append('(');
        ITypeBinding[] parameters = method.getParameterTypes();
        for (int i = 0; i < parameters.length; i++) {
            if (i > 0) {
                shape.append(',');
            }
            shape.append(typeOf(parameters[i]));
        }
        return shape.append(')').toString();
    }

    /**
     * The type as written, TYPE ARGUMENTS INCLUDED.
     *
     * <p>The first version erased, and erasure is where the domain signal goes: a method
     * returning {@code List<Order>} erases to {@code java.util.List}, which is the same
     * shape as one returning {@code List<String>} and carries nothing about what the
     * class is for. Two classes that are genuinely alternatives return the same domain
     * collection, and that is exactly what erasing hides.</p>
     *
     * <p>It also makes matching stricter, which is the right direction for a detector
     * whose whole risk is matching too much.</p>
     */
    private static String typeOf(ITypeBinding type) {
        if (type == null) {
            return "?";
        }
        String name = type.getQualifiedName();
        return name == null || name.isBlank() ? type.getName() : name;
    }

    /** Every supertype above Object, so two siblings can be recognised as siblings. */
    private static Set<String> supertypesOf(ITypeBinding binding) {
        Set<String> out = new LinkedHashSet<>();
        for (ITypeBinding parent = binding.getSuperclass(); parent != null;
                parent = parent.getSuperclass()) {
            String name = parent.getQualifiedName();
            if (name == null || "java.lang.Object".equals(name)) {
                break;
            }
            out.add(name);
        }
        for (ITypeBinding face : binding.getInterfaces()) {
            if (face.getQualifiedName() != null) {
                out.add(face.getQualifiedName());
            }
        }
        return out;
    }
}
