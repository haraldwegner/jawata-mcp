package org.jawata.mcp.access;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Sprint 28h, S13 (D20, ACL1 to ACL9): the shared case file, run through the Java parser. The hook's Rust parser runs the same file, so the one
 * deliberate duplicate named in the design cannot drift: a case added here binds both. Each case carries the clause of the access policy it
 * proves; the tests beside it (AccessPolicyTest) hold what a table of calls cannot, such as the policy file being broken.
 */
class AccessCheckCasesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private Path root;
    private Path home;
    private Path workspace;
    private Path sibling;
    private Path temp;
    private Path policyFile;
    private JsonNode cases;

    @BeforeEach
    void layOutTheMachine() throws IOException {
        root = Files.createTempDirectory("jawata-access-").toRealPath();
        home = Files.createDirectories(root.resolve("home"));
        workspace = Files.createDirectories(home.resolve("CursorProjects").resolve("projA"));
        sibling = Files.createDirectories(home.resolve("CursorProjects").resolve("projB"));
        temp = Files.createDirectories(root.resolve("scratch"));
        Files.createDirectories(home.resolve(".claude").resolve("projects"));
        try (InputStream in = AccessCheckCasesTest.class.getResourceAsStream("/test-resources/access/cases.json")) {
            assertNotNull(in, "the shared case file is not among the test resources");
            cases = JSON.readTree(in);
        }
        policyFile = root.resolve("access-policy.json");
        Files.writeString(policyFile, cases.get("policy").toString().replace("/dev/null", declaredDevice()));
    }

    /**
     * The place the shared file declares "by name". It is /dev/null for the POSIX parsers; Windows has no such absolute path (a policy entry
     * that is not absolute makes the whole policy unusable, as it should), so there the case declares an absolute place of this machine instead,
     * which is what the case is about.
     */
    private String declaredDevice() {
        boolean windows = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win");
        return windows ? inJson(home.resolve("declared-device")) : "/dev/null";
    }

    @AfterEach
    void removeWhatWasCreated() throws IOException {
        try (var walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }

    private String bound(String text) {
        return text.replace("${home}", inJson(home)).replace("${workspace}", inJson(workspace))
            .replace("${sibling}", inJson(sibling)).replace("${temp}", inJson(temp)).replace("/dev/null", declaredDevice());
    }

    /**
     * A path as the case file writes one, inside JSON and inside a shell command: with forward slashes, which Windows accepts as a separator.
     * A backslash would have to be doubled in JSON, and in a command the shell reader takes it for an escape and drops it ({@code \Users}).
     */
    private static String inJson(Path path) {
        return path.toString().replace('\\', '/');
    }

    private AccessCheck check() {
        return new AccessCheck(policyFile, session -> List.of(workspace), home, temp);
    }

    @Test
    @DisplayName("every case of the shared file is decided as it says, and the file is large enough to bind a parser")
    void everySharedCaseIsDecidedAsItSays() throws Exception {
        AccessCheck check = check();
        int allowed = 0;
        int refused = 0;
        List<String> wrong = new java.util.ArrayList<>();
        for (JsonNode c : cases.get("cases")) {
            JsonNode call = JSON.readTree(bound(JSON.writeValueAsString(c.get("call"))));
            Optional<String> refusal = check.refuse("session-1", call);
            String id = c.get("id").asText() + " (" + c.get("acl").asText() + ")";
            if ("allow".equals(c.get("expect").asText())) {
                allowed++;
                if (refusal.isPresent()) {
                    wrong.add(id + " was refused: " + refusal.get());
                }
            } else {
                refused++;
                if (refusal.isEmpty()) {
                    wrong.add(id + " was allowed: " + c.get("why").asText());
                } else if (!refusal.get().startsWith("Access policy: ")) {
                    wrong.add(id + " was refused in other words: " + refusal.get());
                }
            }
        }
        assertEquals(List.of(), wrong, "cases decided otherwise than the file says");
        assertTrue(allowed >= 5 && refused >= 10, "the case file is too small to bind a parser: " + allowed + " allowed, " + refused + " refused");
    }

    @Test
    @DisplayName("ACL8: the verdict is the same whichever client's session makes the call, because it is decided where every call passes")
    void theVerdictDoesNotDependOnTheClient() throws Exception {
        JsonNode call = JSON.readTree(bound("{\"file_path\":\"${home}/Documents/notes.txt\"}"));
        AccessCheck check = check();

        Optional<String> cursor = check.refuse("cursor-session", call);
        Optional<String> claude = check.refuse("claude-code-session", call);
        Optional<String> acp = check.refuse("acp-session", call);

        assertTrue(cursor.isPresent(), "a Cursor session was allowed a place a Claude Code session is refused");
        assertEquals(cursor, claude);
        assertEquals(cursor, acp);
        assertFalse(check.refuse("cursor-session", JSON.readTree(bound("{\"file_path\":\"${workspace}/src/A.java\"}"))).isPresent(),
            "CONTROL: every client was refused");
    }

    @Test
    @DisplayName("ACL4: a session whose workspace is another project is refused this one, and a session with none is refused every project file")
    void theWorkspaceIsTheCallersOwn() throws Exception {
        JsonNode inA = JSON.readTree(bound("{\"file_path\":\"${workspace}/src/A.java\"}"));
        AccessCheck other = new AccessCheck(policyFile, session -> List.of(sibling), home, temp);
        AccessCheck none = new AccessCheck(policyFile, session -> List.of(), home, temp);

        assertTrue(other.refuse("s", inA).isPresent(), "a task on projB was allowed projA");
        assertTrue(none.refuse("s", inA).isPresent(), "a session with no workspace was allowed a project file");
        assertEquals(Optional.empty(), check().refuse("s", inA), "CONTROL: the owner of projA was refused it");
    }
}
