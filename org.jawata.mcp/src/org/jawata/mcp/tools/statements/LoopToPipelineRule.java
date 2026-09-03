package org.jawata.mcp.tools.statements;

import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ContinueStatement;
import org.eclipse.jdt.core.dom.EnhancedForStatement;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;

import java.util.ArrayList;
import java.util.List;

/**
 * Fowler — <b>Replace Loop with Pipeline</b> (row 50).
 *
 * <p>An accumulation loop says HOW it walks and leaves WHAT it computes to be worked out
 * from the walking. A pipeline says the what and lets the library do the how.</p>
 *
 * <pre>
 *   List&lt;String&gt; names = new ArrayList&lt;&gt;();      List&lt;String&gt; names = people.stream()
 *   for (Person p : people) {              --&gt;         .filter(p -&gt; p.isActive())
 *       if (p.isActive()) {                            .map(p -&gt; p.name())
 *           names.add(p.name());                       .collect(Collectors.toList());
 *       }
 *   }
 * </pre>
 *
 * <h2>The shape this rewrites, and the four it refuses</h2>
 *
 * <p>An enhanced-for whose body does exactly one thing: add to a list declared directly
 * above it and initialised empty, optionally inside a single {@code if}. That is the
 * shape {@code find_modernization(loop_to_stream)} has been reporting since Sprint 15 —
 * 667 candidates on this repository — and it is where the whole demand is.</p>
 *
 * <p>Refused, each because the pipeline would not mean the same thing:</p>
 *
 * <ul>
 *   <li>a body that does anything else as well. Two statements are two jobs, and Split
 *       Loop is the refactoring for that, not this one;</li>
 *   <li>{@code break}, {@code continue} or {@code return} anywhere in the body. A
 *       pipeline has no early exit, and the equivalent — {@code findFirst},
 *       {@code anyMatch} — is a different computation with a different result;</li>
 *   <li>a list that is not declared directly above, or not initialised to an empty
 *       collection. Adding to a list that already holds something is not what
 *       {@code collect} produces;</li>
 *   <li>a loop over anything but an {@code Iterable}. An array has no {@code stream()}
 *       method, and {@code Arrays.stream} needs an import this tool cannot add — a known
 *       limit of the import engine, recorded rather than worked around.</li>
 * </ul>
 *
 * <p>The collector is written fully qualified as
 * {@code java.util.stream.Collectors.toList()}. Ugly, and deliberate: the organize-imports
 * engine cannot ADD an import headlessly, so a short name would leave a file that does
 * not compile. And it is {@code collect(toList())} rather than {@code .toList()}, which
 * would return an UNMODIFIABLE list — the original is an ArrayList somebody may still be
 * adding to, and swapping in an immutable one is a behaviour change wearing a
 * modernisation's clothes.</p>
 */
public final class LoopToPipelineRule implements CleanupRule {

    @Override
    public String kind() {
        return "loop_to_pipeline";
    }

    @Override
    public String describe() {
        return "loop_to_pipeline    — Replace Loop with Pipeline: `List<T> out = new\n"
            + "                        ArrayList<>(); for (T x : xs) { if (p) out.add(f(x)); }`\n"
            + "                        becomes a stream with .filter/.map and\n"
            + "                        collect(Collectors.toList()), written fully qualified\n"
            + "                        because the import engine cannot add an import. Refuses a\n"
            + "                        body that does anything else, any break/continue/return, a\n"
            + "                        list not declared empty directly above, and arrays (no\n"
            + "                        stream() method). collect(toList()) and not .toList(): the\n"
            + "                        latter is unmodifiable and the original is not.";
    }

    @Override
    public TextEdit edit(CompilationUnit ast) throws Exception {
        List<Candidate> candidates = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(EnhancedForStatement node) {
                Candidate candidate = candidateFor(node);
                if (candidate != null) {
                    candidates.add(candidate);
                }
                return true;
            }
        });
        if (candidates.isEmpty()) {
            return null;
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        for (Candidate candidate : candidates) {
            rewrite(candidate, rewrite);
        }
        Document document =
            new Document(String.valueOf(ast.getTypeRoot().getBuffer().getContents()));
        return rewrite.rewriteAST(document,
            org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(ast));
    }

    /**
     * The loop, the empty list it fills, the optional filter, and what it adds.
     *
     * @param filter the {@code if} condition, or null when the loop adds every element
     */
    private record Candidate(EnhancedForStatement loop, VariableDeclarationFragment target,
                             Expression filter, Expression added) {
    }

    private static Candidate candidateFor(EnhancedForStatement loop) {
        if (!(loop.getParent() instanceof Block enclosing)) {
            return null;
        }
        // The collection being walked must have stream(). An array does not, and
        // Arrays.stream needs an import this tool cannot add.
        ITypeBinding walked = loop.getExpression().resolveTypeBinding();
        if (walked == null || walked.isArray() || !isIterable(walked)) {
            return null;
        }

        Statement body = unwrapBlock(loop.getBody());
        Expression filter = null;
        if (body instanceof IfStatement guard) {
            if (guard.getElseStatement() != null) {
                return null;   // an else is a second job
            }
            filter = guard.getExpression();
            body = unwrapBlock(guard.getThenStatement());
        }
        if (org.jawata.mcp.refactoring.Effects.containsJump(loop.getBody())) {
            return null;
        }

        // Exactly one thing, and that thing is `list.add(expr)`.
        if (!(body instanceof ExpressionStatement statement)
                || !(statement.getExpression() instanceof MethodInvocation add)
                || !"add".equals(add.getName().getIdentifier())
                || add.arguments().size() != 1
                || !(add.getExpression() instanceof SimpleName listName)) {
            return null;
        }
        if (!(listName.resolveBinding() instanceof IVariableBinding list) || list.isField()) {
            return null;
        }

        VariableDeclarationFragment target = emptyListAbove(enclosing, loop, list);
        return target == null ? null
            : new Candidate(loop, target, filter, (Expression) add.arguments().get(0));
    }

    /** {@code List<T> out = new ArrayList<>();} as the statement directly above. */
    private static VariableDeclarationFragment emptyListAbove(Block block,
            EnhancedForStatement loop, IVariableBinding list) {
        int index = block.statements().indexOf(loop);
        if (index <= 0
                || !(block.statements().get(index - 1)
                        instanceof VariableDeclarationStatement declaration)
                || declaration.fragments().size() != 1) {
            return null;
        }
        VariableDeclarationFragment fragment =
            (VariableDeclarationFragment) declaration.fragments().get(0);
        if (!(fragment.resolveBinding() instanceof IVariableBinding declared)
                || !declared.isEqualTo(list)) {
            return null;
        }
        // AND IT MUST BE A LIST. The declared type was never checked, so
        // `Collection<T> c = new HashSet<>(); for (..) c.add(x);` rewrote to
        // collect(toList()) — assigned to a Collection, so it compiled, while
        // de-duplication silently stopped happening. Nothing in the result would tell
        // anyone.
        ITypeBinding declaredType = declared.getType();
        String erased = declaredType == null || declaredType.getErasure() == null
            ? null : declaredType.getErasure().getQualifiedName();
        if (!"java.util.List".equals(erased) && !"java.util.ArrayList".equals(erased)) {
            return null;
        }
        // Initialised EMPTY. Adding to a list that already holds something is not what
        // collect produces, and this is the check that says so.
        return fragment.getInitializer() instanceof ClassInstanceCreation creation
            && creation.arguments().isEmpty() ? fragment : null;
    }

    private static boolean isIterable(ITypeBinding type) {
        if ("java.lang.Iterable".equals(type.getErasure().getQualifiedName())) {
            return true;
        }
        for (ITypeBinding face : type.getInterfaces()) {
            if (isIterable(face)) {
                return true;
            }
        }
        ITypeBinding parent = type.getSuperclass();
        return parent != null && isIterable(parent);
    }

    /** A pipeline has no early exit, so any jump means this is a different computation. */

    /** A block holding exactly one statement is that statement. */
    private static Statement unwrapBlock(Statement statement) {
        if (statement instanceof Block block && block.statements().size() == 1) {
            return (Statement) block.statements().get(0);
        }
        return statement;
    }

    /** The declaration's initializer becomes the whole pipeline; the loop goes. */
    private static void rewrite(Candidate candidate, ASTRewrite rewrite) {
        org.eclipse.jdt.core.dom.AST ast = candidate.loop().getAST();
        SingleVariableDeclaration element = candidate.loop().getParameter();

        MethodInvocation stream = ast.newMethodInvocation();
        stream.setName(ast.newSimpleName("stream"));
        stream.setExpression((Expression) rewrite.createCopyTarget(candidate.loop().getExpression()));

        Expression pipeline = stream;
        if (candidate.filter() != null) {
            pipeline = stage(ast, rewrite, pipeline, "filter", element, candidate.filter());
        }
        // A map stage only when the added expression is not the element itself — mapping
        // x to x is a step that says nothing.
        if (!isTheElement(candidate.added(), element)) {
            pipeline = stage(ast, rewrite, pipeline, "map", element, candidate.added());
        }

        MethodInvocation collect = ast.newMethodInvocation();
        collect.setName(ast.newSimpleName("collect"));
        collect.setExpression(pipeline);
        MethodInvocation toList = ast.newMethodInvocation();
        toList.setName(ast.newSimpleName("toList"));
        // FULLY QUALIFIED — the import engine cannot add an import headlessly, and a
        // short name here would leave a file that does not compile.
        toList.setExpression(ast.newName("java.util.stream.Collectors"));
        @SuppressWarnings("unchecked")
        List<Expression> collectArgs = collect.arguments();
        collectArgs.add(toList);

        rewrite.set(candidate.target(), VariableDeclarationFragment.INITIALIZER_PROPERTY,
            collect, null);
        rewrite.remove(candidate.loop(), null);
    }

    /** One {@code .filter(x -> ...)} or {@code .map(x -> ...)} stage. */
    private static MethodInvocation stage(org.eclipse.jdt.core.dom.AST ast, ASTRewrite rewrite,
            Expression receiver, String name, SingleVariableDeclaration element,
            Expression body) {
        org.eclipse.jdt.core.dom.LambdaExpression lambda = ast.newLambdaExpression();
        VariableDeclarationFragment parameter = ast.newVariableDeclarationFragment();
        parameter.setName(ast.newSimpleName(element.getName().getIdentifier()));
        @SuppressWarnings("unchecked")
        List<org.eclipse.jdt.core.dom.VariableDeclaration> params = lambda.parameters();
        params.add(parameter);
        lambda.setBody(rewrite.createCopyTarget(body));

        MethodInvocation invocation = ast.newMethodInvocation();
        invocation.setName(ast.newSimpleName(name));
        invocation.setExpression(receiver);
        @SuppressWarnings("unchecked")
        List<Expression> args = invocation.arguments();
        args.add(lambda);
        return invocation;
    }

    private static boolean isTheElement(Expression expression, SingleVariableDeclaration element) {
        return expression instanceof SimpleName name
            && name.getIdentifier().equals(element.getName().getIdentifier());
    }

}
