package org.jawata.core.project;

import org.eclipse.core.resources.IProject;
import org.eclipse.core.resources.IResource;
import org.eclipse.jdt.core.IClasspathAttribute;
import org.eclipse.jdt.core.IClasspathEntry;
import org.eclipse.jdt.core.IJavaProject;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.JavaModelException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Classifies a workspace resource as MAIN or TEST by reading the model — the
 * {@link IClasspathAttribute#TEST} attribute the importer records on every
 * source entry — never by guessing from names.
 *
 * <p>Sprint 28 Stage 3 (D-UNWIRED's consumer), and the actual fix for
 * jawata-mcp#9. The previous classification guessed from two conventions: the
 * {@code src/main}/{@code src/test} path segments, and the project name ending
 * in {@code .tests}. The first says nothing about flat PDE-bundle layouts; the
 * second was UNREACHABLE in every live workspace, because loaded projects are
 * named {@code jawata-<dir>-<session>}, never by their bundle name — the
 * branch existed, had a green unit test feeding it hand-written names, and
 * could not fire in production. A {@code .java} catch-all then swept every
 * flat-layout file into MAIN, which is why {@code scope=main} returned
 * jawata's own test bundles and {@code scope=test} returned nothing.</p>
 *
 * <p>The importer has known main-from-test all along (Stage 2 records it);
 * this class is the read side. No convention, no name pattern: the answer
 * comes from the same model the compiler uses.</p>
 */
public final class SourceRootClassifier {

    private static final Logger log = LoggerFactory.getLogger(SourceRootClassifier.class);

    /** What the model says about a resource. */
    public enum Verdict {
        /** Under a source root not tagged test. */
        MAIN,
        /** Under a source root tagged {@code test=true}. */
        TEST,
        /**
         * Under no source root at all — project-level files (manifests, build
         * scripts, markers on the project itself). Cross-cutting: visible in
         * every scope, EXCEPT in a project whose every source root is test
         * code, where the project itself is the test half and its files
         * classify TEST (the pinned C8-F4 semantics, now derived from the
         * model instead of from a name).
         */
        CROSS_CUTTING
    }

    private SourceRootClassifier() {
    }

    /**
     * Classify {@code resource} by the source entry that contains it.
     *
     * <p>Returns {@link Verdict#CROSS_CUTTING} — never a guess — when the
     * resource has no project, the project is not a Java project, or the
     * resource sits under no source root. Errors reading the classpath are
     * logged and fall open the same way: a classification tool must degrade to
     * "visible", never to "hidden".</p>
     */
    public static Verdict classify(IResource resource) {
        if (resource == null) {
            return Verdict.CROSS_CUTTING;
        }
        IProject project = resource.getProject();
        if (project == null) {
            return Verdict.CROSS_CUTTING;
        }
        try {
            IJavaProject javaProject = JavaCore.create(project);
            if (javaProject == null || !javaProject.exists()) {
                return Verdict.CROSS_CUTTING;
            }
            IClasspathEntry[] entries = javaProject.getRawClasspath();
            org.eclipse.core.runtime.IPath resourcePath = resource.getFullPath();

            IClasspathEntry containing = null;
            for (IClasspathEntry entry : entries) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE
                        && entry.getPath().isPrefixOf(resourcePath)) {
                    // The DEEPEST containing root decides, matching JDT's own
                    // resolution when roots nest.
                    if (containing == null
                            || entry.getPath().segmentCount()
                                > containing.getPath().segmentCount()) {
                        containing = entry;
                    }
                }
            }
            if (containing != null) {
                return isTestEntry(containing) ? Verdict.TEST : Verdict.MAIN;
            }
            // No containing root: project-level. A project whose EVERY source
            // root is test code IS the test half — its manifest problems
            // belong to the test scope, not to both.
            boolean sawSource = false;
            boolean allTest = true;
            for (IClasspathEntry entry : entries) {
                if (entry.getEntryKind() == IClasspathEntry.CPE_SOURCE) {
                    sawSource = true;
                    if (!isTestEntry(entry)) {
                        allTest = false;
                        break;
                    }
                }
            }
            return (sawSource && allTest) ? Verdict.TEST : Verdict.CROSS_CUTTING;
        } catch (JavaModelException e) {
            log.debug("Could not read the classpath of {} to classify {}: {}",
                project.getName(), resource.getFullPath(), e.getMessage());
            return Verdict.CROSS_CUTTING;
        }
    }

    /** The {@link IClasspathAttribute#TEST} flag of one entry. */
    public static boolean isTestEntry(IClasspathEntry entry) {
        for (IClasspathAttribute attribute : entry.getExtraAttributes()) {
            if (IClasspathAttribute.TEST.equals(attribute.getName())
                    && "true".equals(attribute.getValue())) {
                return true;
            }
        }
        return false;
    }
}
