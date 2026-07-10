package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Sprint 19 (Kerievsky) — <b>Form Template Method</b> (toward pattern). Two sibling
 * subclasses whose same-named method shares a skeleton and differs only in a few
 * steps: the common skeleton is pulled up into the (abstract) superclass as a
 * template method, and each differing step becomes an {@code abstract} method the
 * subclasses implement.
 *
 * <p>Conservative so the result compiles: the two methods must be no-arg, same
 * signature, same statement count; each aligned statement is either identical
 * (common) or a "varying step" of one of two shapes — a {@code T x = expr;}
 * declaration (same name+type, differing initializer → {@code abstract T x()}) or an
 * expression statement (→ {@code abstract void stepN()}); a varying step must not
 * reference method locals. The superclass must be {@code abstract} and in the same
 * file. Applies as one {@link ChangeEngine#fromFileEdits} change (one undo).</p>
 */
public class FormTemplateMethodTool extends AbstractApplyingRefactoringTool {

    public FormTemplateMethodTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "form_template_method";
    }

    @Override
    public String getDescription() {
        return "Form Template Method — pull the shared skeleton of two sibling methods into the abstract "
            + "superclass; the differing steps become abstract methods. Conservative. Delegate of "
            + "refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file (superclass + both subclasses)."),
            "line", Map.of("type", "integer", "description", "Zero-based line on one subclass's method."),
            "column", Map.of("type", "integer", "description", "Zero-based column on the method.")));
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
        CompilationUnit ast = PatternSupport.parse(cu);
        int offset = ast.getPosition(line + 1, column);
        MethodDeclaration mA = PatternSupport.enclosing(new NodeFinder(ast, offset, 0).getCoveringNode(),
            MethodDeclaration.class);
        if (mA == null || mA.getBody() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "Position is not inside a method body"));
        }
        if (!mA.parameters().isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("method",
                "form_template_method (v1.4) supports no-arg methods only."));
        }
        TypeDeclaration a = PatternSupport.enclosing(mA, TypeDeclaration.class);
        if (a == null || a.getSuperclassType() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("method", "The method's class must extend a superclass"));
        }
        ITypeBinding superBinding = a.getSuperclassType().resolveBinding();
        TypeDeclaration superType = findTypeInCu(ast, superBinding);
        if (superType == null) {
            return Preparation.fail(ToolResponse.invalidParameter("superclass",
                "form_template_method (v1.4) requires the superclass to be in the same file."));
        }
        if (!Modifier.isAbstract(superType.getModifiers())) {
            return Preparation.fail(ToolResponse.invalidParameter("superclass",
                "The superclass must be abstract to host the template + abstract steps."));
        }

        String methodName = mA.getName().getIdentifier();
        String returnType = mA.getReturnType2() != null ? mA.getReturnType2().toString() : "void";

        // Find the single sibling subclass with a matching no-arg method.
        List<TypeDeclaration> siblings = new ArrayList<>();
        List<MethodDeclaration> siblingMethods = new ArrayList<>();
        for (Object o : ast.types()) {
            if (!(o instanceof TypeDeclaration t) || t == a) {
                continue;
            }
            if (t.getSuperclassType() == null
                || !equalBinding(t.getSuperclassType().resolveBinding(), superBinding)) {
                continue;
            }
            MethodDeclaration m = noArgMethod(t, methodName, returnType);
            if (m != null) {
                siblings.add(t);
                siblingMethods.add(m);
            }
        }
        if (siblings.size() != 1) {
            return Preparation.fail(ToolResponse.invalidParameter("siblings",
                "form_template_method (v1.4) requires exactly one sibling subclass with a matching '"
                    + methodName + "()' (found " + siblings.size() + ")."));
        }
        TypeDeclaration b = siblings.get(0);
        MethodDeclaration mB = siblingMethods.get(0);

        List<?> aStmts = mA.getBody().statements();
        List<?> bStmts = mB.getBody().statements();
        if (aStmts.size() != bStmts.size() || aStmts.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("methods",
                "The two methods must have the same non-zero statement count."));
        }

        List<String> templateLines = new ArrayList<>();
        List<String> abstractDecls = new ArrayList<>();
        List<String[]> aSteps = new ArrayList<>(); // {name, ret, bodyLine}
        List<String[]> bSteps = new ArrayList<>();
        Set<String> priorLocals = new HashSet<>();
        int stepCounter = 0;
        for (int i = 0; i < aStmts.size(); i++) {
            Statement as = (Statement) aStmts.get(i);
            Statement bs = (Statement) bStmts.get(i);
            String aText = text(source, as).trim();
            String bText = text(source, bs).trim();
            if (aText.equals(bText)) {
                templateLines.add(aText);
                collectLocals(as, priorLocals);
                continue;
            }
            // varying step
            if (as instanceof VariableDeclarationStatement av && bs instanceof VariableDeclarationStatement bv
                && av.fragments().size() == 1 && bv.fragments().size() == 1
                && av.getType().toString().equals(bv.getType().toString())) {
                VariableDeclarationFragment af = (VariableDeclarationFragment) av.fragments().get(0);
                VariableDeclarationFragment bf = (VariableDeclarationFragment) bv.fragments().get(0);
                String varName = af.getName().getIdentifier();
                if (!varName.equals(bf.getName().getIdentifier())
                    || af.getInitializer() == null || bf.getInitializer() == null) {
                    return Preparation.fail(ToolResponse.invalidParameter("methods",
                        "Varying declaration steps must share the variable name and type."));
                }
                if (referencesAny(af.getInitializer(), priorLocals) || referencesAny(bf.getInitializer(), priorLocals)) {
                    return Preparation.fail(ToolResponse.invalidParameter("methods",
                        "A varying step references a method-local variable; cannot lift to a no-arg step."));
                }
                String stepType = av.getType().toString();
                templateLines.add(stepType + " " + varName + " = " + varName + "();");
                abstractDecls.add("abstract " + stepType + " " + varName + "();");
                aSteps.add(new String[]{varName, stepType, "return " + text(source, af.getInitializer()) + ";"});
                bSteps.add(new String[]{varName, stepType, "return " + text(source, bf.getInitializer()) + ";"});
                priorLocals.add(varName);
            } else if (as instanceof ExpressionStatement && bs instanceof ExpressionStatement) {
                String stepName = "step" + (++stepCounter);
                templateLines.add(stepName + "();");
                abstractDecls.add("abstract void " + stepName + "();");
                aSteps.add(new String[]{stepName, "void", aText});
                bSteps.add(new String[]{stepName, "void", bText});
            } else {
                return Preparation.fail(ToolResponse.invalidParameter("methods",
                    "Varying steps must be either matching variable declarations or expression statements."));
            }
        }
        if (aSteps.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("methods",
                "The two methods are identical; use pull_up (move_in_hierarchy) instead."));
        }

        String indent = "    ";
        String bodyIndent = indent + indent;
        // Template method + abstract step declarations for the superclass.
        StringBuilder up = new StringBuilder("\n");
        up.append(indent).append(returnType).append(" ").append(methodName).append("() {\n");
        for (String tl : templateLines) {
            up.append(bodyIndent).append(tl).append("\n");
        }
        up.append(indent).append("}\n");
        for (String decl : abstractDecls) {
            up.append(indent).append(decl).append("\n");
        }

        List<TextEdit> edits = new ArrayList<>();
        edits.add(new InsertEdit(superType.getStartPosition() + superType.getLength() - 1, up.toString()));
        edits.add(new ReplaceEdit(mA.getStartPosition(), mA.getLength(), stepImpls(aSteps, indent, bodyIndent)));
        edits.add(new ReplaceEdit(mB.getStartPosition(), mB.getLength(), stepImpls(bSteps, indent, bodyIndent)));

        IFile file = (IFile) cu.getResource();
        Change change = ChangeEngine.fromFileEdits("form template method " + methodName, Map.of(file, edits));

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("superclass", superType.getName().getIdentifier());
        extras.put("templateMethod", methodName);
        extras.put("subclasses", List.of(a.getName().getIdentifier(), b.getName().getIdentifier()));
        extras.put("abstractSteps", abstractDecls.size());
        String summary = "form template method: pulled " + methodName + "() into "
            + superType.getName().getIdentifier() + " with " + abstractDecls.size() + " abstract step(s)";
        return Preparation.of(change, summary, extras);
    }

    // ---- helpers ----

    private static String stepImpls(List<String[]> steps, String indent, String bodyIndent) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < steps.size(); i++) {
            String[] s = steps.get(i);
            if (i > 0) {
                sb.append("\n\n").append(indent);
            }
            sb.append(s[1]).append(" ").append(s[0]).append("() {\n")
                .append(bodyIndent).append(s[2]).append("\n")
                .append(indent).append("}");
        }
        return sb.toString();
    }

    private static String text(String source, org.eclipse.jdt.core.dom.ASTNode n) {
        return source.substring(n.getStartPosition(), n.getStartPosition() + n.getLength());
    }

    private static void collectLocals(Statement s, Set<String> out) {
        s.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                out.add(node.getName().getIdentifier());
                return true;
            }
        });
    }

    private static boolean referencesAny(org.eclipse.jdt.core.dom.ASTNode expr, Set<String> names) {
        if (names.isEmpty()) {
            return false;
        }
        boolean[] hit = {false};
        expr.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (names.contains(node.getIdentifier())) {
                    hit[0] = true;
                }
                return true;
            }
        });
        return hit[0];
    }

    private static MethodDeclaration noArgMethod(TypeDeclaration t, String name, String returnType) {
        for (MethodDeclaration m : t.getMethods()) {
            if (m.getName().getIdentifier().equals(name) && m.parameters().isEmpty() && m.getBody() != null
                && returnType.equals(m.getReturnType2() != null ? m.getReturnType2().toString() : "void")) {
                return m;
            }
        }
        return null;
    }

    private static TypeDeclaration findTypeInCu(CompilationUnit ast, ITypeBinding binding) {
        if (binding == null) {
            return null;
        }
        for (Object o : ast.types()) {
            if (o instanceof TypeDeclaration t && equalBinding(t.resolveBinding(), binding)) {
                return t;
            }
        }
        return null;
    }

    private static boolean equalBinding(ITypeBinding x, ITypeBinding y) {
        return x != null && y != null && x.isEqualTo(y);
    }
}
