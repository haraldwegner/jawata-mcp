package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import org.jawata.core.JdtServiceImpl;
import org.jawata.core.LoadedProject;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.spi.host.BuildKind;
import org.jawata.spi.lang.BuildVerdict;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 28k Stage 2b (E7c), the audit's doubt turned into a measurement: {@code rebuildAfter} finds the projects that require a
 * changed project by reading their resolved classpath for project entries. {@link JavaBuildVerdictRebuildAfterTest} builds that
 * edge by hand; here the edge comes from the importers themselves — Require-Bundle between two PDE bundles, and a Maven
 * dependency between two modules of a reactor — so it is the importers' output that is read, not the test's own.
 */
class RequirerEdgesFromTheImportersTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("a PDE bundle that Require-Bundles a sibling is rebuilt when the sibling changes")
    void pdeRequireBundleIsAnEdge() throws Exception {
        JdtServiceImpl service = helper.loadWorkspaceCopy(new String[] {"pde-reexport-b", "pde-reexport-c"});
        LoadedProject requirer = byDirectory(service, "pde-reexport-b");
        LoadedProject required = byDirectory(service, "pde-reexport-c");

        BuildVerdict.Rebuild rebuild = JavaChange.contextFor(service).verdict()
            .rebuildAfter(List.of(required.projectRoot().resolve("src/Anything.java").toString()));

        assertTrue(rebuild.scope().projects().contains(name(requirer)),
            "the bundle that requires the changed one is in the rebuild: " + rebuild.scope().projects()
            + " (resolved classpath of the requirer: " + classpathOf(requirer) + ")");
        assertEquals(BuildKind.INCREMENTAL, rebuild.kind());
    }

    @Test
    @DisplayName("a PDE bundle that imports a package a sibling bundle exports is rebuilt when the sibling changes")
    void pdeImportPackageIsAnEdge() throws Exception {
        Path importer = helper.copyFixture("pde-bundle-a");
        Path exporter = helper.copyFixture("pde-bundle-b");
        // The fixture's own manifest REQUIRES the sibling bundle; rewrite it to import the package instead, so the only link between
        // the two is the Import-Package header (a test that left the Require-Bundle in would pass on that link alone).
        Path manifest = importer.resolve("META-INF/MANIFEST.MF");
        Files.writeString(manifest, "Manifest-Version: 1.0\nBundle-ManifestVersion: 2\nBundle-Name: PDE Bundle A\n"
            + "Bundle-SymbolicName: org.jawata.fixture.a;singleton:=true\nBundle-Version: 1.0.0.qualifier\n"
            + "Bundle-RequiredExecutionEnvironment: JavaSE-17\nImport-Package: com.example.b\n");
        assertFalse(Files.readString(manifest).contains("Require-Bundle"), "the importer names the sibling by package only");
        JdtServiceImpl service = helper.disposeAfter(new JdtServiceImpl());
        service.loadProject(importer);
        service.addProject(exporter);
        LoadedProject requirer = byDirectory(service, importer.getFileName().toString());
        LoadedProject required = byDirectory(service, exporter.getFileName().toString());

        BuildVerdict.Rebuild rebuild = JavaChange.contextFor(service).verdict()
            .rebuildAfter(List.of(required.projectRoot().resolve("src/Anything.java").toString()));

        assertTrue(rebuild.scope().projects().contains(name(requirer)),
            "the bundle that imports the changed bundle's package must be rebuilt: " + rebuild.scope().projects()
            + " (resolved classpath of the importer: " + classpathOf(requirer) + ")");
        assertEquals(BuildKind.INCREMENTAL, rebuild.kind());
    }

    @Test
    @DisplayName("a Maven reactor is imported as ONE project, so a change in one module is already the owning project's")
    void aReactorIsOneProject() throws Exception {
        JdtServiceImpl service = helper.disposeAfter(new JdtServiceImpl());
        service.loadProject(helper.copyFixture("reactor-cross"));
        assertEquals(1, service.allProjects().size(), "the importer merges the reactor's modules into one project");
        LoadedProject only = service.allProjects().iterator().next();

        BuildVerdict.Rebuild rebuild = JavaChange.contextFor(service).verdict()
            .rebuildAfter(List.of(only.projectRoot().resolve("module-a/src/main/java/com/example/Anything.java").toString()));

        assertEquals(List.of(name(only)), rebuild.scope().projects());
        assertEquals(BuildKind.INCREMENTAL, rebuild.kind());
    }

    private static LoadedProject byDirectory(JdtServiceImpl service, String directoryName) {
        return service.allProjects().stream()
            .filter(p -> p.projectRoot().getFileName().toString().equals(directoryName))
            .findFirst()
            .orElseThrow(() -> new AssertionError(directoryName + " is not loaded: "
                + service.allProjects().stream().map(p -> p.projectRoot().toString()).collect(Collectors.toList())));
    }

    private static String name(LoadedProject project) {
        return project.javaProject().getProject().getName();
    }

    private static List<String> classpathOf(LoadedProject project) {
        List<String> out = new ArrayList<>();
        try {
            for (var entry : project.javaProject().getResolvedClasspath(true)) {
                out.add(entry.getEntryKind() + ":" + entry.getPath());
            }
        } catch (Exception unreadable) {
            out.add("unreadable: " + unreadable);
        }
        return out;
    }
}
