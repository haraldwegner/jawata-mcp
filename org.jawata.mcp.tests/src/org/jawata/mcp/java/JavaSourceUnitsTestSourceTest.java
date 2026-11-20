package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.Optional;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 28h-C27-24: the test-first check asked a path rule what is test code, beside a model that already knows, because it held no model. The
 * language says what the loaded model marks, and says nothing for a place the model does not hold.
 */
class JavaSourceUnitsTestSourceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JavaSourceUnits units;
    private Path project;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        units = new JavaSourceUnits(service);
        project = helper.getFixturePath("simple-maven");
    }

    @Test
    @DisplayName("a file under a test source root is test code, one under a main root is not, and a test file not written yet is judged by its place")
    void theModelMarksTestCodeByItsSourceRoot() {
        Path main = project.resolve("src/main/java/com/example/Calculator.java");
        Path test = project.resolve("src/test/java/com/example/SampleTest.java");
        Path notWrittenYet = project.resolve("src/test/java/com/example/NewBehaviourTest.java");

        assertEquals(Optional.of(false), units.isTest(main), "a main-root file");
        assertEquals(Optional.of(true), units.isTest(test), "a test-root file");
        assertEquals(Optional.of(true), units.isTest(notWrittenYet), "a test file the worker has just written is not known to the model yet");
    }

    @Test
    @DisplayName("a path outside every loaded project, and a file of a project beside no source root, is not the model's to judge")
    void whatTheModelDoesNotHoldIsLeftToTheCaller() {
        assertEquals(Optional.empty(), units.isTest(Path.of("/tmp/elsewhere/Thing.java")), "outside every project");
        assertEquals(Optional.empty(), units.isTest(project.resolve("pom.xml")), "a project-level file lies under no source root");
    }
}
