package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.AnonymousClassDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.internal.corext.fix.LambdaExpressionsFixCore;
import org.eclipse.ltk.core.refactoring.Change;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.shared.HeadlessJdtConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Convert an anonymous class implementing a functional interface to a lambda expression.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 *
 * <p>Sprint 25 (spec D1a item 4): the conversion is computed by JDT's own
 * {@link LambdaExpressionsFixCore} — the engine behind the IDE's
 * "Convert to lambda" clean-up and quick-assist (headless by design; jdt.ls runs
 * the same class). The original implementation string-built the lambda and
 * checked only `this`-usage; it silently IGNORED fields declared in the
 * anonymous class (dropping them from the conversion) and never checked
 * recursion, super-references, or annotation loss. The engine's eligibility
 * ({@code isFunctionalAnonymous} + its finders) covers all of these and refuses
 * what it cannot convert. The v2.12.1 compile-verify gate stays wrapped around
 * the applied change.</p>
 */
public class ConvertAnonymousToLambdaTool extends AbstractApplyingRefactoringTool {

    private static final Logger log = LoggerFactory.getLogger(ConvertAnonymousToLambdaTool.class);

    public ConvertAnonymousToLambdaTool(Supplier<IJdtService> serviceSupplier,
                                        RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "convert_anonymous_to_lambda";
    }

    @Override
    public String getDescription() {
        return """
            Convert an anonymous class implementing a functional interface to a lambda
            expression (JDT's own convert-to-lambda engine).

            Applies the conversion directly (default) and returns
            { filesModified, diff, undoChangeId, summary }, compile-verified on the
            modified file. Revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead — returns { changeId, diff }.

            USAGE: Position cursor on the 'new' keyword of the anonymous class
            OUTPUT: Modified file + unified diff + undo handle

            IMPORTANT: Uses ZERO-BASED coordinates.
            The engine REFUSES conversions that would change behavior — non-functional
            interfaces, multiple methods, fields in the anonymous class, this/super
            references, recursion — with the reason in the response.

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
                "description", "Zero-based line number of anonymous class (on 'new' keyword)"
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
        ClassInstanceCreation creation = findClassInstanceCreation(finder.getCoveringNode());
        if (creation == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No anonymous class found at position. Position cursor on 'new' keyword."));
        }
        AnonymousClassDeclaration anonymousClass = creation.getAnonymousClassDeclaration();
        if (anonymousClass == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "This is not an anonymous class declaration"));
        }

        ITypeBinding typeBinding = creation.getType().resolveBinding();
        String interfaceType = typeBinding != null ? typeBinding.getName() : "?";
        String methodName = firstMethodName(anonymousClass);

        HeadlessJdtConfig.ensureInitialized();
        // The engine's own eligibility (isFunctionalAnonymous + this/super, recursion,
        // field and annotation finders) decides; null = not convertible.
        LambdaExpressionsFixCore fix = LambdaExpressionsFixCore.createConvertToLambdaFix(creation);
        if (fix == null) {
            return Preparation.fail(ToolResponse.error(
                "CONVERT_REFUSED",
                "convert_anonymous_to_lambda refused this anonymous class: JDT's engine "
                    + "determined it cannot be converted without changing behavior (not a "
                    + "functional interface, multiple members, fields, this/super references, "
                    + "or recursion).",
                "Only a single-method anonymous implementation of a functional interface "
                    + "with no self-references converts safely. No files were modified."));
        }

        Change change = fix.createChange(new NullProgressMonitor());

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("interfaceType", interfaceType);
        extras.put("methodName", methodName);

        String summary = "convert anonymous " + interfaceType + " to lambda";
        log.debug("convert_anonymous_to_lambda via JDT LambdaExpressionsFixCore: {}", summary);
        return Preparation.of(change, summary, extras);
    }

    private ClassInstanceCreation findClassInstanceCreation(ASTNode node) {
        while (node != null) {
            if (node instanceof ClassInstanceCreation cic && cic.getAnonymousClassDeclaration() != null) {
                return cic;
            }
            node = node.getParent();
        }
        return null;
    }

    private static String firstMethodName(AnonymousClassDeclaration anonymousClass) {
        for (Object decl : anonymousClass.bodyDeclarations()) {
            if (decl instanceof MethodDeclaration md) {
                return md.getName().getIdentifier();
            }
        }
        return "?";
    }
}
