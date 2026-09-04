package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.Recipe;
import org.jawata.mcp.refactoring.RecipeEngine;
import org.jawata.mcp.refactoring.RecipeStep;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fowler — <b>Replace Temp with Query</b> (row 58), composed rather than written.
 *
 * <p>A delegate of {@link ExtractTool} (kind {@code temp_to_query}); not registered
 * standalone. It is two operations the product already performs: extract the temp's
 * initializer into a method, then inline the temp. Both halves already have their own
 * refusals, their own compile gate and their own undo, and composing them adds a third
 * thing — one undo handle for the pair, and a rollback if the second half declines after
 * the first has run.</p>
 *
 * <h2>The second step addresses the variable BY NAME, and it has to</h2>
 *
 * <p>A recipe's steps are decided before any of them runs, so a step that carries a file
 * POSITION is carrying a position in the document as it was — and the first step has since
 * rewritten it. Extracting an initializer changes the line's length and can add a method
 * above it, so the temp is no longer where it was. The second step therefore carries the
 * variable's name and resolves it against the tree as it stands when the step is built.</p>
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li><b>A temp that is reassigned.</b> Then it is not a name for one value, and a query
 *       returning the initializer would give the wrong answer everywhere after the second
 *       assignment. Fowler's own answer is Split Variable (row 65) first.</li>
 *   <li><b>A temp with no initializer</b>, which has nothing to turn into a query.</li>
 * </ul>
 *
 * <p>Everything else is left to the two halves. The extract engine refuses initializers it
 * cannot turn into a method and says why, and that refusal reaches the caller through the
 * recipe's own error — a second, vaguer refusal invented here would be worse than none.</p>
 */
public class ReplaceTempWithQueryTool extends AbstractRefactoringTool {

    private final ExtractMethodTool extractMethod;
    private final InlineVariableTool inlineVariable;
    private final RefactoringChangeCache cache;

    public ReplaceTempWithQueryTool(Supplier<IJdtService> serviceSupplier,
                                    RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
        this.extractMethod = new ExtractMethodTool(serviceSupplier, cache);
        this.inlineVariable = new InlineVariableTool(serviceSupplier, cache);
        this.cache = cache;
    }

    @Override
    public String getName() {
        return "replace_temp_with_query";
    }

    @Override
    public String getDescription() {
        return "Replace Temp with Query — extract a temp's initializer into a method, then "
            + "inline the temp. Delegate of extract.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file holding the temp."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the temp's declaration."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on that line."));
        properties.put("methodName", Map.of("type", "string",
            "description", "Name for the query (default: the variable's own name — which is "
                + "usually right, since a temp worth replacing was already named for what "
                + "it means)."));
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
            ICompilationUnit unit = service.getCompilationUnit(filePath);
            if (unit == null) {
                return ToolResponse.symbolNotFound("No compilation unit at " + filePathStr);
            }
            CompilationUnit ast = parse(unit);
            VariableDeclarationFragment temp = fragmentAt(ast, line, column);
            if (temp == null || !(temp.getParent() instanceof VariableDeclarationStatement)) {
                return ToolResponse.invalidParameter("position",
                    "No local variable declaration at " + filePathStr + ":" + line + ":"
                        + column);
            }
            Expression initializer = temp.getInitializer();
            if (initializer == null) {
                return ToolResponse.invalidParameter("position",
                    "'" + temp.getName() + "' has no initializer, so there is nothing to turn"
                        + " into a query.");
            }
            String name = temp.getName().getIdentifier();
            MethodDeclaration owner = enclosingMethod(temp);
            if (owner != null && reassigned(owner, name)) {
                return ToolResponse.invalidParameter("position",
                    "'" + name + "' is assigned more than once, so it is not a name for one"
                        + " value — a query returning its initializer would give the wrong"
                        + " answer after the second assignment. Split Variable (data"
                        + " kind=split_variable) is the step before this one.");
            }

            // A RECIPE CANNOT STAGE, and saying so is the only honest answer. Its second
            // step is built against the workspace the first step produced, so there is no
            // change to show a caller until the first one has already been applied — the
            // preview they asked for does not exist yet. This shipped publishing
            // auto_apply (the wrapper adds it to every refactoring schema) and silently
            // ignoring it, so a caller who asked to preview got their workspace mutated.
            // An architect watch found it. Both composed operations that predate this one
            // refuse the same way for the same reason.
            if (!getBooleanParam(arguments, "auto_apply", true)) {
                return ToolResponse.invalidParameter("auto_apply",
                    "replace_temp_with_query is COMPOSED — it extracts the initializer, then"
                        + " inlines the temp, and the second step cannot be built until the"
                        + " first has been applied. So there is no single staged change to"
                        + " preview. Run it (it reverts through one undo handle), or stage"
                        + " the halves yourself: extract kind=method on the initializer,"
                        + " then inline kind=variable on the temp.");
            }

            String methodName = getStringParam(arguments, "methodName");
            if (methodName == null || methodName.isBlank()) {
                methodName = name;
            }
            return compose(service, unit, ast, initializer, name, methodName, filePathStr);
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse compose(IJdtService service, ICompilationUnit unit, CompilationUnit ast,
                                 Expression initializer, String variableName, String methodName,
                                 String filePathStr) {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode extractArgs = mapper.createObjectNode();
        extractArgs.put("filePath", filePathStr);
        extractArgs.put("startLine", ast.getLineNumber(initializer.getStartPosition()) - 1);
        extractArgs.put("startColumn", ast.getColumnNumber(initializer.getStartPosition()));
        int end = initializer.getStartPosition() + initializer.getLength();
        extractArgs.put("endLine", ast.getLineNumber(end) - 1);
        extractArgs.put("endColumn", ast.getColumnNumber(end));
        extractArgs.put("methodName", methodName);
        // The extract's own duplicate replacement is OFF here. Row 49 defaults it on for a
        // direct extract, where finding the other occurrences is the point; inside this
        // recipe it would silently widen a two-step temp replacement into a repo-wide one,
        // and the caller asked about one temp.
        extractArgs.put("replaceDuplicates", false);

        ObjectNode inlineArgs = mapper.createObjectNode();
        inlineArgs.put("filePath", filePathStr);
        inlineArgs.put("variableName", variableName);

        List<RecipeStep> steps = List.of(
            new RecipeStep("extract", extractArgs),
            new RecipeStep("inline", inlineArgs));
        Recipe recipe = new Recipe("replace temp with query", steps);

        RecipeEngine.Result result = recipe.run((operation, args) -> {
            if ("extract".equals(operation)) {
                AbstractApplyingRefactoringTool.Preparation prep =
                    extractMethod.prepareChange(service, args);
                if (prep.error != null) {
                    throw new IllegalStateException("extract '" + methodName + "': "
                        + String.valueOf(prep.error.getError()));
                }
                return prep.change;
            }
            // BY NAME, resolved NOW. The extraction has already rewritten the line the temp
            // sits on, so a position captured before the recipe started points at different
            // text. See the class javadoc.
            ObjectNode resolved = locate(service, filePathStr, args.path("variableName").asText());
            if (resolved == null) {
                throw new IllegalStateException("could not find '"
                    + args.path("variableName").asText() + "' after the extraction — it may"
                    + " have been renamed or removed by the first step.");
            }
            AbstractApplyingRefactoringTool.Preparation prep =
                inlineVariable.prepareChange(service, resolved);
            if (prep.error != null) {
                throw new IllegalStateException("inline '" + variableName + "': "
                    + String.valueOf(prep.error.getError()));
            }
            return prep.change;
        }, service);

        if (!result.ok()) {
            return ToolResponse.error("REFACTORING_FAILED",
                "replace_temp_with_query failed: " + result.error(),
                "No changes were applied (the recipe rolled back).");
        }
        String undoChangeId = cache.put(RefactoringChangeCache.Kind.UNDO, result.compositeUndo(),
            "undo: replace temp with query", "", result.modifiedFilePaths());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("operation", getName());
        data.put("applied", true);
        data.put("filesModified", result.modifiedFilePaths());
        data.put("undoChangeId", undoChangeId);
        data.put("query", methodName);
        data.put("temp", variableName);
        data.put("summary", "replace temp with query: '" + variableName + "' became "
            + methodName + "()");
        return ToolResponse.success(data, ResponseMeta.builder()
            .totalCount(result.modifiedFilePaths().size())
            .returnedCount(result.modifiedFilePaths().size())
            .suggestedNextTools(List.of(
                "compile_workspace to verify the refactoring",
                "undo_refactoring with the undoChangeId if verification fails"))
            .build());
    }

    /** Where the named local sits in the file AS IT IS NOW. */
    private ObjectNode locate(IJdtService service, String filePathStr, String variableName)
            throws Exception {
        ICompilationUnit unit = service.getCompilationUnit(
            service.getPathUtils().resolve(filePathStr));
        if (unit == null) {
            return null;
        }
        CompilationUnit ast = parse(unit);
        VariableDeclarationFragment[] found = { null };
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                if (found[0] == null && variableName.equals(node.getName().getIdentifier())
                        && node.getParent() instanceof VariableDeclarationStatement) {
                    found[0] = node;
                }
                return true;
            }
        });
        if (found[0] == null) {
            return null;
        }
        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("filePath", filePathStr);
        args.put("line", ast.getLineNumber(found[0].getName().getStartPosition()) - 1);
        args.put("column", ast.getColumnNumber(found[0].getName().getStartPosition()));
        return args;
    }

    private static boolean reassigned(MethodDeclaration owner, String name) {
        boolean[] again = { false };
        owner.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                if (node.getLeftHandSide() instanceof SimpleName target
                        && name.equals(target.getIdentifier())) {
                    again[0] = true;
                }
                return true;
            }
        });
        return again[0];
    }

    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) {
                return method;
            }
        }
        return null;
    }

    private static VariableDeclarationFragment fragmentAt(CompilationUnit ast, int line,
                                                          int column) {
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return null;
        }
        List<VariableDeclarationFragment> hits = new ArrayList<>();
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                ASTNode statement = node.getParent();
                if (statement.getStartPosition() <= offset
                        && offset < statement.getStartPosition() + statement.getLength()) {
                    hits.add(node);
                }
                return true;
            }
        });
        return hits.isEmpty() ? null : hits.get(0);
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
