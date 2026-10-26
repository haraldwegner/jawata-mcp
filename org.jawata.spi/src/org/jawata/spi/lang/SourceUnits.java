package org.jawata.spi.lang;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * What the experience store's describe, jobs_for and duplicate_check verbs ask a language about its source files (Sprint 28k, Stage 9;
 * contract §4.5): which files the loaded projects hold, what one file declares, and its methods with the lines they occupy. All plain
 * types: the store describes code in a language it cannot read itself.
 */
public interface SourceUnits {

    /** @return every source file of the loaded projects, in the order the language lists them */
    List<Path> files();

    /**
     * What one file declares.
     *
     * @param file a source file
     * @return its facts, or empty when the language has no such file in a loaded project
     * @throws IOException when the file is in a loaded project and cannot be read
     */
    Optional<SourceFacts> facts(Path file) throws IOException;

    /**
     * The methods one file declares, nested types included.
     *
     * @param file      a source file
     * @param withLines whether to work out the lines each method occupies (it needs the file's text), or leave them at zero
     * @return its methods in declaration order, or empty when the language has no such file in a loaded project
     * @throws IOException when the file is in a loaded project and cannot be read
     */
    Optional<List<MemberAt>> methods(Path file, boolean withLines) throws IOException;

    /**
     * What a file declares.
     *
     * @param packageName the package it declares, empty for none
     * @param bundle      the project that owns it, or null when it has none
     * @param source      its text
     * @param types       the types declared at its top level
     */
    record SourceFacts(String packageName, String bundle, String source, List<TypeFacts> types) {
    }

    /**
     * A type and what it holds.
     *
     * @param name    the type's fully qualified name
     * @param members the names of its methods, then of its fields
     */
    record TypeFacts(String name, List<String> members) {
    }

    /**
     * One method of a file.
     *
     * @param owner     the fully qualified name of the type that declares it
     * @param name      its name
     * @param firstLine the first line it occupies, counted from one, its documentation comment included; zero when not asked for
     * @param lastLine  the last line it occupies; zero when not asked for
     */
    record MemberAt(String owner, String name, int firstLine, int lastLine) {
    }
}
