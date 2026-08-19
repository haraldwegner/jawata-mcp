package org.jawata.core.project;

/**
 * A dependency the import layer was ASKED for and could not find.
 *
 * <p><strong>Why this exists.</strong> Measured on a live 29-project
 * Eclipse/PDE workspace: jawata reported 1229 compile errors, and 1215 of them
 * came from four projects that resolved <em>no dependencies at all</em>. The
 * discriminator was simply whether a project's classpath carried
 * {@code projectDependencies} — projects that had them carried 2 errors each,
 * projects that had none carried hundreds. Nothing in any response said so.
 * The import layer knew every requirement it had failed to satisfy and wrote
 * each one to {@code log.debug}, which is off by default, reaches no tool
 * response, and is invisible to the person looking at the error count.</p>
 *
 * <p>So a project that silently resolves nothing looks exactly like a project
 * whose code is broken. This type is the difference: it says what was
 * requested, in what form, and why it was not found.</p>
 *
 * @param kind  the requirement's form, as the project declared it —
 *              {@code Require-Bundle}, {@code Import-Package},
 *              {@code JUnit-container}, or a build system's own name
 * @param name  what was requested: a bundle symbolic name, a package name, a
 *              coordinate
 * @param reason why it was not found, in terms the reader can act on — never a
 *              bare "not found", because the cure differs by cause
 */
public record UnresolvedRequirement(String kind, String name, String reason) {

    /** A PDE {@code Require-Bundle} satisfied by neither the workspace nor the pools. */
    public static UnresolvedRequirement requireBundle(String symbolicName) {
        return new UnresolvedRequirement("Require-Bundle", symbolicName,
            "no workspace project registers this symbolic name, and no jar in the external "
                + "bundle pools declares it");
    }

    /** A PDE {@code Import-Package} no indexed bundle exports. */
    public static UnresolvedRequirement importPackage(String packageName) {
        return new UnresolvedRequirement("Import-Package", packageName,
            "no workspace project and no jar in the external bundle pools exports this package");
    }

    /** A JDT JUnit-container bundle missing from the pools. */
    public static UnresolvedRequirement junitContainer(String symbolicName) {
        return new UnresolvedRequirement("JUnit-container", symbolicName,
            "the .classpath declares the JDT JUnit container, but this bundle is not in the "
                + "external pools — JUnit annotations will not resolve");
    }
}
