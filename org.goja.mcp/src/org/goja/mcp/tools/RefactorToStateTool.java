package org.goja.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.BreakStatement;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.SwitchCase;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.InsertEdit;
import org.eclipse.text.edits.ReplaceEdit;
import org.eclipse.text.edits.TextEdit;
import org.goja.core.IJdtService;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.refactoring.ChangeEngine;
import org.goja.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 19 (Kerievsky) — <b>Replace State-Altering Conditionals with State</b>
 * (toward pattern). A method that switches on an {@code int} type-code state field
 * becomes a delegation to a State object. Generated as <b>nested classes</b> of the
 * context — a {@code <Context>State} interface plus one inner class per case — so
 * the moved case bodies keep direct access to the context's members (no
 * back-reference plumbing, no visibility breakage).
 *
 * <p>Deliberately conservative so the result always compiles; it refuses anything
 * outside the safe envelope: the switch selector must be a private {@code int}
 * field used only as the selector, its initializer, and {@code field = LABEL}
 * transitions; every case label a {@code static final int} constant of the context;
 * each case body ends in {@code break}/{@code return} with no fall-through, no
 * in-body state assignment, and no {@code this}; the switch is its method's sole
 * statement. Applies as one {@link ChangeEngine#fromFileEdits} change (single file,
 * one undo). Find candidates via {@code find_quality_issue(kind=switch_statements)}.</p>
 */
public class RefactorToStateTool extends AbstractApplyingRefactoringTool {

    public RefactorToStateTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "refactor_to_state";
    }

    @Override
    public String getDescription() {
        return "Replace State-Altering Conditionals with State — a switch on an int state field becomes "
            + "delegation to nested State classes. Conservative (refuses unsafe shapes). Delegate of "
            + "refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file."),
            "line", Map.of("type", "integer", "description", "Zero-based line on/inside the switch statement."),
            "column", Map.of("type", "integer", "description", "Zero-based column on/inside the switch.")));
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("line/column", "Must be >= 0"));
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }
        String source = cu.getSource();
        CompilationUnit ast = parse(cu);
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "Invalid position"));
        }
        SwitchStatement sw = enclosing(new NodeFinder(ast, offset, 0).getCoveringNode(), SwitchStatement.class);
        if (sw == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "No switch statement at position"));
        }
        MethodDeclaration method = enclosing(sw, MethodDeclaration.class);
        TypeDeclaration context = enclosing(sw, TypeDeclaration.class);
        if (method == null || context == null) {
            return Preparation.fail(ToolResponse.invalidParameter("selection", "Switch must be inside a method of a class"));
        }
        // The switch must be the method's sole statement (clean delegation).
        if (method.getBody() == null || method.getBody().statements().size() != 1
            || method.getBody().statements().get(0) != sw) {
            return Preparation.fail(ToolResponse.invalidParameter("method",
                "refactor_to_state (v1.4) requires the switch to be the method's only statement."));
        }
        // Selector must be a private int field of the context.
        if (!(sw.getExpression() instanceof SimpleName selector)
            || !(selector.resolveBinding() instanceof IVariableBinding fieldBinding)
            || !fieldBinding.isField() || !Modifier.isPrivate(fieldBinding.getModifiers())
            || !"int".equals(fieldBinding.getType().getName())) {
            return Preparation.fail(ToolResponse.invalidParameter("switch",
                "The switch selector must be a private int state field."));
        }
        String fieldName = selector.getIdentifier();

        // Parse the cases.
        List<Case> cases = new ArrayList<>();
        String pending = null;
        boolean pendingDefault = false;
        List<Statement> body = new ArrayList<>();
        boolean started = false;
        for (Object o : sw.statements()) {
            if (o instanceof SwitchCase sc) {
                if (started) {
                    cases.add(new Case(pending, pendingDefault, body));
                }
                started = true;
                body = new ArrayList<>();
                if (sc.isDefault()) {
                    pending = null;
                    pendingDefault = true;
                } else {
                    List<?> exprs = sc.expressions();
                    if (exprs.size() != 1 || !(exprs.get(0) instanceof SimpleName labelName)
                        || !isTypeCodeConstant(labelName, context)) {
                        return Preparation.fail(ToolResponse.invalidParameter("switch",
                            "Each case label must be a single static-final int constant of the class."));
                    }
                    pending = labelName.getIdentifier();
                    pendingDefault = false;
                }
            } else if (o instanceof Statement s) {
                body.add(s);
            }
        }
        if (started) {
            cases.add(new Case(pending, pendingDefault, body));
        }
        if (cases.size() < 2) {
            return Preparation.fail(ToolResponse.invalidParameter("switch", "Need >= 2 cases."));
        }

        // Validate every f-usage is safe (selector / initializer / `f = LABEL`), collect transitions.
        List<Assignment> transitions = new ArrayList<>();
        String usageError = validateFieldUsage(context, fieldBinding, selector, transitions);
        if (usageError != null) {
            return Preparation.fail(ToolResponse.invalidParameter("field", usageError));
        }
        // Field declaration + its initial label.
        VariableDeclarationFragment fragment = findFieldFragment(context, fieldName);
        if (fragment == null || !(fragment.getInitializer() instanceof SimpleName initLabel)
            || !isTypeCodeConstant(initLabel, context)) {
            return Preparation.fail(ToolResponse.invalidParameter("field",
                "The state field must be initialized to one of the case-label constants."));
        }

        // Build case bodies (strip trailing break; refuse fall-through / this / f-assignment inside).
        String contextName = context.getName().getIdentifier();
        String stateType = contextName + "State";
        List<GeneratedState> states = new ArrayList<>();
        for (Case c : cases) {
            String bodyText = caseBodyText(c, source, fieldBinding);
            if (bodyText == null) {
                return Preparation.fail(ToolResponse.invalidParameter("switch",
                    "Each case must end with break/return, no fall-through, no `this`, no state assignment."));
            }
            String className = (c.isDefault ? "Default" : pascal(c.label)) + "State";
            states.add(new GeneratedState(className, bodyText, c.label, c.isDefault));
        }

        // Method signature for the state interface.
        String returnType = method.getReturnType2() != null ? method.getReturnType2().toString() : "void";
        String methodName = method.getName().getIdentifier();
        String paramDecls = paramDecls(method);
        String paramArgs = paramArgs(method);
        boolean isVoid = "void".equals(returnType);

        // ---- edits ----
        List<TextEdit> edits = new ArrayList<>();
        String indent = "    ";
        String memberIndent = indent + indent;
        String bodyIndent = memberIndent + indent;

        // 1. nested interface + inner state classes, inserted before the context's closing brace.
        StringBuilder nested = new StringBuilder("\n");
        nested.append(memberIndent).append("interface ").append(stateType).append(" {\n")
            .append(bodyIndent).append(returnType).append(" ").append(methodName).append("(").append(paramDecls).append(");\n")
            .append(memberIndent).append("}\n");
        for (GeneratedState st : states) {
            nested.append(memberIndent).append("final class ").append(st.className)
                .append(" implements ").append(stateType).append(" {\n")
                .append(bodyIndent).append("public ").append(returnType).append(" ").append(methodName)
                .append("(").append(paramDecls).append(") {\n")
                .append(reindent(st.bodyText, bodyIndent + indent))
                .append("\n").append(bodyIndent).append("}\n")
                .append(memberIndent).append("}\n");
        }
        int insertAt = context.getStartPosition() + context.getLength() - 1; // just before the class '}'
        edits.add(new InsertEdit(insertAt, nested.toString()));

        // 2. field declaration: `private int f = INIT;` -> `private <State> f = new <Init>State();`
        FieldDeclaration fieldDecl = enclosing(fragment, FieldDeclaration.class);
        String initClass = pascal(initLabel.getIdentifier()) + "State";
        String newField = "private " + stateType + " " + fieldName + " = new " + initClass + "();";
        edits.add(new ReplaceEdit(fieldDecl.getStartPosition(), fieldDecl.getLength(), newField));

        // 3. transitions: `f = LABEL` -> `f = new <Label>State()`
        for (Assignment a : transitions) {
            String label = ((SimpleName) a.getRightHandSide()).getIdentifier();
            String repl = fieldName + " = new " + pascal(label) + "State()";
            edits.add(new ReplaceEdit(a.getStartPosition(), a.getLength(), repl));
        }

        // 4. the switch -> delegation
        String delegate = (isVoid ? "" : "return ") + fieldName + "." + methodName + "(" + paramArgs + ");";
        edits.add(new ReplaceEdit(sw.getStartPosition(), sw.getLength(), delegate));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits("refactor to state " + contextName, Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("contextClass", contextName);
        extras.put("stateInterface", stateType);
        extras.put("states", states.stream().map(s -> s.className).toList());
        extras.put("transitionsRewritten", transitions.size());
        String summary = "refactor to state: " + contextName + " -> " + stateType + " with "
            + states.size() + " state classes";
        return Preparation.of(change, summary, extras);
    }

    // ---------- helpers ----------

    private record Case(String label, boolean isDefault, List<Statement> body) {
    }

    private record GeneratedState(String className, String bodyText, String label, boolean isDefault) {
    }

    /** Body source minus a trailing break; null if fall-through / `this` / assigns the state field. */
    private static String caseBodyText(Case c, String source, IVariableBinding fieldBinding) {
        List<Statement> stmts = new ArrayList<>(c.body);
        if (stmts.isEmpty()) {
            return null;
        }
        Statement last = stmts.get(stmts.size() - 1);
        boolean endsWithBreak = last instanceof BreakStatement;
        boolean endsWithReturn = last instanceof ReturnStatement;
        if (!endsWithBreak && !endsWithReturn) {
            return null; // fall-through or non-terminating
        }
        if (endsWithBreak) {
            stmts.remove(stmts.size() - 1);
            if (stmts.isEmpty()) {
                return "// (empty)";
            }
        }
        // reject `this` or assignment to the state field inside the body
        boolean[] unsafe = {false};
        for (Statement s : stmts) {
            s.accept(new ASTVisitor() {
                @Override
                public boolean visit(org.eclipse.jdt.core.dom.ThisExpression node) {
                    unsafe[0] = true;
                    return true;
                }

                @Override
                public boolean visit(Assignment node) {
                    if (node.getLeftHandSide() instanceof SimpleName n
                        && n.resolveBinding() instanceof IVariableBinding b && b.isEqualTo(fieldBinding)) {
                        unsafe[0] = true;
                    }
                    return true;
                }
            });
        }
        if (unsafe[0]) {
            return null;
        }
        int start = stmts.get(0).getStartPosition();
        Statement lastKept = stmts.get(stmts.size() - 1);
        int end = lastKept.getStartPosition() + lastKept.getLength();
        return source.substring(start, end);
    }

    /** Ensure the state field is used only as selector / initializer / `f = LABEL`; collect transitions. */
    private static String validateFieldUsage(TypeDeclaration context, IVariableBinding fieldBinding,
                                             SimpleName selector, List<Assignment> transitions) {
        String[] error = {null};
        context.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (error[0] != null) {
                    return false;
                }
                if (!(node.resolveBinding() instanceof IVariableBinding b) || !b.isEqualTo(fieldBinding)) {
                    return true;
                }
                if (node == selector) {
                    return true; // the switch selector
                }
                ASTNode parent = node.getParent();
                // field declaration name
                if (parent instanceof VariableDeclarationFragment vdf && vdf.getName() == node) {
                    return true;
                }
                // `f = LABEL` transition (LABEL a static-final int constant of the context)
                if (parent instanceof Assignment a && a.getLeftHandSide() == node
                    && a.getRightHandSide() instanceof SimpleName rhs && isTypeCodeConstant(rhs, context)) {
                    transitions.add(a);
                    return true;
                }
                error[0] = "state field '" + node.getIdentifier() + "' is used outside the safe set "
                    + "(selector / initializer / `field = LABEL`); refusing to change its type.";
                return false;
            }
        });
        return error[0];
    }

    private static boolean isTypeCodeConstant(SimpleName name, TypeDeclaration context) {
        if (!(name.resolveBinding() instanceof IVariableBinding b) || !b.isField()) {
            return false;
        }
        int mods = b.getModifiers();
        return Modifier.isStatic(mods) && Modifier.isFinal(mods)
            && "int".equals(b.getType().getName())
            && b.getDeclaringClass() != null
            && b.getDeclaringClass().isEqualTo(context.resolveBinding());
    }

    private static VariableDeclarationFragment findFieldFragment(TypeDeclaration context, String fieldName) {
        for (FieldDeclaration f : context.getFields()) {
            for (Object frag : f.fragments()) {
                if (frag instanceof VariableDeclarationFragment vdf
                    && fieldName.equals(vdf.getName().getIdentifier())) {
                    return vdf;
                }
            }
        }
        return null;
    }

    private static String paramDecls(MethodDeclaration m) {
        List<String> parts = new ArrayList<>();
        for (Object p : m.parameters()) {
            SingleVariableDeclaration v = (SingleVariableDeclaration) p;
            parts.add(v.getType().toString() + " " + v.getName().getIdentifier());
        }
        return String.join(", ", parts);
    }

    private static String paramArgs(MethodDeclaration m) {
        List<String> parts = new ArrayList<>();
        for (Object p : m.parameters()) {
            parts.add(((SingleVariableDeclaration) p).getName().getIdentifier());
        }
        return String.join(", ", parts);
    }

    /** Re-indent multi-line body text to the given indent (trimming each line first). */
    private static String reindent(String bodyText, String indent) {
        String[] lines = bodyText.split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) {
                sb.append("\n");
            }
            sb.append(indent).append(lines[i].trim());
        }
        return sb.toString();
    }

    private static String pascal(String label) {
        StringBuilder sb = new StringBuilder();
        for (String part : label.split("_")) {
            if (part.isEmpty()) {
                continue;
            }
            sb.append(Character.toUpperCase(part.charAt(0)))
                .append(part.substring(1).toLowerCase());
        }
        return sb.toString();
    }

    private static <T extends ASTNode> T enclosing(ASTNode node, Class<T> type) {
        while (node != null && !type.isInstance(node)) {
            node = node.getParent();
        }
        return type.cast(node);
    }

    private static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
