package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.knowledge.H2ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * REL44-43 — the stop gate asked about every member of every file a turn touched, so a one-line
 * edit to a class of twenty tests demanded twenty-three descriptions. {@code changedLines} lets the
 * caller say which lines it wrote, and only the members those lines fall in are asked about.
 *
 * <p>The fixture is {@code com.example.HelloWorld}: its {@code printGreeting} body is line 44 and
 * its {@code setGreeting} method spans lines 36 to 38 (its Javadoc starts at line 32).</p>
 */
class JobsForChangedLinesTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final ObjectMapper JSON = new ObjectMapper();

    private Path helloWorld() {
        return helper.getTempDirectory().resolve("simple-maven/src/main/java/com/example/HelloWorld.java");
    }

    /** jobs_for over HelloWorld, given the ranges (null for none given), against a real project. */
    private ToolResponse ask(int[][] ranges) throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        try (H2ExperienceStore store = H2ExperienceStore.open(null)) {
            ExperienceTool tool = new ExperienceTool(() -> service, store);
            Path file = helloWorld();
            ObjectNode a = JSON.createObjectNode();
            a.put("kind", "describe");
            a.put("action", "jobs_for");
            a.putArray("filePaths").add(file.toString());
            if (ranges != null) {
                ArrayNode list = a.putObject("changedLines").putArray(file.toString());
                for (int[] r : ranges) {
                    list.addArray().add(r[0]).add(r[1]);
                }
            }
            return tool.execute(a);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> missing(ToolResponse r) {
        assertTrue(r.isSuccess(), String.valueOf(r.getError()));
        return (List<String>) ((Map<String, Object>) r.getData()).get("missing");
    }

    @Test
    @DisplayName("lines inside one method ask about that method only, not the other members of the file")
    void linesInsideOneMethodAskAboutThatMethodOnly() throws Exception {
        List<String> asked = missing(ask(new int[][] {{44, 44}}));

        assertEquals(List.of("com.example.HelloWorld#printGreeting"), asked,
            "a one-line edit inside printGreeting must not demand the other members: " + asked);
    }

    @Test
    @DisplayName("THE CONTROL: with no changedLines the whole file is asked about, as before")
    void withoutChangedLinesTheWholeFileIsAskedAbout() throws Exception {
        List<String> asked = missing(ask(null));

        assertTrue(asked.contains("com.example.HelloWorld#printGreeting")
                && asked.contains("com.example.HelloWorld#main")
                && asked.contains("com.example.HelloWorld#setGreeting"),
            "no ranges given means every method of the file, which is today's behaviour: " + asked);
    }

    @Test
    @DisplayName("a range that spans two methods asks about both")
    void aRangeSpanningTwoMethodsAsksAboutBoth() throws Exception {
        List<String> asked = missing(ask(new int[][] {{37, 44}}));

        assertEquals(List.of("com.example.HelloWorld#setGreeting", "com.example.HelloWorld#printGreeting"), asked,
            "lines 37 to 44 touch setGreeting and printGreeting and nothing else: " + asked);
    }

    @Test
    @DisplayName("an edit to a method's Javadoc is an edit to that method")
    void javadocBelongsToItsMethod() throws Exception {
        List<String> asked = missing(ask(new int[][] {{33, 33}}));

        assertEquals(List.of("com.example.HelloWorld#setGreeting"), asked,
            "line 33 is inside setGreeting's Javadoc: " + asked);
    }

    @Test
    @DisplayName("lines in no method ask about nothing, and the refusal says so instead of passing as clean")
    void linesInNoMethodAreRefusedNotPassedAsClean() throws Exception {
        ToolResponse r = ask(new int[][] {{7, 7}});

        assertFalse(r.isSuccess(),
            "line 7 is a field: no method was written, so there is nothing to ask — and that is a"
                + " refusal the gate reads as 'no ruling', not an empty list it could misread: " + r.getData());
    }

    @Test
    @DisplayName("an empty list of ranges for a file means the whole file, never none")
    void anEmptyRangeListMeansTheWholeFile() throws Exception {
        List<String> asked = missing(ask(new int[0][]));

        assertTrue(asked.size() >= 5, "an empty list must fail towards asking, not towards silence: " + asked);
    }
}
