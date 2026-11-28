package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.mcp.contract.FakeHost;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeArtifactStore;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * z192: the {@code kind} the debug tool publishes and the kinds its actions accept are one list. A kind the schema names that no action
 * accepts, or a kind an action accepts that the schema does not name, makes a client send what is refused or never send what works.
 *
 * <p>The refusal text {@code needs a kind} is what both {@code breakpoint_set} and {@code probe_set} answer for an unknown kind.
 * {@code breakpoint_set} checks the kind before it looks for a session; {@code probe_set} looks for the session first, so without a
 * session it answers {@code SESSION_REQUIRED} for every kind, and that answer is read here as "not refused as an unknown kind".
 */
class DebugKindSchemaTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final String UNKNOWN_KIND_REFUSAL = "needs a kind";

    @TempDir
    Path scratch;

    private DebugTool tool() {
        return new DebugTool(() -> null, new RuntimeSessionRegistry(), new RuntimeArtifactStore(scratch.resolve("runtime")), null, new FakeHost.Turn());
    }

    @SuppressWarnings("unchecked")
    private static List<String> publishedKinds(DebugTool tool) {
        Map<String, Object> properties = (Map<String, Object>) tool.getInputSchema().get("properties");
        Map<String, Object> kind = (Map<String, Object>) properties.get("kind");
        return (List<String>) kind.get("enum");
    }

    private ToolResponse call(DebugTool tool, String action, String kind) throws Exception {
        return tool.execute(JSON.readTree("{\"action\":\"" + action + "\",\"kind\":\"" + kind + "\",\"className\":\"p.Main\"}"));
    }

    private static boolean refusedAsUnknownKind(ToolResponse answer) {
        return !answer.isSuccess() && answer.getError().getMessage() != null && answer.getError().getMessage().contains(UNKNOWN_KIND_REFUSAL);
    }

    @Test
    @DisplayName("28h z192: logpoint, field_watch, method_trace and conditional are in the published kind enum")
    void theFourKindsArePublished() {
        List<String> published = publishedKinds(tool());

        for (String kind : List.of("logpoint", "field_watch", "method_trace", "conditional")) {
            assertTrue(published.contains(kind), "the published kind enum names " + kind + ", it names " + published);
        }
    }

    @Test
    @DisplayName("28h z192: each of the four kinds is not refused as an unknown kind by the action that takes it")
    void theFourKindsAreNotRefusedAsUnknown() throws Exception {
        DebugTool tool = tool();

        for (String kind : List.of("logpoint", "field_watch", "method_trace")) {
            ToolResponse answer = call(tool, "probe_set", kind);
            assertFalse(refusedAsUnknownKind(answer), "probe_set kind=" + kind + " was refused as an unknown kind: " + answer.getError());
        }
        ToolResponse conditional = call(tool, "breakpoint_set", "conditional");
        assertFalse(refusedAsUnknownKind(conditional), "breakpoint_set kind=conditional was refused as an unknown kind: " + conditional.getError());
    }

    @Test
    @DisplayName("28h z192: a kind no action accepts is refused as an unknown kind and is not in the published enum")
    void anUnacceptedKindIsRefusedAndNotPublished() throws Exception {
        DebugTool tool = tool();

        ToolResponse refused = call(tool, "breakpoint_set", "no_such_kind");

        assertTrue(refusedAsUnknownKind(refused), "breakpoint_set kind=no_such_kind says: " + (refused.isSuccess() ? "success" : refused.getError().getMessage()));
        assertFalse(call(tool, "probe_set", "no_such_kind").isSuccess(), "probe_set kind=no_such_kind is refused too");
        assertFalse(publishedKinds(tool).contains("no_such_kind"), "no_such_kind is not published");
    }

    @Test
    @DisplayName("28h z192: every published kind is accepted by breakpoint_set or is a probe kind it refuses only for the missing session")
    void everyPublishedKindIsTakenByOneAction() throws Exception {
        DebugTool tool = tool();

        java.util.List<String> published = new java.util.ArrayList<>();
        publishedKinds(tool).forEach(published::add);
        java.util.List<String> takenByNone = new java.util.ArrayList<>();
        for (String kind : published) {
            if (refusedAsUnknownKind(call(tool, "breakpoint_set", kind))) {
                ToolResponse asProbe = call(tool, "probe_set", kind);
                // a probe kind is refused here for the missing session only
                if (asProbe.isSuccess() || !"SESSION_REQUIRED".equals(asProbe.getError().getCode())) {
                    takenByNone.add(kind);
                }
            }
        }

        assertFalse(published.isEmpty(), "the schema publishes no kind, so nothing was checked");
        assertEquals(java.util.List.of(), takenByNone, "the schema publishes kinds that neither breakpoint_set nor probe_set takes");
    }
}
