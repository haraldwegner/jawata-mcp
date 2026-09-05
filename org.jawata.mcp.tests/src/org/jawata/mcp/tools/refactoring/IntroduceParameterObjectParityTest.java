package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.tools.ChangeMethodSignatureTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Sprint 28d-rescue row 21 — Introduce Parameter Object: the REGRESSION LOCK.
 *
 * <p>Read {@link RowParity}'s javadoc for what a golden proves and what it does not. This row
 * WRAPS JDT's engine rather than implementing the rewrite, so the golden's subject is different
 * from a written row's: it pins the shape of what the engine produces THROUGH OUR CONFIGURATION
 * — top-level or nested, getters or not, the parameter's name — and any of those defaults
 * changing shows up here rather than in a substring assertion that was not looking.</p>
 *
 * <p><b>Three files are pinned and one of them did not exist before the run.</b> The class the
 * row creates is the operation's main output, and a golden that pinned only the two files it
 * edited would say nothing about the thing it made.</p>
 *
 * <p>Refresh with {@code -Djawata.test.parity.record=true}.</p>
 */
class IntroduceParameterObjectParityTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    @Test
    @DisplayName("row 21 pins the created class, the collapsed signature, and the rewritten "
        + "call in another file")
    void theRowsOutcomeIsPinned() throws Exception {
        RowParity.appliedRow(helper, "change_method_signature", "introduce_parameter_object",
            (service, cache) -> new ChangeMethodSignatureTool(() -> service, cache),
            pkg -> {
                ObjectNode args = new ObjectMapper().createObjectNode();
                args.put("kind", "introduce_parameter_object");
                args.put("className", "DeliveryAddress");
                Path file = pkg.resolve("ParameterObjectTargets.java");
                args.put("filePath", file.toString());
                try {
                    String[] lines = Files.readString(file, StandardCharsets.UTF_8)
                        .split("\n", -1);
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains("public String describeDelivery(")) {
                            args.put("line", i);
                            args.put("column", lines[i].indexOf("describeDelivery"));
                            return args;
                        }
                    }
                    throw new AssertionError("PROOF OF LIFE: the fixture no longer declares"
                        + " describeDelivery");
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            },
            List.of("ParameterObjectTargets.java", "ParameterObjectDesk.java",
                "DeliveryAddress.java"));
    }
}
