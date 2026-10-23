package org.jawata.spi.lang;

/** Where a symbol lives now: its file, and its 1-based line (-1 when the language could not read one). */
public record Location(String filePath, int line) {
}
