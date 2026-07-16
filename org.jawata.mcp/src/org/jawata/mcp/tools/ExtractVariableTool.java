package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractTempRefactoring;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.CheckedChange;
import org.jawata.mcp.refactoring.JdtRefactoringEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.refactoring.RefactoringEngine;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract an expression into a local variable.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 *
 * <p>Sprint 25 (D1c): the extraction is computed by JDT's own
 * {@link ExtractTempRefactoring} (the IDE's Refactor → Extract Local Variable),
 * driven through the {@link RefactoringEngine} seam. The original implementation
 * string-built the declaration and computed the insertion point + indentation by
 * hand — it mis-indented the inserted declaration and de-indented the replaced
 * line. The JDT engine inserts a correctly-formatted declaration, replaces the
 * selected occurrence, and REFUSES a selection it cannot extract. The v2.12.1
 * compile-verify gate stays wrapped around the applied change.</p>
 */
public class ExtractVariableTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ExtractVariableTool.class);

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractVariableTool(Supplier<IJdtService> serviceSupplier,
                               RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "extract_variable";
    }

    @Override
    public String getDescription() {
        return """
            Extract an expression at the given position into a local variable (JDT's own engine).

            Applies the extraction directly (default) and returns
            { filesModified, diff, undoChangeId, summary }, compile-verified on the
            modified file. Revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead — returns { changeId, diff }.

            USAGE: Select an expression by providing start and end positions;
            optionally provide a name (one is suggested otherwise). JDT REFUSES a
            selection it cannot extract — the refusal names the reason.

            IMPORTANT: Uses ZERO-BASED coordinates.

            Requires load_project to be called first.
            """;
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", Map.of(
            "filePath", Map.of(
                "type", "string",
                "description", "Path to source file"
            ),
            "startLine", Map.of(
                "type", "integer",
                "description", "Zero-based start line of expression"
            ),
            "startColumn", Map.of(
                "type", "integer",
                "description", "Zero-based start column of expression"
            ),
            "endLine", Map.of(
                "type", "integer",
                "description", "Zero-based end line of expression"
            ),
            "endColumn", Map.of(
                "type", "integer",
                "description", "Zero-based end column of expression"
            ),
            "variableName", Map.of(
                "type", "string",
                "description", "Name for the new variable (optional, will suggest if not provided)"
            )
        ));
        schema.put("required", List.of("filePath", "startLine", "startColumn", "endLine", "endColumn"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments) throws Exception {
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("filePath", "Required"));
        }

        int startLine = getIntParam(arguments, "startLine", -1);
        int startColumn = getIntParam(arguments, "startColumn", -1);
        int endLine = getIntParam(arguments, "endLine", -1);
        int endColumn = getIntParam(arguments, "endColumn", -1);
        String variableName = getStringParam(arguments, "variableName");

        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            return Preparation.fail(
                ToolResponse.invalidParameter("positions", "All positions must be >= 0"));
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }

        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        int startOffset = ast.getPosition(startLine + 1, startColumn);
        int endOffset = ast.getPosition(endLine + 1, endColumn);
        if (startOffset < 0 || endOffset < 0 || startOffset >= endOffset) {
            return Preparation.fail(
                ToolResponse.invalidParameter("positions", "Invalid selection range"));
        }

        // Resolve the selected expression — for the reported type/name/text (JDT is
        // the authority on whether it is actually extractable).
        NodeFinder finder = new NodeFinder(ast, startOffset, endOffset - startOffset);
        ASTNode coveredNode = finder.getCoveredNode();
        ASTNode coveringNode = finder.getCoveringNode();
        Expression expression = coveredNode instanceof Expression e ? e
            : (coveringNode instanceof Expression e2 ? e2 : null);
        if (expression == null) {
            return Preparation.fail(
                ToolResponse.invalidParameter("selection", "No extractable expression at selection"));
        }

        ITypeBinding typeBinding = expression.resolveTypeBinding();
        String typeName = typeBinding != null ? typeBinding.getName() : "var";
        String expressionText = expression.toString();

        if (variableName == null || variableName.isBlank()) {
            variableName = suggestVariableName(expression, typeBinding);
        }
        if (!isValidJavaIdentifier(variableName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("variableName", "Not a valid Java identifier"));
        }

        HeadlessJdtConfig.ensureInitialized();
        ExtractTempRefactoring refactoring =
            new ExtractTempRefactoring(cu, startOffset, endOffset - startOffset);
        refactoring.setTempName(variableName);
        refactoring.setReplaceAllOccurrences(false);
        refactoring.setDeclareFinal(false);
        refactoring.setDeclareVarType(false);

        CheckedChange checked = engine.propose(refactoring, "extract variable " + variableName);
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error(
                "EXTRACT_REFUSED",
                "extract_variable refused this selection: " + checked.messages(),
                "Select a single complete expression (JDT's Extract Local Variable rules "
                    + "apply) and retry. No files were modified."));
        }

        Change change = checked.change();

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("variableName", variableName);
        extras.put("variableType", typeName);
        extras.put("expressionText", expressionText);
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }

        String summary = "extract variable " + typeName + " " + variableName + " = " + expressionText;
        log.debug("extract_variable via JDT ExtractTempRefactoring: {}", summary);
        return Preparation.of(change, summary, extras);
    }

    private String suggestVariableName(Expression expression, ITypeBinding typeBinding) {
        String suggested = null;
        if (typeBinding != null) {
            String typeName = typeBinding.getName();
            if (!typeName.isEmpty()) {
                int genericIndex = typeName.indexOf('<');
                if (genericIndex > 0) {
                    typeName = typeName.substring(0, genericIndex);
                }
                suggested = Character.toLowerCase(typeName.charAt(0))
                    + (typeName.length() > 1 ? typeName.substring(1) : "");
            }
        }
        if (suggested == null && expression instanceof MethodInvocation mi) {
            String methodName = mi.getName().getIdentifier();
            suggested = methodName.startsWith("get") && methodName.length() > 3
                ? Character.toLowerCase(methodName.charAt(3)) + methodName.substring(4)
                : methodName + "Result";
        }
        if (suggested == null && expression instanceof ClassInstanceCreation cic) {
            String typeName = cic.getType().toString();
            suggested = Character.toLowerCase(typeName.charAt(0))
                + (typeName.length() > 1 ? typeName.substring(1) : "");
        }
        if (suggested == null) {
            suggested = "extracted";
        }
        if (RESERVED_WORDS.contains(suggested)) {
            if (typeBinding != null && typeBinding.isPrimitive()) {
                return suggested + "Value";
            }
            return "value";
        }
        return suggested;
    }

    private boolean isValidJavaIdentifier(String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        if (!Character.isJavaIdentifierStart(name.charAt(0))) {
            return false;
        }
        for (int i = 1; i < name.length(); i++) {
            if (!Character.isJavaIdentifierPart(name.charAt(i))) {
                return false;
            }
        }
        return !RESERVED_WORDS.contains(name);
    }
}
