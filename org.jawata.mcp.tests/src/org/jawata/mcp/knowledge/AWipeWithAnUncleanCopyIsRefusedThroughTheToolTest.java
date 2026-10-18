package org.jawata.mcp.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.ExperienceTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * REL44-33 — the refusal through the real tool path, not only through the policy beside it.
 *
 * <p>It lives in this package because the seam where a copy is made is package-private here, and it
 * drives {@link ExperienceTool} through the constructor that takes the backup policy. The policy cases
 * are in {@code ACopyWhoseCountDisagreesWithItsRowsIsNotKeptTest}; this one proves the tool's
 * {@code wipe} consults the policy, stops when it refuses, removes nothing, and runs when the user
 * repeats the request with {@code proceed_without_clean_backup=true}.</p>
 */
class AWipeWithAnUncleanCopyIsRefusedThroughTheToolTest {

    @Test
    void aWipeIsRefusedAndRemovesNothingUntilTheUserRepeatsItWithTheFlag(@TempDir Path dir) {
        try (H2ExperienceStore store = H2ExperienceStore.openAt(dir)) {
            store.put(SymbolFact.of("domain_fact", "something worth keeping", Confidence.MEDIUM)
                .symbol("com.example.Target").build());
            StoreBackups backups = new StoreBackups(() -> store,
                (h2, target) -> ACopyWhoseCountDisagreesWithItsRowsIsNotKeptTest.copyTheBadOne(target));
            ExperienceTool tool = new ExperienceTool(() -> null, store, List::of, null, backups);
            ObjectMapper mapper = new ObjectMapper();

            ToolResponse refused = tool.execute(mapper.createObjectNode().put("kind", "wipe"));
            assertFalse(refused.isSuccess(), "the wipe did not run");
            assertTrue(String.valueOf(refused.getError()).contains("proceed_without_clean_backup=true"),
                "the refusal says how to go on: " + refused.getError());
            assertEquals(1, store.count(), "NOTHING was removed by the refused wipe");

            ObjectNode goOn = mapper.createObjectNode().put("kind", "wipe")
                .put("proceed_without_clean_backup", true);
            ToolResponse done = tool.execute(goOn);
            assertTrue(done.isSuccess(), "with the flag the user chose, the wipe runs: " + done.getError());
            assertEquals(0, store.count(), "and the store is wiped");
        }
    }
}
