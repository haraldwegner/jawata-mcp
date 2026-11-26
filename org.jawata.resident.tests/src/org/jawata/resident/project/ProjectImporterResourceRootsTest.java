package org.jawata.resident.project;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 28h z104 and z151: the folders a project's tests read their files from, which the test runner puts on its classpath. */
class ProjectImporterResourceRootsTest {

    private static void write(Path file, String text) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
    }

    @Test
    @DisplayName("28h z104: every module's test resources, then its main resources, a declared folder with its target path counted in")
    void theResourceFoldersOfAMavenTreeAreRoots(@TempDir Path root) throws IOException {
        write(root.resolve("pom.xml"), "<project><packaging>pom</packaging><modules><module>lib</module><module>build/tests</module></modules></project>");
        write(root.resolve("lib/pom.xml"), "<project/>");
        Path libTest = Files.createDirectories(root.resolve("lib/src/test/resources"));
        Path libMain = Files.createDirectories(root.resolve("lib/src/main/resources"));
        write(root.resolve("build/tests/pom.xml"), """
            <project><build>
              <resources><resource>
                <directory>${project.basedir}/../../bundle/test-resources</directory>
                <targetPath>test-resources</targetPath>
              </resource></resources>
              <plugins><plugin><configuration><resources><resource>
                <directory>${project.basedir}/../../not-of-the-build</directory>
              </resource></resources></configuration></plugin></plugins>
            </build></project>
            """);
        Files.createDirectories(root.resolve("bundle/test-resources"));
        Files.createDirectories(root.resolve("not-of-the-build"));

        assertEquals(List.of(libTest, libMain, root.resolve("bundle")), new ProjectImporter().resourceRoots(root));
    }
}
