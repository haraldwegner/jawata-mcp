package org.jawata.mcp.tools.inheritance;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.CreateCompilationUnitChange;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.jawata.mcp.tools.shared.TypeLookup;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code hierarchy direction=replace_subclass_with_delegate} — Fowler row 56.
 *
 * <p>A subclass that exists to vary ONE thing has spent the single inheritance slot to do it.
 * The variation becomes an object the base class holds, so a second axis of variation is still
 * possible — which is the whole reason to prefer delegation here.</p>
 *
 * <h2>It is the COMPLEMENT of row 57, and each names the other</h2>
 *
 * <p>{@code replace_superclass_with_delegate} is for a class that inherits only to REUSE and
 * overrides nothing. This row is for the opposite: a subclass whose entire point is what it
 * OVERRIDES. A subclass that overrides nothing is refused here with a pointer to that row, and
 * the same courtesy runs the other way.</p>
 *
 * <h2>Conservative, on the same terms as this stage's other generating row</h2>
 *
 * <p>Row 59 and its Sprint-19 sibling both introduce the abstraction and report the mapping
 * without rewriting construction, because that cascades into every {@code new} in the program
 * and is the caller's decision. <b>This row makes the same choice</b>: it generates the
 * behaviour class carrying the subclass's own method bodies and reports what moved. It does NOT
 * delete the subclass, add the field, or introduce a factory — the three steps that need to
 * happen together and need a human to choose the seam.</p>
 *
 * <p><b>The generated file is not compile-verified, and that is measured rather than assumed.</b>
 * The gate parses created files but does not RESOLVE them (row 59's probe), so the bodies are
 * copied VERBATIM from the subclass rather than regenerated — text that already compiled once is
 * the safest thing to emit when nothing downstream will check it.</p>
 */
public class ReplaceSubclassWithDelegateTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a class. */
        public static final String NOT_A_TYPE = "NOT_A_TYPE";
        /** The class extends nothing but {@code Object}, so it is not a subclass at all. */
        public static final String NO_SUPERCLASS = "NO_SUPERCLASS";
        /** It overrides nothing, so there is no varying behaviour to move out. */
        public static final String NOTHING_OVERRIDDEN = "NOTHING_OVERRIDDEN";
        /** It declares state of its own, which would have to move with the behaviour. */
        public static final String DECLARES_ITS_OWN_STATE = "DECLARES_ITS_OWN_STATE";
        /** The file the behaviour class would occupy already exists. */
        public static final String DELEGATE_FILE_EXISTS = "DELEGATE_FILE_EXISTS";

        private Refusal() {
        }
    }

    public ReplaceSubclassWithDelegateTool(Supplier<IJdtService> serviceSupplier,
                                           RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "replace_subclass_with_delegate";
    }

    @Override
    public String getName() {
        return "replace_subclass_with_delegate";
    }

    @Override
    public String getDescription() {
        return """
            Replace Subclass with Delegate — a subclass that exists to vary ONE thing has spent
            the single inheritance slot on it; the variation becomes an object instead, so a
            second axis stays possible. Point at the SUBCLASS (typeName=pkg.Type, or a position);
            delegateName names the generated class, defaulting to <Subclass>Behaviour.
            CONSERVATIVE, on the same terms as replace_type_code_with_subclasses: it generates
            the behaviour class carrying the subclass's own method bodies VERBATIM and reports
            what moved. It does NOT delete the subclass, add the field, or introduce a factory —
            those three must happen together and need a human to choose the seam.
            REFUSES a subclass that overrides nothing (that is
            replace_superclass_with_delegate's case, and the refusal says so) and one that
            declares state of its own, which would have to travel with the behaviour.""";
    }

    /** Structural: it introduces a type that is meant to take a subclass's place. */
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
            "description", "Source file declaring the subclass."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the class declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("typeName", Map.of("type", "string",
            "description", "Fully-qualified class name, as an alternative to a position."));
        properties.put("delegateName", Map.of("type", "string",
            "description", "Name for the generated behaviour class. Default:"
                + " <Subclass>Behaviour."));
        schema.put("properties", properties);
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        java.util.Optional<ToolResponse> nameForm =
            FqnTarget.materializePosition(service, arguments);
        if (nameForm.isPresent()) {
            return Preparation.fail(nameForm.get());
        }
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath",
                "filePath is required (or name the class with typeName=pkg.Type)"));
        }
        org.eclipse.jdt.core.IJavaElement element = service.getElementAtPosition(
            java.nio.file.Path.of(filePath),
            getIntParam(arguments, "line", -1), getIntParam(arguments, "column", -1));
        IType type = element instanceof IType found ? found : element == null ? null
            : (IType) element.getAncestor(org.eclipse.jdt.core.IJavaElement.TYPE);
        if (type == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a class.", Refusal.NOT_A_TYPE));
        }

        ICompilationUnit unit = type.getCompilationUnit();
        CompilationUnit ast = parse(unit);
        AbstractTypeDeclaration declared = TypeLookup.declaration(ast, type);
        if (!(declared instanceof TypeDeclaration target) || target.isInterface()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the target is not a class.", Refusal.NOT_A_TYPE));
        }
        ITypeBinding subclass = target.resolveBinding();
        ITypeBinding superclass = subclass == null ? null : subclass.getSuperclass();
        if (target.getSuperclassType() == null || superclass == null
                || "java.lang.Object".equals(superclass.getQualifiedName())) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + type.getElementName() + "' is not a subclass of anything, so there is no"
                    + " subclass to replace.", Refusal.NO_SUPERCLASS));
        }

        // WHAT IT OVERRIDES IS WHAT MOVES. Anything else it declares stays where it is.
        List<MethodDeclaration> overrides = new ArrayList<>();
        for (MethodDeclaration method : target.getMethods()) {
            IMethodBinding bound = method.resolveBinding();
            if (bound == null || method.isConstructor()) {
                continue;
            }
            for (IMethodBinding inherited : superclass.getDeclaredMethods()) {
                if (bound.overrides(inherited)) {
                    overrides.add(method);
                    break;
                }
            }
        }
        if (overrides.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + type.getElementName() + "' overrides nothing of "
                    + superclass.getName() + "'s, so there is no varying behaviour to move into"
                    + " a delegate. A subclass that only REUSES its superclass is the other"
                    + " row's case: run"
                    + " hierarchy direction=replace_superclass_with_delegate instead.",
                Refusal.NOTHING_OVERRIDDEN));
        }

        // STATE WOULD HAVE TO TRAVEL WITH THE BEHAVIOUR, and deciding what the delegate should
        // be constructed with is the seam a human chooses. Refused rather than guessed.
        for (Object each : target.bodyDeclarations()) {
            if (each instanceof FieldDeclaration field
                    && !Modifier.isStatic(field.getModifiers())) {
                return Preparation.fail(ToolResponse.invalidParameter("position",
                    "'" + type.getElementName() + "' declares instance state, which would have"
                        + " to move with the behaviour — and what the delegate is constructed"
                        + " with is the seam this row will not choose for you.",
                    Refusal.DECLARES_ITS_OWN_STATE));
            }
        }

        String delegateName = getStringParam(arguments, "delegateName");
        if (delegateName == null || delegateName.isBlank()) {
            delegateName = type.getElementName() + "Behaviour";
        }
        IContainer parent = (IContainer) unit.getResource().getParent();
        IFile file = parent.getFile(new org.eclipse.core.runtime.Path(delegateName + ".java"));
        if (file.exists()) {
            return Preparation.fail(ToolResponse.invalidParameter("delegateName",
                "a file named " + delegateName + ".java already exists in this package.",
                Refusal.DELEGATE_FILE_EXISTS));
        }

        String source = unit.getSource();
        List<String> moved = new ArrayList<>();
        for (MethodDeclaration method : overrides) {
            moved.add(method.getName().getIdentifier());
        }
        String pkg = ast.getPackage() == null ? null
            : ast.getPackage().getName().getFullyQualifiedName();
        Change change = new CreateCompilationUnitChange(file,
            behaviourSource(pkg, delegateName, type.getElementName(), superclass.getName(),
                overrides, source));

        String label = "replace subclass: generated " + delegateName + " carrying "
            + overrides.size() + " overriding method(s) of " + type.getElementName();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("subclass", type.getElementName());
        extras.put("superclass", superclass.getName());
        extras.put("delegateName", delegateName);
        extras.put("methodsMoved", moved);
        extras.put("note", type.getElementName() + " still exists and still extends "
            + superclass.getName() + ". Adding the field to " + superclass.getName()
            + ", routing its methods through it, and deleting the subclass are three steps that"
            + " must happen together — and choosing where the seam goes is yours.");
        return Preparation.of(change, label, extras);
    }

    private String behaviourSource(String pkg, String name, String subclass, String superclass,
                                   List<MethodDeclaration> overrides, String source) {
        StringBuilder out = new StringBuilder();
        if (pkg != null) {
            out.append("package ").append(pkg).append(";\n\n");
        }
        out.append("/**\n")
            .append(" * The behaviour ").append(subclass).append(" added to ").append(superclass)
            .append(", as an object.\n *\n")
            .append(" * <p>Generated by Replace Subclass with Delegate. ").append(subclass)
            .append(" still exists and\n * still extends ").append(superclass)
            .append(": adding the field, routing through it and deleting the\n")
            .append(" * subclass are three steps that must happen together, and where the seam")
            .append(" goes is\n * your decision rather than this row's.</p>\n */\n");
        out.append("public class ").append(name).append(" {\n");
        for (MethodDeclaration method : overrides) {
            // VERBATIM, and the @Override goes: the delegate overrides nothing. Copying the
            // text rather than regenerating it means emitting something that already compiled,
            // which matters because nothing downstream resolves a created file.
            String text = source.substring(method.getStartPosition(),
                method.getStartPosition() + method.getLength());
            out.append('\n');
            for (String line : text.split("\n", -1)) {
                String trimmed = line.strip();
                if (trimmed.equals("@Override")) {
                    continue;
                }
                out.append(line.isBlank() ? "" : line).append('\n');
            }
        }
        out.append("}\n");
        return out.toString();
    }

    /** Parse with bindings. See {@link PullUpConstructorBodyTool}'s note on the sixteen copies. */
    private CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
