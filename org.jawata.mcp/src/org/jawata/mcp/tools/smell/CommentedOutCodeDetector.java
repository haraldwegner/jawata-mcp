package org.jawata.mcp.tools.smell;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.Assignment;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.ForStatement;
import org.eclipse.jdt.core.dom.IfStatement;
import org.eclipse.jdt.core.dom.LineComment;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.ReturnStatement;
import org.eclipse.jdt.core.dom.ThrowStatement;
import org.eclipse.jdt.core.dom.TryStatement;
import org.eclipse.jdt.core.dom.VariableDeclarationStatement;
import org.eclipse.jdt.core.dom.WhileStatement;
import org.jawata.core.IJdtService;
import org.jawata.mcp.domain.Finding;

import java.util.ArrayList;
import java.util.List;

/**
 * Fowler (2nd ed. ch.3, under <b>Comments</b>) — <b>code that has been commented out</b>.
 *
 * <p>Commented-out code is the case Fowler singles out: it is not a comment explaining
 * anything, it is a body of work left in the file because deleting it felt risky, and it
 * decays silently while everything around it moves.</p>
 *
 * <h2>THIS REPORTS A CANDIDATE. IT DOES NOT ASSERT DEAD CODE.</h2>
 *
 * <p>Harald's ruling of 2026-09-02, when this check was agreed: <i>"Comment as a smell if
 * code is commented out — yes, but what if this has some meaning still. So deletion only
 * with safety net, ask user?"</i> The caution is right. Commented-out code can still
 * carry meaning — a worked example, an alternative kept deliberately, a reminder of a
 * path that was tried — and nothing here may treat a find as proof the code is dead.</p>
 *
 * <p>So the wording is a candidate throughout, and the safety net is the product's
 * standing contract rather than something added for this kind: every operation that
 * changes source proposes the change and applies it on a human's yes, returning a staged
 * difference and an undo handle. No removal path here can delete anything by itself,
 * and there is deliberately no sweep that removes what this finds.</p>
 *
 * <h2>How it decides, and why the rule is a parser rather than a pattern</h2>
 *
 * <p>The comment's text is PARSED as Java statements. A pattern — a trailing semicolon,
 * a brace, a keyword — reports every sentence that happens to end in a semicolon and
 * every "// see also: foo()". Parsing asks the only question that matters: is this
 * Java?</p>
 *
 * <p>Parsing cleanly is necessary and not sufficient. {@code // x;} parses, and it is a
 * word with a semicolon after it. So a candidate must also CONTAIN something that does
 * work — a call, an assignment, a return, a declaration, a branch, a loop, a throw or a
 * try. That is the difference between prose punctuated like code and code.</p>
 *
 * <p>Javadoc is never examined: it is documentation by construction, and a
 * {@code {@code ...}} sample inside it is an example on purpose. Adjacent line comments
 * are joined first, so a commented-out block is read as the block it was rather than as
 * a run of fragments that individually parse as nothing.</p>
 *
 * <p>{@code threshold} is the minimum number of statements in the block (default 1).</p>
 */
public final class CommentedOutCodeDetector extends AbstractAstDetector {

    public CommentedOutCodeDetector() {
        super("commented_out_code",
            "Commented-out code — a comment whose text PARSES as Java statements and "
                + "contains something that does work (a call, an assignment, a return, a "
                + "declaration, a branch, a loop, a throw, a try). Reports a CANDIDATE, "
                + "never dead code: commented-out code can still carry meaning, so nothing "
                + "removes it without a human yes and there is no sweep that does. Javadoc "
                + "is never examined. `threshold` is the minimum statement count (default 1).",
            1);
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out) {
        analyze(ast, filePath, service, threshold, out, new ScanDegradation());
    }

    @Override
    protected void analyze(CompilationUnit ast, String filePath, IJdtService service,
                           int threshold, List<Finding> out, ScanDegradation degraded) {
        int minimum = Math.max(1, threshold);
        String source = sourceOf(ast, filePath, degraded);
        if (source == null) {
            return;
        }
        List<Comment> comments = ast.getCommentList();
        if (comments == null) {
            return;
        }
        for (Block block : blocksOf(comments, ast, source)) {
            int statements = statementCount(block.text());
            if (statements < minimum) {
                continue;
            }
            out.add(new Finding("commented_out_code", filePath, block.line(), -1, "info",
                "A comment here parses as " + statements + " Java statement(s) — this looks"
                    + " like code that was commented out rather than a comment about the"
                    + " code. It is a CANDIDATE, not a finding of dead code: it may still"
                    + " be a worked example, an alternative kept on purpose, or a reminder"
                    + " of a path that was tried. Read it, then delete it or say in one"
                    + " line why it stays. Nothing here removes it for you.",
                "line " + block.line()));
        }
    }

    /** One commented-out candidate: its joined text and the line it starts on. */
    private record Block(String text, int line) {
    }

    /**
     * The comment blocks worth parsing, with adjacent line comments joined.
     *
     * <p>Joining matters: a commented-out block is usually a run of {@code //} lines, and
     * each line alone is a fragment that parses as nothing. Read separately, the whole
     * shape this detector exists for is invisible.</p>
     */
    private static List<Block> blocksOf(List<Comment> comments, CompilationUnit ast,
                                        String source) {
        List<Block> blocks = new ArrayList<>();
        StringBuilder run = null;
        int runLine = -1;
        int runEndLine = -1;
        for (Comment comment : comments) {
            if (comment.isDocComment()) {
                continue;   // documentation, by construction
            }
            String text = textOf(comment, source);
            if (text == null) {
                continue;
            }
            int line = ast.getLineNumber(comment.getStartPosition());
            if (comment instanceof LineComment) {
                if (run != null && line == runEndLine + 1) {
                    run.append('\n').append(text);
                    runEndLine = line;
                    continue;
                }
                if (run != null) {
                    blocks.add(new Block(run.toString(), runLine));
                }
                run = new StringBuilder(text);
                runLine = line;
                runEndLine = line;
                continue;
            }
            if (run != null) {
                blocks.add(new Block(run.toString(), runLine));
                run = null;
            }
            blocks.add(new Block(text, line));
        }
        if (run != null) {
            blocks.add(new Block(run.toString(), runLine));
        }
        return blocks;
    }

    /** A comment's body with its markers removed, or null when it is not readable. */
    private static String textOf(Comment comment, String source) {
        int start = comment.getStartPosition();
        int end = start + comment.getLength();
        if (start < 0 || end > source.length() || end <= start) {
            return null;
        }
        String raw = source.substring(start, end);
        if (raw.startsWith("//")) {
            return raw.substring(2).trim();
        }
        if (raw.startsWith("/*")) {
            String body = raw.substring(2, Math.max(2, raw.length() - 2));
            StringBuilder cleaned = new StringBuilder();
            for (String line : body.split("\n", -1)) {
                String trimmed = line.trim();
                if (trimmed.startsWith("*")) {
                    trimmed = trimmed.substring(1).trim();
                }
                cleaned.append(trimmed).append('\n');
            }
            return cleaned.toString().trim();
        }
        return null;
    }

    /**
     * How many statements this text parses as — 0 when it is not Java, and 0 when it
     * parses but does no work.
     */
    private static int statementCount(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setKind(ASTParser.K_STATEMENTS);
        parser.setSource(text.toCharArray());
        org.eclipse.jdt.core.dom.ASTNode parsed;
        try {
            parsed = parser.createAST(null);
        } catch (RuntimeException e) {
            return 0;   // unparseable is the ordinary case: it is prose
        }
        if (!(parsed instanceof org.eclipse.jdt.core.dom.Block block)) {
            return 0;
        }
        // A recovered parse is not a parse. JDT's statement parser is forgiving and will
        // hand back a block full of recovered junk for a sentence; a problem on the unit
        // is the signal that this was never Java.
        if (block.getRoot() instanceof CompilationUnit unit && unit.getProblems().length > 0) {
            return 0;
        }
        List<?> statements = block.statements();
        if (statements.isEmpty()) {
            return 0;
        }
        boolean[] doesWork = {false};
        block.accept(new ASTVisitor() {
            @Override public boolean visit(MethodInvocation node) { return mark(); }
            @Override public boolean visit(Assignment node) { return mark(); }
            @Override public boolean visit(ReturnStatement node) { return mark(); }
            @Override public boolean visit(VariableDeclarationStatement node) { return mark(); }
            @Override public boolean visit(IfStatement node) { return mark(); }
            @Override public boolean visit(ForStatement node) { return mark(); }
            @Override public boolean visit(WhileStatement node) { return mark(); }
            @Override public boolean visit(ThrowStatement node) { return mark(); }
            @Override public boolean visit(TryStatement node) { return mark(); }

            private boolean mark() {
                doesWork[0] = true;
                return false;
            }
        });
        return doesWork[0] ? statements.size() : 0;
    }

    /** The file's source, or null with the reason recorded. */
    private static String sourceOf(CompilationUnit ast, String filePath,
                                   ScanDegradation degraded) {
        try {
            if (ast.getTypeRoot() instanceof ICompilationUnit cu) {
                return cu.getSource();
            }
        } catch (Exception e) {
            degraded.report("commented_out_code could not read the source of " + filePath
                + ": " + e);
            return null;
        }
        degraded.report("commented_out_code skipped " + filePath
            + ": it has no compilation unit to read comments from");
        return null;
    }
}
