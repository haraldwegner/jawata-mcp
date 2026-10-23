package org.jawata.spi.lang;

import java.nio.file.Path;
import java.util.Optional;

import org.eclipse.core.resources.IFile;

/**
 * How a language names the files a change touches: the path string every answer shows, and the file behind an element a change
 * reports as modified when that element is the language's own (Java: a compilation unit). A plain {@link IFile} needs no language.
 */
public interface ChangePaths {

    /** The path every answer and every compile-check key shows for this file. */
    String display(Path absolute);

    /** The file behind a modified element of this language's own kind; empty for anything else. */
    Optional<IFile> fileOf(Object modifiedElement);

    /** No language: the absolute path as it is, and no element of its own. */
    ChangePaths ABSOLUTE = new ChangePaths() {
        @Override
        public String display(Path absolute) {
            return absolute.toString();
        }

        @Override
        public Optional<IFile> fileOf(Object modifiedElement) {
            return Optional.empty();
        }
    };
}
