package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h S14, D22, the loop for lookups: a brain's own shell search over Java source is refused where the engine answers the brain's
 * permission requests, and the refusal names the tools that answer the same question. A text search over anything that is not Java, a
 * build, and a search that declares why the tools cannot answer it are left alone.
 */
class LookupDriverTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static ObjectNode shell(String command) {
        return JSON.createObjectNode().put("command", command);
    }

    private static Optional<String> refusalFor(String command) {
        return LookupDriver.refusal(shell(command));
    }

    @Test
    @DisplayName("grep, rg, find, sed and awk over Java source are refused, and the refusal names the tools that answer a lookup")
    void aSearchOverJavaSourceIsRefused() {
        for (String command : new String[] {
            "grep -rn 'Foo' src/main/java/Foo.java",
            "rg Foo --type java",
            "find . -name '*.java'",
            "sed -n 1,40p src/Foo.java",
            "awk '/class/' Foo.java",
            "cd app && grep -rn --include=*.java Foo ." }) {
            Optional<String> refusal = refusalFor(command);
            assertTrue(refusal.isPresent(), "a search over Java source was let through: " + command);
            assertTrue(refusal.get().contains("search_symbols") && refusal.get().contains("find_references"),
                "the refusal does not name the tools that answer it: " + refusal.get());
        }
    }

    @Test
    @DisplayName("CONTROL: a search over a build file, a build, and a plain read are not touched")
    void whatIsNotASearchOverJavaIsLeftAlone() {
        assertEquals(Optional.empty(), refusalFor("grep -n version pom.xml"), "a search over a build file was refused");
        assertEquals(Optional.empty(), refusalFor("mvn -q -Dtest=FooTest test"), "a build was refused");
        assertEquals(Optional.empty(), refusalFor("cat notes.md"), "a read of notes was refused");
        assertEquals(Optional.empty(), refusalFor("git log --oneline"), "a git call was refused");
        assertEquals(Optional.empty(), refusalFor("grep -rn Foo src/main/java/Foo.java.bak"), "a name that only starts like a Java file was refused");
    }

    @Test
    @DisplayName("a search that declares why the tools cannot answer it is let through, as the guest hook lets it through")
    void aDeclaredFallbackIsLetThrough() {
        assertEquals(Optional.empty(), refusalFor("grep -rn 'Foo' src/Foo.java # jawata-fallback: the string is inside a comment"));
        assertEquals(Optional.empty(), refusalFor("sed -i s/a/b/ src/Foo.java # jawata-author: the rename tool failed on this case"));
        assertTrue(refusalFor("grep -rn 'Foo' src/Foo.java # fallback because I like it").isPresent(),
            "CONTROL: a comment that is not the declaration opened the door");
    }

    @Test
    @DisplayName("a call with no command, a command given as a list, and a command under another key are read; nothing else is a command")
    void theCommandIsReadFromTheShapesBrainsSendIt() {
        assertEquals(Optional.empty(), LookupDriver.refusal(JSON.createObjectNode()), "a call with no input was refused");
        assertEquals(Optional.empty(), LookupDriver.refusal(null), "a missing input was refused");
        ObjectNode list = JSON.createObjectNode();
        list.putArray("command").add("grep").add("-rn").add("Foo").add("src/Foo.java");
        assertTrue(LookupDriver.refusal(list).isPresent(), "a command sent as a list was not read");
        assertFalse(LookupDriver.refusal(JSON.createObjectNode().put("path", "src/Foo.java")).isPresent(),
            "a path that is not a command was taken for a search");
    }
}
