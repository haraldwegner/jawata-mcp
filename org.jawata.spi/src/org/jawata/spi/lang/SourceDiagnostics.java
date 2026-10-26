package org.jawata.spi.lang;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What {@code get_diagnostics} asks a language (Sprint 28k, Stage 9; contract §4.5): the errors and warnings it finds in one source
 * file right now, without a build, or in each file of a project in turn. The language gets the workspace back that it answered, so a
 * call narrowed to one project asks about that project only.
 */
public interface SourceDiagnostics {

    /**
     * @param workspace the workspace the language answered, possibly narrowed to one project
     * @param file      a source file
     * @return its errors and warnings, none when it has none, or empty when the language does not know the file; no other severity is
     *         ever returned
     */
    Optional<List<Row>> inFile(LoadedWorkspace workspace, Path file);

    /**
     * Checks the files of the workspace one after another and hands each one's problems to the visitor, until the visitor says it has
     * enough: a project of thousands of files is not all read to answer for the first hundred problems.
     *
     * @param workspace the workspace the language answered, possibly narrowed to one project
     * @param visitor   what takes each file's problems
     */
    void inEveryFile(LoadedWorkspace workspace, FileVisitor visitor);

    /** Takes one file's problems. */
    interface FileVisitor {

        /**
         * @param file a source file the language checked
         * @param rows its errors and warnings, none when it has none
         * @return whether to go on to the next file
         */
        boolean visit(Path file, List<Row> rows);
    }

    /**
     * One problem in a source file. The offsets, the id and the category are what the language knows of it, absent when it knows none.
     *
     * @param line        the line, counted from zero
     * @param startOffset where the problem starts in the file, or null
     * @param endOffset   where it ends, or null
     * @param severity    {@code error} or {@code warning}
     * @param message     what is wrong
     * @param problemId   the language's own number for this kind of problem, or null
     * @param category    the language's name for the kind of problem, or null
     */
    record Row(int line, Integer startOffset, Integer endOffset, String severity, String message, Integer problemId, String category) {
    }
}
