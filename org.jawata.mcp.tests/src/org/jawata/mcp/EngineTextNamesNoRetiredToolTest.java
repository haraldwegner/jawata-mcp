package org.jawata.mcp;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.jawata.mcp.fixtures.InertShippedTools;
import org.jawata.mcp.tools.Tool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * THE ENGINE DOES NOT TELL A CALLER TO CALL A TOOL IT NO LONGER HAS.
 *
 * <p>The retired-name table answers a caller who calls an old name. This is the other half: what the
 * engine itself SAYS. After every refactoring the response carried "undo_refactoring with the
 * undoChangeId if verification fails", and the published description of {@code rename_symbol} said
 * "revert with undo_refactoring(undoChangeId)": instructions, written by the product, to call a tool
 * the product retired. A caller who followed them got "Tool not found". Nothing checked the product's
 * own sentences against the product's own tool list, so four patches had corrected other copies of the
 * surface while the engine went on teaching the old names in every response.</p>
 *
 * <p><b>What counts as a retired name here</b>: a name the record ({@code build/published-tool-names.txt})
 * marks retired, MINUS every value any live tool's input schema offers. {@code encapsulate_field} was a
 * tool and is now also a legitimate {@code kind} of {@code data}; a sentence saying
 * {@code kind=encapsulate_field} is right, and a check that flagged it would teach people to ignore it.</p>
 *
 * <p><b>Two scopes</b>: the published tool descriptions and schema descriptions (what a client's
 * {@code tools/list} carries), and every string literal in the engine's production source (what a
 * response, an error or a hint can say). A literal that is EXACTLY a retired name is an identity, not
 * guidance (a delegate's {@code getName}, a map key) and is not counted; a sentence containing one is.
 * Comments are not text the engine emits and are skipped.</p>
 *
 * <p>{@code ToolRegistry.java} is excluded as a whole: its table is where retired names belong.</p>
 */
class EngineTextNamesNoRetiredToolTest {

    private static final String SOURCE = "org.jawata.mcp/src";

    private static Set<String> retiredNames() {
        Path file = EveryCrossBundlePackageIsImportedTest.repoRoot()
            .resolve("build").resolve("published-tool-names.txt");
        Set<String> out = new TreeSet<>();
        try {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                if (line.isBlank() || line.startsWith("#")) {
                    continue;
                }
                String[] f = line.split("\t");
                if (f.length == 4 && f[1].equals("retired")) {
                    out.add(f[0]);
                }
            }
        } catch (IOException e) {
            throw new AssertionError("cannot read " + file + ": " + e + " - this check did NOT run", e);
        }
        return out;
    }

    /** Retired names that are not also a value some live tool's schema accepts. */
    private static Set<String> staleNames() {
        Set<String> stale = retiredNames();
        Set<String> offered = new TreeSet<>();
        for (Tool tool : InertShippedTools.tools()) {
            collectEnumValues(tool.getInputSchema(), offered);
        }
        stale.removeAll(offered);
        assertTrue(stale.size() >= 40,
            "only " + stale.size() + " stale names remain after removing the schema values; the record or"
                + " the schemas were read wrongly and this check would pass over nothing");
        return stale;
    }

    private static void collectEnumValues(Object node, Set<String> into) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if ("enum".equals(e.getKey()) && e.getValue() instanceof List<?> values) {
                    for (Object v : values) {
                        into.add(String.valueOf(v));
                    }
                } else {
                    collectEnumValues(e.getValue(), into);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collectEnumValues(v, into);
            }
        }
    }

    private static Pattern wholeWord(String name) {
        return Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(name) + "(?![A-Za-z0-9_])");
    }

    private static List<String> namesIn(String text, Set<String> stale) {
        List<String> hit = new ArrayList<>();
        for (String name : stale) {
            if (wholeWord(name).matcher(text).find()) {
                hit.add(name);
            }
        }
        return hit;
    }

    // ------------------------------------------------------------------ what tools/list carries

    @Test
    @DisplayName("no published tool or schema description tells a caller to call a retired tool")
    void thePublishedDescriptionsNameNoRetiredTool() {
        Set<String> stale = staleNames();
        List<String> complaints = new ArrayList<>();
        for (Tool tool : InertShippedTools.tools()) {
            List<String> inDescription = namesIn(String.valueOf(tool.getDescription()), stale);
            if (!inDescription.isEmpty()) {
                complaints.add(tool.getName() + " description names " + inDescription);
            }
            List<String> texts = new ArrayList<>();
            collectDescriptions(tool.getInputSchema(), texts);
            for (String text : texts) {
                List<String> inSchema = namesIn(text, stale);
                if (!inSchema.isEmpty()) {
                    complaints.add(tool.getName() + " schema text names " + inSchema + ": "
                        + text.substring(0, Math.min(90, text.length())));
                }
            }
        }
        assertTrue(complaints.isEmpty(), complaints.size() + " published text(s) tell a caller to call a"
            + " retired tool:\n  " + String.join("\n  ", complaints));
    }

    private static void collectDescriptions(Object node, List<String> into) {
        if (node instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if ("description".equals(e.getKey()) && e.getValue() instanceof String s) {
                    into.add(s);
                } else {
                    collectDescriptions(e.getValue(), into);
                }
            }
        } else if (node instanceof List<?> list) {
            for (Object v : list) {
                collectDescriptions(v, into);
            }
        }
    }

    // ------------------------------------------------- what a response, an error or a hint can say

    @Test
    @DisplayName("no string the engine can emit tells a caller to call a retired tool")
    void theEnginesOwnSentencesNameNoRetiredTool() throws IOException {
        Set<String> stale = staleNames();
        Set<String> retired = retiredNames();
        Path root = EveryCrossBundlePackageIsImportedTest.repoRoot().resolve(SOURCE);
        List<String> complaints = new ArrayList<>();
        int scanned = 0;
        try (Stream<Path> files = Files.walk(root)) {
            for (Path file : (Iterable<Path>) files.filter(p -> p.toString().endsWith(".java"))::iterator) {
                if (file.getFileName().toString().equals("ToolRegistry.java")) {
                    continue;
                }
                scanned++;
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // A class whose own getName() is a retired name is a RETIRED DELEGATE: the front door
                // that replaced it still calls it, but nothing publishes its description or its input
                // schema (the first test reads what IS published, from every live tool). Its prose
                // is dead; what it builds inside execute() is not, and is still read.
                boolean retiredDelegate = namesItself(source, retired);
                for (Literal literal : literalsIn(source)) {
                    if (retiredDelegate && literal.unpublishedProse()) {
                        continue;
                    }
                    if (stale.contains(literal.text())) {
                        continue; // an identity (a getName, a map key), not a sentence
                    }
                    List<String> names = namesIn(literal.text(), stale);
                    if (!names.isEmpty()) {
                        complaints.add(root.relativize(file) + ":" + literal.line() + " names " + names + " in \""
                            + literal.text().substring(0, Math.min(80, literal.text().length())).replace("\n", " ")
                            + "\"");
                    }
                }
            }
        }
        assertTrue(scanned > 300, "only " + scanned + " source files were read under " + root
            + "; the walk found the wrong tree and this check would pass over nothing");
        assertTrue(complaints.isEmpty(), complaints.size() + " string literal(s) in the engine's production"
            + " source tell a caller to call a retired tool:\n  " + String.join("\n  ", complaints));
    }

    private static final Pattern GETNAME = Pattern.compile(
        "getName\\s*\\(\\s*\\)\\s*\\{\\s*return\\s+\"([a-z][a-z0-9_]*)\"\\s*;");

    /** Whether this source's own {@code getName()} returns one of the given retired names. */
    private static boolean namesItself(String source, Set<String> retired) {
        java.util.regex.Matcher m = GETNAME.matcher(source);
        return m.find() && retired.contains(m.group(1));
    }

    /**
     * A string literal's text, the line it starts on, and whether it sits inside a
     * {@code getDescription()} or {@code getInputSchema()} body: prose a client reads only if some
     * live tool publishes it.
     */
    record Literal(String text, int line, boolean unpublishedProse) {
    }

    /**
     * The string literals of one Java source, comments skipped. A small tokenizer and not a regex,
     * because a quote inside a comment or a character literal ({@code '"'}) would otherwise start a
     * literal that never ends. Text blocks are read; escapes are kept as written, which is enough to
     * find a whole word. Braces are counted so a literal can say whether it is inside a
     * {@code getDescription()} or {@code getInputSchema()} body.
     */
    static List<Literal> literalsIn(String src) {
        List<Literal> out = new ArrayList<>();
        java.util.ArrayDeque<Integer> proseDepths = new java.util.ArrayDeque<>();
        StringBuilder recent = new StringBuilder();
        int depth = 0;
        int i = 0;
        int line = 1;
        int n = src.length();
        while (i < n) {
            char c = src.charAt(i);
            if (c == '{') {
                String tail = recent.toString();
                if (tail.endsWith("getDescription()") || tail.endsWith("getInputSchema()")) {
                    proseDepths.push(depth);
                }
                depth++;
                recent.setLength(0);
                i++;
            } else if (c == '}') {
                depth--;
                if (!proseDepths.isEmpty() && proseDepths.peek() == depth) {
                    proseDepths.pop();
                }
                recent.setLength(0);
                i++;
            } else if (!Character.isWhitespace(c) && c != '/' && c != '"' && c != '\'') {
                recent.append(c);
                if (recent.length() > 40) {
                    recent.delete(0, recent.length() - 40);
                }
                i++;
            } else if (c == '\n') {
                line++;
                i++;
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '/') {
                while (i < n && src.charAt(i) != '\n') {
                    i++;
                }
            } else if (c == '/' && i + 1 < n && src.charAt(i + 1) == '*') {
                i += 2;
                while (i + 1 < n && !(src.charAt(i) == '*' && src.charAt(i + 1) == '/')) {
                    if (src.charAt(i) == '\n') {
                        line++;
                    }
                    i++;
                }
                i += 2;
            } else if (c == '\'') {
                i++;
                while (i < n && src.charAt(i) != '\'') {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                i++;
            } else if (c == '"' && src.startsWith("\"\"\"", i)) {
                int start = line;
                int from = i + 3;
                int end = src.indexOf("\"\"\"", from);
                if (end < 0) {
                    break;
                }
                String body = src.substring(from, end);
                out.add(new Literal(body, start, !proseDepths.isEmpty()));
                for (int k = i; k < end + 3; k++) {
                    if (src.charAt(k) == '\n') {
                        line++;
                    }
                }
                i = end + 3;
            } else if (c == '"') {
                int start = i + 1;
                i++;
                while (i < n && src.charAt(i) != '"' && src.charAt(i) != '\n') {
                    i += src.charAt(i) == '\\' ? 2 : 1;
                }
                out.add(new Literal(src.substring(start, Math.min(i, n)), line, !proseDepths.isEmpty()));
                i++;
            } else {
                i++;
            }
        }
        return out;
    }

    // ------------------------------------------------------------------ the reader reads

    @Test
    @DisplayName("the literal reader skips comments and character literals and reads text blocks")
    void theLiteralReaderReadsOnlyWhatTheEngineCanSay() {
        String src = "// \"in a line comment\"\n"
            + "/* \"in a block\n comment\" */\n"
            + "char q = '\"';\n"
            + "String a = \"one\" + \"two \\\" still two\";\n"
            + "String b = \"\"\"\n    block text\n    \"\"\";\n";
        List<Literal> got = literalsIn(src);
        assertTrue(got.stream().map(Literal::text).toList().containsAll(List.of("one")),
            "plain literals are read: " + got);
        assertTrue(got.stream().noneMatch(l -> l.text().contains("comment")),
            "comments are not text the engine emits: " + got);
        assertTrue(got.stream().anyMatch(l -> l.text().contains("block text")), "text blocks are read: " + got);
        assertTrue(got.stream().anyMatch(l -> l.text().startsWith("two")), "an escaped quote does not end"
            + " the literal: " + got);
        assertTrue(got.stream().filter(l -> l.text().equals("one")).findFirst().orElseThrow().line() == 5,
            "line numbers follow the source: " + got);
    }
}
