package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FEATURE ENVY MUST NAME A TYPE THE METHOD CAN ACTUALLY MOVE TO.
 *
 * <p>Found by dogfooding v4.1.0 against this repository. {@code JdtServiceImpl#addProject}
 * was reported as envying {@code LoadedProject}, and {@code move kind=method} — the operation
 * the message itself names — answered <i>"several possible targets: pathUtils,
 * projectImporter, searchService, workspaceManager"</i>. {@code LoadedProject} was not among
 * them: that door moves a method onto the type of a PARAMETER or a FIELD, and the method does
 * not hold one, it CONSTRUCTS one and returns it.</p>
 *
 * <p>Counting accesses says a method leans on another type. It does not say the method can go
 * there, and those are different claims — the detector was making the first and reporting the
 * second. Same shape as the command-and-query repair at C4, where a method populating the
 * object it was about to return read as a method mutating state: in both, the object is the
 * method's OUTPUT rather than a collaborator it reaches through.</p>
 *
 * <h2>Why the input is written here rather than into the shared fixture</h2>
 *
 * <p>Adding a class to {@code simple-maven} has moved a counted population four times in this
 * sprint — a search ranking, a findings page, a clone-group page and a naming census — each
 * time breaking a test that was right. This writes into the loaded COPY, so nothing outside
 * this class can see it.</p>
 */
class FeatureEnvyOnlySuggestsAReachableTargetTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path pkg = service.allProjects().iterator().next().projectRoot()
            .resolve("src/main/java/com/example");
        // Two methods leaning on ONE foreign type the same number of times. The only
        // difference is how each REACHES it — which is exactly the property `move
        // kind=method` cares about, and the one the detector was ignoring.
        Files.writeString(pkg.resolve("EnvyReachTargets.java"), """
            package com.example;

            class EnvyReachTargets {

                /** Builds the thing it leans on and hands it back — there is nowhere to move to. */
                EnvyReached built(int seed) {
                    EnvyReached made = new EnvyReached();
                    made.setA(seed);
                    made.setB(seed + 1);
                    made.setC(seed + 2);
                    return made;
                }

                /** Leans on it exactly as hard, through a PARAMETER — so the move is real. */
                int held(EnvyReached given) {
                    int a = given.getA();
                    int b = given.getB();
                    given.setC(a + b);
                    return a;
                }
            }

            class EnvyReached {
                private int a;
                private int b;
                private int c;
                int getA() { return a; }
                int getB() { return b; }
                void setA(int v) { a = v; }
                void setB(int v) { b = v; }
                void setC(int v) { c = v; }
            }
            """);
        // THE PROJECT MUST BE TOLD, and the first version of this test forgot. It wrote the
        // file after the load, so the Java model never saw it: the detector reported nine
        // findings from elsewhere and none from here, which made BOTH halves read as "not
        // flagged" — the negative would have passed for the wrong reason and only the control
        // caught it. Written and visible are different states, which is the same fact this
        // release fixes in the compile gate. Idiom taken from NamingViolationCoordinateTest.
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private Set<String> symbols() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "feature_envy");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "feature_envy must dispatch");
        Map<String, Object> data = (Map<String, Object>) r.getData();
        List<Map<String, Object>> findings = (List<Map<String, Object>>) data.get("findings");
        return findings.stream().map(f -> String.valueOf(f.get("symbol"))).collect(Collectors.toSet());
    }

    @Test
    @DisplayName("a method that CONSTRUCTS what it leans on is not told to move into it")
    void aConstructedTypeIsNoTarget() {
        Set<String> hits = symbols();

        // THE CONTROL COMES FIRST, and it is what stops this being a test that would pass if
        // the detector had simply stopped firing. Both methods touch EnvyReached three times
        // and their own type not at all; only the reachability differs.
        assertTrue(hits.contains("com.example.EnvyReachTargets#held"),
            "PROOF OF LIFE: leaning on a PARAMETER's type is still feature envy, and the "
                + "move into it is real: " + hits);

        assertFalse(hits.contains("com.example.EnvyReachTargets#built"),
            "a method that constructs the type and returns it cannot be moved into it — "
                + "`move kind=method` takes the type of a parameter or a field, so this "
                + "finding would send its reader to a refusal: " + hits);
    }
}
