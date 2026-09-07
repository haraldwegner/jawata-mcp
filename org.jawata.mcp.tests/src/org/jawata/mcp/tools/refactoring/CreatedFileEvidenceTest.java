package org.jawata.mcp.tools.refactoring;

import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.refactoring.CompileVerify;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * jawata-mcp#69 — WHEN THE GATE REFUSES OVER A TYPE IT JUST CREATED, IT SAYS WHAT IT WROTE.
 *
 * <p>On the macOS matrix job at the v4.1.0 tag, {@code combine_functions} was refused on
 * upstream code with <i>"App.java: the method getGroupingOfCarsByCategory() from the type
 * CarQueries refers to the missing type Car"</i>. {@code CarQueries} is the class the
 * operation had just created; {@code Car} sits in its own package. The reader is told a type
 * is missing and NOTHING about the file that was supposed to declare it — and the change is
 * undone immediately afterwards, so by the time anyone asks, the answer no longer exists.</p>
 *
 * <p>Diagnosing it from the log alone cost a whole exchange and did not settle it. That is
 * what a failure naming nothing costs, and this project already had the rule: put it in the
 * response.</p>
 *
 * <h2>What is tested here, and what deliberately is not</h2>
 *
 * <p>This covers the EVIDENCE half — that the gate reads a created file's package and imports
 * back while they still exist. The other half of #69 is {@code CompileVerify.settle}, which
 * makes the model see a created file before the modified files are re-parsed. <b>That half
 * has no test and cannot honestly have one here:</b> the timing window it closes does not
 * open on this machine — Linux resolved the same slice correctly in the same CI run that
 * failed on macOS — so any assertion written for it would pass before the fix as well as
 * after, which is the vacuous shape this repository keeps having to remove. Its control is
 * the next macOS run, and saying so is better than a green test that measures nothing.</p>
 */
class CreatedFileEvidenceTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
    }

    private Path write(String simpleName, String body) throws Exception {
        Path file = pkg.resolve(simpleName + ".java");
        Files.writeString(file, body);
        return file;
    }

    @Test
    @DisplayName("a created file is reported with the package and imports that decide what it can see")
    void aCreatedFileIsReportedWithWhatItDeclares() throws Exception {
        Path created = write("GateEvidenceCarQueries", """
            package com.example;

            import java.util.List;

            class GateEvidenceCarQueries {
                private final List<String> cars;
                GateEvidenceCarQueries(List<String> cars) { this.cars = cars; }
            }
            """);

        // before = the map as it stood BEFORE the change: this file is absent from it, which
        // is exactly how the gate recognises a file the change created.
        String evidence = CompileVerify.createdFileContext(service, Map.of(),
            List.of(created.toString()));

        assertTrue(evidence.contains("GateEvidenceCarQueries.java"),
            "the refusal must name the file it created: " + evidence);
        assertTrue(evidence.contains("package com.example;"),
            "the package line is what decides whether a same-package type resolves — it is "
                + "the whole question the macOS refusal could not answer: " + evidence);
        assertTrue(evidence.contains("import java.util.List;"),
            "and the imports, for the cross-package half of the same question: " + evidence);
        assertFalse(evidence.contains("private final"),
            "the BODY is deliberately not carried — this rides in an error message, and a "
                + "whole generated class there is noise rather than evidence: " + evidence);
    }

    @Test
    @DisplayName("a file the change only MODIFIED is not reported as created")
    void aModifiedFileIsNotReportedAsCreated() throws Exception {
        Path existing = write("GateEvidenceAlreadyThere", """
            package com.example;

            class GateEvidenceAlreadyThere { }
            """);

        // Present in `before` = it existed when the change started. This is the control: if
        // it fired for every touched file, the first test would pass while the distinction
        // the gate rests on had been lost.
        Map<String, Set<String>> before = Map.of(existing.toString(), Set.of());
        String evidence = CompileVerify.createdFileContext(service, before,
            List.of(existing.toString()));

        assertTrue(evidence.isEmpty(),
            "a modified file is verified in full and needs no such note; reporting it would "
                + "put a stanza on every ordinary refusal: " + evidence);
    }

    @Test
    @DisplayName("a created file that declares no package is reported AS declaring none")
    void aFileWithNoPackageSaysSo() throws Exception {
        // The shape most likely to cause the macOS error: a generated class in the default
        // package cannot see a type in com.example, however correct the rest of it is. An
        // empty note here would leave a reader unable to tell "no package" from "not read".
        Path created = write("GateEvidenceNoPackage", "class GateEvidenceNoPackage { }\n");

        String evidence = CompileVerify.createdFileContext(service, Map.of(),
            List.of(created.toString()));

        assertTrue(evidence.contains("GateEvidenceNoPackage.java"), evidence);
        assertTrue(evidence.contains("no package or import declarations"),
            "an absence must be stated, not left as a blank the reader has to interpret: "
                + evidence);
    }
}
