package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Optional;

import org.jawata.mcp.access.LookupDriver;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Sprint 28h S14, D22, the loop for lookups: a brain's own shell search over Java source is refused where the engine answers the brain's
 * permission requests. The command is read as a shell reads it, so a sentence inside quotes is not a search; a text search over anything
 * that is not Java, a build, and a search that declares why the tools cannot answer it are left alone; and the declaration grammar is the
 * guest hook's, where {@code jawata-fallback:} opens a read and only {@code jawata-author:} opens a command that rewrites a file in place.
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
    @DisplayName("grep, rg, find, sed and awk over Java source are refused, and the rule names the tools that answer a lookup")
    void aSearchOverJavaSourceIsRefused() {
        for (String command : new String[] {
            "grep -rn 'Foo' src/main/java/Foo.java",
            "rg Foo --type java",
            "find . -name '*.java'",
            "sed -n 1,40p src/Foo.java",
            "awk '/class/' Foo.java",
            "cd app && grep -rn --include=*.java Foo .",
            "/usr/bin/grep -rn Foo src/Foo.java" }) {
            Optional<String> refusal = refusalFor(command);
            assertTrue(refusal.isPresent(), "a search over Java source was let through: " + command);
            assertEquals(LookupDriver.RULE, refusal.get(), "the refusal is the one rule the hire prompt carries");
        }
        assertTrue(LookupDriver.RULE.contains("search_symbols") && LookupDriver.RULE.contains("find_references"),
            "the rule does not name the tools that answer a lookup: " + LookupDriver.RULE);
    }

    @Test
    @DisplayName("CONTROL: a search over a build file, a build, a plain read and a sentence inside quotes are not touched")
    void whatIsNotASearchOverJavaIsLeftAlone() {
        assertEquals(Optional.empty(), refusalFor("grep -n version pom.xml"), "a search over a build file was refused");
        assertEquals(Optional.empty(), refusalFor("mvn -q -Dtest=FooTest test"), "a build was refused");
        assertEquals(Optional.empty(), refusalFor("cat notes.md"), "a read of notes was refused");
        assertEquals(Optional.empty(), refusalFor("git log --oneline"), "a git call was refused");
        assertEquals(Optional.empty(), refusalFor("grep -rn Foo src/main/java/Foo.java.bak"), "a name that only starts like a Java file was refused");
        assertEquals(Optional.empty(), refusalFor("git commit -m \"find the bug in Foo.java\""), "a sentence inside quotes was read as a search");
        assertEquals(Optional.empty(), refusalFor("git commit -m 'grep Foo.java is slow'"), "a sentence inside quotes was read as a search");
    }

    @Test
    @DisplayName("a read that declares why the tools cannot answer it is let through; only the author declaration opens a command that rewrites in place")
    void aDeclaredFallbackIsLetThrough() {
        assertEquals(Optional.empty(), refusalFor("grep -rn 'Foo' src/Foo.java # jawata-fallback: the string is inside a comment"));
        assertEquals(Optional.empty(), refusalFor("sed -i s/a/b/ src/Foo.java # jawata-author: the rename tool failed on this case"));
        assertTrue(refusalFor("sed -i s/a/b/ src/Foo.java # jawata-fallback: the rename tool failed on this case").isPresent(),
            "the fallback declaration is for reads: it must not open a command that rewrites a file in place, as the guest hook does not let it");
        assertEquals(Optional.empty(), refusalFor("grep -i Foo src/Foo.java # jawata-fallback: a case-insensitive read in a comment"),
            "-i means ignore case for grep: the fallback declaration the rule promises must open it");
        assertTrue(refusalFor("grep -rn 'Foo' src/Foo.java # fallback because I like it").isPresent(),
            "CONTROL: a comment that is not the declaration opened the door");
    }

    @Test
    @DisplayName("the brain reads the rule in the prompt it is hired with when its seat changes code, and a seat that does not is asked exactly what it was")
    void aCodeChangingSeatIsToldTheRuleInItsHirePrompt() {
        SeatHire worker = SeatHire.read("---\nname: worker\nchanges_code: true\n---\nYou are the worker.");
        SeatHire reader = SeatHire.read("---\nname: pm\n---\n");

        String told = worker.prompt("rename it", java.util.List.of(), "");

        assertTrue(told.contains(LookupDriver.RULE), "a seat that changes code was not told how to look things up: " + told);
        assertTrue(told.indexOf(LookupDriver.RULE) < told.indexOf("rename it"), "the rule must come before the brief");
        assertEquals("rename it", reader.prompt("rename it", java.util.List.of(), ""), "a read-only seat with no stance was given words it was not given before");
    }

    @Test
    @DisplayName("a command sent as a list of words is read; a missing input, an empty input and an input with no command are no search")
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
