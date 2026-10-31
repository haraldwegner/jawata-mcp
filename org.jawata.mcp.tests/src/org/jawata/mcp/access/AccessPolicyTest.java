package org.jawata.mcp.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S13 (D20): what the access policy does with its FILE. Each clause is shown with the case that must not trigger it, so a
 * policy that refuses everything, or allows everything, fails here.
 */
class AccessPolicyTest {

    private Path root;
    private Path project;
    private Path elsewhere;
    private Path file;

    @BeforeEach
    void layOutFolders() throws IOException {
        root = Files.createTempDirectory("jawata-policy-").toRealPath();
        project = Files.createDirectories(root.resolve("project"));
        elsewhere = Files.createDirectories(root.resolve("elsewhere"));
        file = root.resolve("access-policy.json");
    }

    @AfterEach
    void removeFolders() throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private AccessPolicy read(String json) throws IOException {
        Files.writeString(file, json);
        return AccessPolicy.read(file, Map.of("workspace", List.of(project), "temp", List.of(root.resolve("scratch"))));
    }

    @Test
    @DisplayName("ACL1: the decision changes when the file changes and in no other way")
    void theDecisionFollowsTheFile() throws IOException {
        String allowProject = "{\"allow\":[\"${workspace}\"],\"deny\":[]}";
        String allowElsewhere = "{\"allow\":[\"" + elsewhere + "\"],\"deny\":[]}";
        Path place = project.resolve("A.java");

        boolean before = read(allowProject).check(place).allowed();
        boolean again = read(allowProject).check(place).allowed();
        boolean after = read(allowElsewhere).check(place).allowed();

        assertTrue(before, "the file allows the project and the project was refused");
        assertEquals(before, again, "the same file gave two answers");
        assertFalse(after, "the file no longer allows the project and the project was allowed");
    }

    @Test
    @DisplayName("ACL2: no place is built in: an empty allow list refuses every place, and removing an entry refuses that place")
    void noPlaceIsBuiltIn() throws IOException {
        Path inScratch = root.resolve("scratch").resolve("x");
        assertFalse(read("{\"allow\":[],\"deny\":[]}").check(project.resolve("A.java")).allowed(), "an empty policy allowed a place");
        assertFalse(read("{\"allow\":[],\"deny\":[]}").check(Path.of(System.getProperty("java.io.tmpdir"), "x")).allowed(),
            "the scratch directory was allowed by nothing the file says");
        AccessPolicy both = read("{\"allow\":[\"${workspace}\",\"${temp}\"],\"deny\":[]}");
        AccessPolicy withoutTemp = read("{\"allow\":[\"${workspace}\"],\"deny\":[]}");
        assertTrue(both.check(inScratch).allowed(), "CONTROL: the entry that was there did not allow its place");
        assertFalse(withoutTemp.check(inScratch).allowed(), "removing the entry did not refuse its place");
        assertTrue(withoutTemp.check(project.resolve("A.java")).allowed(), "CONTROL: removing one entry refused another's place");
    }

    @Test
    @DisplayName("ACL5: a policy that is missing, broken, incomplete, or uses a name nobody defined refuses every place, and says which it was")
    void aPolicyThatCannotBeUsedRefusesAndSaysWhy() throws IOException {
        Path place = project.resolve("A.java");

        AccessPolicy missing = AccessPolicy.read(root.resolve("nowhere.json"), Map.of());
        assertFalse(missing.check(place).allowed());
        assertTrue(missing.check(place).reason().contains("there is no access policy at"), missing.check(place).reason());

        AccessPolicy broken = read("{\"allow\": [");
        assertFalse(broken.check(place).allowed());
        assertTrue(broken.check(place).reason().contains("not valid JSON"), broken.check(place).reason());

        AccessPolicy noDeny = read("{\"allow\":[\"${workspace}\"]}");
        assertFalse(noDeny.check(place).allowed());
        assertTrue(noDeny.check(place).reason().contains("no \"deny\" list"), noDeny.check(place).reason());

        AccessPolicy undefined = read("{\"allow\":[\"${nobody}/x\"],\"deny\":[]}");
        assertFalse(undefined.check(place).allowed());
        assertTrue(undefined.check(place).reason().contains("${nobody}, which is not defined"), undefined.check(place).reason());

        AccessPolicy relative = read("{\"allow\":[\"src\"],\"deny\":[]}");
        assertFalse(relative.check(place).allowed());
        assertTrue(relative.check(place).reason().contains("is not an absolute path"), relative.check(place).reason());

        AccessPolicy fine = read("{\"allow\":[\"${workspace}\"],\"deny\":[]}");
        assertTrue(fine.check(place).allowed(), "CONTROL: a good policy refused its own place");
    }

    @Test
    @DisplayName("a deny entry whose variable is undefined makes the whole policy unusable, because dropping it would allow what it denied")
    void anUndefinedNameOnTheDenyListDoesNotLoseTheDenial() throws IOException {
        AccessPolicy policy = read("{\"allow\":[\"${workspace}\"],\"deny\":[\"${nobody}/secret\"]}");

        assertFalse(policy.check(project.resolve("A.java")).allowed(), "the denial was dropped and the project was allowed");
    }

    @Test
    @DisplayName("an entry whose variable stands for no place holds nothing: it allows nothing, and the rest of the policy still works")
    void aVariableWithNoValueAllowsNothing() throws IOException {
        Files.writeString(file, "{\"allow\":[\"${workspace}\",\"" + elsewhere + "\"],\"deny\":[]}");
        AccessPolicy policy = AccessPolicy.read(file, Map.of("workspace", List.of()));

        assertFalse(policy.check(project.resolve("A.java")).allowed(), "a session with no workspace was allowed the project");
        assertTrue(policy.check(elsewhere.resolve("B.java")).allowed(), "CONTROL: the other entry stopped working");
    }

    @Test
    @DisplayName("a link inside an allowed place that points outside it is refused, and a spelling with .. is the place it reaches")
    void aPlaceIsJudgedForWhatItIs() throws IOException {
        Path link = project.resolve("shortcut");
        try {
            Files.createSymbolicLink(link, elsewhere);
        } catch (UnsupportedOperationException | IOException e) {
            Assumptions.abort("this machine cannot create a symbolic link: " + e);
        }
        AccessPolicy policy = read("{\"allow\":[\"${workspace}\"],\"deny\":[]}");

        assertFalse(policy.check(link.resolve("B.java")).allowed(), "a link out of the project was followed as if it stayed inside");
        assertFalse(policy.check(project.resolve("..").resolve("elsewhere").resolve("B.java")).allowed(), "a .. spelling stayed 'inside'");
        assertTrue(policy.check(project.resolve("sub").resolve("..").resolve("A.java")).allowed(), "CONTROL: a .. that stays inside was refused");
    }
}
