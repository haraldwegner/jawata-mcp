package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.internal.corext.refactoring.code.InlineTempRefactoring;
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
import java.util.function.Supplier;

/**
 * Inline a local variable by replacing all usages with its initializer expression.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 *
 * <p>Sprint 25 (spec D1a item 2): the inlining is computed by JDT's own
 * {@link InlineTempRefactoring} (the IDE's Refactor → Inline local variable),
 * driven through the {@link RefactoringEngine} seam. The original implementation
 * walked usages itself and had a proven defect: inlining one variable of a
 * multi-variable declaration (`int a = 1, b = 2;`) deleted the WHOLE statement,
 * losing the sibling variables — and it never checked name capture (an
 * initializer referencing a field silently rebinds where a local shadows it) or
 * generic type-argument loss. The JDT engine removes exactly the inlined
 * fragment, qualifies clashing names, and refuses what it cannot inline safely.
 * The v2.12.1 compile-verify gate stays wrapped around the applied change.</p>
 */
public class InlineVariableTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(InlineVariableTool.class);

    private final RefactoringEngine engine = new JdtRefactoringEngine();

    public InlineVariableTool(Supplier<IJdtService> serviceSupplier,
                              RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "inline_variable";
    }

    @Override
    public String getDescription() {
        return """
            Inline a local variable by replacing all usages with its initializer expression
            (JDT's own Inline Local Variable engine).

            Applies the inlining directly (default) and returns
            { filesModified, diff, undoChangeId, summary }, compile-verified on the
            modified file. Revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead — returns { changeId, diff }.

            USAGE: Position cursor on the variable declaration or a usage
            OUTPUT: Modified file + unified diff + undo handle

            IMPORTANT: Uses ZERO-BASED coordinates.
            SAFETY: Refuses variables that are reassigned after initialization, have
            no initializer, or cannot be inlined without changing behavior — the
            refusal names the reason.

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
            "line", Map.of(
                "type", "integer",
                "description", "Zero-based line number of variable declaration or usage"
            ),
            "column", Map.of(
                "type", "integer",
                "description", "Zero-based column number"
            )
        ));
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

        // Parse with bindings and resolve the caret to the variable's declaration
        // fragment (declaration or usage site both accepted — the proven resolution
        // the old tool used).
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);

        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("position", "Invalid position"));
        }
        NodeFinder finder = new NodeFinder(ast, offset, 0);
        ASTNode node = finder.getCoveringNode();

        IVariableBinding variableBinding = null;
        VariableDeclarationFragment declarationFragment = null;
        if (node instanceof SimpleName simpleName) {
            if (simpleName.resolveBinding() instanceof IVariableBinding vb) {
                variableBinding = vb;
                if (simpleName.getParent() instanceof VariableDeclarationFragment vdf) {
                    declarationFragment = vdf;
                }
            }
        } else if (node instanceof VariableDeclarationFragment vdf) {
            declarationFragment = vdf;
            variableBinding = vdf.resolveBinding();
        }
        if (variableBinding == null) {
            return Preparation.fail(ToolResponse.symbolNotFound("No variable at position"));
        }
        if (!variableBinding.isParameter() && variableBinding.isField()) {
            return Preparation.fail(ToolResponse.invalidParameter(
                "variable", "Can only inline local variables, not fields"));
        }
        if (variableBinding.isParameter()) {
            return Preparation.fail(ToolResponse.invalidParameter(
                "variable", "Cannot inline method parameters"));
        }
        if (declarationFragment == null) {
            declarationFragment = findDeclaration(ast, variableBinding);
        }
        if (declarationFragment == null) {
            return Preparation.fail(ToolResponse.symbolNotFound("Cannot find variable declaration"));
        }

        String variableName = declarationFragment.getName().getIdentifier();
        Expression initializer = declarationFragment.getInitializer();
        String initializerText = initializer != null ? initializer.toString() : null;

        HeadlessJdtConfig.ensureInitialized();
        InlineTempRefactoring refactoring = new InlineTempRefactoring(declarationFragment);
        CheckedChange checked = engine.propose(refactoring, "inline variable " + variableName);
        if (checked.isRefused()) {
            return Preparation.fail(ToolResponse.error(
                "INLINE_REFUSED",
                "inline_variable refused '" + variableName + "': " + checked.messages(),
                "JDT's Inline Local Variable engine rejected it — no initializer, the "
                    + "variable is reassigned, or inlining would change behavior. Adjust "
                    + "the target and retry. No files were modified."));
        }

        Change change = checked.change();
        VariableDeclaration decl = refactoring.getVariableDeclaration();
        SimpleName[] references = refactoring.getReferences();

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("variableName", decl != null ? decl.getName().getIdentifier() : variableName);
        extras.put("initializerText", initializerText);
        extras.put("usageCount", references != null ? references.length : 0);
        if (checked.hasWarnings()) {
            extras.put("warnings", checked.messages());
        }

        String summary = "inline variable " + variableName
            + (initializerText != null ? " = " + initializerText : "")
            + " (" + (references != null ? references.length : 0) + " usages)";
        log.debug("inline_variable via JDT InlineTempRefactoring: {}", summary);
        return Preparation.of(change, summary, extras);
    }

    private VariableDeclarationFragment findDeclaration(CompilationUnit ast, IVariableBinding binding) {
        final VariableDeclarationFragment[] result = {null};
        final String bindingKey = binding.getKey();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                IVariableBinding nodeBinding = node.resolveBinding();
                if (nodeBinding != null && bindingKey.equals(nodeBinding.getKey())) {
                    result[0] = node;
                    return false;
                }
                return true;
            }
        });
        return result[0];
    }
}
