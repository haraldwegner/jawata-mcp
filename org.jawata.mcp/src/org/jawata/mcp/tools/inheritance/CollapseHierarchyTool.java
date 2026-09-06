package org.jawata.mcp.tools.inheritance;

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
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
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
import org.jawata.mcp.refactoring.DeletedTypeLinks;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.atoms.DeleteAtom;
import org.jawata.mcp.tools.AbstractApplyingRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FormatterOptions;
import org.jawata.mcp.tools.shared.FqnTarget;
import org.jawata.mcp.tools.shared.HierarchyFold;
import org.jawata.mcp.tools.shared.TypeLookup;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code hierarchy direction=collapse_hierarchy} — Fowler row 4, <b>Collapse Hierarchy</b>.
 *
 * <p>A class sitting between a parent and its own subtypes, adding little enough that the level
 * is not earning itself, is merged into the parent: its members move up, its subtypes are
 * reparented onto the parent, and it is deleted.</p>
 *
 * <h2>It is the case row 38 REFUSES, and the two refusals name each other</h2>
 *
 * <p>{@code inline kind=subclass} (Remove Subclass) performs the same arithmetic for a LEAF —
 * and refuses a subclass with subtypes of its own, saying in the refusal that reparenting them
 * is Collapse Hierarchy. This row is that operation, and refuses a leaf by pointing back. So
 * the two partition the space on one precondition rather than overlapping on it, which is the
 * complement shape rows 56 and 57 already use in this stage.</p>
 *
 * <p><b>The shared arithmetic is shared rather than copied</b> — {@link HierarchyFold},
 * extracted from row 38 when this row became its second caller, with row 38's own tests as the
 * control that the extraction preserved its behaviour. Writing a second implementation of a
 * fold this product already performs is the thing the architecture refuses outright.</p>
 *
 * <h2>The reparenting is not a separate mechanism</h2>
 *
 * <p>A subtype's {@code extends Middle} clause is a type reference like any other, so the same
 * sweep that turns {@code Middle x = new Middle()} into the parent's spelling also reparents
 * every subtype. That is why this row needs no "use supertype" engine: the plan's recipe named
 * one, and what it describes is a sweep this product already had.</p>
 */
public class CollapseHierarchyTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** WHICH precondition declined — see {@link org.jawata.mcp.models.ErrorInfo}. */
    public static final class Refusal {

        /** The position or name does not resolve to a class. */
        public static final String NOT_A_TYPE = "NOT_A_TYPE";
        /** The class extends nothing but {@code Object}, so there is no level to collapse. */
        public static final String NO_SUPERCLASS = "NO_SUPERCLASS";
        /** It has no subtypes, which is Remove Subclass rather than this. */
        public static final String NO_SUBTYPES = "NO_SUBTYPES";
        /** The parent has no source in this workspace, so its members cannot be added to. */
        public static final String PARENT_NOT_IN_SOURCE = "PARENT_NOT_IN_SOURCE";
        /** The parent is abstract and something constructs the class being collapsed. */
        public static final String PARENT_IS_ABSTRACT = "PARENT_IS_ABSTRACT";
        /** It overrides a parent method, so the override is a distinction it carries. */
        public static final String OVERRIDES_PARENT = "OVERRIDES_PARENT";
        /** Its constructor fixes an argument rather than forwarding its parameters. */
        public static final String CONSTRUCTOR_FIXES_ARGUMENT = "CONSTRUCTOR_FIXES_ARGUMENT";
        /** An {@code instanceof} or a cast asks for its type specifically. */
        public static final String TYPE_IS_OBSERVED = "TYPE_IS_OBSERVED";
        /** A member name is declared by both it and the parent. */
        public static final String MEMBER_NAME_COLLIDES = "MEMBER_NAME_COLLIDES";
        /** Its file declares other top-level types, which deleting the file would take too. */
        public static final String FILE_HAS_OTHER_TYPES = "FILE_HAS_OTHER_TYPES";

        private Refusal() {
        }
    }

    public CollapseHierarchyTool(Supplier<IJdtService> serviceSupplier,
                                 RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String kindName() {
        return "collapse_hierarchy";
    }

    @Override
    public String getName() {
        return "collapse_hierarchy";
    }

    @Override
    public String getDescription() {
        return """
            Collapse Hierarchy — a class between a parent and its own subtypes that is not
            earning the level is merged into the parent: its members move up, its subtypes are
            reparented onto the parent, and it is deleted. Point at the MIDDLE class
            (typeName=pkg.Type, or a position).
            This is the case inline kind=subclass refuses: that row folds a LEAF subclass, this
            one folds a class that still has subtypes, and each refusal names the other.
            REFUSES a class that carries a distinction — it overrides a parent method, an
            instanceof or a cast asks for its type, or its constructor fixes an argument
            instead of forwarding — because replacing a distinction with a field is a design
            decision. Also refuses an abstract parent when something constructs the class, a
            colliding member name, a parent outside this workspace, and a file declaring other
            top-level types.""";
    }

    /** Structural: it removes a level from a hierarchy and reparents everything below it. */
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
            "description", "Source file declaring the class to collapse."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the class declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("typeName", Map.of("type", "string",
            "description", "Fully-qualified class name, as an alternative to a position."));
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
        IJavaElement element = service.getElementAtPosition(java.nio.file.Path.of(filePath),
            getIntParam(arguments, "line", -1), getIntParam(arguments, "column", -1));
        IType middle = element instanceof IType found ? found : element == null ? null
            : (IType) element.getAncestor(IJavaElement.TYPE);
        if (middle == null || middle.getCompilationUnit() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the position does not resolve to a class.", Refusal.NOT_A_TYPE));
        }

        ICompilationUnit middleCu = middle.getCompilationUnit();
        CompilationUnit middleAst = HierarchyFold.parse(middleCu);
        // THE IDENTITY IT WAS GIVEN, descended into member types — not a name search over the
        // unit's top level, which is the defect this repository has closed as a class twice.
        AbstractTypeDeclaration declared = TypeLookup.declaration(middleAst, middle);
        if (!(declared instanceof TypeDeclaration middleType) || middleType.isInterface()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the target is not a class.", Refusal.NOT_A_TYPE));
        }

        Type superclassType = middleType.getSuperclassType();
        ITypeBinding parent = superclassType == null ? null : superclassType.resolveBinding();
        if (superclassType == null || parent == null
                || "java.lang.Object".equals(parent.getQualifiedName())) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + middle.getElementName() + "' extends nothing, so there is no level to"
                    + " collapse it into.", Refusal.NO_SUPERCLASS));
        }

        // THE PARTITION WITH ROW 38, and the refusal hands the caller that row by name.
        IType[] subtypes = service.getSearchService().getAllSubtypes(middle);
        if (subtypes == null || subtypes.length == 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + middle.getElementName() + "' has no subtypes, so nothing would be"
                    + " reparented and this is Remove Subclass rather than Collapse Hierarchy:"
                    + " run inline kind=subclass instead.", Refusal.NO_SUBTYPES)
                // D3a: the sentence has named the sibling since Stage 7; this is the same
                // answer POINTED AT SOMETHING. The address is the class the caller already
                // named, so the step is runnable without them working anything out.
                .withNextStep(new org.jawata.mcp.models.NextStep("inline kind=subclass",
                    new org.jawata.mcp.models.CodeAddress(null, -1, -1,
                        middle.getFullyQualifiedName('.')), null)));
        }

        IJavaElement parentElement = parent.getJavaElement();
        ICompilationUnit parentCu = parentElement == null ? null
            : (ICompilationUnit) parentElement.getAncestor(IJavaElement.COMPILATION_UNIT);
        if (parentCu == null || !parentCu.exists()) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                parent.getName() + " has no source in this workspace, so its members cannot be"
                    + " added to.", Refusal.PARENT_NOT_IN_SOURCE));
        }

        // DELETING THE FILE IS DELETING EVERY TYPE IN IT. Row 38 deletes the compilation unit
        // without asking what else it declares; this row asks, because the answer is cheap.
        //
        // BOTH HALVES ARE NEEDED AND THE SECOND WAS MISSING AT FIRST. A file with two
        // top-level types is the obvious case. A NESTED class is the one that slips through:
        // its unit has exactly ONE top-level type — the class enclosing it — so the count
        // check passes, and deleting the unit would take the enclosing class and every other
        // member with it. Found while writing this row's fixtures, not by review.
        if (middle.getDeclaringType() != null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "'" + middle.getElementName() + "' is declared inside "
                    + middle.getDeclaringType().getElementName() + ", and this row deletes the"
                    + " FILE — which would take its enclosing class with it. Move it into a"
                    + " file of its own first.", Refusal.FILE_HAS_OTHER_TYPES));
        }
        if (middleAst.types().size() != 1) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                middleCu.getElementName() + " declares " + middleAst.types().size()
                    + " top-level types, and this row deletes the FILE — which would take the"
                    + " others with it. Move '" + middle.getElementName()
                    + "' into a file of its own first.", Refusal.FILE_HAS_OTHER_TYPES));
        }

        for (Object member : middleType.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && !method.isConstructor()) {
                IMethodBinding binding = method.resolveBinding();
                if (binding != null && HierarchyFold.overridesSomething(binding, parent)) {
                    return Preparation.fail(ToolResponse.invalidParameter("position",
                        middle.getElementName() + "." + method.getName() + "() overrides "
                            + parent.getName() + ", and that override IS the distinction this"
                            + " level carries — collapsing it changes what gets dispatched for"
                            + " every subtype below it.", Refusal.OVERRIDES_PARENT));
                }
            }
            if (member instanceof MethodDeclaration ctor && ctor.isConstructor()) {
                String forwarding = HierarchyFold.constructorForwardsUnchanged(ctor);
                if (forwarding != null) {
                    return Preparation.fail(ToolResponse.invalidParameter("position",
                        middle.getElementName() + "'s constructor " + forwarding + ", so the"
                            + " super(...) call in each subtype would no longer mean the same"
                            + " thing once " + middle.getElementName() + " is gone.",
                        Refusal.CONSTRUCTOR_FIXES_ARGUMENT));
                }
            }
        }

        String observed = HierarchyFold.observedAnywhere(service, middle);
        if (observed != null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "the type " + middle.getElementName() + " is observed at " + observed
                    + ". Something depends on this level existing, so removing it changes what"
                    + " that code sees.", Refusal.TYPE_IS_OBSERVED));
        }

        Set<ICompilationUnit> users =
            HierarchyFold.referencingUnits(service, middle, parentCu);

        // AN ABSTRACT PARENT ONLY BREAKS WHAT IS CONSTRUCTED. Row 38 refuses an abstract
        // parent outright, which is right for a leaf because folding one rewrites `new Sub()`.
        // A collapsed middle class is frequently abstract and never constructed, so the honest
        // question is whether anything constructs THIS one.
        if (Modifier.isAbstract(parent.getModifiers())) {
            String constructedAt = constructedAnywhere(middle.getElementName(), middleAst,
                middleCu.getElementName(), users);
            if (constructedAt != null) {
                return Preparation.fail(ToolResponse.invalidParameter("position",
                    parent.getName() + " is abstract and " + middle.getElementName()
                        + " is constructed at " + constructedAt + ", so the `new "
                        + parent.getName() + "(...)` this would leave behind does not compile.",
                    Refusal.PARENT_IS_ABSTRACT));
            }
        }

        CompilationUnit parentAst = HierarchyFold.parse(parentCu);
        AbstractTypeDeclaration parentType = TypeLookup.declaration(parentAst, (IType) parentElement);
        if (parentType == null) {
            return Preparation.fail(ToolResponse.symbolNotFound(
                "could not locate the body of " + parent.getName()));
        }
        Set<String> existing = HierarchyFold.memberNames(parentType);
        List<BodyDeclaration> moving = new ArrayList<>();
        List<String> movedNames = new ArrayList<>();
        for (Object member : middleType.bodyDeclarations()) {
            if (member instanceof MethodDeclaration method && method.isConstructor()) {
                continue;
            }
            BodyDeclaration declaration = (BodyDeclaration) member;
            for (String name : HierarchyFold.namesOf(declaration)) {
                if (existing.contains(name)) {
                    return Preparation.fail(ToolResponse.invalidParameter("position",
                        "'" + name + "' is declared by both " + middle.getElementName()
                            + " and " + parent.getName() + ". Two members of one name is a"
                            + " merge, and merging is a decision.",
                        Refusal.MEMBER_NAME_COLLIDES));
                }
                movedNames.add(name);
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
        DeletedTypeLinks.unwrapIn(parentAst, middle.getElementName(), parentRewrite);
        edits.put((IFile) parentCu.getResource(),
            List.of(parentRewrite.rewriteAST(new Document(parentCu.getSource()),
                FormatterOptions.forGeneratedCode(parentAst))));

        int repointed = 0;
        List<String> reparented = new ArrayList<>();
        for (ICompilationUnit user : users) {
            CompilationUnit ast = HierarchyFold.parse(user);
            ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
            int here = HierarchyFold.repointTypeReferences(ast, middle.getElementName(),
                parent.getName(), rewrite);
            if (here == 0) {
                continue;
            }
            repointed += here;
            DeletedTypeLinks.unwrapIn(ast, middle.getElementName(), rewrite);
            edits.put((IFile) user.getResource(),
                List.of(rewrite.rewriteAST(new Document(user.getSource()),
                    FormatterOptions.forGeneratedCode(ast))));
        }
        for (IType subtype : subtypes) {
            reparented.add(subtype.getElementName());
        }
        // SORTED, because the search returns them in its own order. An unsorted list would
        // make this row's parity golden depend on how the index happened to answer, which is
        // a flake waiting for a machine with a different file order rather than a claim.
        java.util.Collections.sort(reparented);

        String label = "collapse hierarchy: " + middle.getElementName() + " into "
            + parent.getName() + " (" + moving.size() + " member(s) moved up, "
            + reparented.size() + " subtype(s) reparented, " + repointed
            + " reference(s) repointed)";
        CheckedChange deletion = DeleteAtom.delete(new IJavaElement[] { middleCu },
            "delete " + middle.getElementName(), new JdtRefactoringEngine());
        if (deletion.isRefused()) {
            return Preparation.fail(ToolResponse.error("REFACTORING_FAILED",
                "the engine refused to delete " + middle.getElementName() + ": "
                    + deletion.messages(),
                "No files were modified."));
        }

        CompositeChange composite = new CompositeChange(label);
        composite.add(ChangeEngine.fromFileEdits(label, edits));
        composite.add(deletion.change());

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("collapsed", middle.getElementName());
        extras.put("parent", parent.getName());
        extras.put("membersMovedUp", movedNames);
        extras.put("subtypesReparented", reparented);
        extras.put("referencesRepointed", repointed);
        extras.put("note", parent.getName() + "'s API grows by exactly the members that moved"
            + " up, and every subtype of " + middle.getElementName() + " now extends "
            + parent.getName() + " directly. That widening is the price of the collapse.");
        return Preparation.of(composite, label, extras);
    }

    /**
     * Where the class is constructed, or null. Its own file is included, because a static
     * factory on the class itself is a construction the deletion would strand just as surely
     * as one in another file.
     */
    private static String constructedAnywhere(String name, CompilationUnit ownAst,
                                              String ownFile, Set<ICompilationUnit> users)
            throws Exception {
        String[] found = { null };
        ASTVisitor visitor = new ASTVisitor() {
            @Override
            public boolean visit(ClassInstanceCreation node) {
                if (found[0] == null && name.equals(node.getType().toString())) {
                    found[0] = "a `new " + name + "(...)`";
                }
                return true;
            }
        };
        ownAst.accept(visitor);
        if (found[0] != null) {
            return found[0] + " in " + ownFile;
        }
        for (ICompilationUnit user : users) {
            CompilationUnit ast = HierarchyFold.parse(user);
            found[0] = null;
            ast.accept(visitor);
            if (found[0] != null) {
                return found[0] + " in " + user.getElementName();
            }
        }
        return null;
    }
}
