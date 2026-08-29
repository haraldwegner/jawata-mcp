package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.QualifiedName;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.ThisExpression;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Supplier;

/**
 * <b>Replace Conditional with Polymorphism</b> — the
 * {@code refactor_to_pattern(kind=replace_conditional_with_polymorphism)} delegate.
 *
 * <p>A switch whose arms differ by a type code becomes one virtual call: an
 * interface with a method per arm's behaviour, one implementation per arm, and a
 * dispatch table keyed by the discriminator. Sprint 28d's operation survey ranked
 * this SECOND of the missing operations and called it "the single highest-leverage
 * behavioral op".</p>
 *
 * <h2>Why this is not `refactor_to_state`, and not a laxer version of it</h2>
 *
 * <p>{@code refactor_to_state} requires a {@code SwitchStatement} that is the
 * method's ONLY statement, on a <b>private int FIELD</b>, with case labels that are
 * static-final int constants. Those four restrictions are what make it the State
 * pattern — behaviour parked behind a state field. This operation handles the
 * general case: an ENUM discriminator, the ARROW switch form, and a selector that is
 * a parameter or local rather than a field. Measured against the survey's own list,
 * four of the six patterns it unblocks were already covered; the genuine remainder
 * is strategy and composite, which need exactly this generality.</p>
 *
 * <h2>The arms may touch the context, and that is the hard part</h2>
 *
 * <p>The conservative bound would be to refuse any arm mentioning {@code this} — and
 * that is what the State tool does. It is the wrong bound here: measured on the
 * before-case, arms assign {@code this.failureCount} AND read a <b>bare</b>
 * {@code failureThreshold}, so a refusal on {@code this} would reject the very code
 * this operation exists for while a bare-reference blind spot let a subtler case
 * through.</p>
 *
 * <p>So the generated method takes the context as its first parameter, and every
 * reference to the context's own state — qualified or bare — is rewritten to reach
 * it. The rewrite is driven by BINDINGS rather than by text: a
 * {@code SimpleName} is rewritten only when it resolves to a field of the enclosing
 * type, so a local variable, a parameter or an unrelated identifier that happens to
 * share a name is left alone.</p>
 *
 * <h2>The method's own scope leaves with the body, so it travels as parameters</h2>
 *
 * <p>Fields are only half the story. An arm may also read a PARAMETER of the
 * enclosing method, or a local declared before the switch — and those are not
 * reachable from the context at all. Every such variable becomes a parameter on the
 * generated interface method, and the dispatch site passes it. The set is the union
 * across arms, because all implementations share one signature, and it is ordered by
 * declaration position so the generated code does not shuffle between runs. The
 * response reports it as {@code threadedParameters}.</p>
 *
 * <h2>What it refuses, and why each refusal is real</h2>
 *
 * <ul>
 *   <li><b>A non-enum discriminator.</b> The dispatch table is keyed by the
 *       discriminator, and only a closed set gives a total mapping. A String switch
 *       has no such guarantee and would need a default nobody asked for.</li>
 *   <li><b>Fall-through.</b> An arm that falls into the next has no single body, so
 *       there is nothing to make a method out of.</li>
 *   <li><b>Fewer than two non-default arms.</b> One arm is an if, and turning it
 *       into a hierarchy adds a type that decides nothing.</li>
 *   <li><b>An arm that ASSIGNS a method-scope variable.</b> It can travel as a
 *       parameter, but Java passes it by value, so the write would land on a copy
 *       and be lost — the refactoring would change behaviour while compiling.</li>
 *   <li><b>An arm using {@code this} for anything but reaching a context field</b> —
 *       a call, an argument, an inherited field. In the generated class {@code this}
 *       IS that class, so the reference would silently come to mean something
 *       else.</li>
 * </ul>
 */
public class ReplaceConditionalWithPolymorphismTool extends AbstractApplyingRefactoringTool {

    public ReplaceConditionalWithPolymorphismTool(
        Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "replace_conditional_with_polymorphism";
    }

    @Override
    public String getDescription() {
        return """
            Replace Conditional with Polymorphism — a switch on an ENUM becomes one
            virtual call: an interface, one implementation per arm, and a dispatch
            table keyed by the discriminator.

            Distinct from refactor_to_state, which requires a private int FIELD, the
            old labelled switch form, and the switch as the method's only statement.
            This handles the general shape: enum discriminator, arrow form, and a
            selector that is a parameter or local.

            Variables the arms read from the enclosing method — a parameter, a local
            declared before the switch — travel with the bodies as parameters on the
            generated method; the response reports them as threadedParameters.

            Needs: filePath + line/column on or inside the switch.
            Optional: interfaceName (default <Method>Behaviour).

            Refuses: a non-enum discriminator (the table needs a closed set), any
            fall-through (an arm with no single body is not a method), fewer than two
            non-default arms (one arm is an if), an arm that ASSIGNS a method-scope
            variable (Java passes by value, so the write would be lost), and an arm
            using `this` for anything but reaching a context field (in the generated
            class `this` is that class).
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the switch."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line on or inside the switch."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("interfaceName", Map.of("type", "string",
            "description", "Optional name for the generated behaviour interface. "
                + "Default: the enclosing method's name, capitalised, + 'Behaviour'."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required."));
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column",
                "Must be >= 0 (zero-based)."));
        }

        ICompilationUnit cu = service.getCompilationUnit(Path.of(filePath));
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }
        String source = cu.getSource();
        CompilationUnit ast = parse(cu);
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "Position " + line + ":" + column + " is not in " + filePath + "."));
        }
        SwitchStatement sw = enclosing(new NodeFinder(ast, offset, 0).getCoveringNode(),
            SwitchStatement.class);
        if (sw == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No switch statement at " + line + ":" + column + "."));
        }
        MethodDeclaration method = enclosing(sw, MethodDeclaration.class);
        TypeDeclaration context = enclosing(sw, TypeDeclaration.class);
        if (method == null || context == null) {
            return Preparation.fail(ToolResponse.invalidParameter("selection",
                "The switch must sit inside a method of a class."));
        }

        // THE DISCRIMINATOR must be an ENUM. The generated dispatch is a table keyed
        // by it, and only a closed set gives a total mapping — a String switch would
        // need a default nobody asked for, and silently inventing one is how a
        // refactoring changes behaviour while every test still passes.
        if (!(sw.getExpression() instanceof SimpleName selector)
            || !(selector.resolveBinding() instanceof IVariableBinding selBinding)
            || !selBinding.getType().isEnum()) {
            return Preparation.fail(ToolResponse.invalidParameter("switch",
                "The discriminator must be a simple name of ENUM type. A switch on a "
                    + "String or an int has no closed set of cases, so the generated "
                    + "dispatch table could not be total. No files were modified."));
        }
        String enumType = selBinding.getType().getName();

        List<Arm> arms = decompose(sw);
        if (arms == null) {
            return Preparation.fail(ToolResponse.invalidParameter("switch",
                "This switch FALLS THROUGH between arms. An arm that continues into the "
                    + "next has no single body, so there is nothing to make a method out "
                    + "of. No files were modified."));
        }
        long realArms = arms.stream().filter(a -> !a.isDefault).count();
        if (realArms < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("switch",
                "Fewer than two non-default arms (" + realArms + "). One arm is an `if`, "
                    + "and turning it into a hierarchy adds a type that decides nothing."));
        }

        String contextName = context.getName().getIdentifier();
        String methodName = method.getName().getIdentifier();
        String ifaceName = getStringParam(arguments, "interfaceName");
        if (ifaceName == null || ifaceName.isBlank()) {
            ifaceName = Character.toUpperCase(methodName.charAt(0))
                + methodName.substring(1) + "Behaviour";
        }

        // Each arm becomes a method body on its own implementation. Context state is
        // reached through a parameter rather than through `this`, so references are
        // rewritten — driven by BINDINGS, so an identifier that merely shares a name
        // with a field is left alone.
        String ctxParam = "ctx";
        List<Generated> generated = new ArrayList<>();
        Map<String, Free> freeByName = new LinkedHashMap<>();
        for (Arm arm : arms) {
            Rewritten rewritten = rewriteArm(arm, source, context, ctxParam, ast);
            if (rewritten.refusal() != null) {
                return Preparation.fail(ToolResponse.invalidParameter("switch",
                    "Arm '" + (arm.isDefault ? "default" : arm.label) + "': "
                        + rewritten.refusal()));
            }
            for (Free f : rewritten.free()) {
                freeByName.putIfAbsent(f.name(), f);
            }
            generated.add(new Generated(
                (arm.isDefault ? "Default" : pascal(arm.label)) + ifaceName,
                rewritten.body(), arm));
        }

        // THE ARMS' FREE VARIABLES BECOME PARAMETERS. An arm body moved out of the
        // method loses the method's scope with it, so anything it reads that is
        // neither a field of the context nor declared inside the arm — a parameter,
        // an enclosing local — has to travel with it or the generated class does not
        // compile. The set is the UNION across arms, because every implementation
        // shares the interface's one signature; ordered by declaration position so
        // the generated code is stable run to run.
        List<Free> free = new ArrayList<>(freeByName.values());
        free.sort(Comparator.comparingInt(Free::declOffset).thenComparing(Free::name));
        StringBuilder extraParams = new StringBuilder();
        StringBuilder extraArgs = new StringBuilder();
        for (Free f : free) {
            extraParams.append(", ").append(f.type()).append(" ").append(f.name());
            extraArgs.append(", ").append(f.name());
        }

        List<TextEdit> edits = new ArrayList<>();
        String indent = "    ";
        String member = indent + indent;
        String inner = member + indent;

        StringBuilder nested = new StringBuilder("\n");
        nested.append(member).append("/** Generated by replace_conditional_with_polymorphism. */\n");
        nested.append(member).append("interface ").append(ifaceName).append(" {\n")
            .append(inner).append("void apply(").append(contextName).append(" ")
            .append(ctxParam).append(extraParams).append(");\n")
            .append(member).append("}\n");
        for (Generated g : generated) {
            nested.append(member).append("static final class ").append(g.className)
                .append(" implements ").append(ifaceName).append(" {\n")
                .append(inner).append("@Override public void apply(").append(contextName)
                .append(" ").append(ctxParam).append(extraParams).append(") {\n")
                .append(reindent(g.body, inner + indent)).append("\n")
                .append(inner).append("}\n")
                .append(member).append("}\n");
        }

        // THE DISPATCH TABLE. A LinkedHashMap in a static initialiser rather than an
        // EnumMap literal, because the enum may be declared elsewhere and this keeps
        // the generated code dependent only on what is already imported.
        nested.append(member).append("private static final java.util.Map<").append(enumType)
            .append(", ").append(ifaceName).append("> ").append(tableName(ifaceName))
            .append(" = java.util.Map.of(\n");
        List<Generated> keyed = generated.stream().filter(g -> !g.arm.isDefault).toList();
        for (int i = 0; i < keyed.size(); i++) {
            Generated g = keyed.get(i);
            nested.append(inner).append(enumType).append(".").append(g.arm.label)
                .append(", new ").append(g.className).append("()")
                .append(i < keyed.size() - 1 ? ",\n" : "\n");
        }
        nested.append(member).append(");\n");

        int insertAt = context.getStartPosition() + context.getLength() - 1;
        edits.add(new InsertEdit(insertAt, nested.toString()));

        // THE DISPATCH SITE COLLAPSES TO ONE VIRTUAL CALL — which is the operation's
        // whole point. The default arm, when present, is what the table's absent keys
        // fall back to, so no behaviour is silently dropped.
        Generated fallback = generated.stream().filter(g -> g.arm.isDefault).findFirst()
            .orElse(null);
        String dispatch = tableName(ifaceName) + ".getOrDefault(" + selector.getIdentifier()
            + ", " + (fallback == null ? "null" : "new " + fallback.className + "()") + ")";
        String call = fallback == null
            ? "{ " + ifaceName + " b = " + dispatch + "; if (b != null) { b.apply(this"
                + extraArgs + "); } }"
            : dispatch + ".apply(this" + extraArgs + ");";
        edits.add(new ReplaceEdit(sw.getStartPosition(), sw.getLength(), call));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits(
            "replace conditional with polymorphism in " + contextName + "." + methodName,
            Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("contextClass", contextName);
        extras.put("interfaceName", ifaceName);
        extras.put("discriminator", enumType);
        extras.put("implementations", generated.stream().map(g -> g.className).toList());
        extras.put("armsMoved", generated.size());
        // Reported, not merely done: these are the enclosing method's variables the
        // arms read, threaded through the generated signature. A caller reading the
        // response can see what the moved bodies still depend on.
        extras.put("threadedParameters", free.stream()
            .map(f -> f.type() + " " + f.name()).toList());
        String summary = "replace conditional with polymorphism: " + contextName + "."
            + methodName + " -> " + ifaceName + " with " + generated.size()
            + " implementation(s)";
        return Preparation.of(change, summary, extras);
    }

    // ---------- decomposition ----------

    private record Arm(String label, boolean isDefault, List<Statement> body) {
    }

    private record Generated(String className, String body, Arm arm) {
    }

    /**
     * Arms by label, handling BOTH switch forms.
     *
     * <p>The arrow form marks each {@link SwitchCase} as a labeled rule and puts the
     * body in the statement that follows. The old labelled form runs statements until
     * the next label, terminated by a break. Rank 2 must read both, and that this is
     * possible at all was the question the stage's spike answered before any of this
     * was written.</p>
     *
     * @return the arms, or null when the switch falls through between them
     */
    private static List<Arm> decompose(SwitchStatement sw) {
        List<Arm> arms = new ArrayList<>();
        String label = null;
        boolean isDefault = false;
        boolean arrow = false;
        List<Statement> body = new ArrayList<>();
        boolean open = false;
        for (Object o : sw.statements()) {
            if (o instanceof SwitchCase sc) {
                if (open) {
                    // In the OLD form a preceding arm must have been terminated. An
                    // arm with statements and no terminator falls through, and a
                    // fall-through arm has no single body to become a method.
                    if (!arrow && !body.isEmpty() && !terminated(body)) {
                        return null;
                    }
                    arms.add(new Arm(label, isDefault, body));
                }
                open = true;
                arrow = sc.isSwitchLabeledRule();
                body = new ArrayList<>();
                isDefault = sc.isDefault();
                label = isDefault ? null : String.valueOf(sc.expressions().get(0));
            } else if (o instanceof Statement st && open) {
                body.add(st);
            }
        }
        if (open) {
            arms.add(new Arm(label, isDefault, body));
        }
        return arms;
    }

    /** Does this arm end in a way that stops it running into the next? */
    private static boolean terminated(List<Statement> body) {
        Statement last = body.get(body.size() - 1);
        int type = last.getNodeType();
        return type == ASTNode.BREAK_STATEMENT
            || type == ASTNode.RETURN_STATEMENT
            || type == ASTNode.THROW_STATEMENT
            || type == ASTNode.CONTINUE_STATEMENT;
    }

    // ---------- the context rewrite ----------

    /**
     * What moving one arm's body out of the method produces: the rewritten source,
     * the variables it still needs from the method's scope, and — where the body
     * cannot be moved soundly — the reason, in place of a silent transformation.
     */
    private record Rewritten(String body, List<Free> free, String refusal) {
        static Rewritten refused(String why) {
            return new Rewritten(null, List.of(), why);
        }
    }

    /**
     * A variable an arm reads that the ENCLOSING METHOD owns, so it has to travel
     * with the body: a parameter, or a local declared before the switch.
     *
     * @param declOffset where it is declared, so the generated signature's parameter
     *     order is the source's own and therefore stable between runs
     */
    private record Free(String name, String type, int declOffset) {
    }

    /**
     * An arm's source with every reference to the context's own state redirected at
     * the parameter, any trailing {@code break} dropped, and the method-scope
     * variables it reads collected.
     *
     * <p>BINDING-DRIVEN, not textual. A {@code SimpleName} is rewritten only where it
     * resolves to a field of the enclosing type, so a local, a parameter or an
     * unrelated identifier sharing the name is untouched — the difference between a
     * refactoring and a search-and-replace.</p>
     *
     * <p><b>Two refusals live here rather than in a precondition</b>, because both
     * are properties of an individual arm's body that only this walk can see: an arm
     * that ASSIGNS a method-scope variable (the write would land on a copy and be
     * lost), and an arm that uses {@code this} for anything but reaching a context
     * field (in the generated class {@code this} is that class, so the reference
     * would silently change meaning).</p>
     */
    private static Rewritten rewriteArm(Arm arm, String source, TypeDeclaration context,
        String ctxParam, CompilationUnit ast) {
        List<Statement> body = new ArrayList<>(arm.body);
        if (!body.isEmpty() && body.get(body.size() - 1).getNodeType() == ASTNode.BREAK_STATEMENT) {
            body.remove(body.size() - 1);
        }
        if (body.isEmpty()) {
            return Rewritten.refused("its body is empty. An empty arm has no behaviour "
                + "to give a class.");
        }
        int from = body.get(0).getStartPosition();
        Statement last = body.get(body.size() - 1);
        int to = last.getStartPosition() + last.getLength();
        String contextName = context.getName().getIdentifier();

        // Collected by absolute offset, applied HIGH-TO-LOW so an earlier edit never
        // shifts a later one's position.
        TreeMap<Integer, int[]> qualified = new TreeMap<>();
        TreeMap<Integer, Integer> bare = new TreeMap<>();
        Map<String, Free> free = new LinkedHashMap<>();
        String[] refusal = new String[1];
        for (Statement st : body) {
            st.accept(new ASTVisitor() {
                @Override
                public boolean visit(ThisExpression node) {
                    if (node.getParent() instanceof FieldAccess fa
                        && fa.getExpression() == node
                        && fa.resolveFieldBinding() != null
                        && fa.resolveFieldBinding().getDeclaringClass() != null
                        && contextName.equals(
                            fa.resolveFieldBinding().getDeclaringClass().getName())) {
                        return true;
                    }
                    if (refusal[0] == null) {
                        refusal[0] = "it uses `this` for something other than reaching a "
                            + "field of " + contextName + " — a call, an argument, or an "
                            + "inherited field. Once the body is a class of its own, "
                            + "`this` IS that class, so the reference would silently "
                            + "change meaning. No files were modified.";
                    }
                    return true;
                }

                @Override
                public boolean visit(SimpleName node) {
                    if (!(node.resolveBinding() instanceof IVariableBinding b)) {
                        return true;
                    }
                    if (b.isField()) {
                        if (!contextName.equals(b.getDeclaringClass() == null ? null
                            : b.getDeclaringClass().getName())) {
                            return true;
                        }
                        ASTNode parent = node.getParent();
                        if (parent instanceof FieldAccess fa
                            && fa.getExpression() instanceof ThisExpression) {
                            // `this.x` -> `ctx.x`: replace the whole `this` expression.
                            qualified.put(fa.getExpression().getStartPosition(),
                                new int[] {fa.getExpression().getLength()});
                        } else if (!(parent instanceof FieldAccess)
                            && !(parent instanceof QualifiedName)) {
                            // a BARE field reference — the case a `this`-only rule misses
                            bare.put(node.getStartPosition(), node.getLength());
                        }
                        return true;
                    }
                    // NOT a field, so a parameter or a local. One declared INSIDE this
                    // arm travels with the body and needs nothing; anything else is
                    // owned by the method's scope, which the body is about to leave.
                    ASTNode decl = ast.findDeclaringNode(b);
                    if (decl != null && decl.getStartPosition() >= from
                        && decl.getStartPosition() < to) {
                        return true;
                    }
                    if (isWritten(node)) {
                        if (refusal[0] == null) {
                            refusal[0] = "it ASSIGNS `" + node.getIdentifier() + "`, which "
                                + "the enclosing method owns. Java passes a parameter by "
                                + "value, so the write would land on a copy and be lost — "
                                + "the refactoring would change behaviour. No files were "
                                + "modified.";
                        }
                        return true;
                    }
                    free.putIfAbsent(b.getName(), new Free(b.getName(),
                        b.getType() == null ? "Object" : b.getType().getName(),
                        decl == null ? Integer.MAX_VALUE : decl.getStartPosition()));
                    return true;
                }
            });
        }
        if (refusal[0] != null) {
            return Rewritten.refused(refusal[0]);
        }

        StringBuilder out = new StringBuilder(source.substring(from, to));
        TreeMap<Integer, String> replacements = new TreeMap<>();
        qualified.forEach((pos, len) -> replacements.put(pos, ctxParam));
        bare.forEach((pos, len) ->
            replacements.put(pos, ctxParam + "." + source.substring(pos, pos + len)));
        for (Map.Entry<Integer, String> e : replacements.descendingMap().entrySet()) {
            int pos = e.getKey() - from;
            int len = qualified.containsKey(e.getKey())
                ? qualified.get(e.getKey())[0] : bare.get(e.getKey());
            if (pos >= 0 && pos + len <= out.length()) {
                out.replace(pos, pos + len, e.getValue());
            }
        }
        return new Rewritten(out.toString(), List.copyOf(free.values()), null);
    }

    /**
     * Is this name being WRITTEN — the target of an assignment, or an operand of
     * {@code ++}/{@code --}? Reading a method-scope variable survives the move as a
     * parameter; writing one does not, and the two must not be confused.
     */
    private static boolean isWritten(SimpleName node) {
        ASTNode parent = node.getParent();
        if (parent instanceof Assignment assignment) {
            return assignment.getLeftHandSide() == node;
        }
        if (parent instanceof PrefixExpression prefix) {
            return prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                || prefix.getOperator() == PrefixExpression.Operator.DECREMENT;
        }
        return parent instanceof PostfixExpression;
    }

    // ---------- small helpers ----------

    private static String tableName(String ifaceName) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ifaceName.length(); i++) {
            char c = ifaceName.charAt(i);
            if (Character.isUpperCase(c) && i > 0) {
                sb.append('_');
            }
            sb.append(Character.toUpperCase(c));
        }
        return sb.toString();
    }

    private static String pascal(String label) {
        StringBuilder sb = new StringBuilder();
        for (String part : label.split("_")) {
            if (!part.isEmpty()) {
                sb.append(Character.toUpperCase(part.charAt(0)))
                    .append(part.substring(1).toLowerCase());
            }
        }
        return sb.toString();
    }

    private static String reindent(String bodyText, String indent) {
        String[] lines = bodyText.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            sb.append(indent).append(lines[i].strip());
            if (i < lines.length - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private static <T> T enclosing(ASTNode node, Class<T> type) {
        ASTNode n = node;
        while (n != null && !type.isInstance(n)) {
            n = n.getParent();
        }
        return type.cast(n);
    }

    private static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
