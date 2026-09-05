package org.jawata.mcp.tools.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FormatterOptions;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.jawata.mcp.tools.shared.MethodLookup;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code change_method_signature kind=preserve_whole_object} — Fowler row 28, Preserve Whole
 * Object.
 *
 * <p>Where every caller pulls several values out of ONE object and passes them separately, the
 * object itself is what the method wanted. Passing the parts costs a longer signature, makes every
 * caller repeat the unpacking, and means a method that later needs a fourth value cannot get it
 * without changing everybody.</p>
 *
 * <pre>{@code
 * plan.withinRange(range.low(), range.high())   ->   plan.withinRange(range)
 * }</pre>
 *
 * <h2>The object is INFERRED from the call sites, and unanimity is the safety argument</h2>
 *
 * <p>The caller names the METHOD and WHICH PARAMETERS to fold; it does not name the object,
 * because the callers already do. Every call site is read, and the row proceeds only if all of
 * them pass, for each named parameter, an accessor on the SAME expression — the same expression
 * within a call, and the same accessor for that parameter across every call.</p>
 *
 * <p>One call site passing anything else means the parameter carries information the object cannot
 * reproduce, and folding it would change what that caller asked for. The row refuses and quotes
 * what it saw.</p>
 *
 * <h2>Two or more, and the neighbouring row is named rather than implied</h2>
 *
 * <p>Folding ONE parameter is not this refactoring: an object passed in place of a single value is
 * a longer expression at every call for nothing gained, and the case where one parameter is
 * derived from ANOTHER ARGUMENT ALREADY PASSED is row 53, {@code replace_parameter_with_query}, on
 * this same door. So fewer than two is refused with that pointer.</p>
 *
 * <h2>And the body must read each folded parameter ONCE</h2>
 *
 * <p>Shared with row 53, in {@link ParameterSubstitution}, for the reason stated there: a
 * parameter is evaluated once at the call and a query in its place is evaluated once per read.
 * Fowler's own caveat, and not decidable any other way.</p>
 */
public class PreserveWholeObjectTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Callers are enumerated, never sampled; reaching this cap REFUSES rather than truncates. */
    private static final int MAX_REFERENCES = 1000;

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a method. */
        public static final String NOT_A_METHOD = "NOT_A_METHOD";
        /** Fewer than two parameters were named, which is a different refactoring. */
        public static final String PARAMETERS_REQUIRED = "PARAMETERS_REQUIRED";
        /** The method declares no parameter of one of those names. */
        public static final String PARAMETER_NOT_FOUND = "PARAMETER_NOT_FOUND";
        /** Nothing calls the method, so no object can be inferred from its callers. */
        public static final String NO_CALL_SITES = "NO_CALL_SITES";
        /** The call sites do not all unpack the same object the same way. */
        public static final String CALLERS_DISAGREE = "CALLERS_DISAGREE";
        /** The object is already passed as another argument — that is row 53's shape. */
        public static final String OBJECT_ALREADY_PASSED = "OBJECT_ALREADY_PASSED";
        /** The body would evaluate an accessor more than the call site evaluated it once. */
        public static final String PARAMETER_READ_REPEATEDLY = "PARAMETER_READ_REPEATEDLY";
        /** The reference search hit its cap, so the caller list is a sample. */
        public static final String REFERENCE_CAP_REACHED = "REFERENCE_CAP_REACHED";

        private Refusal() {
        }
    }

    public PreserveWholeObjectTool(Supplier<IJdtService> serviceSupplier,
                                   RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "preserve_whole_object";
    }

    @Override
    public String getName() {
        return "preserve_whole_object";
    }

    @Override
    public String getDescription() {
        return """
            Preserve Whole Object — several parameters every caller pulls out of ONE object are
            replaced by that object, and the body asks it for each value. Name the METHOD
            (symbol=pkg.Type#m, or a position) and two or more parameters; optional
            parameterName for the new one. The object is INFERRED from the call sites and must
            be UNANIMOUS: every call must pass <x>.something() for each named parameter, with
            the same x. Refuses fewer than two parameters (one derived from another argument is
            replace_parameter_with_query), a caller passing something else, and a body that
            reads a folded parameter more than once or inside a loop.""";
    }

    /** Structural: the signature changes and every call site with it. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the method."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the method's declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("symbol", FqnTarget.symbolSchemaProperty("method with the unpacked object"));
        properties.put("parameters", Map.of("type", "array",
            "items", Map.of("type", "string"),
            "description", "REQUIRED. Two or more parameter names to fold into one object."));
        properties.put("parameterName", Map.of("type", "string",
            "description", "Name for the new parameter. Optional when every caller's object is"
                + " a plain name, which is then used."));
        schema.put("properties", properties);
        schema.put("required", List.of());
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        java.util.Optional<ToolResponse> nameForm =
            FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return Preparation.fail(nameForm.get());
        }
        List<String> folded = new ArrayList<>();
        JsonNode named = arguments.get("parameters");
        if (named != null && named.isArray()) {
            named.forEach(n -> folded.add(n.asText()));
        }
        if (folded.size() < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("parameters",
                "name TWO OR MORE parameters to fold. Folding one is not this refactoring — an"
                    + " object in place of a single value is a longer expression at every call"
                    + " for nothing gained, and a parameter derived from another argument the"
                    + " call already passes is change_method_signature"
                    + " kind=replace_parameter_with_query.", Refusal.PARAMETERS_REQUIRED));
        }
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath",
                "filePath is required (or name the method with symbol=pkg.Type#method)"));
        }
        IJavaElement element = service.getElementAtPosition(Path.of(filePath),
            getIntParam(arguments, "line", -1), getIntParam(arguments, "column", -1));
        if (!(element instanceof IMethod method)) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a method.", Refusal.NOT_A_METHOD));
        }

        ICompilationUnit unit = method.getCompilationUnit();
        CompilationUnit ast = IntroducedParameter.parse(unit);
        MethodDeclaration decl = MethodLookup.declaration(ast, method);
        if (decl == null || decl.getBody() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "could not locate the method's body.", Refusal.NOT_A_METHOD));
        }

        @SuppressWarnings("unchecked")
        List<SingleVariableDeclaration> parameters = decl.parameters();
        List<Integer> indices = new ArrayList<>();
        for (String name : folded) {
            int at = -1;
            for (int i = 0; i < parameters.size(); i++) {
                if (name.equals(parameters.get(i).getName().getIdentifier())) {
                    at = i;
                }
            }
            if (at < 0) {
                return Preparation.fail(ToolResponse.invalidParameter("parameters",
                    "'" + method.getElementName() + "' declares no parameter '" + name + "'.",
                    Refusal.PARAMETER_NOT_FOUND));
            }
            indices.add(at);
        }

        // Fowler's caveat, shared with row 53 — see ParameterSubstitution.
        for (int i = 0; i < indices.size(); i++) {
            List<SimpleName> reads = ParameterSubstitution.readsOf(decl,
                parameters.get(indices.get(i)).resolveBinding());
            String repeated = ParameterSubstitution.whyReadingIsRepeated(reads, decl);
            if (repeated != null) {
                return Preparation.fail(ToolResponse.invalidParameter("parameters",
                    "'" + folded.get(i) + "' " + repeated + ", so the accessor would be"
                        + " evaluated more often than the call site evaluates it once. Where the"
                        + " method changes the object's state, those evaluations can differ.",
                    Refusal.PARAMETER_READ_REPEATEDLY));
            }
        }

        List<SearchMatch> references =
            service.getSearchService().findAllReferences(method, MAX_REFERENCES);
        if (references.size() >= MAX_REFERENCES) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' has at least " + MAX_REFERENCES
                    + " references, which is the search cap — the object would be inferred from"
                    + " a SAMPLE of callers rather than from all of them.",
                Refusal.REFERENCE_CAP_REACHED));
        }

        Map<ICompilationUnit, CompilationUnit> asts = new LinkedHashMap<>();
        Map<ICompilationUnit, ASTRewrite> rewrites = new LinkedHashMap<>();
        asts.put(unit, ast);
        rewrites.put(unit, ASTRewrite.create(ast.getAST()));

        // WHAT EVERY CALLER MUST AGREE ON: the accessor for each folded parameter, and the TYPE
        // of the object they are all read from. The object's own spelling may differ between
        // callers — one may write `order`, another `this.order` — so the text is only required
        // to agree WITHIN a call, where it is what makes the arguments one object rather than
        // several. Across calls it is the type that has to hold, because the type is what the
        // new parameter is declared as.
        List<String> accessors = new ArrayList<>();
        for (int i = 0; i < indices.size(); i++) {
            accessors.add(null);
        }
        Set<String> types = new LinkedHashSet<>();
        List<MethodInvocation> calls = new ArrayList<>();
        List<ICompilationUnit> callUnits = new ArrayList<>();
        List<String> receivers = new ArrayList<>();
        ITypeBinding objectType = null;

        for (SearchMatch match : references) {
            ICompilationUnit callerUnit = match.getElement() instanceof IJavaElement e
                ? (ICompilationUnit) e.getAncestor(IJavaElement.COMPILATION_UNIT) : null;
            if (callerUnit == null) {
                continue;
            }
            CompilationUnit callerAst = asts.computeIfAbsent(callerUnit,
                IntroducedParameter::parse);
            rewrites.computeIfAbsent(callerUnit, u -> ASTRewrite.create(callerAst.getAST()));
            MethodInvocation call = invocationAt(callerAst, match.getOffset());
            if (call == null || call.arguments().size() != parameters.size()) {
                continue;
            }
            String callerSource = callerUnit.getSource();
            String receiver = null;
            for (int i = 0; i < indices.size(); i++) {
                Expression argument = (Expression) call.arguments().get(indices.get(i));
                if (!(argument instanceof MethodInvocation accessor)
                    || !accessor.arguments().isEmpty() || accessor.getExpression() == null) {
                    return Preparation.fail(ToolResponse.invalidParameter("parameters",
                        "a call in " + callerUnit.getElementName() + " passes something that is"
                            + " not a no-argument accessor for '" + folded.get(i) + "': "
                            + text(callerSource, argument),
                        Refusal.CALLERS_DISAGREE));
                }
                String from = text(callerSource, accessor.getExpression());
                if (receiver == null) {
                    receiver = from;
                    objectType = accessor.getExpression().resolveTypeBinding();
                    types.add(objectType == null ? "?" : objectType.getErasure().getQualifiedName());
                } else if (!receiver.equals(from)) {
                    return Preparation.fail(ToolResponse.invalidParameter("parameters",
                        "a call in " + callerUnit.getElementName() + " reads these parameters"
                            + " from DIFFERENT objects — '" + receiver + "' and '" + from
                            + "' — so there is no single whole object to pass instead.",
                        Refusal.CALLERS_DISAGREE));
                }
                String name = accessor.getName().getIdentifier();
                if (accessors.get(i) == null) {
                    accessors.set(i, name);
                } else if (!accessors.get(i).equals(name)) {
                    return Preparation.fail(ToolResponse.invalidParameter("parameters",
                        "the callers derive '" + folded.get(i) + "' differently: "
                            + accessors.get(i) + "() and " + name + "(). Picking either would"
                            + " change what the other caller asked for.",
                        Refusal.CALLERS_DISAGREE));
                }
            }
            for (int i = 0; i < call.arguments().size(); i++) {
                if (!indices.contains(i)
                    && receiver.equals(text(callerSource, (Expression) call.arguments().get(i)))) {
                    return Preparation.fail(ToolResponse.invalidParameter("parameters",
                        "a call in " + callerUnit.getElementName() + " ALREADY passes '"
                            + receiver + "' as another argument, so folding these in would pass"
                            + " it twice. Deriving one parameter from an argument the call"
                            + " already carries is change_method_signature"
                            + " kind=replace_parameter_with_query.",
                        Refusal.OBJECT_ALREADY_PASSED));
                }
            }
            calls.add(call);
            callUnits.add(callerUnit);
            receivers.add(receiver);
        }

        if (calls.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "nothing calls '" + method.getElementName() + "', so there are no callers to read"
                    + " the object from. This row INFERS the whole object from what every caller"
                    + " already unpacks, and with no callers there is nothing to infer.",
                Refusal.NO_CALL_SITES));
        }
        if (types.size() != 1 || objectType == null) {
            return Preparation.fail(ToolResponse.invalidParameter("parameters",
                "the callers read these parameters from objects of different types " + types
                    + ", so there is no one type the new parameter could be declared as.",
                Refusal.CALLERS_DISAGREE));
        }

        String whole = getStringParam(arguments, "parameterName");
        if (whole == null || whole.isBlank()) {
            whole = receivers.get(0);
        }
        if (!whole.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            return Preparation.fail(ToolResponse.invalidParameter("parameterName",
                "the callers write the object as '" + receivers.get(0) + "', which is not a name"
                    + " a parameter can take — pass parameterName.",
                Refusal.PARAMETERS_REQUIRED));
        }

        // The declaration: the folded parameters go, the object takes the first one's place, and
        // every read becomes an accessor on it. The IMPORT is rewritten rather than assumed —
        // row 16 established that writing a simple name compiles only where the type is already
        // visible, so the row would decline every cross-package case via the compile gate.
        ASTRewrite own = rewrites.get(unit);
        ImportRewrite imports = ImportRewrite.create(ast, true);
        String typeName = imports.addImport(objectType.getErasure());
        int first = indices.stream().min(Integer::compareTo).orElseThrow();
        own.getListRewrite(decl, MethodDeclaration.PARAMETERS_PROPERTY)
            .insertAt(own.createStringPlaceholder(typeName + " " + whole,
                ASTNode.SINGLE_VARIABLE_DECLARATION), first, null);
        int rewritten = 0;
        for (int i = 0; i < indices.size(); i++) {
            SingleVariableDeclaration parameter = parameters.get(indices.get(i));
            own.getListRewrite(decl, MethodDeclaration.PARAMETERS_PROPERTY)
                .remove(parameter, null);
            for (SimpleName use : ParameterSubstitution.readsOf(decl, parameter.resolveBinding())) {
                own.replace(use, own.createStringPlaceholder(
                    whole + "." + accessors.get(i) + "()", ASTNode.METHOD_INVOCATION), null);
                rewritten++;
            }
        }

        // The call sites: the unpacking goes, the object takes its place.
        for (int c = 0; c < calls.size(); c++) {
            MethodInvocation call = calls.get(c);
            ASTRewrite rewrite = rewrites.get(callUnits.get(c));
            rewrite.getListRewrite(call, MethodInvocation.ARGUMENTS_PROPERTY)
                .insertAt(rewrite.createStringPlaceholder(receivers.get(c),
                    ASTNode.SIMPLE_NAME), first, null);
            for (int index : indices) {
                rewrite.getListRewrite(call, MethodInvocation.ARGUMENTS_PROPERTY)
                    .remove((ASTNode) call.arguments().get(index), null);
            }
        }

        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();
        for (Map.Entry<ICompilationUnit, ASTRewrite> entry : rewrites.entrySet()) {
            ICompilationUnit each = entry.getKey();
            List<TextEdit> unitEdits = new ArrayList<>();
            TextEdit edit = entry.getValue().rewriteAST(new Document(each.getSource()),
                FormatterOptions.forGeneratedCode(asts.get(each)));
            if (edit.hasChildren()) {
                unitEdits.add(edit);
            }
            if (each.equals(unit) && imports.hasRecordedChanges()) {
                unitEdits.add(imports.rewriteImports(null));
            }
            if (!unitEdits.isEmpty()) {
                byFile.put((IFile) each.getResource(), unitEdits);
            }
        }

        String label = "preserve whole object: " + folded + " become " + typeName + " " + whole;
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("method", method.getElementName());
        extras.put("parameters", folded);
        extras.put("parameterName", whole);
        extras.put("parameterType", typeName);
        extras.put("accessors", accessors);
        extras.put("bodyReadsRewritten", rewritten);
        extras.put("callSitesShortened", calls.size());
        return Preparation.of(ChangeEngine.fromFileEdits(label, byFile),
            label + " (" + rewritten + " read(s) in the body, " + calls.size()
                + " call site(s) shortened)", extras);
    }

    private static MethodInvocation invocationAt(CompilationUnit ast, int offset) {
        ASTNode node = NodeFinder.perform(ast, offset, 0);
        while (node != null && !(node instanceof MethodInvocation)) {
            node = node.getParent();
        }
        return (MethodInvocation) node;
    }

    private static String text(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength());
    }
}
