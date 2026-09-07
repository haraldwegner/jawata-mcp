package org.jawata.mcp.tools.analysis;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.FindNamingViolationsTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * THE COORDINATE BASE OF A NAMING VIOLATION, ON AN INPUT THAT CAN ACTUALLY FAIL.
 *
 * <p>This exists because three earlier attempts at the same control could not fail, and each
 * failed differently — which is the record worth keeping, not the test.</p>
 *
 * <ol>
 *   <li>Scoped to one shared fixture: its three violations are all addressed at a declaration
 *       START, which includes the javadoc, so ZERO rows were checkable. Its own proof-of-life
 *       refused it.</li>
 *   <li>Widened to the whole shared project: still zero, because {@code simple-maven} contains
 *       no badly-named field at all.</li>
 *   <li>Relaxed to "the reported line is not blank". I recorded this one as unable to fail,
 *       having restored the pre-decrement and watched it stay GREEN. <b>That conclusion was
 *       wrong, and the fault was in my mutation rather than in the test.</b> The tool has SIX
 *       emission sites in two spellings — four addressing {@code node}, two addressing
 *       {@code varFrag} — and the mutation replaced only the four. Every field row therefore
 *       kept the corrected base, and the field rows are the ones that land on a blank line.
 *       A C8b round-5 audit restored all six and the check went RED, naming
 *       {@code serialVersionUID} on blank line 21.</li>
 * </ol>
 *
 * <p><b>So the sibling control in {@code FindNamingViolationsToolTest} does discriminate, and
 * this class is a second guard rather than its replacement.</b> The lesson is not about either
 * test: "a mutation that stays green has found something" holds only when the mutation is
 * COMPLETE, and a partial one produces a false negative that reads exactly like a real one.
 * Mine covered four of six sites because I reached for one spelling and did not count the
 * population — the same error, at a smaller scale, that this whole round was convened for.</p>
 *
 * <p><b>What makes this one sound.</b> The tool addresses a FIELD violation at the fragment —
 * {@code varFrag.getStartPosition()} — so the identifier is genuinely at that position and
 * "the name is on the reported line" is true by construction rather than by habit. A row one
 * line short lands on the blank line deliberately placed above it. The fixture is written into
 * the project COPY, so nothing is added to the shared sample project, which has moved a
 * counted population four times in this sprint.</p>
 */
class NamingViolationCoordinateTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private FindNamingViolationsTool tool;
    private ObjectMapper mapper;
    private Path pkgDir;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        tool = new FindNamingViolationsTool(() -> service);
        mapper = new ObjectMapper();
        pkgDir = helper.getTempDirectory().resolve("simple-maven/src/main/java/com/example");
    }

    @Test
    @DisplayName("a field violation's line is 1-based: the identifier is on it, not one above")
    void theFieldRowsLineIsOneBased() throws Exception {
        String source = """
            package com.example;

            /** Written by the test, into its own project copy. */
            public class NamingCoordinateTargets {

                private int Bad_Name = 1;

                int use() {
                    return Bad_Name;
                }
            }
            """;
        Path file = pkgDir.resolve("NamingCoordinateTargets.java");
        Files.writeString(file, source);
        service.getJavaProject().getProject().refreshLocal(
            org.eclipse.core.resources.IResource.DEPTH_INFINITE,
            new org.eclipse.core.runtime.NullProgressMonitor());

        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "the detector must run; got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("violations");

        Map<String, Object> row = rows == null ? null : rows.stream()
            .filter(v -> "Bad_Name".equals(String.valueOf(v.get("name"))))
            .findFirst().orElse(null);
        assertTrue(row != null,
            "PROOF OF LIFE: the fixture's field must be reported, or this test checks nothing."
                + " Rows: " + rows);

        List<String> lines = source.lines().toList();
        int line = (Integer) row.get("line");
        assertTrue(line >= 1 && line <= lines.size(),
            "a 1-based line must be inside the file: reported " + line
                + ", file has " + lines.size() + " lines");
        assertTrue(lines.get(line - 1).contains("Bad_Name"),
            "'Bad_Name' is reported on 1-based line " + line + ", which reads: "
                + lines.get(line - 1).strip() + "\n  the line BELOW reads: "
                + (line < lines.size() ? lines.get(line).strip() : "(end of file)")
                + "\n  One line short is the 0-based base this tool emitted at all six of its"
                + " sites, while the `findings` rows it is merged beside are 1-based. The"
                + " blank line above the field is there so this lands somewhere visibly"
                + " wrong rather than somewhere plausible.");
    }
}
