package org.jawata.spi.lang;

import java.util.List;

/**
 * The folders a project's code lives in and is built into (Sprint 28k, Stage 9): what coverage evidence records as its provenance and what
 * a mutation run reads the sources from. Absolute paths as text, in the order the language lists them.
 *
 * @param classRoots  where the project's compiled classes are written
 * @param sourceRoots where its source files are
 */
public record ProjectRoots(List<String> classRoots, List<String> sourceRoots) {
}
