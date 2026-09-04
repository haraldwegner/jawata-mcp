package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.IField;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMember;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.BodyDeclaration;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldAccess;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.eclipse.core.resources.IFile;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.mcp.refactoring.ChangeEngine;
import java.util.ArrayList;
import org.eclipse.jdt.internal.corext.codemanipulation.CodeGenerationSettings;
import org.eclipse.jdt.internal.corext.refactoring.structure.MoveStaticMembersProcessor;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.participants.ProcessorBasedRefactoring;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fowler — <b>Move Field</b> (row 23), moving a field to an EXISTING class.
 *
 * <p>A delegate of {@link MoveTool} (kind {@code field}); not registered standalone.
 * Routed from {@code shotgun_surgery} and {@code inappropriate_intimacy}, which are the
 * smells that say state is living in the wrong class.</p>
 *
 * <h2>Two engines, because a static field and an instance field are not one problem</h2>
 *
 * <p>A STATIC field has a JDT engine behind it — {@link MoveStaticMembersProcessor}, the
 * IDE's own Move Static Members — which relocates the declaration and rewrites every
 * qualified reference across the workspace. No receiver is involved: the references name
 * the owning type, and the engine renames it.</p>
 *
 * <p>An INSTANCE field has no engine, because the operation needs something the code does
 * not contain. Every access becomes {@code receiver.name}, and which field of the source
 * class is that receiver is not derivable — there may be several of the destination's
 * type, one, or none, and with none the refactoring is impossible until somebody adds one.
 * So it is a PARAMETER, {@code target}, exactly as {@code move kind=method} takes one for
 * the same reason, and as row 64 takes its boundary line. Guessing would produce code that
 * compiles and is wrong, which is the only failure this operation can have.</p>
 *
 * <h2>The instance path is scoped to a PRIVATE field, and the boundary is real</h2>
 *
 * <p>A private field's accesses all live in its own file, so the move is one file's
 * rewrite plus the declaration's arrival in another. A non-private field is read from
 * places this call cannot see, and every one of those sites needs its own receiver derived
 * there — a different operation. It is refused, pointing at {@code data
 * kind=encapsulate_field} as the step that makes it movable.</p>
 *
 * <p>Where the destination is in another package the moved field is widened to public,
 * because the source could not otherwise read it. That is a real consequence of the move
 * and it is reported in the summary rather than done quietly.</p>
 */
public class MoveFieldTool extends AbstractRefactoringTool {

    public MoveFieldTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "move_field";
    }

    @Override
    public String getDescription() {
        return "Move Field — move a field to an existing class, updating every reference. "
            + "A static field moves through the IDE's Move Static Members engine; a "
            + "PRIVATE instance field moves through the receiver named in `target`, and "
            + "a non-private one is refused, pointing at encapsulate_field. "
            + "Delegate of move.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file declaring the field."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the field declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of the field declaration."));
        properties.put("target", Map.of("type", "string",
            "description", "move kind=field: for an INSTANCE field, the name of the field "
                + "in the source class that holds the destination instance — the receiver "
                + "every access is rewritten through. Not needed for a static field."));
        properties.put("targetType", Map.of("type", "string",
            "description", "move kind=field: fully-qualified name of the EXISTING class "
                + "the field moves to."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "targetType"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String targetType = getStringParam(arguments, "targetType");

        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        if (targetType == null || targetType.isBlank()) {
            return ToolResponse.invalidParameter("targetType",
                "targetType is required — the fully-qualified name of the class the field "
                    + "moves to. This refactoring moves state to an EXISTING class; to move "
                    + "fields into a NEW one, use extract kind=class.");
        }

        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            IJavaElement element = service.getElementAtPosition(filePath, line, column);
            if (!(element instanceof IField field)) {
                return ToolResponse.invalidParameter("position",
                    "Position does not resolve to a field; got "
                        + (element == null ? "null" : element.getClass().getSimpleName()));
            }

            IType declaring = field.getDeclaringType();
            if (!Flags.isStatic(field.getFlags())) {
                return moveInstanceField(service, field, declaring,
                    getStringParam(arguments, "target"), targetType, arguments);
            }

            if (declaring != null && targetType.equals(declaring.getFullyQualifiedName())) {
                return ToolResponse.invalidParameter("targetType",
                    "The field already lives in " + targetType + "; nothing to move.");
            }

            MoveStaticMembersProcessor processor =
                new MoveStaticMembersProcessor(new IMember[] { field }, new CodeGenerationSettings());
            processor.setDestinationTypeFullyQualifiedName(targetType);
            // No forwarder: a moved field with a delegate left behind is two names for one
            // piece of state, which is the shotgun-surgery this row exists to remove.
            processor.setDelegateUpdating(false);
            ProcessorBasedRefactoring refactoring = new ProcessorBasedRefactoring(processor);

            RefactoringStatus initial =
                refactoring.checkInitialConditions(new NullProgressMonitor());
            if (initial.hasFatalError()) {
                return ToolResponse.invalidParameter("move kind=field", formatStatus(initial));
            }
            return runPreCheckedRefactoring(service, refactoring, "move_field", arguments);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Move an INSTANCE field through a receiver the caller names.
     *
     * <p>Every access becomes {@code receiver.name}, and nothing in the code says which
     * field is the receiver — the source class may hold several of the destination's type,
     * one, or none. So it is a parameter, exactly as {@code move kind=method} takes
     * {@code target} and as row 64 takes its boundary line. Guessing would produce code
     * that compiles and is wrong.</p>
     *
     * <p>SCOPED TO A PRIVATE FIELD, and the boundary is real rather than convenient. A
     * private field's accesses all live in its own file, so this is one file's rewrite
     * plus the declaration's arrival in another. A non-private field is read from places
     * this call cannot see, and each of those needs its OWN receiver derived at its own
     * site — a different operation, refused here rather than half-done.</p>
     */
    private ToolResponse moveInstanceField(IJdtService service, IField field, IType declaring,
                                           String receiverName, String targetType,
                                           JsonNode arguments) throws Exception {
        if (!Flags.isPrivate(field.getFlags())) {
            return ToolResponse.invalidParameter("position",
                "'" + field.getElementName() + "' is not private, so it is read from places"
                    + " this call cannot see, and each of those sites needs its own receiver"
                    + " derived there. Encapsulate it first (data kind=encapsulate_field),"
                    + " then move it.");
        }
        if (receiverName == null || receiverName.isBlank()) {
            return ToolResponse.invalidParameter("target",
                "moving an instance field rewrites every access to `<receiver>."
                    + field.getElementName() + "`, and nothing in the code says which field"
                    + " of " + (declaring == null ? "the class" : declaring.getElementName())
                    + " is that receiver. Name it in `target`.");
        }
        IType destination = service.findType(targetType);
        if (destination == null || destination.getCompilationUnit() == null) {
            return ToolResponse.symbolNotFound(
                "targetType '" + targetType + "' is not a source type in this workspace");
        }

        ICompilationUnit sourceCu = field.getCompilationUnit();
        CompilationUnit sourceAst = parse(sourceCu);
        FieldDeclaration declaration = declarationOf(sourceAst, field.getElementName());
        if (declaration == null) {
            return ToolResponse.symbolNotFound(
                "could not locate the declaration of '" + field.getElementName() + "'");
        }
        IField receiver = declaring == null ? null : declaring.getField(receiverName);
        if (receiver == null || !receiver.exists()) {
            return ToolResponse.invalidParameter("target",
                "'" + receiverName + "' is not a field of "
                    + (declaring == null ? "the source class" : declaring.getElementName()));
        }

        // The moved field must be reachable from the source. Same package: package-private
        // is enough. Different package: it has to be public, and that WIDENING is reported
        // rather than done quietly — it is a real consequence of the move.
        boolean samePackage = sourceCu.getParent().getElementName()
            .equals(destination.getCompilationUnit().getParent().getElementName());

        ASTRewrite sourceRewrite = ASTRewrite.create(sourceAst.getAST());
        int rewritten = rewriteAccesses(sourceAst, field.getElementName(), receiverName,
            sourceRewrite);
        sourceRewrite.remove(declaration, null);

        ICompilationUnit destinationCu = destination.getCompilationUnit();
        CompilationUnit destinationAst = parse(destinationCu);
        AbstractTypeDeclaration destinationType = typeNamed(destinationAst,
            destination.getElementName());
        if (destinationType == null) {
            return ToolResponse.symbolNotFound(
                "could not locate the body of " + targetType);
        }
        ASTRewrite destinationRewrite = ASTRewrite.create(destinationAst.getAST());
        FieldDeclaration moved = (FieldDeclaration) ASTNode.copySubtree(
            destinationAst.getAST(), declaration);
        moved.modifiers().removeIf(m -> m instanceof Modifier mod
            && (mod.isPrivate() || mod.isPublic() || mod.isProtected()));
        if (!samePackage) {
            moved.modifiers().add(0, destinationAst.getAST()
                .newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD));
        }
        ListRewrite members = destinationRewrite.getListRewrite(destinationType,
            destinationType.getBodyDeclarationsProperty());
        members.insertFirst(moved, null);

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        edits.put((IFile) sourceCu.getResource(),
            List.of(sourceRewrite.rewriteAST(
                new Document(sourceCu.getSource()),
                org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(sourceAst))));
        edits.put((IFile) destinationCu.getResource(),
            List.of(destinationRewrite.rewriteAST(
                new Document(destinationCu.getSource()),
                org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(destinationAst))));

        String label = "move field " + field.getElementName() + " to " + targetType
            + " through " + receiverName + " (" + rewritten + " access(es) rewritten"
            + (samePackage ? "" : "; visibility widened to public — the destination is in"
                + " another package, so the source could not otherwise read it") + ")";
        Change change = ChangeEngine.fromFileEdits(label, edits);
        // Wrapped so it goes through the SAME pipeline every other refactoring uses: the
        // compile gate, the undo handle, the staged/applied contract. A cross-file edit
        // that bypassed that would be the one place in the product where a rewrite is
        // applied unverified.
        return runPreCheckedRefactoring(service, new PreparedRefactoring(change, label),
            "move_field", arguments);
    }

    /** Every read and write of the field becomes `receiver.field`. */
    private static int rewriteAccesses(CompilationUnit ast, String fieldName,
                                       String receiverName, ASTRewrite rewrite) {
        List<ASTNode> sites = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (!fieldName.equals(node.getIdentifier())) {
                    return true;
                }
                if (!(node.resolveBinding() instanceof IVariableBinding variable)
                        || !variable.isField()) {
                    return true;
                }
                if (node.getParent() instanceof org.eclipse.jdt.core.dom.VariableDeclarationFragment) {
                    return true;   // the declaration itself, which is removed separately
                }
                // `this.f` is a FieldAccess; replace the whole access, not the name inside
                // it, or the result reads `this.receiver.f` with the wrong receiver.
                sites.add(node.getParent() instanceof FieldAccess access
                    && access.getName() == node ? access : node);
                return true;
            }
        });
        AST factory = ast.getAST();
        for (ASTNode site : sites) {
            FieldAccess replacement = factory.newFieldAccess();
            replacement.setExpression(factory.newSimpleName(receiverName));
            replacement.setName(factory.newSimpleName(fieldName));
            rewrite.replace(site, replacement, null);
        }
        return sites.size();
    }

    private static FieldDeclaration declarationOf(CompilationUnit ast, String name) {
        List<FieldDeclaration> found = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(FieldDeclaration node) {
                for (Object fragment : node.fragments()) {
                    if (fragment instanceof org.eclipse.jdt.core.dom.VariableDeclarationFragment f
                            && name.equals(f.getName().getIdentifier())) {
                        found.add(node);
                    }
                }
                return true;
            }
        });
        // One fragment only: `int a, b;` shares a declaration, and moving one of them means
        // splitting it, which is a different edit than moving the whole line.
        return found.size() == 1 && found.get(0).fragments().size() == 1 ? found.get(0) : null;
    }

    private static AbstractTypeDeclaration typeNamed(CompilationUnit ast, String name) {
        for (Object type : ast.types()) {
            if (type instanceof AbstractTypeDeclaration declaration
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
