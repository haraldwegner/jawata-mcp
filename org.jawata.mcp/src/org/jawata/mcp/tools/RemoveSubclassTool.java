package org.jawata.mcp.tools;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.ltk.core.refactoring.CompositeChange;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.atoms.DeleteAtom;
import org.jawata.mcp.tools.shared.FormatterOptions;
import org.jawata.mcp.tools.shared.HierarchyFold;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Fowler — <b>Remove Subclass</b> (row 38). A subclass that no longer does enough to earn
 * its place is folded into its parent, and every reference to it becomes a reference to the
 * parent.
 *
 * <p>A delegate of {@link InlineTool} (kind {@code subclass}); not registered standalone.
 * Routed from {@code lazy_class}, the same smell that names {@link InlineClassTool}'s work —
 * the difference is whether the class earning nothing sits in a hierarchy or beside one.</p>
 *
 * <h2>The narrow case this performs, and why the rest is refused</h2>
 *
 * <p>Fowler's own treatment has two halves. Where a subclass carries a DISTINCTION — it
 * overrides something, or callers ask what type it is — removing it means replacing that
 * distinction with a field and a conditional, and WHICH field, with what values, is a design
 * decision no tool can make. This performs the other half: the subclass that carries no
 * distinction at all, where folding it away is a pure simplification.</p>
 *
 * <p>So it refuses, each refusal naming which and what Fowler's answer to that case is:</p>
 *
 * <ul>
 *   <li><b>It overrides a parent method.</b> The override IS the distinction; dispatch would
 *       change. Replace the override with a field-driven conditional on the parent first.</li>
 *   <li><b>Its type is observed</b> — an {@code instanceof} or a cast names it. Something
 *       depends on the distinction, so removing it silently changes what that code sees.</li>
 *   <li><b>Its constructor fixes an argument</b> rather than forwarding its own parameters
 *       through unchanged. {@code new Sub()} and {@code new Parent()} are then not the same
 *       call, and the honest replacement is a factory method on the parent.</li>
 *   <li><b>It has subtypes of its own.</b> They would be reparented, which is Collapse
 *       Hierarchy — a different operation with different preconditions.</li>
 *   <li><b>The parent is abstract</b>, so {@code new Parent(...)} does not compile.</li>
 *   <li><b>The parent is not in this workspace</b>, so its members cannot be added to.</li>
 *   <li><b>A member name collides</b> with one the parent declares.</li>
 * </ul>
 *
 * <p>What it then does is arithmetic: the subclass's own members move up, every mention of
 * the subclass type across the workspace becomes the parent type, and the file goes through
 * {@link DeleteAtom}, which answers no to every offer the engine makes to widen a deletion.
 * The parent's API grows by exactly the members that moved, and the response says so —
 * that widening is the price of the fold, and it should be visible rather than discovered.</p>
 */
public class RemoveSubclassTool extends AbstractRefactoringTool
        implements ToolKindDelegate {

    /** Reached as {@code inline kind=subclass}. */
    @Override
    public String kindName() {
        return "subclass";
    }

    /** The bullet a client reads under {@code inline} — moved here from the door (M5). */
    @Override
    public String kindSummary() {
        return """
            fold a subclass that carries NO DISTINCTION into its parent:
            its members move up, every reference to it becomes a reference
            to the parent, and it is deleted. Refuses when the subclass
            actually distinguishes something — it overrides a parent
            method, an instanceof or a cast names its type, or its
            constructor fixes an argument instead of forwarding — because
            replacing a distinction with a field is a design decision.
            Also refuses a subclass with subtypes (that is Collapse
            Hierarchy), an abstract parent, a parent outside this
            workspace, and a colliding member name.""";
    }

    /**
     * Structural: removing a class from a hierarchy and reparenting every reference is the
     * definition of the case. This door declared NOTHING structural until a C6 audit looked,
     * so the gate was silent here — on the one kind whose entire job is a hierarchy change.
     */
    @Override
    public boolean isStructural() {
        return true;
    }


    public RemoveSubclassTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "remove_subclass";
    }

    @Override
    public String getDescription() {
        return "Remove Subclass — fold a subclass that carries no distinction into its "
            + "parent and repoint every reference. Delegate of inline.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the subclass to remove."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of a caret in that subclass."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of that caret."));
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
            IType subclass = service.getTypeAtPosition(filePath, line, column);
            if (subclass == null || subclass.getCompilationUnit() == null) {
                return ToolResponse.symbolNotFound(
                    "No source type at " + filePathStr + ":" + line + ":" + column);
            }
            IType[] subtypes = service.getSearchService().getAllSubtypes(subclass);
            if (subtypes != null && subtypes.length > 0) {
                return ToolResponse.invalidParameter("position",
                    subclass.getElementName() + " has " + subtypes.length + " subtype(s) of its"
                        + " own, which folding it into its parent would reparent. That is"
                        + " Collapse Hierarchy, not Remove Subclass.")
                    // D3a: the sentence named the sibling by its FOWLER name, which a reader
                    // recognises and a caller cannot run. This is the same answer as an
                    // OPERATION, pointed at the class the caller named — and it is spelled
                    // `direction=` because that is what this door selects on (S8b step 6).
                    .withNextStep(new org.jawata.mcp.models.NextStep(
                        "hierarchy direction=collapse_hierarchy",
                        new org.jawata.mcp.models.CodeAddress(null, -1, -1,
                            subclass.getFullyQualifiedName('.')), null));
            }
            return remove(service, subclass, arguments);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse remove(IJdtService service, IType subclass, JsonNode arguments)
            throws Exception {
        ICompilationUnit subCu = subclass.getCompilationUnit();
        CompilationUnit subAst = parse(subCu);
        TypeDeclaration subType = typeNamed(subAst, subclass.getElementName());
        if (subType == null) {
            return ToolResponse.symbolNotFound(
                "could not locate the body of " + subclass.getElementName());
        }
        Type superclassType = subType.getSuperclassType();
        if (superclassType == null) {
            return ToolResponse.invalidParameter("position",
                subclass.getElementName() + " extends nothing, so there is no parent to fold"
                    + " it into. A class with no superclass is Inline Class's case"
                    + " (inline kind=class), not this one.");
        }
        ITypeBinding parentBinding = superclassType.resolveBinding();
        if (parentBinding == null || parentBinding.getJavaElement() == null) {
            return ToolResponse.invalidParameter("position",
                "could not resolve " + superclassType + " — the parent must be a type this"
                    + " workspace can read and change.");
        }
        if (org.eclipse.jdt.core.dom.Modifier.isAbstract(parentBinding.getModifiers())) {
            return ToolResponse.invalidParameter("position",
                parentBinding.getName() + " is abstract, so every `new "
                    + subclass.getElementName() + "(...)` this would rewrite to `new "
                    + parentBinding.getName() + "(...)` would stop compiling.");
        }
        IJavaElement parentElement = parentBinding.getJavaElement();
        ICompilationUnit parentCu = (ICompilationUnit) parentElement
            .getAncestor(IJavaElement.COMPILATION_UNIT);
        if (parentCu == null || !parentCu.exists()) {
            return ToolResponse.invalidParameter("position",
                parentBinding.getName() + " has no source in this workspace, so its members"
                    + " cannot be added to. Only a parent we can change can absorb a subclass.");
        }

        // THE DISTINCTION CHECKS. Each of these is a way the subclass is doing something,
        // and doing something is exactly what disqualifies it from being removed silently.
        for (Object member : subType.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && !method.isConstructor()) {
                IMethodBinding binding = method.resolveBinding();
                if (binding != null
                        && HierarchyFold.overridesSomething(binding, parentBinding)) {
                    return ToolResponse.invalidParameter("position",
                        subclass.getElementName() + "." + method.getName() + "() overrides "
                            + parentBinding.getName() + ", and that override IS the"
                            + " distinction the subclass carries — folding it in changes what"
                            + " gets dispatched. Replace the override with a field-driven"
                            + " conditional on " + parentBinding.getName() + " first.");
                }
            }
            if (member instanceof MethodDeclaration ctor && ctor.isConstructor()) {
                String forwarding = HierarchyFold.constructorForwardsUnchanged(ctor);
                if (forwarding != null) {
                    return ToolResponse.invalidParameter("position",
                        subclass.getElementName() + "'s constructor " + forwarding
                            + ", so `new " + subclass.getElementName() + "(...)` and `new "
                            + parentBinding.getName() + "(...)` are not the same call. The"
                            + " honest replacement is a factory method on "
                            + parentBinding.getName() + ".");
                }
            }
        }

        String observed = HierarchyFold.observedAnywhere(service, subclass);
        if (observed != null) {
            return ToolResponse.invalidParameter("position",
                "the type " + subclass.getElementName() + " is observed at " + observed
                    + ". Something depends on the distinction, so removing it changes what"
                    + " that code sees.");
        }

        CompilationUnit parentAst = parse(parentCu);
        AbstractTypeDeclaration parentType = typeNamed(parentAst, parentBinding.getName());
        if (parentType == null) {
            return ToolResponse.symbolNotFound(
                "could not locate the body of " + parentBinding.getName());
        }
        Set<String> existing = HierarchyFold.memberNames(parentType);
        List<BodyDeclaration> moving = new ArrayList<>();
        for (Object member : subType.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && method.isConstructor()) {
                continue;
            }
            BodyDeclaration declaration = (BodyDeclaration) member;
            for (String name : HierarchyFold.namesOf(declaration)) {
                if (existing.contains(name)) {
                    return ToolResponse.invalidParameter("position",
                        "'" + name + "' is declared by both " + subclass.getElementName()
                            + " and " + parentBinding.getName() + ". Two members of one name"
                            + " is a merge, and merging is a decision.");
                }
            }
            moving.add(declaration);
        }

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();

        ASTRewrite parentRewrite = ASTRewrite.create(parentAst.getAST());
        ListRewrite members = parentRewrite.getListRewrite(parentType,
            parentType.getBodyDeclarationsProperty());
        for (BodyDeclaration declaration : moving) {
            members.insertLast((BodyDeclaration) ASTNode.copySubtree(
                parentAst.getAST(), declaration), null);
        }
        // THE PARENT'S OWN JAVADOC may link the subclass about to be deleted, and so may
        // every file below. A dangling @link is invisible to the compile gate, which is
        // why it was fixed once in InlineClassTool and stayed broken here until the
        // population of DeleteAtom's callers was enumerated rather than assumed.
        org.jawata.mcp.refactoring.DeletedTypeLinks.unwrapIn(
            parentAst, subclass.getElementName(), parentRewrite);
        edits.put((IFile) parentCu.getResource(),
            List.of(parentRewrite.rewriteAST(new Document(parentCu.getSource()),
                FormatterOptions.forGeneratedCode(parentAst))));

        int repointed = 0;
        for (ICompilationUnit user
                : HierarchyFold.referencingUnits(service, subclass, parentCu)) {
            CompilationUnit ast = parse(user);
            ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
            int here = HierarchyFold.repointTypeReferences(ast, subclass.getElementName(),
                parentBinding.getName(), rewrite);
            if (here == 0) {
                continue;
            }
            repointed += here;
            org.jawata.mcp.refactoring.DeletedTypeLinks.unwrapIn(
                ast, subclass.getElementName(), rewrite);
            edits.put((IFile) user.getResource(),
                List.of(rewrite.rewriteAST(new Document(user.getSource()),
                    FormatterOptions.forGeneratedCode(ast))));
        }

        String label = "remove subclass " + subclass.getElementName() + " into "
            + parentBinding.getName() + " (" + moving.size() + " member(s) moved up, "
            + repointed + " reference(s) repointed)";
        CheckedChange deletion = DeleteAtom.delete(new IJavaElement[] { subCu },
            "delete " + subclass.getElementName(), new JdtRefactoringEngine());
        if (deletion.isRefused()) {
            return ToolResponse.error("REFACTORING_FAILED",
                "the engine refused to delete " + subclass.getElementName() + ": "
                    + deletion.messages(),
                "No files were modified.");
        }

        CompositeChange composite = new CompositeChange(label);
        composite.add(ChangeEngine.fromFileEdits(label, edits));
        composite.add(deletion.change());
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(composite, label), "remove_subclass", arguments);
    }

    /**
     * <b>RECORDED FOR C7, NOT FIXED HERE.</b> This walks the unit's TOP-LEVEL types and matches
     * a SIMPLE NAME — so it cannot see a nested subclass, and it re-derives an identity the
     * caller already holds from a key that is not unique. Both are defects this sprint closed
     * as classes elsewhere ({@code tools.shared.TypeLookup} for the first, the
     * handle-identifier key for the second). It is left standing because row 38 belongs to a
     * CLOSED stage and whether it can actually mis-resolve depends on what
     * {@code getTypeAtPosition} can hand it, which is not established — the same call the C4
     * record makes about {@code MoveStatementsIntoFunctionTool}: written down rather than
     * fixed blind.
     */
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
