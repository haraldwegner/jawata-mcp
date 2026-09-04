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
 * Fowler — <b>Move Statements to Callers</b> (row 26). A statement inside a function that
 * has stopped being every caller's business moves out to the call sites.
 *
 * <p>A delegate of {@link MoveTool} (kind {@code statements_to_callers}); not registered
 * standalone. The exact inverse of {@code move kind=statements_into_function}, row 25, and
 * the two share their preconditions for the same reason read backwards.</p>
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li><b>The statement must be the method's first or last.</b> One in the middle runs
 *       after some of the body and before the rest, and there is no position at a call site
 *       that reproduces that.</li>
 *   <li><b>It may mention only static bindings and literals.</b> A statement reading a
 *       parameter or a local cannot be evaluated at a call site — the name is not there.
 *       Fowler's own answer is to pass the value out, which is a signature change and a
 *       different decision.</li>
 *   <li><b>The method must not be overridden or override anything.</b> With dynamic
 *       dispatch, "the callers" of this body is not a set the compiler can hand over: a
 *       call site may reach a different implementation entirely.</li>
 *   <li><b>Every call must be a statement of its own.</b> A call inside an expression —
 *       {@code total += fee()} — has no statement position beside it to receive the moved
 *       line without changing evaluation order.</li>
 * </ul>
 *
 * <p>When the method has no callers the operation refuses rather than quietly deleting the
 * statement. Moving something to nobody is a deletion, and a deletion should be asked for.</p>
 */
public class MoveStatementsToCallersTool extends AbstractRefactoringTool {

    public MoveStatementsToCallersTool(Supplier<IJdtService> serviceSupplier,
                                       RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "move_statements_to_callers";
    }

    @Override
    public String getDescription() {
        return "Move Statements to Callers — move a function's first or last statement out "
            + "to every call site. Delegate of move.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file holding the statement to move out."));
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
        MethodDeclaration owner = enclosingMethod(statement);
        if (owner == null || owner.getBody() == null) {
            return ToolResponse.invalidParameter("position",
                "the statement is not inside a method body.");
        }
        List<?> body = owner.getBody().statements();
        int index = body.indexOf(statement);
        if (index < 0) {
            return ToolResponse.invalidParameter("position",
                "the statement is nested inside the method rather than being one of its own"
                    + " top-level statements, so it has no call-site position to move to.");
        }
        boolean first = index == 0;
        boolean last = index == body.size() - 1;
        if (!first && !last) {
            return ToolResponse.invalidParameter("position",
                "the statement is neither the method's first nor its last, so it runs after"
                    + " part of the body and before the rest. No position at a call site"
                    + " reproduces that.");
        }

        String free = freeName(statement);
        if (free != null) {
            return ToolResponse.invalidParameter("position",
                "the statement uses '" + free + "', which belongs to " + owner.getName()
                    + "'s own scope and does not exist at a call site. Passing the value out"
                    + " is the real answer, and it is change_method_signature's.");
        }

        IMethodBinding binding = owner.resolveBinding();
        if (binding == null || !(binding.getJavaElement() instanceof IMethod method)) {
            return ToolResponse.symbolNotFound(
                "could not resolve " + owner.getName() + " to a method element.");
        }
        String dispatch = dispatchIsOpen(service, method, binding);
        if (dispatch != null) {
            return ToolResponse.invalidParameter("position",
                owner.getName() + " " + dispatch + ", so a call site may reach a different"
                    + " body entirely and 'the callers of this statement' is not a set the"
                    + " compiler can hand over.");
        }

        List<Site> sites = callSites(service, method);
        if (sites.isEmpty()) {
            return ToolResponse.invalidParameter("position",
                owner.getName() + " has no callers, so moving the statement out would simply"
                    + " delete it. Deleting is a different request.");
        }
        for (Site site : sites) {
            if (site.callStatement == null) {
                return ToolResponse.invalidParameter("position",
                    "a call to " + owner.getName() + " in " + site.unit.getElementName()
                        + " is part of a larger expression, so there is no statement position"
                        + " beside it that would not change evaluation order.");
            }
        }

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        ASTRewrite ownerRewrite = ASTRewrite.create(ast.getAST());
        ownerRewrite.remove(statement, null);
        // QUALIFIED ON THE WAY OUT. A static field of the method's own class resolves bare
        // INSIDE that class and nowhere else, so `exits = exits + 1;` compiles here and not
        // at a call site in another file. The first version of this moved the text as
        // written; the compile gate added at C6 caught it on the very first parity run,
        // which is what a gate is for. Qualifying is the right answer rather than refusing
        // — the statement is unchanged in meaning and a reader at the call site now sees
        // which class's state it touches.
        String moved = qualifyOwnStatics(sourceOf(unit.getSource(), statement), statement);

        Map<ICompilationUnit, List<Site>> byUnit = new LinkedHashMap<>();
        for (Site site : sites) {
            byUnit.computeIfAbsent(site.unit, u -> new ArrayList<>()).add(site);
        }
        for (Map.Entry<ICompilationUnit, List<Site>> entry : byUnit.entrySet()) {
            ICompilationUnit callerCu = entry.getKey();
            boolean sameFile = callerCu.equals(unit);
            CompilationUnit callerAst = sameFile ? ast : parse(callerCu);
            ASTRewrite rewrite = sameFile ? ownerRewrite : ASTRewrite.create(callerAst.getAST());
            for (Site site : entry.getValue()) {
                Statement callStatement = sameFile
                    ? site.callStatement : reboundIn(callerAst, site.callStatement);
                ListRewrite block = rewrite.getListRewrite(callStatement.getParent(),
                    Block.STATEMENTS_PROPERTY);
                Statement arriving = (Statement) rewrite.createStringPlaceholder(
                    moved, ASTNode.EXPRESSION_STATEMENT);
                if (first) {
                    block.insertBefore(arriving, callStatement, null);
                } else {
                    block.insertAfter(arriving, callStatement, null);
                }
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

        String label = "move statement out of " + owner.getName() + " to " + sites.size()
            + " call site(s), " + (first ? "before" : "after") + " each call";
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(ChangeEngine.fromFileEdits(label, edits), label),
            "move_statements_to_callers", arguments);
    }

    /** One call site: its file, and the statement the call is, or null when it is not one. */
    private record Site(ICompilationUnit unit, Statement callStatement) {}

    private static List<Site> callSites(IJdtService service, IMethod target) throws Exception {
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
            ast.accept(new ASTVisitor() {
                @Override
                public boolean visit(MethodInvocation node) {
                    IMethodBinding binding = node.resolveMethodBinding();
                    if (binding == null || !target.equals(binding.getJavaElement())) {
                        return true;
                    }
                    sites.add(node.getParent() instanceof Statement callStatement
                            && callStatement.getParent() instanceof Block
                        ? new Site(unit, callStatement) : new Site(unit, null));
                    return true;
                }
            });
        }
        return sites;
    }

    /**
     * Why dispatch is not settled for this method — it is overridden somewhere, or it
     * overrides something — or null when every call reaches exactly this body.
     */
    private static String dispatchIsOpen(IJdtService service, IMethod method,
                                         IMethodBinding binding) throws Exception {
        IType declaring = method.getDeclaringType();
        if (declaring != null) {
            IType[] subtypes = service.getSearchService().getAllSubtypes(declaring);
            if (subtypes != null && subtypes.length > 0) {
                for (IType subtype : subtypes) {
                    for (IMethod candidate : subtype.getMethods()) {
                        if (candidate.getElementName().equals(method.getElementName())
                                && candidate.getNumberOfParameters()
                                    == method.getNumberOfParameters()) {
                            return "is overridden by " + subtype.getElementName();
                        }
                    }
                }
            }
        }
        for (var type = binding.getDeclaringClass() == null ? null
                : binding.getDeclaringClass().getSuperclass();
                type != null; type = type.getSuperclass()) {
            for (IMethodBinding candidate : type.getDeclaredMethods()) {
                if (!candidate.isConstructor() && binding.overrides(candidate)) {
                    return "overrides " + type.getName();
                }
            }
        }
        return null;
    }

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

    private static String freeName(Statement statement) {
        String[] offending = { null };
        statement.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (offending[0] != null) {
                    return false;
                }
                IBinding binding = node.resolveBinding();
                if (binding instanceof IVariableBinding variable
                        && !Modifier.isStatic(variable.getModifiers())) {
                    offending[0] = node.getIdentifier();
                }
                return true;
            }
        });
        return offending[0];
    }

    /**
     * The statement's text with every BARE reference to a static field of its own class
     * qualified by that class's name.
     *
     * <p>Inside the class, {@code exits} resolves. At a call site in another file it does
     * not, and the statement is being moved to call sites. Rewriting the text by name is
     * sound here because the names are resolved against the ORIGINAL tree first — only a
     * {@code SimpleName} whose binding is a static field, and which is not already the
     * qualified half of something, is touched.</p>
     */
    private static String qualifyOwnStatics(String source, Statement statement) {
        java.util.Set<String> bare = new LinkedHashSet<>();
        java.util.Map<String, String> owner = new java.util.LinkedHashMap<>();
        statement.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.getParent() instanceof org.eclipse.jdt.core.dom.QualifiedName qualified
                        && qualified.getName() == node) {
                    return true;
                }
                if (node.getParent() instanceof org.eclipse.jdt.core.dom.FieldAccess access
                        && access.getName() == node) {
                    return true;
                }
                IBinding binding = node.resolveBinding();
                if (binding instanceof IVariableBinding variable && variable.isField()
                        && Modifier.isStatic(variable.getModifiers())
                        && variable.getDeclaringClass() != null) {
                    bare.add(node.getIdentifier());
                    owner.put(node.getIdentifier(), variable.getDeclaringClass().getName());
                }
                return true;
            }
        });
        String out = source;
        for (String name : bare) {
            out = out.replaceAll("(?<![.\\w])" + java.util.regex.Pattern.quote(name) + "\\b",
                java.util.regex.Matcher.quoteReplacement(owner.get(name) + "." + name));
        }
        return out;
    }

    /**
     * The statement's OWN SOURCE TEXT. Not {@code toString()}, for the reason row 25's twin
     * of this method gives: JDT re-renders from structure and drops the author's spacing,
     * so a move built on it reformats the line it was asked only to relocate.
     */
    private static String sourceOf(String fileSource, Statement statement) {
        return fileSource.substring(statement.getStartPosition(),
            statement.getStartPosition() + statement.getLength()).trim();
    }

    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) {
                return method;
            }
        }
        return null;
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

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
