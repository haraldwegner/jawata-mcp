package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jdt.core.search.IJavaSearchConstants;
import org.eclipse.jdt.core.search.SearchMatch;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Sprint 19 (Kerievsky) — <b>Inline Singleton</b> (away from pattern). A GoF
 * singleton whose uniqueness no longer matters becomes an ordinary class:
 * <ol>
 *   <li>every {@code Type.getInstance()} call site → {@code new Type()},</li>
 *   <li>the private constructor → {@code public},</li>
 *   <li>the static holder field and the static accessor are removed.</li>
 * </ol>
 * All edits are computed against the original AST and composed into one
 * {@link ChangeEngine#fromFileEdits} change (multi-file safe, one undo). The
 * transform is mechanical; whether inlining is <em>behaviour-preserving</em> (the
 * singleton holds no relied-upon shared state) is the agent's arbitration — the
 * {@code find_quality_issue kind=singleton} detector locates candidates.
 *
 * <p>A delegate of {@link RefactorToPatternTool} (kind {@code inline_singleton});
 * not registered as a standalone tool.</p>
 */
public class InlineSingletonTool extends AbstractApplyingRefactoringTool {

    public InlineSingletonTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "inline_singleton";
    }

    @Override
    public String getDescription() {
        return "Inline Singleton — rewrite getInstance() call sites to `new Type()`, publicise the "
            + "constructor, and strip the static holder + accessor. Delegate of refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of("type", "string", "description", "Source file of the singleton type."),
            "line", Map.of("type", "integer", "description", "Zero-based line on the singleton type."),
            "column", Map.of("type", "integer", "description", "Zero-based column on the singleton type.")));
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
        IType singleton = service.getTypeAtPosition(path, line, column);
        if (singleton == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No type found at position " + line + ":" + column));
        }
        ICompilationUnit scu = singleton.getCompilationUnit();
        if (scu == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                "Singleton source not available: " + singleton.getElementName()));
        }
        CompilationUnit sast = parse(scu);
        TypeDeclaration typeDecl = findType(sast, singleton.getElementName());
        if (typeDecl == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                "Cannot locate type declaration for " + singleton.getElementName()));
        }

        // Identify the three singleton marks.
        ITypeBinding self = typeDecl.resolveBinding();
        MethodDeclaration ctor = null;
        MethodDeclaration accessor = null;
        FieldDeclaration holder = null;
        for (MethodDeclaration m : typeDecl.getMethods()) {
            if (m.isConstructor()) {
                if (Modifier.isPrivate(m.getModifiers()) && ctor == null) {
                    ctor = m;
                }
            } else if (Modifier.isStatic(m.getModifiers())) {
                IMethodBinding mb = m.resolveBinding();
                if (self != null && mb != null && self.isEqualTo(mb.getReturnType())) {
                    accessor = m;
                }
            }
        }
        for (FieldDeclaration f : typeDecl.getFields()) {
            if (Modifier.isStatic(f.getModifiers())
                && self != null && self.isEqualTo(f.getType().resolveBinding())) {
                holder = f;
                break;
            }
        }
        if (ctor == null || accessor == null || holder == null) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                "'" + singleton.getElementName() + "' is not a GoF singleton (needs a private "
                    + "constructor, a static self-typed holder field, and a static accessor)."));
        }

        IMethodBinding accBinding = accessor.resolveBinding();
        if (accBinding == null || !(accBinding.getJavaElement() instanceof IMethod accMethod)) {
            return Preparation.fail(ToolResponse.invalidParameter("type",
                "Cannot resolve the static accessor method."));
        }

        List<SearchMatch> matches = service.getSearchService()
            .findReferences(accMethod, IJavaSearchConstants.REFERENCES, 500);

        String typeName = singleton.getElementName();
        Map<IFile, FileRewrite> byFile = new LinkedHashMap<>();

        // Singleton's own file: publicise ctor, remove holder + accessor.
        IFile sfile = (IFile) scu.getResource();
        FileRewrite sfr = new FileRewrite(sfile, scu, sast, ASTRewrite.create(sast.getAST()));
        byFile.put(sfile, sfr);
        makePublic(sfr.rewrite, ctor);
        sfr.rewrite.remove(holder, null);
        sfr.rewrite.remove(accessor, null);

        int rewrittenCallSites = 0;
        for (SearchMatch match : matches) {
            if (!(match.getResource() instanceof IFile mf)) {
                continue;
            }
            FileRewrite fr = byFile.get(mf);
            if (fr == null) {
                ICompilationUnit mcu = service.getCompilationUnit(mf.getLocation().toFile().toPath());
                if (mcu == null) {
                    continue;
                }
                CompilationUnit mast = parse(mcu);
                fr = new FileRewrite(mf, mcu, mast, ASTRewrite.create(mast.getAST()));
                byFile.put(mf, fr);
            }
            MethodInvocation inv = findInvocation(fr.ast, match.getOffset());
            if (inv == null) {
                continue;
            }
            AST a = fr.rewrite.getAST();
            ClassInstanceCreation cic = a.newClassInstanceCreation();
            cic.setType(a.newSimpleType(a.newSimpleName(typeName)));
            fr.rewrite.replace(inv, cic, null);
            rewrittenCallSites++;
        }

        Map<IFile, List<TextEdit>> editsByFile = new LinkedHashMap<>();
        for (FileRewrite fr : byFile.values()) {
            IDocument doc = new Document(fr.cu.getSource());
            editsByFile.put(fr.file, List.of(fr.rewrite.rewriteAST(doc, null)));
        }

        Change change = ChangeEngine.fromFileEdits("inline singleton " + typeName, editsByFile);

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("typeName", typeName);
        extras.put("callSitesRewritten", rewrittenCallSites);
        String summary = "inline singleton " + typeName + " (" + rewrittenCallSites
            + " call site(s) rewritten to `new " + typeName + "()`)";
        return Preparation.of(change, summary, extras);
    }

    /** One AST + rewrite per touched file, so all edits for a file compose into one TextEdit. */
    private record FileRewrite(IFile file, ICompilationUnit cu, CompilationUnit ast, ASTRewrite rewrite) {
    }

    private static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }

    private static TypeDeclaration findType(CompilationUnit ast, String simpleName) {
        for (Object t : ast.types()) {
            if (t instanceof TypeDeclaration td) {
                if (simpleName.equals(td.getName().getIdentifier())) {
                    return td;
                }
                TypeDeclaration nested = findNested(td, simpleName);
                if (nested != null) {
                    return nested;
                }
            }
        }
        return null;
    }

    private static TypeDeclaration findNested(TypeDeclaration outer, String simpleName) {
        for (TypeDeclaration nested : outer.getTypes()) {
            if (simpleName.equals(nested.getName().getIdentifier())) {
                return nested;
            }
            TypeDeclaration deeper = findNested(nested, simpleName);
            if (deeper != null) {
                return deeper;
            }
        }
        return null;
    }

    private static MethodInvocation findInvocation(CompilationUnit ast, int offset) {
        NodeFinder finder = new NodeFinder(ast, offset, 0);
        ASTNode node = finder.getCoveringNode();
        while (node != null && !(node instanceof MethodInvocation)) {
            node = node.getParent();
        }
        return (MethodInvocation) node;
    }

    private static void makePublic(ASTRewrite rewrite, MethodDeclaration ctor) {
        AST a = rewrite.getAST();
        ListRewrite mods = rewrite.getListRewrite(ctor, MethodDeclaration.MODIFIERS2_PROPERTY);
        boolean replaced = false;
        for (Object o : ctor.modifiers()) {
            if (o instanceof Modifier m && m.getKeyword() == Modifier.ModifierKeyword.PRIVATE_KEYWORD) {
                mods.replace(m, a.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD), null);
                replaced = true;
            }
        }
        if (!replaced) {
            mods.insertFirst(a.newModifier(Modifier.ModifierKeyword.PUBLIC_KEYWORD), null);
        }
    }
}
