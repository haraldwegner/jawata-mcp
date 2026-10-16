package org.jawata.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jawata.mcp.fixtures.InertShippedTools;
import org.jawata.mcp.protocol.McpProtocolHandler;
import org.jawata.mcp.tools.Tool;
import org.jawata.mcp.tools.ToolRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * EVERY NAME THIS PRODUCT HAS EVER PUBLISHED EITHER STILL WORKS OR POINTS AT WHAT REPLACED IT.
 *
 * <p>The retired-name table was written from memory, and the question it has to answer, "what did we
 * ever publish", lived nowhere. 35 of the 65 names ever published answered a bare "Tool not found" in
 * 4.4.7, and four patches in a row corrected one hand-kept copy of the tool surface each, at the place
 * a failure happened to show it. The cure is one place that says what was published, checked against
 * the thing that publishes it.</p>
 *
 * <p><b>The record</b> is {@code build/published-tool-names.txt}, derived from each release's own
 * registered tools by {@code build/derive-published-tool-names.py}. This test is what makes that file
 * more than a document: it is checked against the real registry in both directions, and the retired
 * table must hold exactly the file's retired names.</p>
 *
 * <p><b>What a future retirement looks like</b>: a tool is removed from {@code ShippedTools} and
 * nothing else changes. The file still marks it live, so {@link #theFileAndTheRegistryAgreeOnWhatIsLive}
 * fails; re-running the script marks it retired, and {@link #theTableHoldsExactlyTheRetiredNames}
 * fails until a pointer is written. A retirement cannot ship without its pointer.</p>
 *
 * <p>The pointers are checked against the live tools' own input schemas, not against a second list: a
 * pointer whose front door is not live, or whose kind, action, direction or scope that door does not
 * accept, fails here.</p>
 */
class PublishedToolNamesTest {

    private static final String SCRIPT = "python3 build/derive-published-tool-names.py";

    private static final Pattern POINTER = Pattern.compile("Did you mean '([^']+)'");

    /** One line of the record. */
    private record Row(String name, boolean live, String firstTag, String lastTag) {
    }

    private static List<Row> record() {
        Path file = EveryCrossBundlePackageIsImportedTest.repoRoot()
            .resolve("build").resolve("published-tool-names.txt");
        List<String> lines;
        try {
            lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new AssertionError("cannot read " + file + ": " + e + " - this check did NOT run. "
                + "Regenerate it with `" + SCRIPT + "`.", e);
        }
        List<Row> rows = new ArrayList<>();
        for (String line : lines) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] f = line.split("\t");
            assertEquals(4, f.length, "a record line must be name, state, first tag, last tag: " + line);
            assertTrue(f[1].equals("live") || f[1].equals("retired"),
                "the state must be live or retired: " + line);
            rows.add(new Row(f[0], f[1].equals("live"), f[2], f[3]));
        }
        return rows;
    }

    private static Set<String> names(List<Row> rows, boolean live) {
        Set<String> out = new TreeSet<>();
        for (Row r : rows) {
            if (r.live() == live) {
                out.add(r.name());
            }
        }
        return out;
    }

    /** The pointer the product actually answers a retired name with, as a caller sees it. */
    private static String pointerAnsweredFor(String retired) {
        try {
            new ToolRegistry().callTool(retired, new ObjectMapper().createObjectNode());
        } catch (ToolRegistry.ToolNotFoundException e) {
            Matcher m = POINTER.matcher(e.getMessage());
            return m.find() ? m.group(1) : null;
        }
        throw new AssertionError(retired + " resolved to a tool in an empty registry");
    }

    @Test
    @DisplayName("the record is not vacuous: it holds the history, including the names that once went unanswered")
    void theRecordIsNotVacuous() {
        List<Row> rows = record();
        assertTrue(rows.size() >= 100,
            "the record holds " + rows.size() + " names; the history this product has is 100 or more, so"
                + " the file was truncated or the derivation read nothing. Regenerate with `" + SCRIPT + "`.");
        Set<String> retired = names(rows, false);
        for (String once : List.of("undo_refactoring", "apply_refactoring", "inspect_refactoring",
                "get_type_at_position", "list_projects", "add_project", "get_document_symbols",
                "add_dependency")) {
            assertTrue(retired.contains(once),
                once + " was a published tool and went unanswered in 4.4.7; it must be in the record as"
                    + " retired, or this check has stopped reading the history it exists for");
        }
    }

    @Test
    @DisplayName("the record and the registry agree on which tools are live")
    void theFileAndTheRegistryAgreeOnWhatIsLive() {
        Set<String> registered = InertShippedTools.names();
        Set<String> recorded = names(record(), true);

        Set<String> unrecorded = new TreeSet<>(registered);
        unrecorded.removeAll(recorded);
        Set<String> gone = new TreeSet<>(recorded);
        gone.removeAll(registered);
        assertTrue(unrecorded.isEmpty() && gone.isEmpty(),
            "build/published-tool-names.txt is out of date. Registered but not recorded as live: "
                + unrecorded + ". Recorded as live but no longer registered: " + gone + ". Re-run `"
                + SCRIPT + "`; a tool that is no longer registered becomes retired and then needs a"
                + " row in ToolRegistry.RENAMED_TOOLS.");
    }

    @Test
    @DisplayName("the retired-name table holds exactly the names the record says were retired")
    void theTableHoldsExactlyTheRetiredNames() {
        Set<String> retired = names(record(), false);
        Set<String> table = new TreeSet<>(ToolRegistry.retiredNames());

        Set<String> unanswered = new TreeSet<>(retired);
        unanswered.removeAll(table);
        Set<String> invented = new TreeSet<>(table);
        invented.removeAll(retired);
        assertTrue(unanswered.isEmpty(),
            unanswered.size() + " published tool name(s) answer a bare 'Tool not found' because"
                + " ToolRegistry.RENAMED_TOOLS has no row for them: " + unanswered + ". Each needs a"
                + " pointer that names the front door and its kind or action.");
        assertTrue(invented.isEmpty(),
            "ToolRegistry.RENAMED_TOOLS holds row(s) for name(s) the record says this product never"
                + " published, or that are live: " + invented + ". A misspelt key answers for nothing;"
                + " a live name must not be retired.");
    }

    @Test
    @DisplayName("every pointer names a live front door and a kind, action, direction or scope it accepts")
    void everyPointerLandsOnAcceptedInput() {
        Map<String, Tool> live = new LinkedHashMap<>();
        for (Tool tool : InertShippedTools.tools()) {
            live.put(tool.getName(), tool);
        }
        List<String> complaints = new ArrayList<>();
        for (String retired : names(record(), false)) {
            String pointer = pointerAnsweredFor(retired);
            if (pointer == null) {
                complaints.add(retired + ": the refusal carries no 'Did you mean' pointer at all");
                continue;
            }
            String[] tokens = pointer.split(" ");
            Tool door = live.get(tokens[0]);
            if (door == null) {
                complaints.add(retired + " points at '" + pointer + "', and '" + tokens[0]
                    + "' is not a live tool");
                continue;
            }
            for (int i = 1; i < tokens.length; i++) {
                int eq = tokens[i].indexOf('=');
                if (eq < 0) {
                    continue; // the word 'with' in "organize_imports with scope=workspace"
                }
                String property = tokens[i].substring(0, eq);
                String value = tokens[i].substring(eq + 1);
                Object accepted = enumOf(door, property);
                if (accepted == null) {
                    complaints.add(retired + " points at '" + pointer + "', and " + tokens[0]
                        + " has no parameter '" + property + "' with a fixed set of values");
                } else if (!((List<?>) accepted).contains(value)) {
                    complaints.add(retired + " points at '" + pointer + "', and " + tokens[0]
                        + " accepts " + property + " only from " + accepted);
                }
            }
        }
        assertTrue(complaints.isEmpty(), "pointers that do not land on accepted input:\n  "
            + String.join("\n  ", complaints));
    }

    /** The fixed values a live tool's input schema allows for one parameter, or null. */
    @SuppressWarnings("unchecked")
    private static Object enumOf(Tool tool, String property) {
        Object properties = tool.getInputSchema().get("properties");
        if (!(properties instanceof Map<?, ?> props)) {
            return null;
        }
        Object p = props.get(property);
        if (!(p instanceof Map<?, ?> spec)) {
            return null;
        }
        Object values = ((Map<String, Object>) spec).get("enum");
        return values instanceof List<?> ? values : null;
    }

    @Test
    @DisplayName("a client calling any retired name through the protocol reads its replacement")
    void everyRetiredNameReachesTheClientWithItsPointer() throws Exception {
        ObjectMapper json = new ObjectMapper();
        McpProtocolHandler handler = new McpProtocolHandler(new ToolRegistry());
        for (String retired : names(record(), false)) {
            String request = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
                + "\"params\":{\"name\":\"" + retired + "\",\"arguments\":{}}}";
            JsonNode error = json.readTree(handler.processMessage(request)).get("error");
            assertNotNull(error, retired + " must be refused");
            String message = error.get("message").asText();
            String pointer = pointerAnsweredFor(retired);
            assertNotNull(pointer, retired + " has no pointer");
            assertTrue(message.contains("Did you mean '" + pointer + "'"),
                retired + " reached the client as '" + message + "' without its pointer '" + pointer + "'");
        }
    }

    @Test
    @DisplayName("a name this product never published still gets no invented pointer")
    void aNameNeverPublishedGetsNoPointer() {
        for (String never : List.of("refactor_the_whole_thing", "analyze_everything", "inspect_nothing")) {
            Set<String> recorded = new TreeSet<>();
            record().forEach(r -> recorded.add(r.name()));
            assertFalse(recorded.contains(never), never + " must not be a published name for this check");
            assertNull(pointerAnsweredFor(never),
                never + " was never a tool; guessing a pointer for it would send a caller somewhere on no evidence");
        }
    }
}
