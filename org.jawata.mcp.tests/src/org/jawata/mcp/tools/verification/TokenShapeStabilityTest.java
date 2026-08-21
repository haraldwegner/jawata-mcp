package org.jawata.mcp.tools.verification;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindDuplicateCodeTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28c S1 — the parity pin for extracting {@code TokenShape}.
 *
 * <p>{@code replace_duplicates} re-resolves a clone group STATELESSLY: the caller
 * hands back a {@code groupId} it was given earlier, and the tool re-scans and
 * matches by that id (see {@code ReplaceDuplicatesTool}, which compares
 * {@code groupIdOf(...)} against the supplied id). The id is a SHA-1 prefix of the
 * normalized token sequence. So the normalization pipeline —
 * comment/literal collapsing, the token pattern, the keyword set, the per-token
 * normalizer — is not an implementation detail: <b>its output is a published
 * identifier</b>. Change any link in that chain and every id an agent is holding
 * silently stops resolving, with no compiler error and no failing assertion
 * anywhere else in the suite.</p>
 *
 * <p>This test pins the id of one known clone shape in the committed
 * {@code simple-maven} fixture, driven through the production tool path so that
 * every member of the chain participates. It is written BEFORE the extraction and
 * runs UNCHANGED after it: the move is internal, the observable id is not. If the
 * extraction alters behaviour anywhere in the pipeline, this goes red.</p>
 *
 * <p>The literal below is not a guess — it was recorded from the implementation as
 * it stood before the move.</p>
 */
class TokenShapeStabilityTest {

    /**
     * The id of the {@code void NAME() { System.out.println("…"); }} shape shared by
     * {@code Animal.speak}, {@code Animal.move} and {@code Dog.speak} in the
     * {@code simple-maven} fixture, as produced before the {@code TokenShape}
     * extraction.
     */
    private static final String PINNED_GROUP_ID = "b08a9882bf44";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private FindDuplicateCodeTool tool;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProject("simple-maven");
        tool = new FindDuplicateCodeTool(() -> service);
        objectMapper = new ObjectMapper();
    }

    @Test
    @DisplayName("the groupId of a known clone shape survives the TokenShape extraction unchanged")
    void theGroupIdOfAKnownCloneShapeIsStable() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 5);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "find_duplicate_code must succeed; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        assertNotNull(groups, "groups list must be present");

        Optional<Map<String, Object>> speakAndMove = groups.stream()
            .filter(g -> {
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> instances = (List<Map<String, Object>>) g.get("instances");
                boolean hasSpeak = instances.stream().anyMatch(i -> "speak".equals(i.get("methodName")));
                boolean hasMove = instances.stream().anyMatch(i -> "move".equals(i.get("methodName")));
                return hasSpeak && hasMove;
            })
            .findFirst();

        assertTrue(speakAndMove.isPresent(),
            "the fixture's speak+move clone shape must be detected, or this test pins nothing; groups=" + groups);

        String actual = (String) speakAndMove.get().get("groupId");
        assertNotNull(actual, "a detected group must carry a groupId");
        assertEquals(PINNED_GROUP_ID, actual,
            "the normalized-token-shape id CHANGED. Every groupId an agent is holding — and every "
                + "replace_duplicates call that re-resolves one — now points at nothing. If this "
                + "change was intended, the id contract was broken deliberately and callers must be "
                + "told; if not, the normalization pipeline was altered by accident.");
    }
}
