package org.jawata.core.project;

import org.eclipse.jdt.launching.IVMInstallType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Sprint 28 (v3.6.3) — the macOS unbound-JRE defect, pinned on EVERY platform.
 *
 * <p>The failure looked macOS-only and was not. {@code ensureDefaultVm} falls
 * back to registering the running JVM when no default VM exists, and it looked
 * the VM install type up by its CLASS name
 * ({@code org.eclipse.jdt.internal.launching.StandardVMType}) while
 * {@link org.eclipse.jdt.launching.JavaRuntime#getVMInstallType(String)}
 * matches on the EXTENSION id
 * ({@code org.eclipse.jdt.internal.debug.ui.launcher.StandardVMType}). The
 * lookup therefore returned {@code null} everywhere.</p>
 *
 * <p>It stayed invisible because only macOS reaches the fallback: JDT's
 * {@code StandardVMType.detectInstallLocation()} returns {@code null} on macOS
 * and auto-detects everywhere else, so Linux and Windows return before the
 * broken line. On macOS the result was an unbound {@code JRE_CONTAINER} in
 * every generated {@code .classpath} — no JDK type resolved, and every compile
 * came back clean because the compiler never ran.</p>
 *
 * <p>These tests run on the DEVELOPMENT platform and fail there without the
 * fix, which is the point: the previous two macOS patch rounds shipped changes
 * that could only be judged on Harald's Mac.</p>
 */
class ProjectImporterDefaultVmTest {

    /**
     * The lookup resolves. Before the fix this returned {@code null} on Linux
     * too — the assertion that would have caught the defect at desk height.
     */
    @Test
    @DisplayName("JDT's standard VM install type resolves through our lookup")
    void standardVmTypeResolves() {
        IVMInstallType type = ProjectImporter.standardVmType();
        assertNotNull(
            type,
            "no standard VM install type: JRE_CONTAINER cannot be bound on a "
                + "platform where JDT does not auto-detect the running JVM (macOS)");
    }

    /**
     * The defect itself, pinned: the CLASS name is not a valid install-type id.
     *
     * <p>This is the assertion the old code needed and did not have. It fails
     * if someone "simplifies" the constant back to the class name, and it
     * documents why the two strings are not interchangeable.</p>
     */
    @Test
    @DisplayName("the class name is NOT a valid VM-install-type id (the v3.6.2 defect)")
    void theClassNameIsNotAValidTypeId() {
        org.junit.jupiter.api.Assertions.assertNull(
            org.eclipse.jdt.launching.JavaRuntime.getVMInstallType(
                "org.eclipse.jdt.internal.launching.StandardVMType"),
            "if this ever resolves, JDT changed the contributed id and the "
                + "comment on STANDARD_VM_TYPE_ID needs rereading");
    }

    /**
     * And it is the STANDARD type, not merely some registered type — the
     * fallback scan must not settle for {@code EEVMType}, which cannot register
     * a plain JDK directory.
     */
    @Test
    @DisplayName("the resolved type is JDT's StandardVMType implementation")
    void resolvedTypeIsTheStandardImplementation() {
        IVMInstallType type = ProjectImporter.standardVmType();
        assertNotNull(type, "precondition: a standard VM install type resolves");
        assertEquals(
            "org.eclipse.jdt.internal.launching.StandardVMType",
            type.getClass().getName(),
            "the standard type is the one that can register a JDK by directory");
    }

    /**
     * The type can actually create a VM install for the JVM this test runs on.
     * The id lookup succeeding is necessary but not sufficient — this drives
     * the rest of the fallback, which had never once run to completion on any
     * platform.
     */
    @Test
    @DisplayName("the running JVM's java.home can be registered as a VM install")
    void theRunningJvmCanBeRegistered() {
        IVMInstallType type = ProjectImporter.standardVmType();
        assertNotNull(type, "precondition: a standard VM install type resolves");
        java.io.File javaHome = new java.io.File(System.getProperty("java.home", ""));
        assertNotNull(
            type.validateInstallLocation(javaHome),
            "validateInstallLocation returns a status, never null");
        org.junit.jupiter.api.Assertions.assertTrue(
            type.validateInstallLocation(javaHome).isOK(),
            "the JVM running this test must be registrable as a VM install; "
                + "java.home=" + javaHome);
    }
}
