package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.shared.FormatterOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fowler — <b>Remove Middle Man</b> (row 36). A class that does nothing but forward to a
 * collaborator stops forwarding: callers are pointed at the collaborator instead, and the
 * forwarders go.
 *
 * <p>A delegate of {@link InlineTool} (kind {@code middle_man}); not registered standalone.
 * Routed from {@code middle_man}, the detector that counts a class's forwarding ratio.</p>
 *
 * <h2>It is Hide Delegate run backwards, and that is the judgement it does not make</h2>
 *
 * <p>Fowler files this against Hide Delegate, and neither is right in general — a middle man
 * buys encapsulation and costs a method per delegated call, and which side of that trade a
 * class should be on depends on how much the delegate's interface churns. This performs the
 * direction the caller asked for, on the methods that genuinely only forward, and leaves
 * everything else where it is.</p>
 *
 * <h2>What it does, and what it refuses</h2>
 *
 * <p>A forwarder is a method whose entire body is one call on the same field, with the same
 * arguments in the same order. Each one's call sites become
 * {@code middleMan.<accessor>().method(args)}, and the forwarder is deleted. The accessor is
 * generated if the class has none — that exposure IS the refactoring, and the summary says
 * it happened rather than leaving it to be noticed.</p>
 *
 * <ul>
 *   <li><b>The forwarders must all target ONE field.</b> A class forwarding to two
 *       collaborators is two middle men, and removing both at once would be two decisions
 *       taken as one.</li>
 *   <li><b>At least one forwarder must exist</b>, or there is no middle man here.</li>
 *   <li><b>A method that does anything besides forward is left alone</b> — including one
 *       that transforms the result. That is not a forwarder, it is behaviour, and deleting
 *       it would change what callers get.</li>
 * </ul>
 */
public class RemoveMiddleManTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    /** Reached as {@code inline kind=middle_man}. */
    @Override
    public String kindName() {
        return "middle_man";
    }

    /** The bullet a client reads under {@code inline} — moved here from the door (M5). */
    @Override
    public String kindSummary() {
        return """
            stop a class forwarding: every method whose whole body is
            one call on one of its own fields, passing its parameters
            through unchanged, is deleted and its call sites become
            `middleMan.<accessor>().method(args)`. The accessor is
            generated if the class has none — that exposure IS the
            refactoring, and the summary says it happened. Refuses a class
            with no forwarder at all. A class forwarding to SEVERAL
            fields is NOT refused — name the one to remove with
            `delegateField` and repeat; the fork's own GiantController is
            why the old blanket refusal was wrong. A method that
            transforms the result is left alone: that is behaviour, not
            forwarding. (find_quality_issue kind=middle_man finds them.)""";
    }

    /** Structural: it changes what the class exposes to every caller it had. */
    @Override
    public boolean isStructural() {
        return true;
    }


    public RemoveMiddleManTool(Supplier<IJdtService> serviceSupplier,
                               RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "remove_middle_man";
    }

    @Override
    public String getDescription() {
        return "Remove Middle Man — point callers at the delegate and delete the forwarding "
            + "methods. Delegate of inline.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the middle man."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of a caret in that class."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of that caret."));
        properties.put("accessorName", Map.of("type", "string",
            "description", "Name for the accessor that exposes the delegate (default: the "
                + "field's own name). Every rewritten call site reads it, so it is worth "
                + "choosing."));
        properties.put("delegateField", Map.of("type", "string",
            "description", "Which field to stop forwarding to, when the class forwards to "
                + "more than one. Required only then, and the refusal lists the candidates."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IType middleMan = service.getTypeAtPosition(filePath, line, column);
            if (middleMan == null || middleMan.getCompilationUnit() == null) {
                return ToolResponse.symbolNotFound(
                    "No source type at " + filePathStr + ":" + line + ":" + column);
            }
            return remove(service, middleMan, getStringParam(arguments, "accessorName"),
                arguments);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse remove(IJdtService service, IType middleMan, String accessorName,
                                JsonNode arguments) throws Exception {
        ICompilationUnit unit = middleMan.getCompilationUnit();
        CompilationUnit ast = parse(unit);
        TypeDeclaration type = typeNamed(ast, middleMan.getElementName());
        if (type == null) {
            return ToolResponse.symbolNotFound(
                "could not locate the body of " + middleMan.getElementName());
        }

        Set<String> fields = new LinkedHashSet<>();
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof FieldDeclaration field) {
                for (Object fragment : field.fragments()) {
                    fields.add(((VariableDeclarationFragment) fragment)
                        .getName().getIdentifier());
                }
            }
        }

        Map<String, List<MethodDeclaration>> byTarget = new LinkedHashMap<>();
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && !method.isConstructor()) {
                String target = forwardedField(method, fields);
                if (target != null) {
                    byTarget.computeIfAbsent(target, t -> new ArrayList<>()).add(method);
                }
            }
        }
        if (byTarget.isEmpty()) {
            return ToolResponse.invalidParameter("position",
                middleMan.getElementName() + " has no method whose whole body is one call on"
                    + " one of its own fields, passing the same arguments through. There is"
                    + " no middle man here to remove.");
        }
        // SEVERAL DELEGATES IS ONE-AT-A-TIME, not a dead end. The first version refused
        // outright, and the fork's GiantController is why that was wrong: it forwards six
        // methods to `giant` and one to `view`, which makes it a middle man for `giant`
        // with an unrelated method beside — not "two middle men" in any sense a caller
        // would recognise. Removing both at once WOULD be two decisions taken as one, so
        // the caller names which, and only that field's forwarders go.
        String delegateField = getStringParam(arguments, "delegateField");
        if (delegateField == null || delegateField.isBlank()) {
            if (byTarget.size() > 1) {
                // D3a, AND THE WRITTEN REASON FOR CARRYING NO `nextStep` HERE. Every other
                // composed row's refusal hands the caller a SMALLER OPERATION. This one does
                // not, because the step it leaves is THIS operation again with one more
                // value — and that value is a decision (which collaborator stops being
                // forwarded to) that only the caller can make. A NextStep naming
                // `inline kind=middle_man` pointed back at the same place would be an
                // instruction to repeat the call that just failed. The message names the
                // candidate fields instead, which is the information the choice needs.
                return ToolResponse.invalidParameter("delegateField",
                    middleMan.getElementName() + " forwards to " + byTarget.size()
                        + " different fields " + byTarget.keySet() + ". Removing them all at"
                        + " once would be that many decisions taken as one, so name the one"
                        + " to stop forwarding to in `delegateField`.");
            }
            delegateField = byTarget.keySet().iterator().next();
        } else if (!byTarget.containsKey(delegateField)) {
            return ToolResponse.invalidParameter("delegateField",
                "no method forwards to '" + delegateField + "'. The fields that are forwarded"
                    + " to are " + byTarget.keySet() + ".");
        }
        List<MethodDeclaration> forwarders = byTarget.get(delegateField);
        String accessor = accessorName != null && !accessorName.isBlank()
            ? accessorName : delegateField;

        // THE ACCESSOR IS THE EXPOSURE, and it is the whole point of the direction: callers
        // now reach the delegate directly, which is what a middle man existed to prevent.
        boolean accessorExists = false;
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method
                    && accessor.equals(method.getName().getIdentifier())
                    && method.parameters().isEmpty()) {
                accessorExists = true;
            }
        }

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        ASTRewrite ownerRewrite = ASTRewrite.create(ast.getAST());
        Map<String, String> removed = new LinkedHashMap<>();
        List<Call> calls = new ArrayList<>();
        for (MethodDeclaration forwarder : forwarders) {
            IMethodBinding binding = forwarder.resolveBinding();
            if (binding == null || !(binding.getJavaElement() instanceof IMethod method)) {
                return ToolResponse.symbolNotFound(
                    "could not resolve " + forwarder.getName() + " to a method element.");
            }
            // THE DELEGATE'S NAME, not the forwarder's. `TaskSet.addTask(t)` forwards to
            // `queue.put(t)`, and the rewritten call must read `taskSet.queue().put(t)` —
            // the first version emitted `.addTask(t)` on the queue, which does not exist.
            // Both names were the same in the hand-written fixture, so nothing showed it;
            // the fork slice found it on the first run, which is what that clause is for.
            calls.addAll(callsTo(service, method, delegateMethodName(forwarder)));
            removed.put(forwarder.getName().getIdentifier(),
                delegateField + "." + delegateMethodName(forwarder));
            ownerRewrite.remove(forwarder, null);
        }
        if (!accessorExists) {
            String delegateType = delegateTypeOf(type, delegateField);
            ListRewrite members = ownerRewrite.getListRewrite(type,
                type.getBodyDeclarationsProperty());
            members.insertLast(ownerRewrite.createStringPlaceholder(
                "/** The delegate, now reached directly. Generated by Remove Middle Man. */\n"
                    + "public " + delegateType + " " + accessor + "() {\n    return "
                    + delegateField + ";\n}",
                ASTNode.METHOD_DECLARATION), null);
        }

        Map<ICompilationUnit, List<Call>> byUnit = new LinkedHashMap<>();
        for (Call call : calls) {
            byUnit.computeIfAbsent(call.unit, u -> new ArrayList<>()).add(call);
        }
        for (Map.Entry<ICompilationUnit, List<Call>> entry : byUnit.entrySet()) {
            ICompilationUnit callerCu = entry.getKey();
            boolean sameFile = callerCu.equals(unit);
            CompilationUnit callerAst = sameFile ? ast : parse(callerCu);
            ASTRewrite rewrite = sameFile ? ownerRewrite : ASTRewrite.create(callerAst.getAST());
            String callerSource = callerCu.getSource();
            for (Call call : entry.getValue()) {
                MethodInvocation node = sameFile ? call.node : reboundIn(callerAst, call.node);
                if (node.getExpression() == null) {
                    // An unqualified call inside the middle man itself has no receiver to
                    // insert the accessor after; it is rewritten to reach the field directly.
                    rewrite.replace(node, rewrite.createStringPlaceholder(
                        delegateField + "." + call.delegateMethod() + "("
                            + argumentsOf(callerSource, node) + ")",
                        ASTNode.METHOD_INVOCATION), null);
                    continue;
                }
                rewrite.replace(node, rewrite.createStringPlaceholder(
                    textOf(callerSource, node.getExpression()) + "." + accessor + "()."
                        + call.delegateMethod() + "("
                        + argumentsOf(callerSource, node) + ")",
                    ASTNode.METHOD_INVOCATION), null);
            }
            if (!sameFile) {
                edits.put((IFile) callerCu.getResource(),
                    List.of(rewrite.rewriteAST(new Document(callerCu.getSource()),
                        FormatterOptions.forGeneratedCode(callerAst))));
            }
        }
        edits.put((IFile) unit.getResource(),
            List.of(ownerRewrite.rewriteAST(new Document(unit.getSource()),
                FormatterOptions.forGeneratedCode(ast))));

        String label = "remove middle man " + middleMan.getElementName() + " ("
            + forwarders.size() + " forwarder(s) removed, " + calls.size()
            + " call site(s) repointed through " + accessor + "()"
            + (accessorExists ? "" : ", which was generated") + ")";
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(ChangeEngine.fromFileEdits(label, edits), label),
            "remove_middle_man", arguments);
    }

    /** One call to a forwarder, and the delegate method it must become. */
    private record Call(ICompilationUnit unit, MethodInvocation node, String delegateMethod) {}

    /** The method a forwarder's single statement actually calls. */
    private static String delegateMethodName(MethodDeclaration forwarder) {
        Statement only = (Statement) forwarder.getBody().statements().get(0);
        MethodInvocation call = only instanceof ReturnStatement r
                && r.getExpression() instanceof MethodInvocation invocation ? invocation
            : ((ExpressionStatement) only).getExpression() instanceof MethodInvocation e
                ? e : null;
        return call == null ? forwarder.getName().getIdentifier()
            : call.getName().getIdentifier();
    }

    /**
     * The field this method forwards to, or null when it is not a forwarder. A forwarder's
     * whole body is ONE call on a field of this class, receiving exactly this method's own
     * parameters in order. Anything else — a transformed result, a second statement, a
     * reordered argument — is behaviour, and deleting behaviour is not this refactoring.
     */
    private static String forwardedField(MethodDeclaration method, Set<String> fields) {
        if (method.getBody() == null || method.getBody().statements().size() != 1) {
            return null;
        }
        Statement only = (Statement) method.getBody().statements().get(0);
        MethodInvocation call = only instanceof ReturnStatement r
                && r.getExpression() instanceof MethodInvocation invocation ? invocation
            : only instanceof ExpressionStatement e
                && e.getExpression() instanceof MethodInvocation invocation ? invocation
            : null;
        if (call == null) {
            return null;
        }
        // `giant.getHealth()` AND `this.giant.setHealth(h)` are the same receiver, and the
        // first version of this recognised only the first. The fork's own GiantController
        // writes its getters one way and its setters the other — so half its forwarders
        // were invisible and the class read as a partial middle man. Real code mixes the
        // two forms; a tool that treats them differently is wrong rather than careful.
        String receiverName = call.getExpression() instanceof SimpleName simple
            ? simple.getIdentifier()
            : call.getExpression() instanceof org.eclipse.jdt.core.dom.FieldAccess access
                && access.getExpression() instanceof org.eclipse.jdt.core.dom.ThisExpression
                    ? access.getName().getIdentifier() : null;
        if (receiverName == null || !fields.contains(receiverName)) {
            return null;
        }
        List<?> parameters = method.parameters();
        List<?> arguments = call.arguments();
        if (parameters.size() != arguments.size()) {
            return null;
        }
        for (int i = 0; i < parameters.size(); i++) {
            String parameter = ((org.eclipse.jdt.core.dom.SingleVariableDeclaration)
                parameters.get(i)).getName().getIdentifier();
            if (!(arguments.get(i) instanceof SimpleName argument)
                    || !parameter.equals(argument.getIdentifier())) {
                return null;
            }
        }
        return receiverName;
    }

    private static String delegateTypeOf(TypeDeclaration type, String fieldName) {
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof FieldDeclaration field) {
                for (Object fragment : field.fragments()) {
                    if (fieldName.equals(((VariableDeclarationFragment) fragment)
                            .getName().getIdentifier())) {
                        return field.getType().toString();
                    }
                }
            }
        }
        return "Object";
    }

    private static String argumentsOf(String source, MethodInvocation call) {
        StringBuilder out = new StringBuilder();
        List<?> arguments = call.arguments();
        for (int i = 0; i < arguments.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(textOf(source, (ASTNode) arguments.get(i)));
        }
        return out.toString();
    }

    private static String textOf(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength()).trim();
    }

    private static List<Call> callsTo(IJdtService service, IMethod target,
                                      String delegateMethod) throws Exception {
        Set<ICompilationUnit> units = new LinkedHashSet<>();
        for (org.eclipse.jdt.core.search.SearchMatch match
                : org.jawata.mcp.refactoring.CompleteReferences.of(service, target)) {
            if (match.getElement() instanceof IJavaElement element) {
                ICompilationUnit unit = (ICompilationUnit) element
                    .getAncestor(IJavaElement.COMPILATION_UNIT);
                if (unit != null) {
                    units.add(unit);
                }
            }
        }
        List<Call> calls = new ArrayList<>();
        for (ICompilationUnit unit : units) {
            CompilationUnit ast = parse(unit);
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodInvocation node) {
                    IMethodBinding binding = node.resolveMethodBinding();
                    if (binding != null && target.equals(binding.getJavaElement())) {
                        calls.add(new Call(unit, node, delegateMethod));
                    }
                    return true;
                }
            });
        }
        return calls;
    }

    private static MethodInvocation reboundIn(CompilationUnit ast, MethodInvocation original) {
        MethodInvocation[] found = { null };
        int start = original.getStartPosition();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                if (found[0] == null && node.getStartPosition() == start) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0] != null ? found[0] : original;
    }

    private static TypeDeclaration typeNamed(CompilationUnit ast, String name) {
        for (Object type : ast.types()) {
            if (type instanceof TypeDeclaration declaration
                    && name.equals(declaration.getName().getIdentifier())) {
                return declaration;
            }
        }
        return null;
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
