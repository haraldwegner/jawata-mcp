package org.jawata.mcp.tools.data;

import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.eclipse.core.resources.IFile;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.IType;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.IMethodBinding;
import org.eclipse.jdt.core.dom.ITypeBinding;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.rewrite.ASTRewrite;
import org.eclipse.jdt.core.dom.rewrite.ImportRewrite;
import org.eclipse.jdt.core.dom.rewrite.ListRewrite;
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
 * {@code data kind=hide_delegate} — Fowler row 16, and the exact inverse of
 * {@code inline kind=middle_man}.
 *
 * <p>A client that writes {@code john.getDepartment().getManager()} knows two things it has
 * no business knowing: that a person HAS a department, and that a department is where a
 * manager lives. Hide Delegate puts a forwarding method on the server —
 * {@code Person.getManager()} — so the client asks the question it actually has, and the
 * department stops being part of the client's vocabulary.</p>
 *
 * <p><b>It is routed from {@code find_quality_issue kind=message_chains}</b>, whose findings
 * carry the file and the line of the chain and whose message already names this refactoring:
 * <em>"Method-call chain of length 5 (threshold 3). Consider Hide Delegate."</em> Measured on
 * this repository, that detector reports 273 findings — the largest single population any
 * Stage 5 row answers, which is why this row is built first.</p>
 *
 * <h2>ONE HOP PER RUN, and that is a decision rather than a limitation</h2>
 *
 * <p>The findings are chains of four, five and seven calls. This hides the OUTERMOST hop and
 * says so: {@code a.b().c().d()} becomes {@code a.b().d()} with a forwarder on whatever
 * {@code b()} returns. Fowler hides one delegate at a time because each hop is a separate
 * judgement about whose vocabulary a concept belongs in, and a run that collapsed a chain of
 * seven would be six of those judgements taken as one. Repeat it to go further.</p>
 *
 * <h2>What it refuses, and why each refusal is not merely caution</h2>
 *
 * <ul>
 *   <li><b>A position that is not a two-deep call chain.</b> There is no delegate to hide,
 *       and the refusal says what was found instead.</li>
 *   <li><b>A server type this workspace cannot edit</b> — the JDK, a dependency jar. The
 *       whole refactoring is "add a method to the server"; without the source there is
 *       nothing to add it to. This is the commonest refusal on real code, because
 *       {@code getClass().getName()} and friends match the shape.</li>
 *   <li><b>An intermediate call that takes arguments.</b> {@code a.at(i).name()} would need
 *       the forwarder to carry {@code i}, and whether the forwarder should take an index is a
 *       design question the caller has not been asked.</li>
 *   <li><b>A name already declared on the server.</b> Generating over it would either fail to
 *       compile or silently change which method a caller reaches; the refusal names the
 *       clash so a caller can pass {@code delegateMethodName}.</li>
 * </ul>
 */
public class HideDelegateTool extends AbstractRefactoringTool implements ToolKindDelegate {

    public HideDelegateTool(Supplier<IJdtService> serviceSupplier, RefactoringChangeCache cache) {
        super(serviceSupplier, cache);
    }

    /** Reached as {@code data kind=hide_delegate}. */
    @Override
    public String kindName() {
        return "hide_delegate";
    }

    /** The bullet a client reads under {@code data} — projected, not written on the door. */
    @Override
    public String kindSummary() {
        return """
            hide a delegate the client should not know about: `john.getDepartment()
            .getManager()` becomes `john.getManager()`, with a forwarding method
            generated on the server. Hides ONE hop per run, because each hop is a
            separate judgement about whose vocabulary a concept belongs in — a chain
            of five takes four runs. Refuses a server type this workspace cannot edit
            (the JDK, a dependency jar), an intermediate call that takes arguments,
            and a name already declared on the server. Each refusal names which.
            (find_quality_issue kind=message_chains locates candidates.)""";
    }

    /** Structural: it adds a method to the server's published surface. */
    @Override
    public boolean isStructural() {
        return true;
    }

    @Override
    public String getName() {
        return "hide_delegate";
    }

    @Override
    public String getDescription() {
        return "Hide Delegate — put a forwarding method on the server so the client stops "
            + "reaching through it. Delegate of data.";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("filePath", Map.of("type", "string",
            "description", "Source file containing the call chain."));
        properties.put("line", Map.of("type", "integer",
            "description", "Zero-based line of the chain — the line a message_chains "
                + "finding reports."));
        properties.put("column", Map.of("type", "integer",
            "description", "Zero-based column inside the chain."));
        properties.put("delegateMethodName", Map.of("type", "string",
            "description", "Name for the generated forwarder on the server (default: the "
                + "hidden call's own name). Every rewritten call site reads it."));
        schema.put("properties", properties);
        schema.put("required", List.of("filePath", "line", "column"));
        return withAutoApply(withProjectKey(schema));
    }

    @Override
    public ToolResponse executeWithService(IJdtService service, JsonNode arguments) {
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
            return hide(service, unit, line, column,
                getStringParam(arguments, "delegateMethodName"), arguments);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(HideDelegateTool.class)
                .warn("hide_delegate failed: {}", e.toString(), e);
            return ToolResponse.internalError(e);
        }
    }

    private ToolResponse hide(IJdtService service, ICompilationUnit unit, int line, int column,
                              String requestedName, JsonNode arguments) throws Exception {
        CompilationUnit ast = parse(unit);
        // JDT counts lines from one; every jawata coordinate is zero-based.
        int offset = ast.getPosition(line + 1, column);
        if (offset < 0) {
            return ToolResponse.invalidCoordinates(line, column,
                "that position is not inside " + unit.getElementName());
        }

        MethodInvocation outer = chainAt(ast, offset);
        if (outer == null) {
            return ToolResponse.invalidParameter("position",
                "no two-deep call chain at that position. Hide Delegate needs "
                    + "`receiver.first().second()` — a call whose own receiver is another "
                    + "call. Position on the chain a message_chains finding reports.");
        }
        MethodInvocation inner = (MethodInvocation) outer.getExpression();

        if (!inner.arguments().isEmpty()) {
            return ToolResponse.invalidParameter("position",
                "the intermediate call `" + inner.getName().getIdentifier() + "` takes "
                    + inner.arguments().size() + " argument(s). The forwarder would have to "
                    + "carry them, and whether it should is a design question this tool "
                    + "cannot answer for you.");
        }

        IMethodBinding innerBinding = inner.resolveMethodBinding();
        IMethodBinding outerBinding = outer.resolveMethodBinding();
        if (innerBinding == null || outerBinding == null) {
            return ToolResponse.symbolNotFound(
                "could not resolve the call chain to method bindings; the file may not "
                    + "compile against this workspace's classpath.");
        }

        // A STATIC intermediate call is not a delegate the client reaches THROUGH — it is a
        // factory the client calls ON A TYPE. `Product.builder().name("Eggs")` has no
        // receiver object to forward from, and generating `Product.name("Eggs")` would drop
        // the builder entirely. Found by censusing the fork corpus: of 84 message-chain
        // findings there, 43 are fluent builders, and this shape is most of them.
        if (Modifier.isStatic(innerBinding.getModifiers())) {
            return ToolResponse.invalidParameter("position",
                "the intermediate call `" + inner.getName().getIdentifier() + "` is STATIC, so"
                    + " there is no receiver to hide a delegate behind. This is the fluent-"
                    + "builder shape (Type.builder().field(...)), where the chain is one"
                    + " object configuring itself rather than a client reaching through a"
                    + " server to a delegate.");
        }

        ITypeBinding serverBinding = innerBinding.getDeclaringClass();
        if (serverBinding == null) {
            return ToolResponse.symbolNotFound("could not resolve the server type.");
        }
        // AN INTERFACE IS NOT A SERVER WE CAN QUIETLY ADD TO. Generating a concrete body
        // there is invalid Java, and generating a `default` method instead would change the
        // contract of every implementor — a design decision the caller has not been asked to
        // make. Found on the fork corpus, not on a fixture: upstream's step-builder chains
        // dispatch through step INTERFACES, so pointing at one produced "Abstract methods do
        // not specify a body" and the pipeline undid it. A fixture would have had a class.
        if (serverBinding.isInterface()) {
            return ToolResponse.invalidParameter("position",
                "the server " + serverBinding.getName() + " is an INTERFACE. A forwarder there"
                    + " would have to be a default method, which changes the contract of every"
                    + " implementor — a design decision this tool will not take for you.");
        }

        IType server = serverType(service, serverBinding);
        if (server == null || server.getCompilationUnit() == null) {
            return ToolResponse.invalidParameter("position",
                "the server type " + serverBinding.getQualifiedName() + " has no source in "
                    + "this workspace, so a forwarding method cannot be added to it. Hide "
                    + "Delegate is 'add a method to the server'; on a JDK or dependency type "
                    + "there is nothing to add it to.");
        }

        String forwarder = requestedName != null && !requestedName.isBlank()
            ? requestedName
            : outer.getName().getIdentifier();
        int arity = outer.arguments().size();
        for (org.eclipse.jdt.core.IMethod existing : server.getMethods()) {
            if (existing.getElementName().equals(forwarder)
                    && existing.getNumberOfParameters() == arity) {
                return ToolResponse.invalidParameter("delegateMethodName",
                    server.getElementName() + " already declares " + forwarder + "/" + arity
                        + ". Generating over it would change which method a caller reaches; "
                        + "pass delegateMethodName to choose another.");
            }
        }

        ICompilationUnit serverUnit = server.getCompilationUnit();
        boolean sameFile = serverUnit.equals(unit);
        CompilationUnit serverAst = sameFile ? ast : parse(serverUnit);
        TypeDeclaration serverType =
            org.jawata.mcp.tools.shared.TypeLookup.declaration(serverAst, server)
                instanceof TypeDeclaration found ? found : null;
        if (serverType == null) {
            return ToolResponse.symbolNotFound(
                "could not locate " + server.getElementName() + " in its own source.");
        }

        Map<IFile, List<TextEdit>> edits = new LinkedHashMap<>();
        ASTRewrite serverRewrite = sameFile ? ASTRewrite.create(ast.getAST())
            : ASTRewrite.create(serverAst.getAST());

        // THE IMPORTS ARE REWRITTEN, not assumed. The first version wrote the return and
        // parameter types as SIMPLE names, which compiles only when every one of them is
        // already visible in the server's file — so the operation declined, via the
        // pipeline's compile gate, on every cross-package case. Nothing broken shipped,
        // because the gate refused; but "declines on a whole class of correct input" is a
        // defect rather than a safe default. addImport returns the name to USE and records
        // the import to add, falling back to the qualified name when a simple one would
        // clash with something already imported.
        ImportRewrite imports = ImportRewrite.create(serverAst, true);
        String returns = imports.addImport(outerBinding.getReturnType());
        String params = signatureOf(outerBinding, imports);
        String passed = argumentNamesOf(outerBinding);
        ListRewrite members = serverRewrite.getListRewrite(serverType,
            serverType.getBodyDeclarationsProperty());
        members.insertLast(serverRewrite.createStringPlaceholder(
            "/** Hides the " + innerBinding.getReturnType().getName()
                + " delegate: generated by Hide Delegate. */\n"
                + "public " + returns + " " + forwarder + "(" + params + ") {\n"
                + "    return " + inner.getName().getIdentifier() + "()."
                + outer.getName().getIdentifier() + "(" + passed + ");\n}",
            ASTNode.METHOD_DECLARATION), null);

        // The call site. When the chain is in the server's own file the two rewrites must be
        // ONE, or the second overwrites the first — the same linked-edit hazard Stage 3's
        // member narrowing hit from the other direction.
        ASTRewrite callerRewrite = sameFile ? serverRewrite : ASTRewrite.create(ast.getAST());
        String source = unit.getSource();
        String receiver = inner.getExpression() == null
            ? "this"
            : source.substring(inner.getExpression().getStartPosition(),
                inner.getExpression().getStartPosition() + inner.getExpression().getLength());
        callerRewrite.replace(outer, callerRewrite.createStringPlaceholder(
            receiver + "." + forwarder + "(" + argumentTextOf(source, outer) + ")",
            ASTNode.METHOD_INVOCATION), null);

        // The import edit lands on the SERVER's file, which is the caller's own when the
        // chain is in it — and the two edits must then travel in ONE list, or the second
        // apply overwrites the first.
        TextEdit importEdit = imports.hasRecordedChanges() ? imports.rewriteImports(null) : null;
        if (!sameFile) {
            List<TextEdit> serverEdits = new java.util.ArrayList<>();
            serverEdits.add(serverRewrite.rewriteAST(new Document(serverUnit.getSource()),
                FormatterOptions.forGeneratedCode(serverAst)));
            if (importEdit != null) {
                serverEdits.add(importEdit);
            }
            edits.put((IFile) serverUnit.getResource(), serverEdits);
        }
        List<TextEdit> callerEdits = new java.util.ArrayList<>();
        callerEdits.add(callerRewrite.rewriteAST(new Document(source),
            FormatterOptions.forGeneratedCode(ast)));
        if (sameFile && importEdit != null) {
            callerEdits.add(importEdit);
        }
        edits.put((IFile) unit.getResource(), callerEdits);

        String label = "hide delegate " + innerBinding.getReturnType().getName() + " behind "
            + server.getElementName() + "." + forwarder + "() (one hop of the chain at "
            + unit.getElementName() + ":" + line + ")";
        return runPreCheckedRefactoring(service,
            new PreparedRefactoring(ChangeEngine.fromFileEdits(label, edits), label),
            "hide_delegate", arguments);
    }

    /**
     * The OUTERMOST two-deep chain covering the offset.
     *
     * <p>Outermost rather than innermost, because a chain of five contains four candidate
     * pairs and the one a caller means when they point at the line is the whole expression.
     * Hiding the outermost hop is also the only one that shortens what the client writes.</p>
     */
    private static MethodInvocation chainAt(CompilationUnit ast, int offset) {
        MethodInvocation[] found = new MethodInvocation[1];
        ast.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                int start = node.getStartPosition();
                if (offset < start || offset > start + node.getLength()) {
                    return true;
                }
                if (node.getExpression() instanceof MethodInvocation
                        && (found[0] == null || node.getLength() > found[0].getLength())) {
                    found[0] = node;
                }
                return true;
            }
        });
        return found[0];
    }

    /**
     * {@code int a0, String a1} for the forwarder's parameter list.
     *
     * <p>Each type goes through the same {@link ImportRewrite} as the return type, so a
     * parameter from another package is imported rather than written as a bare simple name
     * the server's file cannot resolve.</p>
     */
    private static String signatureOf(IMethodBinding binding, ImportRewrite imports) {
        StringBuilder out = new StringBuilder();
        ITypeBinding[] types = binding.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            out.append(i == 0 ? "" : ", ")
                .append(imports.addImport(types[i])).append(" a").append(i);
        }
        return out.toString();
    }

    /** {@code a0, a1} — the names {@link #signatureOf} generated, passed straight through. */
    private static String argumentNamesOf(IMethodBinding binding) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < binding.getParameterTypes().length; i++) {
            out.append(i == 0 ? "" : ", ").append("a").append(i);
        }
        return out.toString();
    }

    /** The call site's OWN argument text, kept verbatim rather than re-rendered. */
    private static String argumentTextOf(String source, MethodInvocation call) {
        StringBuilder out = new StringBuilder();
        List<?> args = call.arguments();
        for (int i = 0; i < args.size(); i++) {
            ASTNode arg = (ASTNode) args.get(i);
            out.append(i == 0 ? "" : ", ").append(source, arg.getStartPosition(),
                arg.getStartPosition() + arg.getLength());
        }
        return out.toString();
    }

    private static IType serverType(IJdtService service, ITypeBinding binding) throws Exception {
        IJavaProject project = service.getJavaProject();
        if (project == null) {
            return null;
        }
        return project.findType(binding.getErasure().getQualifiedName());
    }

    // `typeNamed` WAS HERE, and THIS ONE IS THE RECORD WORTH KEEPING — it is the only copy in
    // the population that had ALREADY BEEN FIXED. Its javadoc said so in its own words: the
    // first version walked `ast.types()`, the top-level types only, and returned null for
    // every nested one, so the operation refused about a shape that is not exceptional at all;
    // two of this row's own tests caught it on the first run and no review had, "because the
    // method reads correctly until you ask what types() actually contains."
    //
    // THE DEFECT WAS FOUND, DIAGNOSED EXACTLY, AND FIXED IN PRIVATE. Four other files went on
    // shipping the unfixed version, and one of them — `inline kind=class` — is what C8b round
    // 2 caught. A cure written where only its own file can reach it does not reduce the class;
    // it removes the one instance that would have made the class visible.
    //
    // The visitor also kept the weaker half: it took the FIRST node of that simple name, so
    // two sibling nested classes sharing a name resolved to whichever came first. That is the
    // key defect Stage 5 closed by joining on the element's own source range, which is what
    // `tools.shared.TypeLookup` does and what this now asks.

    private static CompilationUnit parse(ICompilationUnit unit) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(true);
        parser.setBindingsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }
}
