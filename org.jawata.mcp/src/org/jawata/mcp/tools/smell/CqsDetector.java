package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ArrayAccess;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.LambdaExpression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ParenthesizedExpression;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SuperFieldAccess;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Sprint 28d — <b>Command Query Separation</b> (Meyer). A method should either
 * change observable state (a command, answering nothing) or answer a question
 * (a query, changing nothing) — never both. A method that does both cannot be
 * called to ask without also causing, which is what makes such code hard to
 * reason about and impossible to memoise, retry or reorder. Pointed
 * refactoring: <b>Separate Query from Modifier</b>. Threshold is unused.
 *
 * <h2>The decision rule</h2>
 * <p>A method is flagged when its return type is not {@code void} <em>and</em>
 * its body assigns to (or increments/decrements) at least one <em>field</em> —
 * the mutation signal JDT resolves exactly, via
 * {@link IVariableBinding#isField()}, with no purity analysis and no guessing.</p>
 *
 * <h2>What is deliberately NOT treated as mutation</h2>
 * <p>A call to a method that happens to mutate its receiver
 * ({@code list.add(x)}, {@code map.put(k, v)}) is <b>not</b> counted. Deciding
 * whether an arbitrary call mutates requires whole-program purity analysis;
 * approximating it by method name would flag every {@code add}/{@code set}
 * wrapper in the codebase. Precision is worth more here than recall: a CQS
 * finding argues for changing a signature, and a wrong one costs the reader
 * real time. Field writes are the half the compiler can prove.</p>
 *
 * <h2>The exclusions, and why each one is excluded</h2>
 * <ul>
 *   <li><b>Fluent / builder returns</b> — the method returns its own declaring
 *       type, or returns {@code this}. Answering {@code this} is not answering a
 *       question; it is the chaining protocol, and the value carries no
 *       information the caller did not already hold.</li>
 *   <li><b>Previous-value protocol</b> ({@code Map.put}, {@code getAndIncrement})
 *       — every return hands back a local initialised from a field this method
 *       goes on to write. The returned value is the state that was
 *       <em>displaced</em>; splitting the method cannot preserve it, because
 *       after the command the old value is gone. Separate Query from Modifier is
 *       not available, so the finding would name a cure that does not exist.</li>
 *   <li><b>Lazy initialisation / memoisation</b> — every write to the field is
 *       nested inside an {@code if} whose condition reads that same field. The
 *       write is a cache fill, not a state change the caller can observe: the
 *       answer is identical whether or not it happened.</li>
 *   <li><b>Deferred writes</b> — a write lexically inside a lambda, an anonymous
 *       class or a local type. That code runs when someone invokes it later, so
 *       the enclosing method returns without having mutated anything.</li>
 *   <li><b>Imposed supertype signatures</b> — the method overrides or implements
 *       a supertype method ({@code Iterator#next}, {@code Map#put},
 *       {@code Queue#poll}). The shape is not the author's to change, so the
 *       cure cannot be applied where the finding points.</li>
 * </ul>
 *
 * <p>Two of these (previous-value, lazy-init) are intentionally slightly
 * over-broad — the previous-value test does not verify that the read precedes
 * the write in execution order, only that both appear. Erring toward silence is
 * the deliberate direction: a missed CQS violation costs a reader nothing,
 * while a false one costs them the time to disprove it.</p>
 */
public final class CqsDetector extends AbstractAstDetector {

    public CqsDetector() {
        super("cqs",
            "Command Query Separation — a method that BOTH writes a field and returns a value, so a "
                + "caller cannot ask without also causing; points to Separate Query from Modifier. "
                + "Excludes fluent/`this` returns, the previous-value protocol (Map.put, "
                + "getAndIncrement), lazy initialisation, writes deferred into a lambda, and methods "
                + "whose signature is imposed by a supertype.",
            0);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                Finding finding = examine(ast, filePath, node);
                if (finding != null) {
                    out.add(finding);
                }
                return true;
            }
        });
    }

    /** The rule for one method; null when it is a command, a query, or an exclusion. */
    private static Finding examine(CompilationUnit ast, String filePath, MethodDeclaration node) {
        Block body = node.getBody();
        if (node.isConstructor() || body == null || !answersAValue(node)) {
            return null;
        }
        if (returnsItsOwnType(node) || overridesSupertypeMethod(node)) {
            return null;
        }

        List<Expression> writes = fieldWrites(node, body);
        if (writes.isEmpty()) {
            return null;
        }
        Set<String> mutated = new LinkedHashSet<>();
        for (Expression target : writes) {
            IVariableBinding field = fieldOf(target);
            if (field != null && !isLazyInit(node, target, field.getName())) {
                mutated.add(field.getName());
            }
        }
        if (mutated.isEmpty() || returnsThis(body) || returnsDisplacedValue(body, mutated)) {
            return null;
        }

        String name = node.getName().getIdentifier();
        return new Finding(
            "cqs", filePath, ast.getLineNumber(node.getName().getStartPosition()), -1, "warning",
            "Method '" + name + "' both writes " + describe(mutated) + " and returns a value — a "
                + "command and a query in one, so it cannot be asked without also causing. "
                + "Consider Separate Query from Modifier.",
            name);
    }

    private static String describe(Set<String> fields) {
        return fields.size() == 1
            ? "field '" + fields.iterator().next() + "'"
            : "fields " + fields;
    }

    /** True when the method declares a non-void return type. */
    private static boolean answersAValue(MethodDeclaration node) {
        Type returnType = node.getReturnType2();
        if (returnType == null) {
            return false;
        }
        return !(returnType instanceof PrimitiveType primitive)
            || primitive.getPrimitiveTypeCode() != PrimitiveType.VOID;
    }

    /** Fluent/builder exclusion by TYPE: the method answers its own declaring type. */
    private static boolean returnsItsOwnType(MethodDeclaration node) {
        IMethodBinding binding = node.resolveBinding();
        if (binding == null || binding.getDeclaringClass() == null
            || binding.getReturnType() == null) {
            return false;
        }
        ITypeBinding declaring = binding.getDeclaringClass().getErasure();
        ITypeBinding returned = binding.getReturnType().getErasure();
        return declaring != null && returned != null
            && declaring.getQualifiedName().equals(returned.getQualifiedName());
    }

    /** Fluent/builder exclusion by VALUE: some return statement hands back {@code this}. */
    private static boolean returnsThis(Block body) {
        boolean[] found = {false};
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                if (unwrap(node.getExpression()) instanceof ThisExpression) {
                    found[0] = true;
                }
                return true;
            }

            @Override
            public boolean visit(LambdaExpression node) {
                return false; // a lambda's returns are the lambda's, not this method's
            }

            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                return false;
            }
        });
        return found[0];
    }

    /**
     * Previous-value exclusion: every value-bearing return hands back a local
     * whose initialiser read one of the fields this method writes.
     */
    private static boolean returnsDisplacedValue(Block body, Set<String> mutated) {
        Set<String> displaced = new LinkedHashSet<>();
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                if (node.getInitializer() != null && readsAny(node.getInitializer(), mutated)) {
                    displaced.add(node.getName().getIdentifier());
                }
                return true;
            }
        });
        if (displaced.isEmpty()) {
            return false;
        }
        boolean[] allDisplaced = {true};
        boolean[] sawReturn = {false};
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                Expression value = unwrap(node.getExpression());
                if (value == null) {
                    return true;
                }
                sawReturn[0] = true;
                if (!(value instanceof SimpleName name)
                    || !displaced.contains(name.getIdentifier())) {
                    allDisplaced[0] = false;
                }
                return true;
            }

            @Override
            public boolean visit(LambdaExpression node) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                return false;
            }
        });
        return sawReturn[0] && allDisplaced[0];
    }

    /** True when {@code expression} mentions any of {@code fieldNames} as a field read. */
    private static boolean readsAny(Expression expression, Set<String> fieldNames) {
        boolean[] hit = {false};
        expression.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.resolveBinding() instanceof IVariableBinding variable
                    && variable.isField() && fieldNames.contains(variable.getName())) {
                    hit[0] = true;
                }
                return true;
            }
        });
        return hit[0];
    }

    /**
     * The assignment/increment TARGETS in this method's own body — writes nested
     * in a lambda, anonymous class or local type are excluded as deferred.
     */
    private static List<Expression> fieldWrites(MethodDeclaration owner, Block body) {
        List<Expression> targets = new ArrayList<>();
        body.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                targets.add(node.getLeftHandSide());
                return true;
            }

            @Override
            public boolean visit(PrefixExpression node) {
                if (node.getOperator() == PrefixExpression.Operator.INCREMENT
                    || node.getOperator() == PrefixExpression.Operator.DECREMENT) {
                    targets.add(node.getOperand());
                }
                return true;
            }

            @Override
            public boolean visit(PostfixExpression node) {
                targets.add(node.getOperand());
                return true;
            }

            @Override
            public boolean visit(LambdaExpression node) {
                return false;
            }

            @Override
            public boolean visit(AnonymousClassDeclaration node) {
                return false;
            }

            @Override
            public boolean visit(TypeDeclaration node) {
                return false; // a local class's methods are its own, not this one's
            }
        });
        targets.removeIf(t -> deferred(owner, t));
        return targets;
    }

    /** Belt-and-braces: is this write lexically inside deferred code within the method? */
    private static boolean deferred(MethodDeclaration owner, ASTNode write) {
        for (ASTNode n = write; n != null && n != owner; n = n.getParent()) {
            if (n instanceof LambdaExpression || n instanceof AnonymousClassDeclaration
                || n instanceof TypeDeclaration) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lazy-initialisation exclusion: this write sits inside an {@code if} whose
     * condition reads the very field being written (the {@code if (cache == null)
     * cache = …} guard).
     */
    private static boolean isLazyInit(MethodDeclaration owner, ASTNode write, String fieldName) {
        Set<String> self = Set.of(fieldName);
        for (ASTNode n = write; n != null && n != owner; n = n.getParent()) {
            if (n.getParent() instanceof IfStatement guard && guard.getExpression() != null
                && readsAny(guard.getExpression(), self)) {
                return true;
            }
        }
        return false;
    }

    /** Whether this method overrides or implements a supertype method. */
    private static boolean overridesSupertypeMethod(MethodDeclaration node) {
        for (Object modifier : node.modifiers()) {
            if (modifier instanceof org.eclipse.jdt.core.dom.MarkerAnnotation annotation
                && "Override".equals(annotation.getTypeName().getFullyQualifiedName())) {
                return true;
            }
        }
        IMethodBinding binding = node.resolveBinding();
        if (binding == null || binding.getDeclaringClass() == null) {
            return false;
        }
        Set<String> seen = new LinkedHashSet<>();
        for (ITypeBinding t = binding.getDeclaringClass().getSuperclass(); t != null;
             t = t.getSuperclass()) {
            if (overridesAnyIn(binding, t, seen)) {
                return true;
            }
        }
        return overridesAnyInterfaceOf(binding, binding.getDeclaringClass(), seen);
    }

    private static boolean overridesAnyInterfaceOf(IMethodBinding binding, ITypeBinding type,
                                                   Set<String> seen) {
        if (type == null) {
            return false;
        }
        for (ITypeBinding iface : type.getInterfaces()) {
            if (overridesAnyIn(binding, iface, seen)
                || overridesAnyInterfaceOf(binding, iface, seen)) {
                return true;
            }
        }
        return type.getSuperclass() != null
            && overridesAnyInterfaceOf(binding, type.getSuperclass(), seen);
    }

    private static boolean overridesAnyIn(IMethodBinding binding, ITypeBinding type,
                                          Set<String> seen) {
        if (type == null || !seen.add(type.getQualifiedName())) {
            return false;
        }
        for (IMethodBinding candidate : type.getDeclaredMethods()) {
            if (binding.overrides(candidate)) {
                return true;
            }
        }
        return false;
    }

    /** The field a write TARGET refers to, unwrapping parens and array indexing; null if not a field. */
    private static IVariableBinding fieldOf(Expression target) {
        Expression e = unwrap(target);
        while (e instanceof ArrayAccess access) {
            e = unwrap(access.getArray());
        }
        IBinding binding = switch (e) {
            case SimpleName name -> name.resolveBinding();
            case QualifiedName name -> name.resolveBinding();
            case FieldAccess access -> access.resolveFieldBinding();
            case SuperFieldAccess access -> access.resolveFieldBinding();
            case null, default -> null;
        };
        return binding instanceof IVariableBinding variable && variable.isField() ? variable : null;
    }

    private static Expression unwrap(Expression e) {
        Expression x = e;
        while (x instanceof ParenthesizedExpression parens) {
            x = parens.getExpression();
        }
        return x;
    }
}
