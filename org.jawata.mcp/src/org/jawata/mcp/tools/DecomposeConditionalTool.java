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
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.Statement;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ResponseMeta;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.Recipe;
import org.jawata.mcp.refactoring.RecipeEngine;
import org.jawata.mcp.refactoring.RecipeStep;
import org.jawata.mcp.refactoring.RefactoringChangeCache;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Fowler — <b>Decompose Conditional</b> (row 34's neighbour, row 8). A tangled {@code if}
 * becomes a short sentence: a named test, and a named branch on each side.
 *
 * <pre>
 *   if (date.before(SUMMER_START) || date.after(SUMMER_END)) {
 *       charge = quantity * winterRate + winterServiceCharge;
 *   } else {
 *       charge = quantity * summerRate;
 *   }
 *                              becomes
 *   if (notSummer(date)) {
 *       charge = winterCharge(quantity);
 *   } else {
 *       charge = summerCharge(quantity);
 *   }
 * </pre>
 *
 * <h2>Why it lives HERE and not on {@code apply_cleanup}</h2>
 *
 * <p>The plan put this row on {@code apply_cleanup}, and it cannot go there. That tool is
 * a SWEEP: it takes no input beyond which clean-up to run, because everything it does
 * must be decidable from the code alone. The whole value of this refactoring is the
 * names — {@code notSummer}, {@code winterCharge} — and no analysis derives them. A
 * sweep could only produce {@code if (condition1())}, which is worse than the code it
 * replaces, so its correct usage count would be zero.</p>
 *
 * <p>{@code refactor_to_pattern} is the front door that already asks the caller for
 * names: {@link ComposeMethodTool} takes a {@code methodName} per section. This is that
 * same shape pointed at a conditional, so it reuses the same recipe machinery rather than
 * inventing a second one. The caller supplies INTENT (the names); this class computes the
 * offsets, which is what earns it a class rather than being compose_method with different
 * words.</p>
 *
 * <h2>What it refuses, and why</h2>
 *
 * <ul>
 *   <li><b>An {@code else if} chain.</b> When the else branch is itself an {@code if},
 *       flattening one link of the chain into a named call says something false about the
 *       shape — the chain is one decision with several arms, and Replace Conditional with
 *       Polymorphism is the row that owns it.</li>
 *   <li><b>An assignment or increment inside the condition.</b> Extracting the condition
 *       turns its free variables into PARAMETERS, so {@code if ((x = compute()) > 0)}
 *       would assign the parameter instead of the caller's local. The code still
 *       compiles and the write silently stops happening, which is the only kind of defect
 *       this operation can produce.</li>
 *   <li><b>A part you did not name.</b> Naming is the operation; a part with no name is
 *       left exactly as it was. Asking for a name on a branch that does not exist is
 *       refused rather than ignored, because silently doing less than was asked is how a
 *       caller comes to trust a result they did not get.</li>
 *   <li><b>An empty branch.</b> There is nothing to extract, and a method whose body is
 *       empty is not an intention.</li>
 * </ul>
 *
 * <p>A branch whose statements JDT cannot extract — several locals live afterwards, an
 * unreachable {@code return} shape — is refused by the extract engine itself, and the
 * recipe rolls the whole operation back. That refusal is deliberately not pre-empted
 * here: the engine's answer is the accurate one, and a second copy of its rules in this
 * class would be the copy that goes stale.</p>
 *
 * <p>A delegate of {@link RefactorToPatternTool} (kind {@code decompose_conditional});
 * not registered as a standalone tool.</p>
 */
public class DecomposeConditionalTool extends AbstractTool
        implements ToolKindDelegate {

    /**
     * Reached as {@code refactor_to_pattern kind=decompose_conditional}. The plan had assigned
     * row 8 to {@code apply_cleanup} and it could not live there — that door is a sweep and
     * takes no per-call input, while this row's whole value is the caller's names.
     */
    @Override
    public String kindName() {
        return "decompose_conditional";
    }

    /** The bullet a client reads under {@code refactor_to_pattern} — from the door (M5). */
    @Override
    public String kindSummary() {
        return """
            TOWARD: a tangled `if` becomes a named test and a named
            branch on each side. Needs: line, column on the `if`, plus
            conditionName / thenName / elseName — name at least one; a part
            you do not name is left alone. YOU supply the names, because they
            are the refactoring. Refuses an `else if` chain (that is
            replace_conditional_with_polymorphism) and a condition that
            assigns. Applies atomically (auto_apply=false not supported).""";
    }


    private final RefactoringChangeCache cache;
    private final ExtractMethodTool extract;
    private final ObjectMapper mapper = new ObjectMapper();

    public DecomposeConditionalTool(Supplier<IJdtService> serviceSupplier,
                                    RefactoringChangeCache cache) {
        super(serviceSupplier);
        this.cache = cache;
        this.extract = new ExtractMethodTool(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "decompose_conditional";
    }

    @Override
    public String getDescription() {
        return "Decompose Conditional — replace a tangled if with a named test and a named branch on "
            + "each side. You supply the names; that is the refactoring. Delegate of "
            + "refactor_to_pattern.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the conditional."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of a caret on the `if`."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of that caret."));
        properties.put("conditionName", Map.of("type", "string",
            "description", "decompose_conditional: name for the extracted test, e.g. notSummer. "
                + "Omit to leave the condition alone."));
        properties.put("thenName", Map.of("type", "string",
            "description", "decompose_conditional: name for the extracted then-branch. "
                + "Omit to leave it alone."));
        properties.put("elseName", Map.of("type", "string",
            "description", "decompose_conditional: name for the extracted else-branch. "
                + "Omit to leave it alone."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        if (!getBooleanParam(arguments, "auto_apply", true)) {
            return ToolResponse.invalidParameter("auto_apply",
                "decompose_conditional applies atomically as a multi-step recipe; staging "
                    + "(auto_apply=false) is not supported. It applies and returns an undoChangeId.");
        }
        String filePath = getStringParam(arguments, "filePath");
        if (filePath == null || filePath.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "Required");
        }
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        if (line < 0 || column < 0) {
            return ToolResponse.invalidParameter("line/column", "Must be >= 0");
        }
        String conditionName = getStringParam(arguments, "conditionName");
        String thenName = getStringParam(arguments, "thenName");
        String elseName = getStringParam(arguments, "elseName");
        if (blank(conditionName) && blank(thenName) && blank(elseName)) {
            return ToolResponse.invalidParameter("conditionName/thenName/elseName",
                "Name at least one part. The names ARE this refactoring — without one there is "
                    + "nothing to do that would leave the code better than it was.");
        }

        try {
            ICompilationUnit cu = service.getCompilationUnit(Path.of(filePath));
            if (cu == null) {
                return ToolResponse.fileNotFound(filePath);
            }
            CompilationUnit ast = parse(cu);
            int offset = ast.getPosition(line + 1, column);
            if (offset < 0) {
                return ToolResponse.invalidParameter("position", "Invalid position");
            }
            ASTNode at = new NodeFinder(ast, offset, 0).getCoveringNode();
            IfStatement branch = enclosingIf(at);
            if (branch == null) {
                // A SYMBOL resolves to the member's NAME, where there is no `if` — and a
                // finding names a method, not a line inside one. So when the position is
                // not in a conditional, look for the method's own: exactly one is
                // unambiguous, and anything else must be pointed at rather than guessed.
                List<IfStatement> inMethod = topLevelIfsIn(enclosingMethod(at));
                if (inMethod.size() == 1) {
                    branch = inMethod.get(0);
                } else if (inMethod.isEmpty()) {
                    return ToolResponse.invalidParameter("position",
                        "No if statement here, and none in the enclosing method either.");
                } else {
                    return ToolResponse.invalidParameter("position",
                        "The enclosing method has " + inMethod.size() + " conditionals and"
                            + " nothing here says which. Point at one with line/column —"
                            + " picking for you would decompose a conditional you did not"
                            + " name.");
                }
            }
            if (branch.getElseStatement() instanceof IfStatement) {
                return ToolResponse.invalidParameter("position",
                    "This is an `else if` chain. Flattening one link of it into a named call "
                        + "describes the code wrongly — the chain is one decision with several "
                        + "arms. Use refactor_to_pattern kind=replace_conditional_with_polymorphism.")
                    // D3a: the SIBLING operation, pointed at the same place the caller was.
                    .withNextStep(new org.jawata.mcp.models.NextStep(
                        "refactor_to_pattern kind=replace_conditional_with_polymorphism",
                        org.jawata.mcp.models.CodeAddress.of(arguments), null));
            }
            if (writesInside(branch.getExpression())) {
                return ToolResponse.invalidParameter("position",
                    "The condition assigns or increments. Extracting it turns its variables into "
                        + "parameters, so the write would land on the parameter and silently stop "
                        + "affecting the caller's local — while still compiling.");
            }

            List<Part> parts = new ArrayList<>();
            if (!blank(conditionName)) {
                parts.add(Part.of(ast, branch.getExpression(), conditionName));
            }
            ToolResponse bad = addBranch(parts, ast, branch.getThenStatement(), thenName, "then");
            if (bad != null) {
                return bad;
            }
            bad = addBranch(parts, ast, branch.getElseStatement(), elseName, "else");
            if (bad != null) {
                return bad;
            }

            // Bottom-up, exactly as compose_method: extracting the lowest part first
            // leaves every higher part's coordinates valid.
            parts.sort(Comparator.comparingInt((Part p) -> p.startLine)
                .thenComparingInt(p -> p.startColumn).reversed());

            List<RecipeStep> steps = new ArrayList<>();
            for (Part p : parts) {
                ObjectNode a = mapper.createObjectNode();
                a.put("filePath", filePath);
                a.put("startLine", p.startLine);
                a.put("startColumn", p.startColumn);
                a.put("endLine", p.endLine);
                a.put("endColumn", p.endColumn);
                a.put("methodName", p.methodName);
                steps.add(new RecipeStep("extract", a));
            }

            RecipeEngine.Result result =
                new Recipe("decompose conditional", steps).run((operation, args) -> {
                    AbstractApplyingRefactoringTool.Preparation prep =
                        extract.prepareChange(service, args);
                    if (prep.error != null) {
                        throw new IllegalStateException("extract '"
                            + args.path("methodName").asText("?") + "': "
                            + String.valueOf(prep.error.getError()));
                    }
                    return prep.change;
                }, service);
            if (!result.ok()) {
                return ToolResponse.error("REFACTORING_FAILED",
                    "decompose_conditional failed: " + result.error(),
                    "No changes were applied (the recipe rolled back). A branch JDT cannot "
                        + "extract — several locals still live after it, or a return shape it "
                        + "cannot honour — is refused by the extract engine, and its message "
                        + "above says which part and why.");
            }

            String undoChangeId = cache.put(RefactoringChangeCache.Kind.UNDO, result.compositeUndo(),
                "undo: decompose conditional", "", result.modifiedFilePaths());

            Map<String, Object> data = new LinkedHashMap<>();
            data.put("operation", getName());
            data.put("applied", true);
            data.put("filesModified", result.modifiedFilePaths());
            data.put("undoChangeId", undoChangeId);
            // mcp#80 — see ComposeMethodTool: the composite's final state was verified by
            // nothing, and the response carried no diff.
            data.put("compileVerified", result.compileVerified());
            data.put("introducedErrors", result.introducedErrors());
            // Absent rather than null — see ComposeMethodTool.
            if (result.diff() != null) {
                data.put("diff", result.diff());
            }
            data.put("partsExtracted", parts.size());
            data.put("summary", "decompose conditional: extracted " + parts.size()
                + " named part(s) from the if");
            return ToolResponse.success(data, ResponseMeta.builder()
                .totalCount(result.modifiedFilePaths().size())
                .returnedCount(result.modifiedFilePaths().size())
                .suggestedNextTools(List.of(
                    "compile_workspace to verify the refactoring",
                    "undo_refactoring with the undoChangeId if verification fails"))
                .build());
        } catch (Exception e) {
            return ToolResponse.internalError(e);
        }
    }

    /**
     * Add a branch as a part, or return the refusal.
     *
     * <p>A name for a branch that is not there is an error rather than a no-op: the caller
     * asked for something the code cannot give, and doing less than was asked without
     * saying so is how a caller comes to trust a result they did not get.</p>
     */
    private static ToolResponse addBranch(List<Part> parts, CompilationUnit ast,
                                          Statement statement, String name, String which) {
        if (blank(name)) {
            return null;
        }
        if (statement == null) {
            return ToolResponse.invalidParameter(which + "Name",
                "There is no " + which + " branch on this if, so there is nothing to name.");
        }
        if (statement instanceof Block block) {
            List<?> inner = block.statements();
            if (inner.isEmpty()) {
                return ToolResponse.invalidParameter(which + "Name",
                    "The " + which + " branch is empty; a method with an empty body is not an "
                        + "intention.");
            }
            ASTNode first = (ASTNode) inner.get(0);
            ASTNode last = (ASTNode) inner.get(inner.size() - 1);
            parts.add(Part.between(ast, first.getStartPosition(),
                last.getStartPosition() + last.getLength(), name));
            return null;
        }
        parts.add(Part.of(ast, statement, name));
        return null;
    }

    /** Does this expression write to anything? */
    private static boolean writesInside(ASTNode expression) {
        boolean[] found = { false };
        expression.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                found[0] = true;
                return false;
            }

            @Override
            public boolean visit(PrefixExpression node) {
                if (node.getOperator() == PrefixExpression.Operator.INCREMENT
                        || node.getOperator() == PrefixExpression.Operator.DECREMENT) {
                    found[0] = true;
                    return false;
                }
                return true;
            }

            @Override
            public boolean visit(PostfixExpression node) {
                found[0] = true;
                return false;
            }
        });
        return found[0];
    }

    /** The method the position sits in, or null. */
    private static org.eclipse.jdt.core.dom.MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof org.eclipse.jdt.core.dom.MethodDeclaration method) {
                return method;
            }
        }
        return null;
    }

    /**
     * The method's own conditionals, not counting the links of an else-if chain.
     *
     * <p>A chain is ONE decision, so counting its links would report a method holding a
     * single {@code if/else if/else} as ambiguous — and that shape is refused anyway, with
     * a message that names the right operation.</p>
     */
    private static List<IfStatement> topLevelIfsIn(org.eclipse.jdt.core.dom.MethodDeclaration method) {
        List<IfStatement> found = new ArrayList<>();
        if (method == null) {
            return found;
        }
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(IfStatement node) {
                if (!(node.getParent() instanceof IfStatement parent)
                        || parent.getElseStatement() != node) {
                    found.add(node);
                }
                return true;
            }
        });
        return found;
    }

    private static IfStatement enclosingIf(ASTNode node) {
        for (ASTNode n = node; n != null; n = n.getParent()) {
            if (n instanceof IfStatement found) {
                return found;
            }
        }
        return null;
    }

    private static CompilationUnit parse(ICompilationUnit cu) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    /** One range to extract, in the zero-based coordinates the extract engine takes. */
    private record Part(int startLine, int startColumn, int endLine, int endColumn,
                        String methodName) {

        static Part of(CompilationUnit ast, ASTNode node, String name) {
            return between(ast, node.getStartPosition(),
                node.getStartPosition() + node.getLength(), name);
        }

        static Part between(CompilationUnit ast, int start, int end, String name) {
            return new Part(ast.getLineNumber(start) - 1, ast.getColumnNumber(start),
                ast.getLineNumber(end) - 1, ast.getColumnNumber(end), name);
        }
    }
}
