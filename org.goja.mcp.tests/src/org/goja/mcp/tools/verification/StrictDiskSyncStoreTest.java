package org.goja.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.goja.core.JdtServiceImpl;
import org.goja.core.workspace.StrictDiskSync;
import org.goja.mcp.fixtures.TestProjectHelper;
import org.goja.mcp.knowledge.Confidence;
import org.goja.mcp.knowledge.ExperienceEntry;
import org.goja.mcp.knowledge.H2ExperienceStore;
import org.goja.mcp.knowledge.StoredEntry;
import org.goja.mcp.knowledge.SymbolFact;
import org.goja.mcp.models.ToolResponse;
import org.goja.mcp.tools.ExperienceTool;
import org.goja.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 21d (item C) — the knowledge store's pointer judgments inherit strict disk
 * sync via dispatch: after an EXTERNAL rename (git-checkout shape: old file gone, new
 * file appears), the automatic {@code refresh()} judges anchors against CURRENT disk —
 * the re-anchored entry survives, the stale anchor is retired. Without the guard both
 * verdicts invert (the model-lag mis-supersede this sprint exists to prevent).
 */
class StrictDiskSyncStoreTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private final ObjectMapper mapper = new ObjectMapper();

    private static ExperienceEntry anchored(String summary, String fqn) {
        return ExperienceEntry.of(
                SymbolFact.of("lesson", summary, Confidence.MEDIUM).symbol(fqn).build())
            .status(ExperienceEntry.ACCEPTED)
            .build();
    }

    private static String statusOf(H2ExperienceStore store, String id) {
        return store.all().stream()
            .filter(e -> e.id().equals(id))
            .map(StoredEntry::status)
            .findFirst().orElse("(missing)");
    }

    @Test
    @DisplayName("external rename: refresh() through dispatch judges against current disk — no mis-supersede")
    void external_rename_is_judged_against_current_disk() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        H2ExperienceStore store = H2ExperienceStore.open(null);
        try {
            ToolRegistry registry = new ToolRegistry();
            registry.register(new ExperienceTool(() -> service, store));
            registry.setDiskSync(new StrictDiskSync(() -> service));

            // Prime the guard: the first call reconciles the new root once.
            ObjectNode stats = mapper.createObjectNode();
            stats.put("kind", "stats");
            assertTrue(registry.callTool("experience", stats).isSuccess());

            // A anchors the FUTURE (post-rename) name, B the current one.
            String idNew = store.put(anchored("survives the rename", "com.example.HelloWorldRenamed"));
            String idOld = store.put(anchored("retired by the rename", "com.example.HelloWorld"));

            // The external rename, git-checkout shape: delete + add.
            Path root = service.allProjects().iterator().next().projectRoot();
            Path oldFile = root.resolve("src/main/java/com/example/HelloWorld.java");
            Path newFile = root.resolve("src/main/java/com/example/HelloWorldRenamed.java");
            String renamed = Files.readString(oldFile).replace("HelloWorld", "HelloWorldRenamed");
            Files.delete(oldFile);
            Files.writeString(newFile, renamed);

            ObjectNode refresh = mapper.createObjectNode();
            refresh.put("kind", "refresh");
            ToolResponse r = registry.callTool("experience", refresh);
            assertTrue(r.isSuccess(), "got: " + r.getError());

            assertEquals(ExperienceEntry.ACCEPTED, statusOf(store, idNew),
                "the NEW-name anchor resolves against reconciled disk — NOT mis-superseded");
            assertEquals(ExperienceEntry.SUPERSEDED, statusOf(store, idOld),
                "the OLD-name anchor is correctly retired — grounded judgment both directions");

            // And recall's pointer resolution sees the reconciled model too.
            ObjectNode recall = mapper.createObjectNode();
            recall.put("kind", "recall");
            recall.put("symbol", "com.example.HelloWorldRenamed");
            ToolResponse rec = registry.callTool("experience", recall);
            assertTrue(rec.isSuccess());
            assertTrue(String.valueOf(rec.getData()).contains("resolved_pointer"),
                "recall resolves the fresh symbol pointer against current code");
        } finally {
            store.close();
        }
    }
}
