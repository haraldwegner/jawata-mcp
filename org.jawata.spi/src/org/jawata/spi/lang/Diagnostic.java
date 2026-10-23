package org.jawata.spi.lang;

/**
 * One error a build left: the file it is in (null when it is about a project, not a file), its 1-based line (-1 when there is
 * none), and the language's own message.
 */
public record Diagnostic(String filePath, int line, String message) {
}
