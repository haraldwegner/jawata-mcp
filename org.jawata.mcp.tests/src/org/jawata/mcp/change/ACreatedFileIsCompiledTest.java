package org.jawata.mcp.change;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.eclipse.core.resources.IFile;
import org.eclipse.core.runtime.Path;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.java.JavaChange;
import org.jawata.mcp.refactoring.CreateCompilationUnitChange;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 28h z155: a file a change creates was checked for syntax only, because parsing it alone cannot resolve every sibling. A refactoring so
 * applied a class that did not compile and answered success. The created file's errors are now read from a real build of its project, and
 * a staged change shows the file it would create.
 */
class ACreatedFileIsCompiledTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static IFile newFileBesideCalculator(JdtServiceImpl service) throws Exception {
        return service.getJavaProject().findType("com.example.Calculator").getResource().getParent().getFile(new Path("Broken.java"));
    }

    @Test
    @DisplayName("a change that creates a file using a type that does not exist is refused, and the file is not left behind")
    void aCreatedFileThatDoesNotCompileIsRefused() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        IFile file = newFileBesideCalculator(service);

        GatedApply.Result result = GatedApply.perform(
            new CreateCompilationUnitChange(file, "package com.example;\n\npublic class Broken {\n    Missing missing;\n}\n"),
            JavaChange.contextFor(service), GatedApply.Mode.UNDO);

        assertAll(
            () -> assertTrue(result.refused(), "a created class that does not compile was accepted"),
            () -> assertTrue(String.valueOf(result.failure()).contains("Missing"), "the refusal does not name the error: " + result.failure()),
            () -> assertFalse(file.getLocation().toFile().exists(), "the broken file was left behind"));
    }

    @Test
    @DisplayName("CONTROL: a created file that compiles is applied")
    void aCreatedFileThatCompilesIsApplied() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        IFile file = newFileBesideCalculator(service);

        GatedApply.Result result = GatedApply.perform(
            new CreateCompilationUnitChange(file, "package com.example;\n\npublic class Broken {\n    Calculator calculator;\n}\n"),
            JavaChange.contextFor(service), GatedApply.Mode.UNDO);

        assertAll(
            () -> assertFalse(result.refused(), "a correct new class was refused: " + result.failure()),
            () -> assertTrue(file.getLocation().toFile().exists()));
    }

    @Test
    @DisplayName("the preview of a change that creates a file shows the file's lines")
    void thePreviewShowsTheCreatedFile() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");

        String diff = ChangeEngine.previewDiff(
            new CreateCompilationUnitChange(newFileBesideCalculator(service), "package com.example;\n\npublic class Broken {\n}\n"),
            JavaChange.contextFor(service));

        assertTrue(diff.contains("+public class Broken {"), "the new file is not in the preview: " + diff);
    }
}
