package org.jawata.mcp.tools.data;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ExpressionStatement;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.PostfixExpression;
import org.eclipse.jdt.core.dom.PrefixExpression;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.PreparedRefactoring;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.AbstractRefactoringTool;
import org.jawata.mcp.tools.ToolKindDelegate;
import org.jawata.mcp.tools.shared.FormatterOptions;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * {@code data kind=split_variable} — Fowler row 65, Split Variable.
 *
 * <p>A variable assigned twice is usually two variables wearing one name. The first value
 * means one thing, the second means another, and the reader has to hold both and work out
 * which is live at each line. Giving the second its own name and its own declaration removes
 * that work, and it makes each variable assigned exactly once — which is the property that
 * lets a later reader move, extract or inline the code around it.</p>
 *
 * <h2>It is also Remove Assignment to Parameter, and that is one operation rather than two</h2>
 *
 * <p>Fowler lists assigning to a parameter as its own smell, and the cure is identical: the
 * parameter is the first value, the assignment begins a second, and the second gets a local of
 * its own. So this row handles both from one implementation, and the only difference a caller
 * sees is what they pointed at.</p>
 *
 * <h2>The NAME is required, because the name is the refactoring</h2>
 *
 * <p>There is no default and deliberately so. The point of the split is that the second value
 * MEANS something the first does not, and saying what is the whole content of the change —
 * a generated {@code temp2} would perform the mechanics and deliver none of the value. The
 * same reasoning that makes {@code extract kind=split_phase} take its boundary and
 * {@code compose_method} take a name per section.</p>
 *
 * <h2>What it refuses, and why each refusal is not merely caution</h2>
 *
 * <ul>
 *   <li><b>A variable assigned once.</b> There is nothing to split; the refusal says how many
 *       assignments were found so a caller can see what it saw.</li>
 *   <li><b>More than one reassignment.</b> Three values need two names and this API carries
 *       one. Run it again on the result rather than have the tool invent the rest.</li>
 *   <li><b>An ACCUMULATOR</b> — an assignment inside a LOOP, or an increment. Each pass reads
 *       what the last one left, so these are not two values with one name; they are one value
 *       being built. Fowler excludes the case by name.
 *       <p><b>The test is the loop, not self-reference, and the first version had that
 *       wrong.</b> It refused any assignment whose value read the variable — which kills
 *       {@code prefix = prefix.trim()}, Fowler's own Remove Assignment to Parameter example,
 *       and every other straight-line derivation, all of which split correctly. Self-reference
 *       merely correlates with accumulation; the loop is what makes it true.</p></li>
 *   <li><b>A reassignment in a different block from the declaration.</b> "Every use after the
 *       assignment" is a statement about the source order, and source order only means
 *       execution order inside one block. An assignment inside an {@code if} with reads after
 *       it would have those reads rewritten to a variable that may never have been declared.
 *       </li>
 *   <li><b>A name already visible in the method.</b> The declaration would shadow or clash.
 *       </li>
 * </ul>
 *
 * <h2>Its route, or rather its absence</h2>
 *
 * <p>UNROUTED. No shipped detector reports a variable serving two purposes, and one is not
 * among Stage 8's six. It could not be run from a finding even if the detector existed,
 * because the operation's entire input is the NAME the second value should carry, and no
 * finding carries a name — the same reason {@code refactor_to_pattern
 * kind=decompose_conditional} is recorded unrouted in {@code CureCatalog}.</p>
 */
public class SplitVariableTool extends AbstractRefactoringTool implements ToolKindDelegate {

    public SplitVariableTool(Supplier<IJdtService> serviceSupplier,
                             RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String kindName() {
        return "split_variable";
    }

    @Override
    public String kindSummary() {
        return """
            split a variable assigned twice into two, each assigned once — and,
            from the same implementation, remove an assignment to a PARAMETER,
            which Fowler lists separately and cures identically. `newName` is
            REQUIRED: the second value means something the first does not, and
            saying what is the whole content of the change. Refuses a variable
            assigned once (nothing to split), more than one reassignment (three
            values need two names), an ACCUMULATOR — an assignment inside a LOOP,
            or an increment — where each pass reads what the last one left, a
            reassignment in a different block from the declaration, and a name
            already visible. Each refusal names which. Note the accumulator test
            is the LOOP and not self-reference: `prefix = prefix.trim()` reads
            itself and splits correctly.""";
    }

    /** Not structural: nothing outside the method can see a local. */
    @Override
    public boolean isStructural() {
        return false;
    }

    @Override
    public String getName() {
        return "split_variable";
    }

    @Override
    public String getDescription() {
        return "Split Variable — give a variable's second value its own name and declaration, "
            + "so each is assigned once. Also removes an assignment to a parameter. "
            + "Delegate of data.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the method."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the variable — its declaration, its parameter, "
                + "or any use of it."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column on the variable's name."));
        properties.put("newName", Map.of("type", "string",
            "description", "REQUIRED. Name for the SECOND value. There is no default: saying "
                + "what the second value means is the whole point of the split, and a "
                + "generated name would do the mechanics and deliver none of it."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "newName"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    public ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        String newName = getStringParam(arguments, "newName");
        if (filePathStr == null || filePathStr.isBlank()) {
            return ToolResponse.invalidParameter("filePath", "filePath is required");
        }
        if (line < 0 || column < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers");
        }
        if (newName == null || newName.isBlank()) {
            return ToolResponse.invalidParameter("newName",
                "newName is required. The second value means something the first does not,"
                    + " and naming it IS this refactoring — a generated name would perform"
                    + " the mechanics and deliver none of the point.");
        }
        try {
            Path filePath = service.getPathUtils().resolve(filePathStr);
            ICompilationUnit unit = service.getCompilationUnit(filePath);
            if (unit == null) {
                return ToolResponse.symbolNotFound("No compilation unit at " + filePathStr);
            }
            return split(service, unit, line, column, newName, arguments);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(SplitVariableTool.class)
                .warn("split_variable failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse split(IJdtService service, ICompilationUnit unit, int line, int column,
                               String newName, JsonNode arguments) throws Exception {
        CompilationUnit ast = parse(unit);
        // JDT counts lines from one; every jawata coordinate is zero-based.
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "that position is not inside " + unit.getElementName());
        }
        SimpleName at = nameAt(ast, offset);
        if (at == null || !(at.resolveBinding() instanceof IVariableBinding variable)
                || variable.isField()) {
            return ToolResponse.invalidParameter("position",
                "that position is not a local variable or a parameter. Split Variable acts on"
                    + " one whose value changes; a FIELD is a different question and a"
                    + " different operation.");
        }
        MethodDeclaration method = enclosingMethod(at);
        if (method == null || method.getBody() == null) {
            return ToolResponse.invalidParameter("position",
                "the variable is not inside a method body with source.");
        }

        List<SimpleName> uses = usesOf(method, variable);
        ASTNode declaration = declarationOf(method, variable);
        List<Assignment> writes = new ArrayList<>();
        for (SimpleName use : uses) {
            if (use.getParent() instanceof Assignment assignment
                    && assignment.getLeftHandSide() == use) {
                writes.add(assignment);
            }
            // An INCREMENT is an accumulator by construction — the new value is the old one.
            if (use.getParent() instanceof PrefixExpression prefix
                    && (prefix.getOperator() == PrefixExpression.Operator.INCREMENT
                        || prefix.getOperator() == PrefixExpression.Operator.DECREMENT)
                    || use.getParent() instanceof PostfixExpression) {
                return ToolResponse.invalidParameter("position",
                    "'" + variable.getName() + "' is incremented, so its second value is"
                        + " computed FROM its first. That is one thing being built rather than"
                        + " two things sharing a name, and Fowler excludes it by name —"
                        + " splitting it would change what the code computes.");
            }
        }
        if (writes.isEmpty()) {
            return ToolResponse.invalidParameter("position",
                "'" + variable.getName() + "' is never reassigned"
                    + (declaration == null ? "" : " after its declaration")
                    + ", so there is nothing to split. It already has one value and one"
                    + " meaning.");
        }
        if (writes.size() > 1) {
            return ToolResponse.invalidParameter("position",
                "'" + variable.getName() + "' is reassigned " + writes.size() + " times, so it"
                    + " carries " + (writes.size() + 1) + " values and needs " + writes.size()
                    + " names. This call takes one. Split the first, then run it again on the"
                    + " result — which is also how a reader would read it.");
        }

        Assignment write = writes.get(0);
        if (write.getOperator() != Assignment.Operator.ASSIGN) {
            return ToolResponse.invalidParameter("position",
                "'" + variable.getName() + "' is compound-assigned (" + write.getOperator()
                    + "), so its second value is computed from its first. That is one thing"
                    + " being built rather than two sharing a name.");
        }
        // ACCUMULATION IS A PROPERTY OF THE LOOP, NOT OF SELF-REFERENCE, and the first
        // version had it wrong. It refused any assignment whose value read the variable —
        // which kills `prefix = prefix.trim()`, Fowler's own Remove Assignment to Parameter
        // example, and every other straight-line derivation, all of which split correctly.
        // What actually breaks is a loop: the next iteration reads the ORIGINAL variable,
        // so renaming the later uses changes what the code computes. That is the condition,
        // and it is checked directly rather than through a proxy that correlates with it.
        ASTNode loop = enclosingLoop(write, method);
        if (loop != null && !contains(loop, declaration)) {
            return ToolResponse.invalidParameter("position",
                "the assignment to '" + variable.getName() + "' is inside a LOOP and the"
                    + " variable is declared OUTSIDE it, so it accumulates across iterations —"
                    + " each pass reads what the last one left. Those are not two values with"
                    + " one name; they are one value being built, which Fowler excludes by"
                    + " name.");
        }
        if (!(write.getParent() instanceof ExpressionStatement statement)) {
            return ToolResponse.invalidParameter("position",
                "the assignment to '" + variable.getName() + "' is part of a larger"
                    + " expression rather than a statement of its own, so it cannot become a"
                    + " declaration.");
        }
        // SOURCE ORDER ONLY MEANS EXECUTION ORDER INSIDE ONE BLOCK. An assignment nested in a
        // conditional, with reads after it, would have those reads rewritten to a variable
        // that may never have been declared on the path that reaches them.
        if (enclosingBlock(statement) != declaringBlock(declaration, method)) {
            return ToolResponse.invalidParameter("position",
                "the assignment to '" + variable.getName() + "' is in a different block from"
                    + " its declaration, so 'every use after it' is not something the source"
                    + " order settles — a use reached without running the assignment would be"
                    + " rewritten to a variable that was never declared.");
        }
        for (SimpleName visible : usesOfName(method, newName)) {
            if (visible != null) {
                return ToolResponse.invalidParameter("newName",
                    "'" + newName + "' is already used in " + method.getName().getIdentifier()
                        + ", so the new declaration would clash with it or shadow it.");
            }
        }

        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        ImportRewrite imports = ImportRewrite.create(ast, true);
        String type = imports.addImport(variable.getType());
        String source = unit.getSource();

        // The assignment becomes a DECLARATION of the second value.
        rewrite.replace(statement, rewrite.createStringPlaceholder(
            type + " " + newName + " = " + text(source, write.getRightHandSide()) + ";",
            ASTNode.VARIABLE_DECLARATION_STATEMENT), null);

        // Every use AFTER it takes the new name. The write's own left-hand side is not one of
        // them — it has just become the new declaration's name.
        int rewritten = 0;
        for (SimpleName use : uses) {
            if (use.getStartPosition() > statement.getStartPosition() + statement.getLength()) {
                rewrite.replace(use, ast.getAST().newSimpleName(newName), null);
                rewritten++;
            }
        }
        if (rewritten == 0) {
            return ToolResponse.invalidParameter("position",
                "nothing reads '" + variable.getName() + "' after the assignment, so the"
                    + " second value is never used and the assignment is dead rather than a"
                    + " second meaning. Remove it instead (apply_cleanup"
                    + " kind=remove_dead_code).");
        }

        List<TextEdit> edits = new ArrayList<>();
        edits.add(rewrite.rewriteAST(new Document(source), FormatterOptions.forGeneratedCode(ast)));
        if (imports.hasRecordedChanges()) {
            edits.add(imports.rewriteImports(null));
        }
        Map<IFile, List<TextEdit>> byFile = new LinkedHashMap<>();
        byFile.put((IFile) unit.getResource(), edits);

        boolean isParameter = declaration instanceof SingleVariableDeclaration;
        String label = "split variable " + variable.getName() + " into " + newName + " ("
            + rewritten + " later use(s) rewritten"
            + (isParameter ? "; the assignment to the PARAMETER is gone" : "") + ")";
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(ChangeEngine.fromFileEdits(label, byFile), label),
            "split_variable", arguments);
    }

    /**
     * The loop the node sits inside, or null — searched up to the method, not to the block.
     *
     * <p>Stopping at the enclosing BLOCK would miss a braceless body ({@code while (c) x =
     * f();}), where the assignment's nearest block is the method's own and the block check
     * therefore passes. Walking to the method declaration catches that shape, which is the
     * one a block-based test is blind to.</p>
     */
    private static ASTNode enclosingLoop(ASTNode node, MethodDeclaration method) {
        for (ASTNode at = node; at != null && at != method; at = at.getParent()) {
            if (at instanceof org.eclipse.jdt.core.dom.ForStatement
                    || at instanceof org.eclipse.jdt.core.dom.EnhancedForStatement
                    || at instanceof org.eclipse.jdt.core.dom.WhileStatement
                    || at instanceof org.eclipse.jdt.core.dom.DoStatement) {
                return at;
            }
        }
        return null;
    }

    private static List<SimpleName> usesOf(MethodDeclaration method, IVariableBinding variable) {
        List<SimpleName> uses = new ArrayList<>();
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.resolveBinding() instanceof IVariableBinding other
                        && other.isEqualTo(variable)) {
                    uses.add(node);
                }
                return true;
            }
        });
        return uses;
    }

    /** Any use of a NAME in the method, whatever it binds to — the shadowing check. */
    private static List<SimpleName> usesOfName(MethodDeclaration method, String name) {
        List<SimpleName> uses = new ArrayList<>();
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                if (node.getIdentifier().equals(name)) {
                    uses.add(node);
                }
                return true;
            }
        });
        return uses;
    }

    /** The declaration node — a fragment for a local, a parameter declaration for a parameter. */
    private static ASTNode declarationOf(MethodDeclaration method, IVariableBinding variable) {
        ASTNode[] found = new ASTNode[1];
        method.accept(new ASTVisitor() {
            @Override
            public boolean visit(VariableDeclarationFragment node) {
                if (found[0] == null && node.resolveBinding() != null
                        && node.resolveBinding().isEqualTo(variable)) {
                    found[0] = node;
                }
                return true;
            }

            @Override
            public boolean visit(SingleVariableDeclaration node) {
                if (found[0] == null && node.resolveBinding() != null
                        && node.resolveBinding().isEqualTo(variable)) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    /** Whether {@code inner} sits anywhere inside {@code outer}. */
    private static boolean contains(ASTNode outer, ASTNode inner) {
        for (ASTNode at = inner; at != null; at = at.getParent()) {
            if (at == outer) {
                return true;
            }
        }
        return false;
    }

    /**
     * The block the variable is declared in — the METHOD BODY for a parameter.
     *
     * <p>A parameter has no block of its own, and treating it as if it did would refuse every
     * Remove Assignment to Parameter case, which is half of what this row is for.</p>
     */
    private static Block declaringBlock(ASTNode declaration, MethodDeclaration method) {
        if (declaration == null) {
            return method.getBody();
        }
        // A for-each's own variable is ALSO a SingleVariableDeclaration, and it is not a
        // method parameter: its scope is the loop body, so that is the block its uses live
        // in. Treating every SingleVariableDeclaration as a parameter refused upstream's
        // `for (String word : words) { word = word.toLowerCase(); ... }` — a legitimate
        // split, and one this row is meant to perform.
        if (declaration instanceof SingleVariableDeclaration
                && declaration.getParent() instanceof org.eclipse.jdt.core.dom
                    .EnhancedForStatement each) {
            return enclosingBlock(each.getBody());
        }
        if (declaration instanceof SingleVariableDeclaration) {
            return method.getBody();
        }
        return enclosingBlock(declaration);
    }

    private static Block enclosingBlock(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof Block block) {
                return block;
            }
        }
        return null;
    }

    private static MethodDeclaration enclosingMethod(ASTNode node) {
        for (ASTNode at = node; at != null; at = at.getParent()) {
            if (at instanceof MethodDeclaration method) {
                return method;
            }
        }
        return null;
    }

    private static SimpleName nameAt(CompilationUnit ast, int offset) {
        SimpleName[] found = new SimpleName[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(SimpleName node) {
                int start = node.getStartPosition();
                if (found[0] == null && offset >= start && offset <= start + node.getLength()) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    private static String text(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength());
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
