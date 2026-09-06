package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
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
 * Fowler — <b>Move Statements into Function</b> (row 25). A statement that appears beside
 * every call to some function belongs inside that function.
 *
 * <p>A delegate of {@link MoveTool} (kind {@code statements_into_function}); not registered
 * standalone. Its inverse is {@code move kind=statements_to_callers}, row 26.</p>
 *
 * <h2>"Every call" is checked, not assumed — that check IS the refactoring</h2>
 *
 * <p>Fowler's precondition is that the statements <em>always</em> run with the call. If that
 * is true, moving them in changes nothing; if it is true at three call sites out of four, it
 * silently adds behaviour at the fourth. Nothing about the code being looked at says which,
 * so this enumerates every call site of the target method and requires the same statement,
 * on the same side, at all of them. The refusal says how many had it and how many did not,
 * because that ratio is what tells a caller whether the idea was nearly right or wrong.</p>
 *
 * <p>Statements are compared by their source text with whitespace collapsed. That is a
 * coarser test than a semantic one and it is deliberately the conservative direction: two
 * statements that differ in text are treated as different even when they might be
 * equivalent, so the operation declines rather than merges something it did not understand.</p>
 *
 * <h2>What the moved statement may mention</h2>
 *
 * <p>Only static bindings and literals. A statement referring to a local or a parameter of
 * the CALLER cannot be evaluated inside the callee — the name is not in scope there, and the
 * value differs per call site anyway. Passing it in as a new parameter is a real answer, but
 * it is {@code change_method_signature}'s answer and a different decision, so this refuses
 * and names it.</p>
 */
public class MoveStatementsIntoFunctionTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    /** Reached as {@code move kind=statements_into_function}. */
    @Override
    public String kindName() {
        return "statements_into_function";
    }

    /** The bullet a client reads under {@code move} — moved here from the door (M5). */
    @Override
    public String kindSummary() {
        return """
            move a statement that sits beside a call INTO the function being
            called. Needs: filePath, line, column on the statement; the call
            is its neighbour, and which side it is on decides whether the
            statement lands at the top or the bottom of the callee.
            EVERY call site is checked for the same statement first: with
            three of four, moving it in would ADD behaviour at the fourth, so
            the call is refused and says how many matched. The statement may
            mention only static bindings and literals — a caller's local is
            not in scope inside the callee and differs per call anyway.""";
    }


    public MoveStatementsIntoFunctionTool(Supplier<IJdtService> serviceSupplier,
                                          RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "move_statements_into_function";
    }

    @Override
    public String getDescription() {
        return "Move Statements into Function — move a statement that appears beside EVERY "
            + "call to a function into that function. Delegate of move.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file holding the statement to move."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of that statement."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
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
            ICompilationUnit unit = service.getCompilationUnit(filePath);
            if (unit == null) {
                return ToolResponse.symbolNotFound("No compilation unit at " + filePathStr);
            }
            CompilationUnit ast = parse(unit);
            Statement statement = statementAt(ast, line, column);
            if (statement == null) {
                return ToolResponse.invalidParameter("position",
                    "No statement at " + filePathStr + ":" + line + ":" + column);
            }
            return move(service, unit, ast, statement, arguments);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse move(IJdtService service, ICompilationUnit unit, CompilationUnit ast,
                              Statement statement, JsonNode arguments) throws Exception {
        if (!(statement.getParent() instanceof Block block)) {
            return ToolResponse.invalidParameter("position",
                "the statement is not inside a block, so it has no neighbour to be beside."
                    + " An if or loop body written without braces has to gain them first.");
        }
        List<?> statements = block.statements();
        int index = statements.indexOf(statement);

        // WHICH SIDE. A statement before the call moves to the top of the callee, one after
        // it moves to the bottom; the neighbour that holds the call decides which, and when
        // both neighbours hold one there is no way to tell which call was meant.
        MethodInvocation before = index + 1 < statements.size()
            ? soleInvocation((Statement) statements.get(index + 1)) : null;
        MethodInvocation after = index > 0
            ? soleInvocation((Statement) statements.get(index - 1)) : null;
        if (before != null && after != null) {
            return ToolResponse.invalidParameter("position",
                "the statement sits between two calls, so which function it belongs to is"
                    + " not readable from the code. Move it next to one of them first.");
        }
        MethodInvocation call = before != null ? before : after;
        boolean toTop = before != null;
        if (call == null) {
            return ToolResponse.invalidParameter("position",
                "neither neighbour of this statement is a single call to a method, so there"
                    + " is no function for it to move into.");
        }

        IMethodBinding binding = call.resolveMethodBinding();
        if (binding == null || binding.getJavaElement() == null) {
            return ToolResponse.invalidParameter("position",
                "could not resolve " + call + " to a method this workspace declares.");
        }
        if (!(binding.getJavaElement() instanceof IMethod target)
                || target.getCompilationUnit() == null) {
            return ToolResponse.invalidParameter("position",
                binding.getName() + " has no source here, so nothing can be moved into it.");
        }

        String free = freeCallerName(statement, call);
        if (free != null) {
            return ToolResponse.invalidParameter("position",
                "the statement uses '" + free + "', which is local to the caller and is not"
                    + " in scope inside " + binding.getName() + " — and its value differs per"
                    + " call site anyway. Passing it in is a real answer, but it is"
                    + " change_method_signature kind=change_signature's, and a different"
                    + " decision.");
        }

        // RENAMED ON THE WAY IN — the caller's argument name becomes the callee's
        // parameter name, which is the mirror of the qualification row 26 does on the
        // way out. Both directions rewrite what the name MEANS on the other side.
        String movedSource = renameArgumentsToParameters(
            unit.getSource(), statement, call, binding);
        String wanted = normalize(movedSource);
        List<Site> sites = callSites(service, target, wanted, toTop);
        long matching = sites.stream().filter(s -> s.matches).count();
        if (matching != sites.size() || sites.isEmpty()) {
            return ToolResponse.invalidParameter("position",
                sites.isEmpty()
                    ? "no call to " + binding.getName() + " was found, so there is nothing to"
                        + " move the statement out of."
                    : matching + " of " + sites.size() + " calls to " + binding.getName()
                        + " have this statement beside them. Moving it in would ADD it at the"
                        + " other " + (sites.size() - matching) + ", which is a behaviour"
                        + " change, not a move. Fowler's precondition is that the statements"
                        + " always run with the call.");
        }

        // The callee gains the statement once; every call site loses its copy.
        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        ICompilationUnit targetCu = target.getCompilationUnit();
        CompilationUnit targetAst = parse(targetCu);
        MethodDeclaration declaration = declarationOf(targetAst, target.getElementName());
        if (declaration == null || declaration.getBody() == null) {
            return ToolResponse.symbolNotFound(
                "could not locate a body for " + target.getElementName());
        }
        ASTRewrite targetRewrite = ASTRewrite.create(targetAst.getAST());
        ListRewrite body = targetRewrite.getListRewrite(declaration.getBody(),
            Block.STATEMENTS_PROPERTY);
        Statement arriving = (Statement) targetRewrite.createStringPlaceholder(
            movedSource, ASTNode.EXPRESSION_STATEMENT);
        if (toTop) {
            body.insertFirst(arriving, null);
        } else {
            body.insertLast(arriving, null);
        }

        Map<ICompilationUnit, List<Statement>> removals = new LinkedHashMap<>();
        for (Site site : sites) {
            removals.computeIfAbsent(site.unit, u -> new ArrayList<>()).add(site.statement);
        }
        for (Map.Entry<ICompilationUnit, List<Statement>> entry : removals.entrySet()) {
            ICompilationUnit callerCu = entry.getKey();
            if (callerCu.equals(targetCu)) {
                // Same file as the callee: one rewrite must carry both halves, or the two
                // documents disagree about what the file contained.
                for (Statement remove : entry.getValue()) {
                    targetRewrite.remove(reboundIn(targetAst, remove), null);
                }
                continue;
            }
            CompilationUnit callerAst = parse(callerCu);
            ASTRewrite callerRewrite = ASTRewrite.create(callerAst.getAST());
            for (Statement remove : entry.getValue()) {
                callerRewrite.remove(reboundIn(callerAst, remove), null);
            }
            edits.put((IFile) callerCu.getResource(),
                List.of(callerRewrite.rewriteAST(new Document(callerCu.getSource()),
                    FormatterOptions.forGeneratedCode(callerAst))));
        }
        edits.put((IFile) targetCu.getResource(),
            List.of(targetRewrite.rewriteAST(new Document(targetCu.getSource()),
                FormatterOptions.forGeneratedCode(targetAst))));

        String label = "move statement into " + target.getElementName() + " ("
            + (toTop ? "at its top" : "at its end") + ", removed from " + sites.size()
            + " call site(s))";
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(ChangeEngine.fromFileEdits(label, edits), label),
            "move_statements_into_function", arguments);
    }

    /** One call site: the statement beside the call, and whether it is the one we want. */
    private record Site(ICompilationUnit unit, Statement statement, boolean matches) {}

    /**
     * Every call to the target, paired with the neighbouring statement on the side being
     * moved from. A call with no such neighbour yields a non-matching site rather than being
     * skipped — an absent statement is exactly the case that must block the move.
     */
    private static List<Site> callSites(IJdtService service, IMethod target, String wanted,
                                        boolean toTop) throws Exception {
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
        List<Site> sites = new ArrayList<>();
        for (ICompilationUnit unit : units) {
            CompilationUnit ast = parse(unit);
            String source = unit.getSource();
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodInvocation node) {
                    IMethodBinding binding = node.resolveMethodBinding();
                    if (binding == null || !target.equals(binding.getJavaElement())) {
                        return true;
                    }
                    ASTNode holder = node.getParent();
                    if (!(holder instanceof Statement callStatement)
                            || !(callStatement.getParent() instanceof Block block)) {
                        sites.add(new Site(unit, null, false));
                        return true;
                    }
                    List<?> statements = block.statements();
                    int at = statements.indexOf(callStatement);
                    int neighbour = toTop ? at - 1 : at + 1;
                    if (neighbour < 0 || neighbour >= statements.size()) {
                        sites.add(new Site(unit, null, false));
                        return true;
                    }
                    Statement candidate = (Statement) statements.get(neighbour);
                    sites.add(new Site(unit, candidate,
                        wanted.equals(normalize(sourceOf(source, candidate)))));
                    return true;
                }
            });
        }
        return sites;
    }

    /**
     * The same statement, located in a freshly parsed AST of the same file. Rewrites are
     * bound to the tree they were built from, and the call-site scan parses its own.
     */
    private static Statement reboundIn(CompilationUnit ast, Statement original) {
        Statement[] found = { null };
        int start = original.getStartPosition();
        ast.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (found[0] == null && node instanceof Statement statement
                        && statement.getStartPosition() == start
                        && statement.getLength() == original.getLength()) {
                    found[0] = statement;
                }
            }
        });
        return found[0] != null ? found[0] : original;
    }

    /** The invocation this statement consists of, or null when it is anything else. */
    private static MethodInvocation soleInvocation(Statement statement) {
        if (statement instanceof org.eclipse.jdt.core.dom.ExpressionStatement expression
                && expression.getExpression() instanceof MethodInvocation call) {
            return call;
        }
        return null;
    }

    /**
     * A name in the statement that belongs to the caller's own scope — a local or a
     * parameter — or null when everything it mentions is static and would resolve the same
     * way anywhere.
     */
    /**
     * A name the statement uses that the callee cannot see — or null when it can see all.
     *
     * <p>A name the call PASSES AS AN ARGUMENT is not such a name. Inside the callee it is
     * simply that parameter, under whatever name the callee gave it, and
     * {@link #renameArgumentsToParameters} does the substitution. This is the exact mirror
     * of the parameter exemption in {@code MoveStatementsToCallersTool}, and it has to be:
     * the two rows are inverses, so a statement one of them can move out is a statement the
     * other must be able to move back. Without it the pair was not an inverse pair at all,
     * which is what running them as a round trip on upstream's code showed.</p>
     *
     * <p>Anything else local to the caller is still refused: the callee has no name for it,
     * and its value would differ per call site besides.</p>
     */
    private static String freeCallerName(Statement statement, MethodInvocation call) {
        Set<String> passed = new LinkedHashSet<>();
        for (Object argument : call.arguments()) {
            if (argument instanceof SimpleName name) {
                passed.add(name.getIdentifier());
            }
        }
        String[] offending = { null };
        statement.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (offending[0] != null) {
                    return false;
                }
                IBinding binding = node.resolveBinding();
                if (binding instanceof IVariableBinding variable
                        && !Modifier.isStatic(variable.getModifiers())
                        && !passed.contains(node.getIdentifier())) {
                    offending[0] = node.getIdentifier();
                }
                return true;
            }
        });
        return offending[0];
    }

    /**
     * The moved text with each passed argument renamed to the parameter it becomes.
     *
     * <p>Upstream's caller and callee happen to use the same word for both, so this is a
     * no-op there — which is precisely why it is written rather than assumed. A caller
     * naming its local {@code msg} where the callee's parameter is {@code message} is the
     * ordinary case, and moving the text unchanged would produce a reference to a name the
     * callee does not have.</p>
     */
    private static String renameArgumentsToParameters(String fileSource, Statement statement,
                                                      MethodInvocation call,
                                                      IMethodBinding binding) {
        Map<String, String> replacements = new LinkedHashMap<>();
        List<?> arguments = call.arguments();
        String[] parameters = parameterNames(binding);
        for (int i = 0; i < arguments.size() && i < parameters.length; i++) {
            if (arguments.get(i) instanceof SimpleName name
                    && !name.getIdentifier().equals(parameters[i])) {
                replacements.put(name.getIdentifier(), parameters[i]);
            }
        }
        return org.jawata.mcp.refactoring.MovedStatement.withVariablesRenamed(
            fileSource, statement, replacements);
    }

    /** The callee's parameter names, read off the declaration the binding points at. */
    private static String[] parameterNames(IMethodBinding binding) {
        if (binding.getJavaElement() instanceof IMethod method) {
            try {
                return method.getParameterNames();
            } catch (Exception e) {
                return new String[0];
            }
        }
        return new String[0];
    }

    /**
     * The statement's OWN SOURCE TEXT. Not {@code toString()}: JDT re-renders an AST node
     * from its structure, which drops the author's spacing — {@code a = a + 1;} comes back
     * as {@code a=a + 1;} — and a move that reformats what it moves is editing code it was
     * asked only to relocate.
     */
    private static String sourceOf(String fileSource, Statement statement) {
        return org.jawata.mcp.refactoring.MovedStatement.sourceOf(fileSource, statement);
    }

    /** Source text with runs of whitespace collapsed, so layout is not a difference. */
    private static String normalize(String source) {
        return source.trim().replaceAll("\\s+", " ");
    }

    private static Statement statementAt(CompilationUnit ast, int line, int column) {
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return null;
        }
        Statement[] found = { null };
        ast.accept(new ASTVisitor() {
            @Override
            public void preVisit(ASTNode node) {
                if (node instanceof Statement statement
                        && statement.getStartPosition() <= offset
                        && offset < statement.getStartPosition() + statement.getLength()
                        && !(statement instanceof Block)
                        && (found[0] == null || statement.getLength() < found[0].getLength())) {
                    found[0] = statement;
                }
            }
        });
        return found[0];
    }

    private static MethodDeclaration declarationOf(CompilationUnit ast, String name) {
        MethodDeclaration[] found = { null };
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (found[0] == null && name.equals(node.getName().getIdentifier())) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
