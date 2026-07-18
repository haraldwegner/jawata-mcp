package org.jawata.mcp.learn;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.knowledge.LearnerEventStore;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sprint 26 Stage 2 (D1, the fixture clause): a REAL change introducing a
 * smell surfaces through the REAL detector binding on the next watch pass —
 * no command invoked, no hook installed. The engine's detector seam is bound
 * to the actual find_quality_issue single-file path, exactly as the
 * application wires it.
 */
class WatchEngineIntegrationTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private H2ExperienceStore store;

    @AfterEach
    void tearDown() throws Exception {
        if (store != null) {
            store.close();
        }
    }

    @Test
    void a_real_smell_added_to_a_real_file_surfaces_on_the_next_pass() throws Exception {
        // A TEMP COPY — this test mutates sources; the shared fixture must
        // stay pristine (a polluted fixture poisons every later run).
        java.nio.file.Path copy = helper.copyFixture("simple-maven");
        JdtServiceImpl service = new JdtServiceImpl();
        service.loadProject(copy);
        FindQualityIssueTool tool = new FindQualityIssueTool(() -> service);
        ObjectMapper mapper = new ObjectMapper();
        store = H2ExperienceStore.openMemory();
        WatchEngine engine = new WatchEngine((kind, filePath) -> {
            ObjectNode args = mapper.createObjectNode();
            args.put("kind", kind);
            args.put("filePath", filePath);
            return tool.execute(args);
        }, new LearnerEventStore(store));

        // MODIFY an existing fixture source (an added file needs the
        // source-root cache dance only the disk sync performs; a changed
        // file is the proven refresh path — and the real hand-edit shape).
        Path projectRoot = service.allProjects().iterator().next().projectRoot();
        Path src;
        try (var stream = Files.walk(projectRoot.resolve("src/main/java"))) {
            src = stream.filter(f -> f.toString().endsWith(".java")).findFirst().orElseThrow();
        }

        // Pass 1: whatever the file already carries becomes the baseline.
        engine.watch("it-session", java.util.List.of(src.toString()));

        // The change: an unused private method — a real, detectable smell.
        String original = Files.readString(src);
        String smelly = original.replaceFirst("}\\s*$",
            "    private int watchSeededNeverCalled() { return 42; }\n}\n");
        Files.writeString(src, smelly);
        refresh(service);

        // Pass 2 (the NEXT call): only the NEW finding surfaces.
        Optional<String> block = engine.watch("it-session", java.util.List.of(src.toString()));
        assertTrue(block.isPresent(), "the seeded smell must surface on the next pass");
        assertTrue(block.get().contains("watchSeededNeverCalled"),
            "the finding names the seeded symbol:\n" + block.get());
        assertTrue(block.get().contains("design fix or bandage?"));
    }

    private static void refresh(JdtServiceImpl service) throws Exception {
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());
    }
}
