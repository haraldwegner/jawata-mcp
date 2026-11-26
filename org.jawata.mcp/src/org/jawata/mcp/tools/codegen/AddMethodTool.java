package org.jawata.mcp.tools.codegen;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.Flags;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaElement;
import org.eclipse.jdt.core.IMethod;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PrimitiveType;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.StringLiteral;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.Type;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.SourceCommit;
import org.jawata.mcp.tools.AbstractTool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code generate kind=add_method} (28h-C27-20): adds one method to the type at the caret from a name, a return type and parameters.
 *
 * <p>A developer who writes the test first needs a method to call before the test can fail for what the method should do, and no tool added
 * one, so the stub was written by hand. On a class the method is a stub whose body throws {@code UnsupportedOperationException}, which
 * compiles whatever the return type is and fails a test of its behaviour; on an interface it is the bare signature.</p>
 */
public class AddMethodTool extends AbstractTool
        implements org.jawata.mcp.tools.ToolKindDelegate {

    private static final Logger log = LoggerFactory.getLogger(AddMethodTool.class);

    private static final List<String> VISIBILITIES = List.of("public", "protected", "package", "private");

    private final RefactoringChangeCache changeCache;

    public AddMethodTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache changeCache) {
        super(serviceSupplier);
        this.changeCache = changeCache;
    }

    /** Reached as {@code generate kind=add_method}. */
    @Override
    public String kindName() {
        return "add_method";
    }

    /** The bullet a client reads under {@code generate}. */
    @Override
    public String kindSummary() {
        return "a method on the type at the caret: on a class a stub whose body throws UnsupportedOperationException, so a test of its behaviour"
            + " fails for the behaviour and not for a missing method; on an interface the bare signature. Needs: methodName."
            + " Optional: returnType (default void), parameters[] ({name, type}), visibility (public by default).";
    }

    @Override
    public String getName() {
        return "add_method";
    }

    @Override
    public String getDescription() {
        return "Add one method to the type at the caret: a throwing stub on a class, the bare signature on an interface. Delegate of generate(kind=add_method).";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string"));
        properties.put("line", Map.of("type", "integer"));
        properties.put("column", Map.of("type", "integer"));
        properties.put("methodName", Map.of("type", "string", "description", "add_method: the new method's name."));
        properties.put("returnType", Map.of("type", "string", "description", "add_method: the return type as written in source (default void)."));
        properties.put("parameters", Map.of("type", "array", "description", "add_method: the parameters, in order, each {name, type}.",
            "items", Map.of("type", "object", "properties", Map.of("name", Map.of("type", "string"), "type", Map.of("type", "string")))));
        properties.put("visibility", Map.of("type", "string", "enum", VISIBILITIES, "description", "constructor/getters_setters/add_method: member visibility (e.g. public)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "methodName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        ToolResponse missing = requireParam(arguments, "filePath");
        if (missing != null) return missing;
        missing = requireParam(arguments, "methodName");
        if (missing != null) return missing;

        String methodName = getStringParam(arguments, "methodName");
        if (!isIdentifier(methodName)) {
            return ToolResponse.invalidParameter("methodName", "'" + methodName + "' is not a Java method name.");
        }
        String visibility = getStringParam(arguments, "visibility", "public");
        if (!VISIBILITIES.contains(visibility)) {
            return ToolResponse.invalidParameter("visibility", "visibility is one of " + VISIBILITIES + ", not '" + visibility + "'.");
        }

        try {
            IType type = walkUpToType(service.getElementAtPosition(Path.of(getStringParam(arguments, "filePath")),
                getIntParam(arguments, "line", 0), getIntParam(arguments, "column", 0)));
            if (type == null) {
                return ToolResponse.invalidParameter("filePath/line/column", "Caret does not resolve to a class or interface.");
            }
            ICompilationUnit cu = type.getCompilationUnit();
            if (cu == null) {
                return ToolResponse.invalidParameter("filePath", "Target type has no compilation unit.");
            }

            ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
            parser.setSource(cu);
            parser.setKind(ASTParser.K_COMPILATION_UNIT);
            parser.setResolveBindings(true);
            CompilationUnit astRoot = (CompilationUnit) parser.createAST(new NullProgressMonitor());
            AbstractTypeDeclaration target = org.jawata.mcp.tools.shared.TypeLookup.declaration(astRoot, type);
            if (!(target instanceof TypeDeclaration typeDecl)) {
                return ToolResponse.invalidParameter("filePath/line/column", "Target is not a class or an interface.");
            }

            AST ast = astRoot.getAST();
            MethodDeclaration method = ast.newMethodDeclaration();
            method.setName(ast.newSimpleName(methodName));
            ToolResponse unreadable = describeSignature(ast, method, arguments, typeDecl.isInterface() ? null : visibility);
            if (unreadable != null) {
                return unreadable;
            }
            if (methodAlreadyExists(type, methodName, method.parameters().size())) {
                return ToolResponse.invalidParameter("methodName", type.getElementName() + " already declares " + methodName
                    + " with " + method.parameters().size() + " parameter(s).");
            }
            boolean asDefault = typeDecl.isInterface() && abstractWouldBreakImplementors(type);
            if (asDefault) {
                method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.DEFAULT_KEYWORD));
            }
            if (!typeDecl.isInterface() || asDefault) {
                method.setBody(throwingBody(ast));
            }

            ASTRewrite rewrite = ASTRewrite.create(ast);
            ListRewrite body = rewrite.getListRewrite(typeDecl, TypeDeclaration.BODY_DECLARATIONS_PROPERTY);
            body.insertLast(method, null);

            Document doc = new Document(cu.getSource());
            TextEdit edits = rewrite.rewriteAST(doc, org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(
                cu, getStringParam(arguments, "indentChar", null)));
            edits.apply(doc);
            return commit(service, cu, doc.get(), methodName, arguments, asDefault ? AS_DEFAULT : null);
        } catch (Exception e) {
            log.warn("generate(kind=add_method) failed: {}", e.getMessage(), e);
            return ToolResponse.internalError(e);
        }
    }

    /** Fills the method's modifiers, return type and parameters from the request; a response when one of them is not Java, else null. */
    @SuppressWarnings("unchecked")
    private ToolResponse describeSignature(AST ast, MethodDeclaration method, JsonNode arguments, String visibility) {
        String returnText = getStringParam(arguments, "returnType", "void").trim();
        Type returnType = "void".equals(returnText) ? ast.newPrimitiveType(PrimitiveType.VOID) : parseType(ast, returnText);
        if (returnType == null) {
            return ToolResponse.invalidParameter("returnType", "'" + returnText + "' is not a Java type.");
        }
        method.setReturnType2(returnType);
        if (visibility != null && !"package".equals(visibility)) {
            method.modifiers().add(ast.newModifier(Modifier.ModifierKeyword.toKeyword(visibility)));
        }
        for (JsonNode given : arguments.path("parameters")) {
            String name = given.path("name").asText("");
            Type type = parseType(ast, given.path("type").asText(""));
            if (!isIdentifier(name) || type == null) {
                return ToolResponse.invalidParameter("parameters", "each parameter needs a Java name and a Java type; got name '" + name
                    + "' and type '" + given.path("type").asText("") + "'.");
            }
            SingleVariableDeclaration parameter = ast.newSingleVariableDeclaration();
            parameter.setName(ast.newSimpleName(name));
            parameter.setType(type);
            method.parameters().add(parameter);
        }
        return null;
    }

    private ToolResponse commit(IJdtService service, ICompilationUnit cu, String newSource, String methodName, JsonNode arguments, String note)
            throws Exception {
        if (!getBooleanParam(arguments, "auto_apply", true)) {
            SourceCommit.Staged staged = SourceCommit.stageFullReplace(cu, newSource, "add_method", changeCache, service);
            Map<String, Object> stagedData = new LinkedHashMap<>();
            stagedData.put("operation", "add_method");
            stagedData.put("applied", false);
            stagedData.put("changeId", staged.changeId());
            putDiff(stagedData, staged.diff(), arguments);
            stagedData.put("filePath", staged.filePath());
            if (note != null) {
                stagedData.put("note", note);
            }
            return ToolResponse.success(stagedData, ResponseMeta.builder()
                .suggestedNextTools(List.of(
                    "refactoring(action=apply) with this changeId to commit the staged change",
                    "refactoring(action=inspect) with this changeId to re-examine the diff"))
                .build());
        }
        SourceCommit.Committed committed = SourceCommit.commitWithUndo(cu, newSource, "add_method", changeCache, service);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", "add_method");
        data.put("filePath", service.getPathUtils().formatPath(cu.getResource().getLocation().toFile().toPath()));
        data.put("methodsAdded", List.of(methodName));
        putGeneratedSource(data, newSource);
        data.put("applied", true);
        data.put("filesModified", List.of(committed.filePath()));
        putDiff(data, committed.diff(), arguments);
        data.put("undoChangeId", committed.undoChangeId());
        if (note != null) {
            data.put("note", note);
        }
        return ToolResponse.success(data, ResponseMeta.builder().totalCount(1).returnedCount(1).build());
    }

    private static final String AS_DEFAULT = "added as a default method: a bare signature would break what implements this interface, or the"
        + " lambdas its one abstract method may be";

    /** True when something extends or implements the interface, or its one abstract method may be implemented by lambdas. */
    private static boolean abstractWouldBreakImplementors(IType type) throws JavaModelException {
        int abstractMethods = 0;
        for (IMethod declared : type.getMethods()) {
            int flags = declared.getFlags();
            if (!Flags.isStatic(flags) && !Flags.isDefaultMethod(flags) && !Flags.isPrivate(flags)) {
                abstractMethods++;
            }
        }
        return abstractMethods == 1 || type.newTypeHierarchy(new NullProgressMonitor()).getAllSubtypes(type).length > 0;
    }

    /** {@code throw new UnsupportedOperationException("not implemented");}, the body that compiles for any return type. */
    @SuppressWarnings("unchecked")
    private static Block throwingBody(AST ast) {
        ClassInstanceCreation unsupported = ast.newClassInstanceCreation();
        unsupported.setType(ast.newSimpleType(ast.newSimpleName("UnsupportedOperationException")));
        StringLiteral message = ast.newStringLiteral();
        message.setLiteralValue("not implemented");
        unsupported.arguments().add(message);
        ThrowStatement thrown = ast.newThrowStatement();
        thrown.setExpression(unsupported);
        Block body = ast.newBlock();
        body.statements().add(thrown);
        return body;
    }

    /**
     * The type the text names, built in {@code ast}, or null when the text is not exactly one Java type: it is parsed as the type of a field
     * of a throwaway class, so a type with generics or array brackets is read as Java reads it and text that carries more than a type is not.
     */
    private static Type parseType(AST ast, String text) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        parser.setSource(("class T { " + text + " x; }").toCharArray());
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        if (text.isBlank() || unit.getProblems().length > 0 || unit.types().size() != 1) {
            return null;
        }
        TypeDeclaration holder = (TypeDeclaration) unit.types().get(0);
        if (holder.bodyDeclarations().size() != 1 || !(holder.bodyDeclarations().get(0) instanceof FieldDeclaration field)
                || field.fragments().size() != 1) {
            return null;
        }
        return (Type) ASTNode.copySubtree(ast, field.getType());
    }

    private static boolean isIdentifier(String name) {
        if (name == null || name.isEmpty() || !Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        return name.chars().allMatch(Character::isJavaIdentifierPart);
    }

    private static boolean methodAlreadyExists(IType type, String name, int parameterCount) {
        try {
            for (IMethod existing : type.getMethods()) {
                if (name.equals(existing.getElementName()) && existing.getNumberOfParameters() == parameterCount) {
                    return true;
                }
            }
        } catch (org.eclipse.jdt.core.JavaModelException unreadable) {
            log.debug("could not list the methods of {}: {}", type.getElementName(), unreadable.getMessage());
        }
        return false;
    }

    private static IType walkUpToType(IJavaElement element) {
        IJavaElement cursor = element;
        while (cursor != null) {
            if (cursor instanceof IType t) return t;
            cursor = cursor.getParent();
        }
        return null;
    }
}
