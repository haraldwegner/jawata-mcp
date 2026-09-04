package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.internal.corext.refactoring.code.ExtractMethodRefactoring;
import org.eclipse.ltk.core.refactoring.Change;
import org.eclipse.ltk.core.refactoring.RefactoringStatus;
import org.eclipse.ltk.core.refactoring.RefactoringStatusEntry;
import org.jawata.core.IJdtService;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Extract a code block into a new method.
 *
 * <p>Sprint 14b: auto-applies by default via
 * {@link AbstractApplyingRefactoringTool}.</p>
 *
 * <p>v2.12.1: the transformation is computed by JDT's own
 * {@link ExtractMethodRefactoring} — the engine behind the IDE's Refactor →
 * Extract Method. The original implementation was a hand-rolled heuristic,
 * a faithful fossil of javalens's "return a preview, don't apply" contract;
 * once Sprint 14b made tools APPLY their changes, its dataflow analysis was
 * carrying a responsibility it was never designed for, and on the first live
 * self-refactor it produced non-compiling code (a variable declared in the
 * selection and used after it — a shape the heuristic did not know). The
 * JDT engine analyzes exhaustively and REFUSES what it cannot transform,
 * with its reasons in the response.</p>
 */
public class ExtractMethodTool extends AbstractApplyingRefactoringTool
        implements ToolKindDelegate {

    /** Reached as {@code extract kind=method}; the tool's own name is not the kind. */
    @Override
    public String kindName() {
        return "method";
    }

    /** The bullet a client reads under {@code extract} — moved here from the door (M5). */
    @Override
    public String kindSummary() {
        return """
            extract a statement range into a new method.
            Needs: startLine, startColumn, endLine, endColumn, methodName.""";
    }


    private static final Logger log = LoggerFactory.getLogger(ExtractMethodTool.class);

    private static final Set<String> RESERVED_WORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char",
        "class", "const", "continue", "default", "do", "double", "else", "enum",
        "extends", "final", "finally", "float", "for", "goto", "if", "implements",
        "import", "instanceof", "int", "interface", "long", "native", "new", "package",
        "private", "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws", "transient",
        "try", "void", "volatile", "while", "true", "false", "null"
    );

    public ExtractMethodTool(Supplier<IJdtService> serviceSupplier,
                             RefactoringChangeCache changeCache) {
        super(serviceSupplier, changeCache);
    }

    @Override
    public String getName() {
        return "extract_method";
    }

    @Override
    public String getDescription() {
        return """
            Extract a code block into a new method (JDT's own Extract Method engine).

            Applies the extraction directly (default) and returns
            { filesModified, diff, undoChangeId, summary }, compile-verified on
            the modified files. Revert with undo_refactoring(undoChangeId).
            Pass auto_apply: false to stage instead — returns { changeId, diff }.

            USAGE: Select a range of COMPLETE statements (or one expression),
            provide a method name. The engine determines parameters and return
            value from the selection's dataflow, and REFUSES selections it
            cannot transform correctly — the refusal names the reason.

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
                "description", "Zero-based start line of code to extract"
            ),
            "startColumn", Map.of(
                "type", "integer",
                "description", "Zero-based start column"
            ),
            "endLine", Map.of(
                "type", "integer",
                "description", "Zero-based end line of code to extract"
            ),
            "endColumn", Map.of(
                "type", "integer",
                "description", "Zero-based end column"
            ),
            "methodName", Map.of(
                "type", "string",
                "description", "Name for the new method"
            ),
            "replaceDuplicates", Map.of(
                "type", "boolean",
                "description", "Also rewrite every OTHER occurrence of the selected "
                    + "statements in the same type to call the new method (Fowler's Replace "
                    + "Inline Code with Function Call). DEFAULT FALSE, because this "
                    + "operation predates the parameter and its shipped behaviour is to "
                    + "extract only the selection. The response reports otherOccurrences "
                    + "either way, so a non-zero count is the signal to re-run with this "
                    + "set. The engine matches on structure AND resolved bindings, so it "
                    + "never rewrites code that merely looks alike."
            )
        ));
        schema.put("required", List.of("filePath", "startLine", "startColumn", "endLine", "endColumn", "methodName"));
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
        String methodName = getStringParam(arguments, "methodName");

        if (startLine < 0 || startColumn < 0 || endLine < 0 || endColumn < 0) {
            return Preparation.fail(
                ToolResponse.invalidParameter("positions", "All positions must be >= 0"));
        }

        if (methodName == null || methodName.isBlank()) {
            return Preparation.fail(ToolResponse.invalidParameter("methodName", "Required"));
        }

        if (!isValidJavaIdentifier(methodName)) {
            return Preparation.fail(
                ToolResponse.invalidParameter("methodName", "Not a valid Java identifier"));
        }

        Path path = Path.of(filePath);
        ICompilationUnit cu = service.getCompilationUnit(path);
        if (cu == null) {
            return Preparation.fail(ToolResponse.fileNotFound(filePath));
        }

        // Parse only to translate line/column into offsets.
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(cu);
        CompilationUnit ast = (CompilationUnit) parser.createAST(null);
        int startOffset = ast.getPosition(startLine + 1, startColumn);
        int endOffset = ast.getPosition(endLine + 1, endColumn);
        if (startOffset < 0 || endOffset < 0 || startOffset >= endOffset) {
            return Preparation.fail(
                ToolResponse.invalidParameter("positions", "Invalid selection range"));
        }

        // jdt.core.manipulation reads preference nodes + code templates that the
        // IDE would have initialized; headless embedders must do it themselves.
        // JawataApplication.start() does — but tests construct tools directly,
        // so the tool that depends on it ensures it (idempotent). Without this,
        // the engine dies with an IAE inside ProjectScope.getNode.
        org.jawata.mcp.tools.shared.HeadlessJdtConfig.ensureInitialized();

        // THE FOURTH-ARGUMENT CONSTRUCTOR, and it is the whole fix for a defect that has
        // now appeared three times. Without formatter options this engine reads the
        // preference store, whose default tab char is TAB, and emits tab-indented members
        // into space-indented files — visible in the extract goldens and, through this
        // tool, in row 8's. Two central routes were tried and measured as not reaching it
        // (see HeadlessJdtConfig); an audit and I both read this class as taking no
        // options at all. It takes them here.
        ExtractMethodRefactoring refactoring = new ExtractMethodRefactoring(
            cu, startOffset, endOffset - startOffset,
            org.jawata.mcp.tools.shared.FormatterOptions.forGeneratedCode(cu, null));
        refactoring.setMethodName(methodName);
        refactoring.setVisibility(Modifier.PRIVATE);
        // Sprint 28d-rescue row 49 — Replace Inline Code with Function Call, and the plan
        // was right that it might be a setter on an engine already wrapped here. It was:
        // this asks the engine to find every OTHER occurrence of the selected statements in
        // the same type and rewrite it to call the new method too.
        //
        // OFF BY DEFAULT, and that is a REVERSAL made at C6 on the architect seat's
        // finding. It shipped ON for a day, with the argument that an opt-in nobody chooses
        // is a row that never fires. The argument was right about opt-ins and wrong about
        // this one: `extract kind=method` has shipped since Sprint 16b, and
        // ARCHITECTURE-fowler-full.md §8 lists "the behaviour of the 17 shipped
        // refactorings" under Do not touch. Defaulting it on silently widened an operation
        // every existing caller already relies on — and this sprint's own recipe had
        // already turned it off for exactly that reason (ReplaceTempWithQueryTool), so the
        // hazard was seen for the internal caller and left on for every external one.
        //
        // What keeps the row from being an unchosen flag is the COUNT, which is reported
        // whether or not the flag is set: a caller who extracts and reads
        // "otherOccurrences: 3" learns the thing they could not have counted, and asks for
        // it deliberately on the next call. That is discovery without a behaviour change.
        boolean replaceDuplicates = getBooleanParam(arguments, "replaceDuplicates", false);

        RefactoringStatus status = refactoring.checkAllConditions(new NullProgressMonitor());
        if (status.hasError()) {
            // The engine analyzed the selection and cannot transform it correctly.
            // Its reasons ARE the answer — a refusal that names the problem beats
            // a generated method that compiles the problem into the code.
            return Preparation.fail(ToolResponse.error(
                "EXTRACT_REFUSED",
                "extract_method refused this selection: " + statusMessages(status),
                "Adjust the selection (complete statements or a single expression; "
                    + "JDT's Extract Method rules apply) and retry. No files were modified."));
        }

        // AFTER the conditions, and that ordering is not a style choice. The engine finds
        // the duplicates while checking final conditions and resets this flag as it goes, so
        // a value set beforehand is overwritten and the engine's own default governs. Set
        // it earlier and BOTH settings produce the same output — which is what the control
        // test caught: the parameter was published, accepted, echoed back, and inert.
        refactoring.setReplaceDuplicates(replaceDuplicates);

        Change change = refactoring.createChange(new NullProgressMonitor());

        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("filePath", service.getPathUtils().formatPath(path));
        extras.put("methodName", methodName);
        extras.put("signature", refactoring.getSignature());
        // The number the caller could not have counted for themselves, and the reason
        // replaceDuplicates defaults on: seeing "3 other occurrences" is how they learn the
        // code they selected was not one place.
        extras.put("otherOccurrences", refactoring.getNumberOfDuplicates());
        extras.put("replaceDuplicates", replaceDuplicates);
        if (status.hasWarning()) {
            extras.put("warnings", statusMessages(status));
        }

        String summary = "extract method " + refactoring.getSignature();
        log.debug("extract_method via JDT ExtractMethodRefactoring: {}", summary);
        return Preparation.of(change, summary, extras);
    }

    private static String statusMessages(RefactoringStatus status) {
        List<String> messages = new ArrayList<>();
        for (RefactoringStatusEntry entry : status.getEntries()) {
            messages.add(entry.getMessage());
        }
        return String.join(" | ", messages);
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
