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
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
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
 * {@code change_method_signature kind=replace_parameter_with_query} — Fowler row 53, Replace
 * Parameter with Query.
 *
 * <p>The exact inverse of row 55. A parameter every caller derives the same way is not
 * information the method was given — it is information the method could have fetched, and the
 * parameter merely makes every caller repeat the fetch. Removing it shortens the signature and
 * moves the derivation to the one place that needs it.</p>
 *
 * <h2>The direction is a JUDGEMENT, and the two rows are opposites on purpose</h2>
 *
 * <p>Row 55 pushes a query OUT to the callers so the method stops depending on a collaborator.
 * This one pulls a query IN so the callers stop repeating themselves. Which is right depends on
 * whether the dependency or the repetition costs more, and that is not a tool's call — so both
 * exist, neither is a default, and this javadoc says so rather than implying one direction is
 * the improvement.</p>
 *
 * <h2>The query is INFERRED from the call sites, and it must be unanimous</h2>
 *
 * <p>The caller does not name the query. Every call site is read, and the row proceeds only if
 * ALL of them pass the same shape for that parameter — {@code order.level()}, where
 * {@code order} is the argument they pass for ANOTHER parameter of the same call. Then the body
 * can replace the parameter with {@code order.level()} and mean exactly what it meant before.</p>
 *
 * <p>Unanimity is the whole safety argument. One call site passing something else means the
 * parameter carries information the query cannot reproduce, and removing it would change what
 * that caller asked for — so the row refuses and names the file.</p>
 *
 * <h2>And the body must read the parameter ONCE, which is Fowler's own precondition</h2>
 *
 * <p>The call site evaluates the derivation once; the body would evaluate it once per read. Where
 * the method changes the state the query reads, those evaluations answer differently and the
 * method computes something no caller asked for — and it compiles, so nothing below this row
 * could catch it. Fowler says the same thing as a caveat: do not do this when the query depends on
 * state the function modifies.</p>
 *
 * <p><b>Upstream's own instance of the trigger is exactly that case</b>, which is how the
 * precondition came to be here rather than by review. {@code Feind.fightForTheSword(reacher,
 * sword.getLocker(), sword)} is called twice and both callers agree, so every other condition
 * passes — and {@code holder} is read five times inside a loop whose body attacks it and can
 * release the sword. See {@code fork-lockable-object}, which pins the refusal.</p>
 */
public class ReplaceParameterWithQueryTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Callers are enumerated, never sampled; reaching this cap REFUSES rather than truncates. */
    private static final int MAX_REFERENCES = 1000;

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a method. */
        public static final String NOT_A_METHOD = "NOT_A_METHOD";
        /** {@code parameter} was absent, and there is no default for which one to remove. */
        public static final String PARAMETER_REQUIRED = "PARAMETER_REQUIRED";
        /** The method declares no parameter of that name. */
        public static final String PARAMETER_NOT_FOUND = "PARAMETER_NOT_FOUND";
        /** Nothing calls the method, so no query can be inferred from its callers. */
        public static final String NO_CALL_SITES = "NO_CALL_SITES";
        /** The call sites do not all pass the same derivation for that parameter. */
        public static final String CALLERS_DISAGREE = "CALLERS_DISAGREE";
        /** The reference search hit its cap, so the caller list is a sample. */
        public static final String REFERENCE_CAP_REACHED = "REFERENCE_CAP_REACHED";
        /** The body would evaluate the query more than the call site evaluated it once. */
        public static final String PARAMETER_READ_REPEATEDLY = "PARAMETER_READ_REPEATEDLY";

        private Refusal() {
        }
    }

    public ReplaceParameterWithQueryTool(Supplier<IJdtService> serviceSupplier,
                                         RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "replace_parameter_with_query";
    }

    @Override
    public String getName() {
        return "replace_parameter_with_query";
    }

    @Override
    public String getDescription() {
        return """
            Replace Parameter with Query — a parameter every caller derives the same way is
            removed, and the body derives it instead. Name the METHOD (symbol=pkg.Type#m, or a
            position) and the parameter. The query is INFERRED from the call sites and must be
            UNANIMOUS: every call must pass <x>.something() where x is the argument it passes
            for another parameter of the same call. The exact inverse of
            replace_query_with_parameter — which direction is right depends on whether the
            dependency or the repetition costs more, so neither is a default. Refuses if the
            method has no callers, if one caller passes something else, or if the body reads
            the parameter more than once or inside a loop — the call site evaluates the
            derivation once and the body would evaluate it once per read.""";
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
        properties.put("symbol", FqnTarget.symbolSchemaProperty("method with the derived parameter"));
        properties.put("parameter", Map.of("type", "string",
            "description", "REQUIRED. Name of the parameter to remove."));
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
        String parameter = getStringParam(arguments, "parameter");
        if (parameter == null || parameter.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("parameter",
                "parameter is required: it names which argument the callers are all deriving"
                    + " the same way, and there is no sensible default for that.",
                Refusal.PARAMETER_REQUIRED));
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
        int index = -1;
        for (int i = 0; i < parameters.size(); i++) {
            if (parameter.equals(parameters.get(i).getName().getIdentifier())) {
                index = i;
            }
        }
        if (index < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("parameter",
                "'" + method.getElementName() + "' declares no parameter '" + parameter + "'.",
                Refusal.PARAMETER_NOT_FOUND));
        }

        // FOWLER'S OWN PRECONDITION, and the corpus is what put it here. The call site evaluates
        // the derivation ONCE; the body would evaluate it once per read. Where the method changes
        // the state the query reads, several reads answer differently and the method computes
        // something no caller asked for — and it COMPILES, so no gate below this one can see it.
        // Purity is not decidable here, exactly as row 45 states for its own impurity rule, so
        // the check is the read count and the read's context rather than an analysis of the query.
        List<SimpleName> reads = readsOf(decl, parameters.get(index).resolveBinding());
        String repeated = whyReadingIsRepeated(reads, decl);
        if (repeated != null) {
            return Preparation.fail(ToolResponse.invalidParameter("parameter",
                "'" + parameter + "' " + repeated + ", so the query would be evaluated more often"
                    + " than the call site evaluates it once. Where the method changes the state"
                    + " the query reads, those evaluations can differ.",
                Refusal.PARAMETER_READ_REPEATEDLY));
        }

        List<SearchMatch> references =
            service.getSearchService().findAllReferences(method, MAX_REFERENCES);
        if (references.size() >= MAX_REFERENCES) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' has at least " + MAX_REFERENCES
                    + " references, which is the search cap — the derivation would be inferred"
                    + " from a SAMPLE of callers rather than from all of them.",
                Refusal.REFERENCE_CAP_REACHED));
        }

        Map<ICompilationUnit, CompilationUnit> asts = new LinkedHashMap<>();
        Map<ICompilationUnit, ASTRewrite> rewrites = new LinkedHashMap<>();
        asts.put(unit, ast);
        rewrites.put(unit, ASTRewrite.create(ast.getAST()));

        // WHAT EVERY CALLER MUST AGREE ON: the query's name, and which OTHER parameter its
        // receiver is. Both are read off the call sites; neither is supplied by the caller of
        // this tool, because a name they typed could disagree with what the code actually does.
        Set<String> queries = new LinkedHashSet<>();
        Set<Integer> receiverIndices = new LinkedHashSet<>();
        List<MethodInvocation> calls = new ArrayList<>();
        List<ICompilationUnit> callUnits = new ArrayList<>();

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
            Expression argument = (Expression) call.arguments().get(index);
            if (!(argument instanceof MethodInvocation query) || !query.arguments().isEmpty()
                || query.getExpression() == null) {
                return Preparation.fail(ToolResponse.invalidParameter("parameter",
                    "a call in " + callerUnit.getElementName() + " passes something that is not"
                        + " a no-argument query for '" + parameter + "', so the body cannot"
                        + " derive it: " + text(callerSource, argument),
                    Refusal.CALLERS_DISAGREE));
            }
            String receiver = text(callerSource, query.getExpression());
            int receiverIndex = -1;
            for (int i = 0; i < call.arguments().size(); i++) {
                if (i != index
                    && receiver.equals(text(callerSource, (Expression) call.arguments().get(i)))) {
                    receiverIndex = i;
                }
            }
            if (receiverIndex < 0) {
                return Preparation.fail(ToolResponse.invalidParameter("parameter",
                    "a call in " + callerUnit.getElementName() + " derives '" + parameter
                        + "' from '" + receiver + "', which is not one of the OTHER arguments it"
                        + " passes — so inside the method there is nothing to ask.",
                    Refusal.CALLERS_DISAGREE));
            }
            queries.add(query.getName().getIdentifier());
            receiverIndices.add(receiverIndex);
            calls.add(call);
            callUnits.add(callerUnit);
        }

        if (calls.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "nothing calls '" + method.getElementName() + "', so there are no callers to"
                    + " read the derivation from. This row INFERS the query from what every"
                    + " caller already passes, and with no callers there is nothing to infer.",
                Refusal.NO_CALL_SITES));
        }
        if (queries.size() != 1 || receiverIndices.size() != 1) {
            return Preparation.fail(ToolResponse.invalidParameter("parameter",
                "the callers do not agree: they derive '" + parameter + "' as " + queries
                    + " from argument position(s) " + receiverIndices + ". One caller passing"
                    + " something else means the parameter carries information the query cannot"
                    + " reproduce.", Refusal.CALLERS_DISAGREE));
        }
        String query = queries.iterator().next();
        String receiverParameter =
            parameters.get(receiverIndices.iterator().next()).getName().getIdentifier();

        // The declaration: drop the parameter, and every read of it becomes the query.
        ASTRewrite own = rewrites.get(unit);
        own.getListRewrite(decl, MethodDeclaration.PARAMETERS_PROPERTY)
            .remove(parameters.get(index), null);
        int rewritten = 0;
        for (SimpleName use : reads) {
            own.replace(use, own.createStringPlaceholder(receiverParameter + "." + query + "()",
                ASTNode.METHOD_INVOCATION), null);
            rewritten++;
        }

        // The call sites: drop the argument they no longer need to compute.
        for (int i = 0; i < calls.size(); i++) {
            MethodInvocation call = calls.get(i);
            ASTRewrite rewrite = rewrites.get(callUnits.get(i));
            rewrite.getListRewrite(call, MethodInvocation.ARGUMENTS_PROPERTY)
                .remove((ASTNode) call.arguments().get(index), null);
        }

        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();
        for (Map.Entry<ICompilationUnit, ASTRewrite> entry : rewrites.entrySet()) {
            ICompilationUnit each = entry.getKey();
            TextEdit edit = entry.getValue().rewriteAST(new Document(each.getSource()),
                FormatterOptions.forGeneratedCode(asts.get(each)));
            if (edit.hasChildren()) {
                byFile.put((IFile) each.getResource(), List.of(edit));
            }
        }

        String label = "replace parameter " + parameter + " with " + receiverParameter + "."
            + query + "()";
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("method", method.getElementName());
        extras.put("parameter", parameter);
        extras.put("query", receiverParameter + "." + query + "()");
        extras.put("bodyReadsRewritten", rewritten);
        extras.put("callSitesShortened", calls.size());
        return Preparation.of(ChangeEngine.fromFileEdits(label, byFile),
            label + " (" + rewritten + " read(s) in the body, " + calls.size()
                + " call site(s) shortened)", extras);
    }

    /**
     * WHY the substitution would evaluate the query more than once, or {@code null} if it would
     * not. The answer is a phrase completing "'{@code x}' …", so the refusal names what was seen
     * rather than restating the rule.
     *
     * <p>Two shapes are refused and they are the same defect counted differently. SEVERAL reads
     * become several evaluations outright. ONE read inside a loop, a lambda or an anonymous class
     * is evaluated once per iteration or per invocation, which the read count alone cannot see —
     * and that second shape is not hypothetical: upstream's own instance of this refactoring's
     * trigger has both at once.</p>
     *
     * <p>What it deliberately does NOT do is decide whether the query is pure. That is not
     * decidable here, which is the same reasoning row 45 gives for refusing any call in a derived
     * expression rather than trying to classify it. The cost is that a genuinely pure query read
     * twice is refused too, and the refusal says which shape it saw so a reader can judge that.</p>
     */
    private static String whyReadingIsRepeated(List<SimpleName> reads, MethodDeclaration decl) {
        if (reads.size() > 1) {
            return "is read " + reads.size() + " times in the body";
        }
        if (reads.size() == 1) {
            for (ASTNode n = reads.get(0); n != null && n != decl; n = n.getParent()) {
                String shape = switch (n) {
                    case org.eclipse.jdt.core.dom.WhileStatement ignored -> "a while loop";
                    case org.eclipse.jdt.core.dom.ForStatement ignored -> "a for loop";
                    case org.eclipse.jdt.core.dom.EnhancedForStatement ignored -> "a for-each loop";
                    case org.eclipse.jdt.core.dom.DoStatement ignored -> "a do-while loop";
                    case org.eclipse.jdt.core.dom.LambdaExpression ignored -> "a lambda";
                    case org.eclipse.jdt.core.dom.AnonymousClassDeclaration ignored ->
                        "an anonymous class";
                    case null, default -> null;
                };
                if (shape != null) {
                    return "is read inside " + shape + ", so its one read runs many times";
                }
            }
        }
        return null;
    }

    /** Every READ of that parameter in the body — its own declaration is not one. */
    private static List<SimpleName> readsOf(MethodDeclaration decl, IVariableBinding parameter) {
        List<SimpleName> found = new ArrayList<>();
        if (parameter == null) {
            return found;
        }
        decl.getBody().accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.resolveBinding() instanceof IVariableBinding v
                    && v.isEqualTo(parameter)) {
                    found.add(node);
                }
                return true;
            }
        });
        return found;
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
