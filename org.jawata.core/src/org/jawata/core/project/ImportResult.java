package org.jawata.core.project;

import java.util.List;

import org.eclipse.jdt.core.IJavaProject;

/**
 * What an import produced, and what it could not.
 *
 * <p>{@code configureJavaProject} used to return the {@link IJavaProject}
 * alone, which made the import's failures unreturnable: the requirements it
 * could not satisfy were known inside {@code addDependencyEntries} and went to
 * {@code log.debug}, because there was nowhere else for them to go. A method
 * that can only report success will only ever report success.</p>
 *
 * <p>So the result carries both halves. {@code unresolved} is EMPTY when
 * everything resolved — never null, and never absent — so a reader can tell
 * "asked and satisfied" from "never asked".</p>
 *
 * @param javaProject the configured project
 * @param unresolved  every requirement the import was asked for and could not find
 */
public record ImportResult(IJavaProject javaProject, List<UnresolvedRequirement> unresolved) {

    public ImportResult {
        unresolved = unresolved == null ? List.of() : List.copyOf(unresolved);
    }
}
