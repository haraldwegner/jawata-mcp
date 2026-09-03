package org.jawata.mcp.tools.smell;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindQualityIssueTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 28d-rescue — the commented-out-code check, against
 * com.example.CommentedOutTargets.
 *
 * <p>The silence half is most of this file, because the easy version of this check
 * reports every comment with a semicolon in it and gets turned off the same day. The
 * fixture holds four things that must stay silent: prose ending in a semicolon, prose
 * naming a method by name, a TODO, and a word with a semicolon after it — the last of
 * which parses perfectly well as Java and does no work.</p>
 *
 * <p>It also pins the WORDING. Harald's ruling was that a find is a candidate and never
 * proof the code is dead, so the message has to say so; a check whose text asserts more
 * than it knows is how a caution becomes a deletion.</p>
 */
class CommentedOutCodeDetectorTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private FindQualityIssueTool tool;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        tool = new FindQualityIssueTool(() -> service);
        mapper = new ObjectMapper();
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> findings() {
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "commented_out_code");
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "commented_out_code must dispatch — refused with: "
            + (r.getError() != null ? r.getError().getCode() + " / " + r.getError().getMessage()
                                    : "(no error info)"));
        Map<String, Object> data = (Map<String, Object>) r.getData();
        return (List<Map<String, Object>>) data.get("findings");
    }

    /** Findings in the fixture, which is the only file this test makes claims about. */
    private List<Map<String, Object>> inFixture() {
        return findings().stream()
            .filter(f -> String.valueOf(f.get("filePath")).endsWith("CommentedOutTargets.java"))
            .toList();
    }

    @Test
    @DisplayName("a run of line comments and a block comment both read as the code they were")
    void bothCommentShapesAreFound() {
        List<Map<String, Object>> hits = inFixture();
        assertEquals(2, hits.size(),
            "the fixture holds exactly two commented-out blocks — a run of two line"
                + " comments and one block comment — and six comments that are prose."
                + " Got: " + hits);
    }

    @Test
    @DisplayName("prose is silent, however much it is punctuated like code")
    void proseIsNotReported() {
        // Asserted through the count above and the lines below: the fixture's prose
        // comments sit on known lines, and a hit on any of them would push the count
        // past two. Naming them here is what makes a future failure readable rather
        // than a bare number.
        String all = String.valueOf(inFixture());
        for (String prose : List.of("TODO", "see also", "This sentence ends in a semicolon")) {
            assertFalse(all.contains(prose),
                "a comment containing '" + prose + "' is prose, and a check that reports"
                    + " it is a check nobody leaves switched on: " + all);
        }
    }

    @Test
    @DisplayName("javadoc is never examined, so a code sample inside it is safe")
    void javadocIsNotExamined() {
        for (Map<String, Object> hit : inFixture()) {
            int line = ((Number) hit.get("line")).intValue();
            assertTrue(line > 9,
                "the fixture's class javadoc ends at line 9 and contains"
                    + " `int x = compute(); return x;` on purpose. A hit inside it means"
                    + " documentation is being read as leftovers: " + hit);
        }
    }

    @Test
    @DisplayName("the message offers a candidate and never asserts the code is dead")
    void theMessageIsACandidate() {
        for (Map<String, Object> hit : inFixture()) {
            String message = String.valueOf(hit.get("message"));
            assertTrue(message.contains("CANDIDATE"),
                "the ruling behind this check is that commented-out code may still carry"
                    + " meaning, so the finding must say what it is: " + message);
            assertTrue(message.contains("Nothing here removes it for you"),
                "and it must say that nothing acts on it, because the whole caution is"
                    + " about deletion: " + message);
            // The NEGATION, pinned literally. Banning the phrase "dead code" outright
            // failed on the message's own denial of it, which is the sentence that does
            // the work: a check that never mentions the claim cannot be seen to disclaim
            // it either.
            assertTrue(message.contains("not a finding of dead code"),
                "the message must deny the claim in so many words, because a reader who"
                    + " skims it will otherwise supply the claim themselves: " + message);
        }
    }
}
