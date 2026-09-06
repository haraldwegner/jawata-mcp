package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.HierarchyTool;
import org.jawata.mcp.tools.inheritance.CollapseHierarchyTool;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Row 4 ON CODE WE DID NOT AUTHOR — a REFUSAL, and the census is what makes it evidence rather
 * than a shrug.
 *
 * <h2>The corpus contains exactly ONE three-level hierarchy, and this is it</h2>
 *
 * <p>Across all sixteen vendored slices, twenty-one classes extend a named class. Of those, only
 * ONE is itself extended — {@code RateLimitException}, with {@code ThrottlingException} and
 * {@code ServiceUnavailableException} below it. Every other {@code extends} in the corpus reaches
 * a base that nothing further extends: an abstract base plus its implementations, two levels,
 * which is the shape a pattern demonstration takes because the base/implementation split IS what
 * such a corpus is written to show. A middle level that is not earning itself is a maintenance
 * artefact, and a curated teaching repository has not been maintained into one.</p>
 *
 * <p><b>And the one candidate is refused for a reason that is upstream's, not ours:</b> its
 * parent is {@code java.lang.Exception}, which has no source in the workspace, so there is
 * nowhere to move its members TO. That is also why the level is legitimate there — an
 * intermediate exception exists precisely so a caller can catch the supertype, which is the
 * substitutability row 57 refuses to remove for the same reason.</p>
 *
 * <p>This slice cannot use {@code ForkSliceSupport.load}: upstream's files here carry no
 * per-file licence header, because upstream's originals carry none, and that header is what the
 * provenance check reads. It asserts the SHAPE instead, the way row 21's slice does.</p>
 */
class CollapseHierarchyForkSliceTest {

    private static final String PKG = "src/main/java/com/iluwatar/rate/limiting/pattern";

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private HierarchyTool tool;
    private Path pkg;

    @BeforeEach
    void setUp() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("fork-rate-limiting");
        tool = new HierarchyTool(() -> service, new RefactoringChangeCache());
        pkg = service.allProjects().iterator().next().projectRoot().resolve(PKG);
    }

    private String read(String file) throws Exception {
        Path path = pkg.resolve(file);
        return Files.exists(path) ? Files.readString(path, StandardCharsets.UTF_8) : "(absent)";
    }

    @Test
    @DisplayName("row 4 refuses upstream's only middle level, because its parent is the JDK's")
    void refusesUpstreamsOnlyMiddleLevel() throws Exception {
        String before = read("RateLimitException.java");
        Assertions.assertAll(
            () -> assertTrue(before.contains("class RateLimitException extends Exception"),
                "PROOF OF LIFE, the middle level itself. If the fork pin moved, everything"
                    + " below would pass over nothing:\n" + before),
            () -> assertTrue(read("ThrottlingException.java").contains("extends"
                    + " RateLimitException"),
                "PROOF OF LIFE, the first subtype — without one this would refuse as"
                    + " NO_SUBTYPES and prove row 38's case instead of this row's"),
            () -> assertTrue(read("ServiceUnavailableException.java").contains("extends"
                    + " RateLimitException"),
                "PROOF OF LIFE, the second subtype"));

        ObjectNode args = new ObjectMapper().createObjectNode();
        args.put("direction", "collapse_hierarchy");
        args.put("typeName", "com.iluwatar.rate.limiting.pattern.RateLimitException");

        ToolResponse r = tool.execute(args);

        Assertions.assertAll(
            () -> assertFalse(r.isSuccess(), "there is nowhere to move its members to"),
            () -> assertEquals(CollapseHierarchyTool.Refusal.PARENT_NOT_IN_SOURCE,
                r.getError().getReason(),
                "the REASON CODE, not a substring — an earlier precondition declining for its"
                    + " own reason would otherwise read as this one: " + r.getError()),
            () -> assertEquals(before, read("RateLimitException.java"),
                "and upstream's file is untouched"));
    }
}
