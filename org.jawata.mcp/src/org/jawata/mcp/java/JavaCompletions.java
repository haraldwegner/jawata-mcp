package org.jawata.mcp.java;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.eclipse.jdt.core.CompletionProposal;
import org.eclipse.jdt.core.CompletionRequestor;
import org.eclipse.jdt.core.ICompilationUnit;
import org.eclipse.jdt.core.JavaModelException;
import org.eclipse.jdt.core.Signature;

/**
 * Java's answer to "what could be typed here" (Sprint 28h, S16b): JDT's code assist asked on a compilation unit at a position. Given the
 * working copy of an open file it completes the text the person has typed, saved or not. It lives with the provider because code
 * assist is a JDT call no published tool wraps; the language server asks it directly, as the architecture's picture draws.
 *
 * <p>Each answer is a map an editor can show: a {@code label} to read, the {@code insertText} to put in, a {@code kind} in the
 * protocol's numbering, a {@code detail} line and a {@code sortText} that keeps JDT's own ranking.</p>
 */
public final class JavaCompletions {

    private static final int KIND_METHOD = 2;
    private static final int KIND_CONSTRUCTOR = 4;
    private static final int KIND_FIELD = 5;
    private static final int KIND_VARIABLE = 6;
    private static final int KIND_CLASS = 7;
    private static final int KIND_MODULE = 9;
    private static final int KIND_KEYWORD = 14;
    private static final int KIND_SNIPPET = 15;

    private JavaCompletions() {
    }

    /**
     * The proposals at a position of a unit's text, best first.
     *
     * @param unit the working copy of an open file, or the saved unit of a file nobody has open
     * @param line zero-based line
     * @param column zero-based UTF-16 column
     */
    public static List<Map<String, Object>> at(ICompilationUnit unit, int line, int column) throws JavaModelException {
        int offset = JavaRanges.linesOf(unit).offsetOf(line, column);
        List<CompletionProposal> proposals = new ArrayList<>();
        unit.codeComplete(offset, new CompletionRequestor() {
            @Override
            public void accept(CompletionProposal proposal) {
                proposals.add(proposal);
            }
        });
        proposals.sort((a, b) -> Integer.compare(b.getRelevance(), a.getRelevance()));
        List<Map<String, Object>> items = new ArrayList<>();
        int rank = 0;
        for (CompletionProposal proposal : proposals) {
            Map<String, Object> item = itemOf(proposal, rank);
            if (item != null) {
                items.add(item);
                rank++;
            }
        }
        return items;
    }

    private static Map<String, Object> itemOf(CompletionProposal proposal, int rank) {
        Integer kind = kindOf(proposal.getKind());
        if (kind == null) {
            return null;
        }
        String insert = new String(proposal.getCompletion());
        String label = proposal.getName() == null ? insert : new String(proposal.getName());
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("label", label);
        item.put("kind", kind);
        item.put("insertText", insert);
        item.put("sortText", String.format("%06d", rank));
        String detail = detailOf(proposal);
        if (detail != null) {
            item.put("detail", detail);
        }
        return item;
    }

    private static Integer kindOf(int proposalKind) {
        return switch (proposalKind) {
            case CompletionProposal.METHOD_REF, CompletionProposal.METHOD_NAME_REFERENCE -> KIND_METHOD;
            case CompletionProposal.CONSTRUCTOR_INVOCATION -> KIND_CONSTRUCTOR;
            case CompletionProposal.FIELD_REF -> KIND_FIELD;
            case CompletionProposal.LOCAL_VARIABLE_REF, CompletionProposal.VARIABLE_DECLARATION -> KIND_VARIABLE;
            case CompletionProposal.TYPE_REF -> KIND_CLASS;
            case CompletionProposal.PACKAGE_REF -> KIND_MODULE;
            case CompletionProposal.KEYWORD -> KIND_KEYWORD;
            case CompletionProposal.POTENTIAL_METHOD_DECLARATION, CompletionProposal.METHOD_DECLARATION -> KIND_SNIPPET;
            default -> null;
        };
    }

    private static String detailOf(CompletionProposal proposal) {
        char[] signature = proposal.getSignature();
        if (signature == null || signature.length == 0) {
            return null;
        }
        return switch (proposal.getKind()) {
            case CompletionProposal.METHOD_REF, CompletionProposal.FIELD_REF, CompletionProposal.LOCAL_VARIABLE_REF,
                CompletionProposal.TYPE_REF -> Signature.toString(new String(signature));
            default -> null;
        };
    }
}
