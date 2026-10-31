package org.jawata.mcp.access;

import java.util.ArrayList;
import java.util.List;

/**
 * A shell command read as words, the way a shell reads them (Sprint 28h, S13, D20, ACL7), so that the places a command names are the
 * words that are places and the sentences it carries are not.
 *
 * <p>Single quotes keep everything; double quotes keep everything but a backslash escape; outside quotes a backslash escapes the next
 * character, and whitespace and the operators {@code ; | & < > ( )} end a word. A here-document ({@code <<WORD}, {@code <<-WORD},
 * quoted or not) is data, so its body, up to the line that is the delimiter, is skipped. Substitutions are not expanded: a word is
 * what is written.</p>
 */
final class Shell {

    private Shell() {
    }

    static List<String> words(String command) {
        List<String> words = new ArrayList<>();
        List<String> heredocs = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean inWord = false;
        int i = 0;
        int n = command.length();
        while (i < n) {
            char c = command.charAt(i);
            if (c == '\'') {
                int end = command.indexOf('\'', i + 1);
                int stop = end < 0 ? n : end;
                word.append(command, i + 1, stop);
                inWord = true;
                i = stop + 1;
            } else if (c == '"') {
                i++;
                while (i < n && command.charAt(i) != '"') {
                    if (command.charAt(i) == '\\' && i + 1 < n) {
                        i++;
                    }
                    word.append(command.charAt(i));
                    i++;
                }
                inWord = true;
                i++;
            } else if (c == '\\' && i + 1 < n) {
                word.append(command.charAt(i + 1));
                inWord = true;
                i += 2;
            } else if (c == '<' && i + 1 < n && command.charAt(i + 1) == '<' && !(i + 2 < n && command.charAt(i + 2) == '<')) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
                int at = i + 2;
                if (at < n && command.charAt(at) == '-') {
                    at++;
                }
                while (at < n && command.charAt(at) == ' ') {
                    at++;
                }
                StringBuilder delimiter = new StringBuilder();
                char quote = at < n && (command.charAt(at) == '\'' || command.charAt(at) == '"') ? command.charAt(at++) : 0;
                while (at < n && (quote != 0 ? command.charAt(at) != quote : !isEnd(command.charAt(at)))) {
                    delimiter.append(command.charAt(at++));
                }
                if (quote != 0 && at < n) {
                    at++;
                }
                if (delimiter.length() > 0) {
                    heredocs.add(delimiter.toString());
                }
                i = at;
            } else if (c == '\n' && !heredocs.isEmpty()) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
                i = skipBodies(command, i + 1, heredocs);
                heredocs.clear();
            } else if (Character.isWhitespace(c) || ";|&<>()".indexOf(c) >= 0) {
                if (inWord) {
                    words.add(word.toString());
                    word.setLength(0);
                    inWord = false;
                }
                i++;
            } else {
                word.append(c);
                inWord = true;
                i++;
            }
        }
        if (inWord) {
            words.add(word.toString());
        }
        return words;
    }

    private static boolean isEnd(char c) {
        return Character.isWhitespace(c) || ";|&<>()".indexOf(c) >= 0;
    }

    /** The index just after the last pending here-document body, each ending at a line that is its delimiter. */
    private static int skipBodies(String command, int from, List<String> delimiters) {
        int at = from;
        for (String delimiter : delimiters) {
            while (at < command.length()) {
                int end = command.indexOf('\n', at);
                String line = end < 0 ? command.substring(at) : command.substring(at, end);
                at = end < 0 ? command.length() : end + 1;
                if (line.strip().equals(delimiter)) {
                    break;
                }
            }
        }
        return at;
    }
}
