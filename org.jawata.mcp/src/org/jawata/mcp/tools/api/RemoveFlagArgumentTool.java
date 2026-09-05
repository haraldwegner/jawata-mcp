package org.jawata.mcp.tools.api;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BooleanLiteral;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
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
 * {@code change_method_signature kind=remove_flag_argument} — Fowler row 35, Remove Flag Argument.
 *
 * <p>{@code book(customer, true)} tells a reader nothing. The literal at the call site is the
 * whole decision and it is written in a language only the callee understands, so every reader has
 * to open the callee to learn what {@code true} meant. Two explicitly named methods say it at the
 * call, where the decision is made.</p>
 *
 * <pre>{@code
 * book(customer, true)    ->   bookPremium(customer)
 * book(customer, false)   ->   bookRegular(customer)
 * }</pre>
 *
 * <h2>The safe half of Fowler's mechanics, and the second half is a composition</h2>
 *
 * <p>Fowler's first step is to add an explicit method per value that DELEGATES with the literal,
 * then to point the callers at them. That is exactly what this row does, and it is
 * behaviour-preserving without reading the body at all: the flag still exists, the branch still
 * runs, and every call means what it meant.</p>
 *
 * <p>The optional second half — inlining the delegates and splitting the branch — is
 * {@code inline kind=method} on this same door's neighbour, applied where the caller judges it
 * worth it. It is NOT done here, because it needs the body specialised for each value, which is a
 * decision about the code rather than a mechanical step. Both the unit test and the golden assert
 * the original is STILL THERE, so a reader cannot take it as done.</p>
 *
 * <h2>The literal at every call site is the precondition</h2>
 *
 * <p>A caller passing a VARIABLE has not made the decision yet — it is passing one on, and there is
 * no name to rewrite it to. The row refuses and names the file, rather than leaving that one call
 * behind on a method the others no longer use.</p>
 *
 * <p><b>And every caller passing the SAME literal is refused too, which the FORK CORPUS is what
 * put here.</b> Of its fourteen methods declaring a boolean parameter, the seven called with a
 * literal all pass one value and only one — three setters, two constructors this row does not
 * reach, a JDK-fixed signature, and a guard helper whose other callers pass expressions. For those
 * the flag is not selecting between two things: it is a constant the callers happen to agree on,
 * and two named methods would leave one with no caller. That case wants the parameter REMOVED,
 * which is {@code kind=change_signature} on this same door.</p>
 *
 * <p>Scoped to a {@code boolean} flag, and that is stated rather than left to be discovered: an
 * enum flag is the same idea with N names instead of two, and a caller passing an enum constant
 * that this row silently treated as unrecognised would be the worst of both.</p>
 */
public class RemoveFlagArgumentTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Callers are enumerated, never sampled; reaching this cap REFUSES rather than truncates. */
    private static final int MAX_REFERENCES = 1000;

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a method. */
        public static final String NOT_A_METHOD = "NOT_A_METHOD";
        /** {@code parameter} was absent, and there is no default for which one is the flag. */
        public static final String PARAMETER_REQUIRED = "PARAMETER_REQUIRED";
        /** The method declares no parameter of that name. */
        public static final String PARAMETER_NOT_FOUND = "PARAMETER_NOT_FOUND";
        /** The named parameter is not a boolean, which is what this row is scoped to. */
        public static final String NOT_A_BOOLEAN_FLAG = "NOT_A_BOOLEAN_FLAG";
        /** Both names are required: saying what the flag MEANS is the whole change. */
        public static final String NAMES_REQUIRED = "NAMES_REQUIRED";
        /** The class already declares a method that one of the new names would collide with. */
        public static final String NAME_TAKEN = "NAME_TAKEN";
        /** Nothing calls the method, so there is no call site the new names would serve. */
        public static final String NO_CALL_SITES = "NO_CALL_SITES";
        /** A caller passes a variable rather than a literal, so it has no name to become. */
        public static final String CALLER_PASSES_A_VARIABLE = "CALLER_PASSES_A_VARIABLE";
        /** Every caller passes the SAME literal, so one generated method would have no caller. */
        public static final String FLAG_IS_ONE_SIDED = "FLAG_IS_ONE_SIDED";
        /** The reference search hit its cap, so the caller list is a sample. */
        public static final String REFERENCE_CAP_REACHED = "REFERENCE_CAP_REACHED";

        private Refusal() {
        }
    }

    public RemoveFlagArgumentTool(Supplier<IJdtService> serviceSupplier,
                                  RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "remove_flag_argument";
    }

    @Override
    public String getName() {
        return "remove_flag_argument";
    }

    @Override
    public String getDescription() {
        return """
            Remove Flag Argument — a boolean that selects behaviour becomes two explicitly named
            methods, so the call says what it means. Name the METHOD (symbol=pkg.Type#m, or a
            position), the boolean parameter, and BOTH names (whenTrue, whenFalse) — there is no
            default, because naming the two cases IS the change. Each new method DELEGATES with
            its literal and every call site is rewritten to the one matching the literal it
            passed; the original stays, so nothing about behaviour moves. Refuses a caller that
            passes a variable (it has not made the decision yet, so it has no name to become) and
            a flag every caller passes the SAME way (one of the two methods would have no caller;
            that parameter is a constant, and kind=change_signature removes it).""";
    }

    /** Structural: call sites move to different methods. */
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
        properties.put("symbol", FqnTarget.symbolSchemaProperty("method with the flag argument"));
        properties.put("parameter", Map.of("type", "string",
            "description", "REQUIRED. Name of the boolean parameter that selects behaviour."));
        properties.put("whenTrue", Map.of("type", "string",
            "description", "REQUIRED. Name for the method callers passing true will call."));
        properties.put("whenFalse", Map.of("type", "string",
            "description", "REQUIRED. Name for the method callers passing false will call."));
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
                "parameter is required: it names which argument is the flag.",
                Refusal.PARAMETER_REQUIRED));
        }
        String whenTrue = getStringParam(arguments, "whenTrue");
        String whenFalse = getStringParam(arguments, "whenFalse");
        if (whenTrue == null || whenTrue.isBlank() || whenFalse == null || whenFalse.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("whenTrue",
                "whenTrue and whenFalse are both required. Saying what the two cases MEAN is the"
                    + " entire content of this refactoring — a generated pair like bookTrue and"
                    + " bookFalse would perform the mechanics and deliver none of it.",
                Refusal.NAMES_REQUIRED));
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
        if (decl == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "could not locate the method's declaration.", Refusal.NOT_A_METHOD));
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
        if (!isBoolean(parameters.get(index))) {
            return Preparation.fail(ToolResponse.invalidParameter("parameter",
                "'" + parameter + "' is declared "
                    + parameters.get(index).getType() + ", and this row is scoped to a boolean"
                    + " flag. An enum flag is the same idea with one name per constant, and"
                    + " treating one as unrecognised here would be worse than declining.",
                Refusal.NOT_A_BOOLEAN_FLAG));
        }

        AbstractTypeDeclaration owner = enclosingType(decl);
        if (owner == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "could not locate the type declaring '" + method.getElementName() + "'.",
                Refusal.NOT_A_METHOD));
        }
        for (String name : List.of(whenTrue, whenFalse)) {
            if (declaresMethod(owner, name, parameters.size() - 1)) {
                return Preparation.fail(ToolResponse.invalidParameter("whenTrue",
                    owner.getName().getIdentifier() + " already declares a method '" + name
                        + "' taking " + (parameters.size() - 1) + " parameter(s), so generating"
                        + " this one would not compile.", Refusal.NAME_TAKEN));
            }
        }

        List<SearchMatch> references =
            service.getSearchService().findAllReferences(method, MAX_REFERENCES);
        if (references.size() >= MAX_REFERENCES) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + method.getElementName() + "' has at least " + MAX_REFERENCES
                    + " references, which is the search cap — some call site would keep calling"
                    + " a method the others no longer use, unseen.",
                Refusal.REFERENCE_CAP_REACHED));
        }

        Map<ICompilationUnit, CompilationUnit> asts = new LinkedHashMap<>();
        Map<ICompilationUnit, ASTRewrite> rewrites = new LinkedHashMap<>();
        asts.put(unit, ast);
        rewrites.put(unit, ASTRewrite.create(ast.getAST()));

        int rewrittenTrue = 0;
        int rewrittenFalse = 0;
        for (SearchMatch match : references) {
            ICompilationUnit callerUnit = match.getElement() instanceof IJavaElement e
                ? (ICompilationUnit) e.getAncestor(IJavaElement.COMPILATION_UNIT) : null;
            if (callerUnit == null) {
                continue;
            }
            CompilationUnit callerAst = asts.computeIfAbsent(callerUnit,
                IntroducedParameter::parse);
            ASTRewrite rewrite = rewrites.computeIfAbsent(callerUnit,
                u -> ASTRewrite.create(callerAst.getAST()));
            MethodInvocation call = invocationAt(callerAst, match.getOffset());
            if (call == null || call.arguments().size() != parameters.size()) {
                continue;
            }
            Expression flag = (Expression) call.arguments().get(index);
            if (!(flag instanceof BooleanLiteral literal)) {
                return Preparation.fail(ToolResponse.invalidParameter("parameter",
                    "a call in " + callerUnit.getElementName() + " passes '"
                        + text(callerUnit.getSource(), flag) + "' for '" + parameter + "' rather"
                        + " than a literal. That caller has not made the decision yet — it is"
                        + " passing one on — so there is no name to rewrite it to.",
                    Refusal.CALLER_PASSES_A_VARIABLE));
            }
            rewrite.set(call, MethodInvocation.NAME_PROPERTY,
                callerAst.getAST().newSimpleName(literal.booleanValue() ? whenTrue : whenFalse),
                null);
            rewrite.getListRewrite(call, MethodInvocation.ARGUMENTS_PROPERTY)
                .remove(flag, null);
            if (literal.booleanValue()) {
                rewrittenTrue++;
            } else {
                rewrittenFalse++;
            }
        }

        if (rewrittenTrue + rewrittenFalse == 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "nothing calls '" + method.getElementName() + "', so the two named methods would"
                    + " be generated for nobody. The point of this row is what the CALL SITES"
                    + " read like afterwards.", Refusal.NO_CALL_SITES));
        }
        // THE CORPUS PUT THIS HERE. Every candidate in the fork passes ONE literal and only one —
        // three setters and a guard helper — and for those the flag is not selecting between two
        // things at all: it is a constant every caller happens to agree on. Two named methods
        // would leave one with no caller, which is dead code this row would have generated.
        if (rewrittenTrue == 0 || rewrittenFalse == 0) {
            boolean only = rewrittenFalse == 0;
            return Preparation.fail(ToolResponse.invalidParameter("parameter",
                "every caller of '" + method.getElementName() + "' passes " + only + " for '"
                    + parameter + "', so '" + (only ? whenFalse : whenTrue) + "' would be"
                    + " generated with no caller. The flag is not selecting between two things"
                    + " here — it is a constant, and removing it outright is"
                    + " change_method_signature kind=change_signature.",
                Refusal.FLAG_IS_ONE_SIDED));
        }

        // The two delegates, generated next to the original, which STAYS. Nothing about the
        // behaviour moves: the flag still exists and the branch still runs where it did.
        ASTRewrite own = rewrites.get(unit);
        ListRewrite members = own.getListRewrite(owner, owner.getBodyDeclarationsProperty());
        String source = unit.getSource();
        String modifiers = modifiersOf(decl);
        String returns = decl.getReturnType2() == null ? "void"
            : text(source, decl.getReturnType2());
        String signature = signatureWithout(source, parameters, index);
        // FALSE is inserted first so TRUE ends up first, because each insertAfter goes directly
        // after `decl` and the second one lands ahead of the first. The order the reader gets is
        // whenTrue then whenFalse, matching the order they named them — which is a choice rather
        // than whatever the two calls happened to produce. The golden is what holds it.
        members.insertAfter(own.createStringPlaceholder(delegate(modifiers, returns, whenFalse,
            signature, method.getElementName(), parameters, index, false),
            ASTNode.METHOD_DECLARATION), decl, null);
        members.insertAfter(own.createStringPlaceholder(delegate(modifiers, returns, whenTrue,
            signature, method.getElementName(), parameters, index, true),
            ASTNode.METHOD_DECLARATION), decl, null);

        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();
        for (Map.Entry<ICompilationUnit, ASTRewrite> entry : rewrites.entrySet()) {
            ICompilationUnit each = entry.getKey();
            TextEdit edit = entry.getValue().rewriteAST(new Document(each.getSource()),
                FormatterOptions.forGeneratedCode(asts.get(each)));
            if (edit.hasChildren()) {
                byFile.put((IFile) each.getResource(), List.of(edit));
            }
        }

        String label = "remove flag argument " + parameter + " from "
            + method.getElementName() + ": " + whenTrue + " / " + whenFalse;
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("method", method.getElementName());
        extras.put("parameter", parameter);
        extras.put("whenTrue", whenTrue);
        extras.put("whenFalse", whenFalse);
        extras.put("callSitesRewrittenTrue", rewrittenTrue);
        extras.put("callSitesRewrittenFalse", rewrittenFalse);
        return Preparation.of(ChangeEngine.fromFileEdits(label, byFile),
            label + " (" + rewrittenTrue + " call site(s) to " + whenTrue + ", " + rewrittenFalse
                + " to " + whenFalse + "; the original method is unchanged and still called by"
                + " both)", extras);
    }

    /** The delegate's whole text — it forwards with the literal and nothing else. */
    private static String delegate(String modifiers, String returns, String name, String signature,
                                   String original, List<SingleVariableDeclaration> parameters,
                                   int index, boolean value) {
        StringBuilder passed = new StringBuilder();
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                passed.append(", ");
            }
            passed.append(i == index ? String.valueOf(value)
                : parameters.get(i).getName().getIdentifier());
        }
        return "/** " + original + " with the flag set to " + value
            + ": generated by Remove Flag Argument (Fowler row 35). */\n"
            + modifiers + returns + " " + name + "(" + signature + ") {\n"
            + "    " + ("void".equals(returns) ? "" : "return ") + original + "("
            + passed + ");\n}";
    }

    /** The original's parameter list with the flag taken out, spelled as it was written. */
    private static String signatureWithout(String source,
                                           List<SingleVariableDeclaration> parameters, int index) {
        List<String> kept = new ArrayList<>();
        for (int i = 0; i < parameters.size(); i++) {
            if (i != index) {
                kept.add(text(source, parameters.get(i)));
            }
        }
        return String.join(", ", kept);
    }

    /** The original's own modifiers, so the delegates are as reachable and as static as it is. */
    private static String modifiersOf(MethodDeclaration decl) {
        StringBuilder out = new StringBuilder();
        for (Object modifier : decl.modifiers()) {
            if (modifier instanceof Modifier m && !m.isAbstract() && !m.isDefault()) {
                out.append(m.getKeyword().toString()).append(' ');
            }
        }
        return out.toString();
    }

    private static boolean isBoolean(SingleVariableDeclaration parameter) {
        return parameter.getType() instanceof PrimitiveType primitive
            && primitive.getPrimitiveTypeCode() == PrimitiveType.BOOLEAN;
    }

    private static boolean declaresMethod(AbstractTypeDeclaration owner, String name, int arity) {
        for (Object member : owner.bodyDeclarations()) {
            if (member instanceof MethodDeclaration m
                && name.equals(m.getName().getIdentifier())
                && m.parameters().size() == arity) {
                return true;
            }
        }
        return false;
    }

    private static AbstractTypeDeclaration enclosingType(MethodDeclaration decl) {
        for (ASTNode n = decl.getParent(); n != null; n = n.getParent()) {
            if (n instanceof AbstractTypeDeclaration type) {
                return type;
            }
        }
        return null;
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
