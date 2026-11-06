package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Sprint 28h, S18a (D51): who hires whom is what the seat files declare, laid over the signed tree. Each refusal is tested beside the case
 * that must not trigger it, and the signed tree is the control: a folder of seat files that declares none of the three keys gives exactly it.
 */
class SeatTreeFromSeatFilesTest {

    private static final Set<String> CODE_CHANGING = Set.of("rename_symbol", "extract");

    @TempDir
    Path folder;

    private void seat(String name, String frontmatter) throws IOException {
        Files.writeString(folder.resolve(name + ".md"), "---\nname: " + name + "\n" + frontmatter + "---\nbody\n");
    }

    private SeatTree.Loaded load() {
        return SeatTree.in(folder, CODE_CHANGING);
    }

    @Test
    @DisplayName("seat files that declare none of the three keys give exactly the signed tree")
    void todaysSeatFilesGiveTheSignedTree() throws IOException {
        seat("pm", "tools: [compile_workspace, hire]\n");
        seat("developer", "changes_code: true\ntools: [rename_symbol]\n");
        seat("architect", "tools: [inspect]\n");

        SeatTree.Loaded loaded = load();

        assertFalse(loaded.refused(), loaded.refusals().toString());
        assertEquals(SeatTree.standard().declarations(), loaded.tree().declarations());
        assertEquals(SeatTree.standard().declarations(), SeatTree.forSetting(Optional.empty(), CODE_CHANGING).tree().declarations(),
            "no seats folder named: the signed tree");
    }

    @Test
    @DisplayName("the signed tree is the table jawata-studio mirrors in src-tauri/src/workflow.rs (SIGNED): change one and the other, or this test, says so")
    void theSignedTreeIsTheOneTheStudioMirrors() {
        String table = SeatTree.standard().declarations().entrySet().stream()
            .sorted(Map.Entry.comparingByKey())
            .map(e -> e.getKey() + ">" + e.getValue().parent() + ":" + e.getValue().role().spelling() + ":" + e.getValue().escalatesTo().orElse("-"))
            .collect(java.util.stream.Collectors.joining(" "));

        assertEquals("architect>main:reviewer:- cataloguer>chief-engineer:reviewer:- chief-engineer>pm:lead:- consultant>main:reviewer:- "
            + "debugger>pm:reviewer:- developer>chief-engineer:worker:senior-developer javadoc-writer>chief-engineer:worker:- pm>main:lead:- "
            + "profiler>pm:reviewer:- senior-developer>chief-engineer:worker:- spec-auditor>main:reviewer:- spec-editor>main:reviewer:- "
            + "test-manager>pm:lead:- test-writer>test-manager:worker:- ux>main:reviewer:-", table);
    }

    @Test
    @DisplayName("a changed parent is honoured: the new parent may hire the seat and the old parent may not")
    void aChangedParentIsHonoured() throws IOException {
        seat("developer", "parent: pm\n");

        SeatTree tree = load().tree();

        assertTrue(tree.mayHire("pm", "developer"));
        assertFalse(tree.mayHire("chief-engineer", "developer"), "the old parent no longer hires it");
        assertEquals(SeatTree.Role.WORKER, tree.roleOf("developer").orElseThrow(), "the role the file did not change stays");
        assertTrue(tree.mayHire("chief-engineer", "senior-developer"), "a seat the file did not touch keeps its parent");
    }

    @Test
    @DisplayName("the role decides who may change code, and a new seat joins with a parent and a role")
    void theRoleDecidesWhoMayChangeCode() throws IOException {
        seat("cataloguer", "role: worker\n");
        seat("scribe", "parent: chief-engineer\nrole: reviewer\n");

        SeatTree tree = load().tree();

        assertTrue(tree.mayEditCode("cataloguer"));
        assertFalse(tree.mayEditCode("scribe"));
        assertTrue(tree.knows("scribe"));
        assertTrue(tree.mayHire("chief-engineer", "scribe"));
    }

    @Test
    @DisplayName("escalates_to names where a task goes next; blank means nowhere; the signed default is the developer to the senior developer")
    void escalationIsDeclared() throws IOException {
        assertEquals(Optional.of("senior-developer"), new Hirer(SeatTree.standard(), Map.of(), Set.of()).escalateTo("developer"));

        seat("developer", "escalates_to: javadoc-writer\n");
        assertEquals(Optional.of("javadoc-writer"), new Hirer(load().tree(), Map.of(), Set.of()).escalateTo("developer"));

        seat("developer", "escalates_to: \n");
        assertEquals(Optional.empty(), new Hirer(load().tree(), Map.of(), Set.of()).escalateTo("developer"));
    }

    @Test
    @DisplayName("a lead that lists a tool that changes code is refused, and a worker that lists one is not")
    void aLeadHoldsNoCodeChangingTool() throws IOException {
        seat("developer", "tools: [rename_symbol, extract, Edit]\n");
        assertFalse(load().refused(), "a worker may hold code-changing tools");

        seat("pm", "tools: [compile_workspace, rename_symbol]\n");
        SeatTree.Loaded loaded = load();

        assertTrue(loaded.refused());
        assertTrue(loaded.refusals().stream().anyMatch(line -> line.startsWith("pm:") && line.contains("rename_symbol")), loaded.refusals().toString());
        assertEquals(SeatTree.standard().declarations(), loaded.tree().declarations(), "a refused tree is replaced by the signed one");
    }

    @Test
    @DisplayName("a reviewer that sets changes_code or lists the brain's editor is refused")
    void aReviewerChangesNoCode() throws IOException {
        seat("architect", "changes_code: true\n");
        seat("ux", "tools: [Write]\n");

        SeatTree.Loaded loaded = load();

        assertTrue(loaded.refusals().stream().anyMatch(line -> line.startsWith("architect:") && line.contains("changes_code")), loaded.refusals().toString());
        assertTrue(loaded.refusals().stream().anyMatch(line -> line.startsWith("ux:") && line.contains("Write")), loaded.refusals().toString());
    }

    @Test
    @DisplayName("main is the one top: its file may declare no parent, and a parent that is no seat or a cycle is refused")
    void mainIsTheOneTop() throws IOException {
        seat("main", "parent: pm\n");
        assertTrue(load().refusals().stream().anyMatch(line -> line.startsWith("main:")), load().refusals().toString());
        Files.delete(folder.resolve("main.md"));

        seat("pm", "parent: nobody\n");
        assertTrue(load().refusals().stream().anyMatch(line -> line.startsWith("pm:") && line.contains("'nobody' is no seat")), load().refusals().toString());
        Files.delete(folder.resolve("pm.md"));

        seat("scribe-a", "parent: scribe-b\nrole: reviewer\n");
        seat("scribe-b", "parent: scribe-a\nrole: reviewer\n");
        SeatTree.Loaded cycle = load();
        assertTrue(cycle.refusals().stream().anyMatch(line -> line.startsWith("scribe-a:") && line.contains("cycle")), cycle.refusals().toString());
        assertTrue(cycle.refusals().stream().anyMatch(line -> line.startsWith("scribe-b:") && line.contains("cycle")), cycle.refusals().toString());
    }

    @Test
    @DisplayName("a role that is none of lead, worker or reviewer, a new seat without a role, and an escalation to no seat or to itself are refused")
    void malformedDeclarationsAreRefused() throws IOException {
        seat("developer", "role: boss\n");
        seat("scribe", "parent: pm\n");
        seat("senior-developer", "escalates_to: senior-developer\n");
        seat("javadoc-writer", "escalates_to: ghost\n");

        SeatTree.Loaded loaded = load();

        assertTrue(loaded.refusals().stream().anyMatch(line -> line.startsWith("developer:") && line.contains("'boss'")), loaded.refusals().toString());
        assertTrue(loaded.refusals().stream().anyMatch(line -> line.startsWith("scribe:") && line.contains("no role")), loaded.refusals().toString());
        assertTrue(loaded.refusals().stream().anyMatch(line -> line.startsWith("senior-developer:") && line.contains("names itself")), loaded.refusals().toString());
        assertTrue(loaded.refusals().stream().anyMatch(line -> line.startsWith("javadoc-writer:") && line.contains("'ghost' is no seat")), loaded.refusals().toString());
    }

    @Test
    @DisplayName("a seat file that cannot be read is named, not skipped")
    void anUnreadableFileIsNamed() throws IOException {
        Files.createDirectory(folder.resolve("broken.md"));

        SeatTree.Loaded loaded = load();

        assertFalse(loaded.unreadable().isEmpty());
        assertTrue(loaded.unreadable().get(0).contains("broken.md"), loaded.unreadable().toString());
    }
}
