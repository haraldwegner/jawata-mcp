package org.jawata.mcp.java.lsp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.Block;
import org.eclipse.jdt.core.dom.BlockComment;
import org.eclipse.jdt.core.dom.Comment;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.Expression;
import org.eclipse.jdt.core.dom.Javadoc;
import org.eclipse.jdt.core.dom.NodeFinder;
import org.eclipse.jdt.core.dom.SwitchExpression;
import org.eclipse.jdt.core.dom.SwitchStatement;
import org.jawata.mcp.java.JavaRanges;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The language server's structure requests (Sprint 28h, S16d): the ranges an editor folds, and the ranges "expand selection" widens
 * through. Both are read from JDT's syntax tree of the text the editor holds, saved or not, so they follow what the person has typed;
 * nothing is asked of the engine's tools, because no tool answers either (the design note lists them as a gap).
 */
final class LspStructure {

    private static final String KIND_COMMENT = "comment";
    private static final String KIND_IMPORTS = "imports";
    private static final String KIND_REGION = "region";

    private final Function<String, ICompilationUnit> unitOf;

    /** @param unitOf the unit a request reads for a document: its working copy when open, else the saved unit; null for a file the model does not hold */
    LspStructure(Function<String, ICompilationUnit> unitOf) {
        this.unitOf = unitOf;
    }

    /**
     * The syntax tree of a unit's current text, with the bindings the colour and the hints need when asked for them. A tree of text that
     * does not compile is still answered, as far as JDT recovers it.
     */
    static CompilationUnit syntaxOf(ICompilationUnit unit, boolean withBindings) {
        ASTParser parser = ASTParser.newParser(AST.getJLSLatest());
        parser.setSource(unit);
        parser.setResolveBindings(withBindings);
        parser.setBindingsRecovery(withBindings);
        parser.setStatementsRecovery(true);
        return (CompilationUnit) parser.createAST(null);
    }

    /**
     * The regions an editor can fold: the body of each type, method, initializer, lambda and switch, the run of imports, and each
     * comment over several lines. A body folds from the line of its opening brace to the line before the closing one, so the closing
     * brace stays in view; a comment folds through its last line.
     */
    Object foldingRange(JsonNode params) throws JavaModelException {
        ICompilationUnit unit = unitOf.apply(params.path("textDocument").path("uri").asText());
        List<Map<String, Object>> ranges = new ArrayList<>();
        if (unit == null) {
            return ranges;
        }
        String text = unit.getSource();
        JavaRanges.Lines lines = JavaRanges.linesOf(text);
        CompilationUnit root = syntaxOf(unit, false);
        root.accept(new ASTVisitor(true) {
            /** Every block folds once: a method, initializer or lambda body, and the block of an {@code if}, {@code else}, loop, {@code try}, {@code catch}, {@code finally} or {@code synchronized}. */
            @Override
            public boolean visit(Block node) {
                foldBlock(ranges, lines, node);
                return true;
            }

            @Override
            public boolean visit(SwitchStatement node) {
                foldSwitch(ranges, lines, text, node.getExpression(), node);
                return true;
            }

            @Override
            public boolean visit(SwitchExpression node) {
                foldSwitch(ranges, lines, text, node.getExpression(), node);
                return true;
            }
        });
        for (Object each : root.types()) {
            foldType(ranges, lines, text, (AbstractTypeDeclaration) each);
        }
        if (root.imports().size() > 1) {
            ASTNode first = (ASTNode) root.imports().get(0);
            ASTNode last = (ASTNode) root.imports().get(root.imports().size() - 1);
            JavaRanges.Range span = lines.span(first.getStartPosition(), last.getStartPosition() + last.getLength() - first.getStartPosition());
            if (span.endLine() > span.line()) {
                ranges.add(range(span.line(), span.endLine(), KIND_IMPORTS));
            }
        }
        for (Object each : root.getCommentList()) {
            Comment comment = (Comment) each;
            if (comment instanceof Javadoc || comment instanceof BlockComment) {
                JavaRanges.Range span = lines.span(comment.getStartPosition(), comment.getLength());
                if (span.endLine() > span.line()) {
                    ranges.add(range(span.line(), span.endLine(), KIND_COMMENT));
                }
            }
        }
        return ranges;
    }

    /** The body of a type and, inside it, of each member type. */
    private static void foldType(List<Map<String, Object>> ranges, JavaRanges.Lines lines, String text, AbstractTypeDeclaration type) {
        fold(ranges, lines, text.indexOf('{', type.getName().getStartPosition()), type.getStartPosition() + type.getLength() - 1);
        for (Object member : type.bodyDeclarations()) {
            if (member instanceof AbstractTypeDeclaration nested) {
                foldType(ranges, lines, text, nested);
            }
        }
    }

    private static void foldBlock(List<Map<String, Object>> ranges, JavaRanges.Lines lines, Block body) {
        if (body != null) {
            fold(ranges, lines, body.getStartPosition(), body.getStartPosition() + body.getLength() - 1);
        }
    }

    /** The body of a {@code switch}, statement or expression: from the brace after its selector to the line before the closing brace. */
    private static void foldSwitch(List<Map<String, Object>> ranges, JavaRanges.Lines lines, String text, Expression selector, ASTNode node) {
        int brace = text.indexOf('{', selector.getStartPosition() + selector.getLength());
        fold(ranges, lines, brace, node.getStartPosition() + node.getLength() - 1);
    }

    /** A fold from the line of the opening brace to the line before the closing one; a body of one or two lines is not worth folding. */
    private static void fold(List<Map<String, Object>> ranges, JavaRanges.Lines lines, int openBrace, int closeBrace) {
        if (openBrace < 0 || closeBrace <= openBrace) {
            return;
        }
        int from = lines.span(openBrace, 0).line();
        int to = lines.span(closeBrace, 0).line() - 1;
        if (to > from) {
            ranges.add(range(from, to, KIND_REGION));
        }
    }

    private static Map<String, Object> range(int startLine, int endLine, String kind) {
        Map<String, Object> range = new LinkedHashMap<>();
        range.put("startLine", startLine);
        range.put("endLine", endLine);
        range.put("kind", kind);
        return range;
    }

    /**
     * For each position asked, the ranges "expand selection" widens through: the name under it, then each syntax node around it up to
     * the whole file, each range inside the next.
     */
    Object selectionRange(JsonNode params) throws JavaModelException {
        ICompilationUnit unit = unitOf.apply(params.path("textDocument").path("uri").asText());
        List<Object> answers = new ArrayList<>();
        String text = unit == null ? "" : unit.getSource();
        CompilationUnit root = unit == null ? null : syntaxOf(unit, false);
        JavaRanges.Lines lines = JavaRanges.linesOf(text);
        for (JsonNode position : params.path("positions")) {
            int offset = lines.offsetOf(position.path("line").asInt(), position.path("character").asInt());
            List<int[]> spans = new ArrayList<>();
            if (root != null) {
                for (ASTNode node = NodeFinder.perform(root, offset, 0); node != null; node = node.getParent()) {
                    int[] span = {node.getStartPosition(), node.getLength()};
                    if (spans.isEmpty() || spans.get(spans.size() - 1)[0] != span[0] || spans.get(spans.size() - 1)[1] != span[1]) {
                        spans.add(span);
                    }
                }
            }
            if (spans.isEmpty()) {
                spans.add(new int[] {offset, 0});
            }
            Map<String, Object> chain = null;
            for (int i = spans.size() - 1; i >= 0; i--) { // the outermost first, so each inner range can name its parent
                Map<String, Object> link = new LinkedHashMap<>();
                link.put("range", lines.span(spans.get(i)[0], spans.get(i)[1]).toProtocol());
                if (chain != null) {
                    link.put("parent", chain);
                }
                chain = link;
            }
            answers.add(chain);
        }
        return answers;
    }
}
