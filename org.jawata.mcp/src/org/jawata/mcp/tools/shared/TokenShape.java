package org.jawata.mcp.tools.shared;

import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ONE definition of "normalized token shape" for this codebase.
 *
 * <p>A method's shape is its token sequence with the details thrown away:
 * identifiers become {@code ID}, literals become {@code STR}/{@code INT}/
 * {@code FLT}/{@code CHAR}/{@code NULL}/{@code BOOL}, keywords and operators
 * stay verbatim, comments and literal CONTENT are dropped before tokenizing.
 * Two methods with the same sequence are the same shape however they were
 * named.</p>
 *
 * <p>Extracted from {@code FindDuplicateCodeTool} in Sprint 28c so that clone
 * detection and the experience store's snippet matching cannot drift apart:
 * one normalizer, one hash, two callers.</p>
 *
 * <p><b>{@link #groupIdOf} output is a published identifier, not an internal
 * detail.</b> {@code replace_duplicates} re-resolves a clone group statelessly
 * by that id — an agent hands back an id it was given earlier and the tool
 * re-scans to match it. Change anything in this class and every id in flight
 * silently stops resolving, with no compiler error. {@code
 * TokenShapeStabilityTest} pins one known shape's id against exactly that.</p>
 */
public final class TokenShape {

    private TokenShape() {
    }

    private static final Pattern TOKEN_PATTERN = Pattern.compile(
        "\"(?:\\\\.|[^\"\\\\])*\"" +                                            // string literal
        "|'(?:\\\\.|[^'\\\\])*'" +                                              // char literal
        "|\\d+\\.\\d+(?:[eE][+-]?\\d+)?[fFdD]?|\\.\\d+(?:[eE][+-]?\\d+)?[fFdD]?" + // float literal
        "|\\d+[eE][+-]?\\d+[fFdD]?" +                                           // float scientific without dot
        "|\\d+[lL]?" +                                                          // int / long literal
        "|[a-zA-Z_$][a-zA-Z0-9_$]*" +                                           // identifier / keyword
        "|<<=|>>>=|>>=|<<|>>>|>>|<=|>=|==|!=|&&|\\|\\||\\+\\+|--|->|::" +       // multi-char operators
        "|[+\\-*/%&|^~?:;,(){}\\[\\]<>=!.@]"                                    // single-char operators/punctuation
    );

    private static final Set<String> JAVA_KEYWORDS = Set.of(
        "abstract", "assert", "boolean", "break", "byte", "case", "catch",
        "char", "class", "const", "continue", "default", "do", "double",
        "else", "enum", "extends", "final", "finally", "float", "for",
        "goto", "if", "implements", "import", "instanceof", "int",
        "interface", "long", "native", "new", "package", "private",
        "protected", "public", "return", "short", "static", "strictfp",
        "super", "switch", "synchronized", "this", "throw", "throws",
        "transient", "try", "void", "volatile", "while",
        "var", "yield", "record", "sealed", "permits"
        // "non-sealed" is hyphenated; handled by the operator branch via "-" between two ID tokens.
    );

    /**
     * Regex-based tokenizer. JDT's {@code IScanner} would be ideal but is in
     * {@code org.eclipse.jdt.core.compiler}, which the headless Tycho test
     * runtime fails to resolve at OSGi load — Sprint 14 Stage 10 noted this
     * during integration. Regex tokenization is portable, fast enough, and
     * sufficient for clone detection (the normalization step throws away
     * exactness anyway).
     *
     * @param source the raw source characters to tokenize
     * @param seq    receives the space-joined normalized token sequence
     * @return the number of tokens contributed to {@code seq}
     */
    public static int countAndNormalize(char[] source, StringBuilder seq) {
        String src = collapseNoise(new String(source));
        Matcher m = TOKEN_PATTERN.matcher(src);
        int count = 0;
        while (m.find()) {
            String tok = m.group();
            String norm = normalize(tok);
            if (norm == null) continue;
            if (seq.length() > 0) seq.append(' ');
            seq.append(norm);
            count++;
        }
        return count;
    }

    /**
     * Stable id for a clone shape: SHA-1 of the normalized token sequence.
     *
     * @param normalizedSeq the sequence produced by {@link #countAndNormalize}
     * @return a 12-character hex id, stable across calls and processes
     */
    public static String groupIdOf(String normalizedSeq) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(normalizedSeq.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 6 && i < hash.length; i++) {
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            // SHA-1 is mandatory on every JRE; fall back to a plain hash.
            return Integer.toHexString(normalizedSeq.hashCode());
        }
    }

    /**
     * v2.7.1 — linear single-pass scanner: drops comments and collapses
     * string / char / text-block literals to short placeholders BEFORE the
     * regex tokenizer runs.
     *
     * <p>Why not regex: the tokenizer's quantified-alternation literal
     * branches ({@code "(?:\\.|[^"\\])*"}) burn one backtracking frame per
     * matched char — a string literal past ~2k chars overflows the stack.
     * {@link StackOverflowError} is an {@link Error}, so it sailed past every
     * {@code catch (Exception)} and killed the transport worker (dogfood find
     * 2026-07-10: jawata-mcp's own tool descriptions are text blocks far past
     * the threshold — self-scan dropped the socket). A hand scan is O(n) with
     * zero recursion, and also lexes text blocks correctly, which the old
     * regex mis-read as an empty string plus a dangling quote.</p>
     */
    private static String collapseNoise(String src) {
        StringBuilder out = new StringBuilder(src.length());
        int i = 0;
        final int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                // line comment — drop to EOL
                while (i < n && src.charAt(i) != '\n') i++;
                out.append(' ');
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                // block comment / Javadoc — drop to */
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) i++;
                i = Math.min(i + 2, n);
                out.append(' ');
            } else if (c == '"' && i + 2 < n
                    && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"') {
                // text block — collapse to a short string placeholder
                i += 3;
                while (i + 2 < n && !(src.charAt(i) == '"'
                        && src.charAt(i + 1) == '"' && src.charAt(i + 2) == '"')) {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 3, n);
                out.append("\"S\"");
            } else if (c == '"') {
                // string literal — collapse (escape-aware)
                i++;
                while (i < n && src.charAt(i) != '"') {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append("\"S\"");
            } else if (c == '\'') {
                // char literal — collapse (escape-aware)
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    if (src.charAt(i) == '\\') i++;
                    i++;
                }
                i = Math.min(i + 1, n);
                out.append("'c'");
            } else {
                out.append(c);
                i++;
            }
        }
        return out.toString();
    }

    private static String normalize(String token) {
        if (token.isEmpty()) return null;
        char first = token.charAt(0);
        if (first == '"') return "STR";
        if (first == '\'') return "CHAR";
        if (Character.isDigit(first) || (first == '.' && token.length() > 1 && Character.isDigit(token.charAt(1)))) {
            char last = token.charAt(token.length() - 1);
            if (token.contains(".") || token.contains("e") || token.contains("E")
                || last == 'f' || last == 'F' || last == 'd' || last == 'D') {
                return "FLT";
            }
            return "INT";
        }
        if (Character.isJavaIdentifierStart(first)) {
            if ("null".equals(token)) return "NULL";
            if ("true".equals(token) || "false".equals(token)) return "BOOL";
            if (JAVA_KEYWORDS.contains(token)) return token;
            return "ID";
        }
        // Operator / punctuation: keep verbatim
        return token;
    }
}
