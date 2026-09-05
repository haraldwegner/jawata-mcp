package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.DataTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 28d-rescue row 9 — Encapsulate Collection: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves here and what it does not.
 * This row is a NEW operation, so a golden recorded from it is a snapshot of itself. It pins
 * the exact outcome and fails the day that changes for any reason. Behaviour preservation
 * lives in the compile gate and in {@code EncapsulateCollectionToolTest}'s five refusals.</p>
 *
 * <p><b>What the golden is uniquely good for here is the GENERATED MUTATORS' whole text.</b>
 * The unit test asserts their signatures and one body line each; the golden pins the javadoc,
 * the placement in the class, the import that was added and the formatting. A map's pair
 * differs from a collection's in arity, in verb and in which type argument goes where — four
 * decisions that are invisible to a signature assertion and all visible here.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class EncapsulateCollectionParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 9 pins the view, the generated mutators and the import that carries them")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "data", "encapsulate_collection",
            (service, cache) -> new DataTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "encapsulate_collection");
                Path file = pkg.resolve("EncapsulateCollectionTargets.java");
                args.put("filePath", file.toString());
                try {
                    // The MAP case, deliberately: its mutator pair is the one with the most
                    // independent decisions in it, so it is the most informative thing to pin.
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8)
                        .split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains("getScores(")) {
                            args.put("line", i);
                            args.put("column", lines[i].indexOf("getScores"));
                            return args;
                        }
                    }
                    throw new AssertionError("PROOF OF LIFE: the fixture no longer declares"
                        + " getScores");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            List.of("EncapsulateCollectionTargets.java"));
    }
}
