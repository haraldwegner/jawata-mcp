package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IBinding;
import org.eclipse.jdt.core.dom.IVariableBinding;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.Statement;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
import org.eclipse.jface.text.Document;
import org.eclipse.text.edits.TextEdit;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.ChangeEngine;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.shared.FormatterOptions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Fowler — <b>Split Phase</b> (row 64). A function doing two different jobs in sequence
 * becomes two functions that communicate through an intermediate data structure.
 *
 * <p>A delegate of {@link ExtractTool} (kind {@code split_phase}); not registered
 * standalone.</p>
 *
 * <h2>The boundary is a parameter, and it has to be</h2>
 *
 * <p>Where one phase ends and the next begins is a judgement about what the code MEANS —
 * parsing input then computing on it, gathering data then formatting it. Nothing in the
 * syntax marks it, and a tool that guessed would split a function at a place its author
 * would not recognise. So {@code boundaryLine} is required, exactly as {@code fields[}] is
 * required by {@code extract kind=class} and for the same reason.</p>
 *
 * <h2>What the intermediate carries is DERIVED, not asked</h2>
 *
 * <p>That part the code does say: every local declared before the boundary and read after
 * it. Those become the components of a generated record, phase one returns it, phase two
 * takes it, and the original method becomes the two calls composed. A local that phase two
 * never reads stays inside phase one where it belongs, which is usually most of them —
 * that shrinkage is the readability the refactoring buys.</p>
 *
 * <h2>What it refuses</h2>
 *
 * <ul>
 *   <li><b>A return before the boundary.</b> Then the first part is not a phase, it is an
 *       early exit, and phase two would not always run.</li>
 *   <li><b>Phase two ASSIGNING to a phase-one local.</b> The carrier is a record and its
 *       components are final; a re-assignment needs a mutable carrier, which is a different
 *       design and the caller's decision.</li>
 *   <li><b>A boundary that is not between two top-level statements</b> of the method.</li>
 * </ul>
 */
public class SplitPhaseTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Reached as {@code extract kind=split_phase}. */
    @Override
    public String kindName() {
        return "split_phase";
    }

    /** Structural: it generates a carrier record and leaves two methods where one stood. */
    @Override
    public boolean isStructural() {
        return true;
    }


    public SplitPhaseTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    @Override
    public String getName() {
        return "split_phase";
    }

    @Override
    public String getDescription() {
        return "Split Phase — split a function at a boundary the caller names into two "
            + "phases joined by a generated record. Delegate of extract.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file holding the function."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of a caret in the function."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column of that caret."));
        properties.put("boundaryLine", Map.of("type", "integer",
            "description", "Zero-based line of the FIRST statement of the second phase. "
                + "Required and undefaulted: where one job ends and the next begins is a "
                + "judgement about meaning that nothing in the syntax marks."));
        properties.put("firstName", Map.of("type", "string",
            "description", "Name for the first phase (default: <function>Phase1)."));
        properties.put("secondName", Map.of("type", "string",
            "description", "Name for the second phase (default: <function>Phase2)."));
        properties.put("intermediateName", Map.of("type", "string",
            "description", "Name for the record the phases pass between them "
                + "(default: <Function>Intermediate)."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column", "boundaryLine"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    protected Preparation prepareChange(IJdtService service, JsonNode arguments)
            throws Exception {
        String filePathStr = getStringParam(arguments, "filePath");
        int line = getIntParam(arguments, "line", -1);
        int column = getIntParam(arguments, "column", -1);
        int boundaryLine = getIntParam(arguments, "boundaryLine", -1);

        if (filePathStr == null || filePathStr.isBlank()) {
            return Preparation.fail(
                ToolResponse.invalidParameter("filePath", "filePath is required"));
        }
        if (line < 0 || column < 0) {
            return Preparation.fail(ToolResponse.invalidCoordinates(line, column,
                "line and column are required and must be zero-based non-negative integers"));
        }
        if (boundaryLine < 0) {
            return Preparation.fail(ToolResponse.invalidParameter("boundaryLine",
                "boundaryLine is required — the zero-based line of the first statement of"
                    + " the second phase. There is no default because where one job ends and"
                    + " the next begins is a judgement about what the code means."));
        }

        Path filePath = service.getPathUtils().resolve(filePathStr);
        ICompilationUnit unit = service.getCompilationUnit(filePath);
        if (unit == null) {
            return Preparation.fail(
                ToolResponse.symbolNotFound("No compilation unit at " + filePathStr));
        }
        CompilationUnit ast = parse(unit);
        MethodDeclaration method = methodAt(ast, line, column);
        if (method == null || method.getBody() == null) {
            return Preparation.fail(ToolResponse.invalidParameter("position",
                "No method with a body at " + filePathStr + ":" + line + ":" + column));
        }
        return split(service, unit, ast, method, boundaryLine, arguments);
    }

    private Preparation split(IJdtService service, ICompilationUnit unit, CompilationUnit ast,
                              MethodDeclaration method, int boundaryLine, JsonNode arguments)
            throws Exception {
        List<?> statements = method.getBody().statements();
        int boundary = -1;
        for (int i = 0; i < statements.size(); i++) {
            Statement statement = (Statement) statements.get(i);
            if (ast.getLineNumber(statement.getStartPosition()) - 1 == boundaryLine) {
                boundary = i;
                break;
            }
        }
        if (boundary <= 0 || boundary >= statements.size()) {
            return Preparation.fail(ToolResponse.invalidParameter("boundaryLine",
                boundary == 0
                    ? "the boundary is at the method's FIRST statement, which leaves phase"
                        + " one empty. Name the first statement of the SECOND phase."
                    : "line " + boundaryLine + " is not the start of a top-level statement of "
                        + method.getName() + ". A phase boundary has to fall between two of"
                        + " the method's own statements, not inside one."));
        }

        List<Statement> first = new ArrayList<>();
        List<Statement> second = new ArrayList<>();
        for (int i = 0; i < statements.size(); i++) {
            (i < boundary ? first : second).add((Statement) statements.get(i));
        }
        for (Statement statement : first) {
            if (containsReturn(statement)) {
                return Preparation.fail(ToolResponse.invalidParameter("boundaryLine",
                    "the first part returns, so it is an early exit rather than a phase —"
                        + " phase two would not always run, and composing the two calls would"
                        + " change what the function does."));
            }
        }

        // WHAT CROSSES. Every local declared before the boundary and read after it. Nothing
        // is asked here, because the code says it.
        Map<String, String> carried = new LinkedHashMap<>();
        Map<String, VariableDeclarationFragment> declared = new LinkedHashMap<>();
        for (Statement statement : first) {
            if (statement instanceof VariableDeclarationStatement declaration) {
                for (Object fragment : declaration.fragments()) {
                    VariableDeclarationFragment f = (VariableDeclarationFragment) fragment;
                    declared.put(f.getName().getIdentifier(), f);
                    carried.put(f.getName().getIdentifier(), declaration.getType().toString());
                }
            }
        }
        Set<String> readLater = new LinkedHashSet<>();
        Set<String> writtenLater = new LinkedHashSet<>();
        for (Statement statement : second) {
            collectNames(statement, declared.keySet(), readLater, writtenLater);
        }
        if (!writtenLater.isEmpty()) {
            return Preparation.fail(ToolResponse.invalidParameter("boundaryLine",
                "the second phase assigns to '" + writtenLater.iterator().next() + "', which"
                    + " the first phase declared. The carrier between phases is a record and"
                    + " its components are final; a phase that writes back needs a mutable"
                    + " carrier, and that is a design decision rather than a mechanical"
                    + " split."));
        }
        carried.keySet().retainAll(readLater);

        String name = method.getName().getIdentifier();
        String firstName = orDefault(getStringParam(arguments, "firstName"), name + "Phase1");
        String secondName = orDefault(getStringParam(arguments, "secondName"), name + "Phase2");
        String intermediateName = orDefault(getStringParam(arguments, "intermediateName"),
            Character.toUpperCase(name.charAt(0)) + name.substring(1) + "Intermediate");

        // Parameters the second phase still reads come across as parameters of their own;
        // the first phase takes whatever it reads. Both are derived the same way.
        List<SingleVariableDeclaration> parameters = new ArrayList<>();
        for (Object parameter : method.parameters()) {
            parameters.add((SingleVariableDeclaration) parameter);
        }
        List<SingleVariableDeclaration> secondPhaseParameters = new ArrayList<>();
        for (SingleVariableDeclaration parameter : parameters) {
            Set<String> reads = new LinkedHashSet<>();
            Set<String> writes = new LinkedHashSet<>();
            for (Statement statement : second) {
                collectNames(statement, Set.of(parameter.getName().getIdentifier()),
                    reads, writes);
            }
            if (!reads.isEmpty() || !writes.isEmpty()) {
                secondPhaseParameters.add(parameter);
            }
        }

        String source = unit.getSource();
        ASTRewrite rewrite = ASTRewrite.create(ast.getAST());
        ListRewrite body = rewrite.getListRewrite(method.getBody(), Block.STATEMENTS_PROPERTY);
        for (Statement statement : statements.stream().map(Statement.class::cast).toList()) {
            rewrite.remove(statement, null);
        }
        StringBuilder call = new StringBuilder();
        call.append(intermediateName).append(" intermediate = ").append(firstName).append('(');
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                call.append(", ");
            }
            call.append(parameters.get(i).getName().getIdentifier());
        }
        call.append(");");
        body.insertLast((Statement) rewrite.createStringPlaceholder(
            call.toString(), ASTNode.EXPRESSION_STATEMENT), null);
        StringBuilder tail = new StringBuilder();
        boolean returns = !"void".equals(String.valueOf(method.getReturnType2()));
        tail.append(returns ? "return " : "").append(secondName).append("(intermediate");
        for (SingleVariableDeclaration parameter : secondPhaseParameters) {
            tail.append(", ").append(parameter.getName().getIdentifier());
        }
        tail.append(");");
        body.insertLast((Statement) rewrite.createStringPlaceholder(
            tail.toString(), ASTNode.EXPRESSION_STATEMENT), null);

        ListRewrite members = rewrite.getListRewrite(
            (ASTNode) method.getParent(),
            ((org.eclipse.jdt.core.dom.AbstractTypeDeclaration) method.getParent())
                .getBodyDeclarationsProperty());
        members.insertLast(rewrite.createStringPlaceholder(
            buildRecord(intermediateName, carried), ASTNode.TYPE_DECLARATION), null);
        // STATIC IF THE ORIGINAL IS. The phases are called from the original method's own
        // body, so a static method calling instance phases does not compile. Every fixture
        // written for this row used an instance method, so nothing showed it until a fork
        // slice pointed at MapReduce.mapReduce — which is static, as a utility class's
        // method usually is.
        String modifiers =
            org.eclipse.jdt.core.dom.Modifier.isStatic(method.getModifiers())
                ? "private static " : "private ";
        members.insertLast(rewrite.createStringPlaceholder(
            buildFirstPhase(modifiers, firstName, intermediateName, parameters, first,
                carried, source),
            ASTNode.METHOD_DECLARATION), null);
        members.insertLast(rewrite.createStringPlaceholder(
            buildSecondPhase(modifiers, secondName, intermediateName,
                method.getReturnType2().toString(), secondPhaseParameters, second, carried,
                source),
            ASTNode.METHOD_DECLARATION), null);

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        edits.put((IFile) unit.getResource(),
            List.of(rewrite.rewriteAST(new Document(source),
                FormatterOptions.forGeneratedCode(ast))));

        String label = "split phase " + name + " into " + firstName + " and " + secondName
            + " (" + carried.size() + " value(s) carried across)";
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("firstPhase", firstName);
        extras.put("secondPhase", secondName);
        extras.put("intermediate", intermediateName);
        // Derived, not asked — and worth reporting, because the SHRINKAGE is the point: the
        // locals that did not cross stayed in phase one.
        extras.put("carried", carried);
        extras.put("localsDeclaredInFirstPhase", declared.size());
        return Preparation.of(ChangeEngine.fromFileEdits(label, edits), label, extras);
    }

    private static String buildRecord(String name, Map<String, String> carried) {
        StringBuilder out = new StringBuilder();
        out.append("/** What the first phase hands the second. Generated by Split Phase. */\n");
        out.append("private record ").append(name).append('(');
        int i = 0;
        for (Map.Entry<String, String> component : carried.entrySet()) {
            if (i++ > 0) {
                out.append(", ");
            }
            out.append(component.getValue()).append(' ').append(component.getKey());
        }
        out.append(") {}");
        return out.toString();
    }

    private static String buildFirstPhase(String modifiers, String name,
                                          String intermediateName,
                                          List<SingleVariableDeclaration> parameters,
                                          List<Statement> statements,
                                          Map<String, String> carried, String source) {
        StringBuilder out = new StringBuilder();
        out.append(modifiers).append(intermediateName).append(' ').append(name).append('(');
        for (int i = 0; i < parameters.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(parameters.get(i));
        }
        out.append(") {\n");
        for (Statement statement : statements) {
            out.append("    ").append(textOf(source, statement)).append('\n');
        }
        out.append("    return new ").append(intermediateName).append('(')
            .append(String.join(", ", carried.keySet())).append(");\n}");
        return out.toString();
    }

    private static String buildSecondPhase(String modifiers, String name,
                                           String intermediateName, String returnType,
                                           List<SingleVariableDeclaration> parameters,
                                           List<Statement> statements,
                                           Map<String, String> carried, String source) {
        StringBuilder out = new StringBuilder();
        out.append(modifiers).append(returnType).append(' ').append(name).append('(')
            .append(intermediateName).append(" intermediate");
        for (SingleVariableDeclaration parameter : parameters) {
            out.append(", ").append(parameter);
        }
        out.append(") {\n");
        // The carried values are unpacked by name, so the phase-two statements come across
        // untouched: every name in them still resolves to the same value it did before.
        for (Map.Entry<String, String> component : carried.entrySet()) {
            out.append("    ").append(component.getValue()).append(' ')
                .append(component.getKey()).append(" = intermediate.")
                .append(component.getKey()).append("();\n");
        }
        for (Statement statement : statements) {
            out.append("    ").append(textOf(source, statement)).append('\n');
        }
        out.append("}");
        return out.toString();
    }

    private static String textOf(String source, ASTNode node) {
        return source.substring(node.getStartPosition(),
            node.getStartPosition() + node.getLength()).trim();
    }

    private static String orDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static boolean containsReturn(Statement statement) {
        boolean[] found = { false };
        statement.accept(new ASTVisitor() {
            @Override
            public boolean visit(ReturnStatement node) {
                found[0] = true;
                return false;
            }
        });
        return found[0];
    }

    /** Which of the given names this statement reads, and which it assigns to. */
    private static void collectNames(Statement statement, Set<String> interesting,
                                     Set<String> reads, Set<String> writes) {
        statement.accept(new ASTVisitor() {
            @Override
            public boolean visit(Assignment node) {
                if (node.getLeftHandSide() instanceof SimpleName target
                        && interesting.contains(target.getIdentifier())) {
                    writes.add(target.getIdentifier());
                }
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                IBinding binding = node.resolveBinding();
                if (binding instanceof IVariableBinding variable && !variable.isField()
                        && interesting.contains(node.getIdentifier())) {
                    reads.add(node.getIdentifier());
                }
                return true;
            }
        });
        reads.removeAll(writes);
    }

    private static MethodDeclaration methodAt(CompilationUnit ast, int line, int column) {
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return null;
        }
        MethodDeclaration[] found = { null };
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodDeclaration node) {
                if (node.getStartPosition() <= offset
                        && offset < node.getStartPosition() + node.getLength()) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
