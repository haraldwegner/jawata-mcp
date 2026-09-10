package org.jawata.mcp.tools.refactoring;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.refactoring.RefactoringChangeCache;
import org.jawata.mcp.tools.ApplyNullAnnotationsTool;
import org.jawata.mcp.tools.codegen.GenerateConstructorTool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#75 — THE LINE DELIMITER A GENERATED MEMBER IS WRITTEN WITH.
 *
 * <h2>The issue said this needed Windows. It does not.</h2>
 *
 * <p>mcp#75 describes CRLF written into an LF file and records the discriminating step as
 * never run, because it was framed as needing the Eclipse line-delimiter preference set to
 * {@code \n} on a Windows host. That framing is wrong about where the mismatch lives: the
 * defect is between the FILE's delimiter and whatever the writer uses, not between two
 * operating systems. A CRLF file on Linux is the same mismatch pointing the other way, and it
 * runs anywhere — which is what this test does.</p>
 *
 * <h2>Why no existing gate could see it</h2>
 *
 * <p>{@code RowParity} compares a golden with
 * {@code now.lines().map(String::strip).filter(...).sorted()}. {@code String.lines()} splits
 * on {@code \n}, {@code \r\n} and {@code \r} alike and {@code strip()} removes the carriage
 * return, so the comparison is blind to delimiters by construction — and it sorts, so it is
 * blind to order too. Every parity golden in this sprint would pass over a file whose endings
 * had been changed wholesale. This assertion therefore reads BYTES.</p>
 *
 * <p>The file is written into the COPY rather than added to the shared fixture project:
 * {@code simple-maven} is shared and monotonically growing, and adding to it has moved a
 * counted population four times in this sprint.</p>
 */
class GeneratedMemberKeepsTheFilesDelimiterTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    /** A CRLF probe with an import block, so the rewrite has to place one too. */
    private static final String CRLF_ANNOTATION_SOURCE =
        "package com.example;\r\n"
            + "\r\n"
            + "/** mcp#75 probe: every delimiter in this file is CRLF. */\r\n"
            + "public class CrlfAnnotationTarget {\r\n"
            + "    String find(String key) {\r\n"
            + "        return key;\r\n"
            + "    }\r\n"
            + "}\r\n";

    private static final String CRLF_SOURCE =
        "package com.example;\r\n"
            + "\r\n"
            + "/** mcp#75 probe: every delimiter in this file is CRLF. */\r\n"
            + "public class CrlfDelimiterTarget {\r\n"
            + "    private String name;\r\n"
            + "    private int size;\r\n"
            + "\r\n"
            + "    int measure() {\r\n"
            + "        int a = size * 2;\r\n"
            + "        int b = a + 1;\r\n"
            + "        return b;\r\n"
            + "    }\r\n"
            + "}\r\n";

    @Test
    @DisplayName("the control: a lone LF spliced into the result IS caught")
    void theDetectorCatchesALoneLf() {
        // WITHOUT THIS the case above is green against a detector that can never fire, which
        // is the defect class this sprint has recorded at every checkpoint. The needle is
        // applied to the fixture's own text rather than to a synthetic string, so it is the
        // same content the real assertion reads.
        assertEquals(0, countLoneLf(CRLF_SOURCE), "the fixture is pure CRLF");
        String spliced = CRLF_SOURCE.replaceFirst("\r\n", "\n");
        assertEquals(1, countLoneLf(spliced),
            "one delimiter foreign to the file must be counted as exactly one");
    }

    @Test
    @DisplayName("mcp#75: a member generated into a CRLF file does not splice LF lines into it")
    void aGeneratedMemberKeepsTheFilesDelimiter() throws Exception {
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path root = service.allProjects().iterator().next().projectRoot();
        Path file = root.resolve("src/main/java/com/example/CrlfDelimiterTarget.java");
        Files.writeString(file, CRLF_SOURCE, StandardCharsets.UTF_8);
        ResourcesPlugin.getWorkspace().getRoot()
            .refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        String before = Files.readString(file, StandardCharsets.UTF_8);
        assertEquals(0, countLoneLf(before),
            "PROOF OF LIFE: the probe file must be pure CRLF before the operation, or this"
                + " test measures nothing");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("line", 3);     // zero-based: the class declaration
        args.put("column", 13);
        ArrayNode fields = args.putArray("fields");
        fields.add("name");
        fields.add("size");
        ToolResponse r = new GenerateConstructorTool(() -> service, new RefactoringChangeCache())
            .execute(args);
        assertTrue(r.isSuccess(), "PROOF OF LIFE: the generation must succeed, or the byte"
            + " assertion below holds over an unchanged file: "
            + (r.getError() != null ? r.getError().getCode() + " / " + r.getError().getMessage()
                                    : "(no error)"));

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(after.contains("CrlfDelimiterTarget(String name"),
            "PROOF OF LIFE: the constructor must actually be in the file: " + after);
        assertEquals(0, countLoneLf(after),
            "A generated member must be written with the delimiter the FILE uses. Lone LFs"
                + " here mean the writer used the platform default and spliced mixed endings"
                + " into somebody's CRLF file — invisible to every parity golden, because"
                + " RowParity strips and sorts the lines it compares. File now:\n" + after);
    }

    @Test
    @DisplayName("mcp#75: an ANNOTATION added to a CRLF file keeps the file's delimiter")
    void anAddedAnnotationKeepsTheFilesDelimiter() throws Exception {
        // THE THIRD WRITER, and the reason it is here rather than assumed covered:
        // `ApplyNullAnnotationsTool` used ASTRewrite's NO-ARGUMENT rewriteAST(), which
        // reads the type root's own options instead of the document's — so it saw neither
        // the file's indentation nor its line delimiter. `HeadlessJdtConfig` named it and
        // its sibling as still open and predicted that fixing them would make their parity
        // goldens diverge.
        //
        // THE PREDICTION WAS WRONG, WHICH IS WHY THIS TEST EXISTS. After the fix the
        // goldens did not move, and a mutation reverting all three sites left 17 of 17
        // green — so the repair was real and nothing could tell whether it was present. A
        // golden that compares stripped, sorted lines is blind to delimiters by
        // construction; only a byte-level assertion can see this.
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path root = service.allProjects().iterator().next().projectRoot();
        Path file = root.resolve("src/main/java/com/example/CrlfAnnotationTarget.java");
        Files.writeString(file, CRLF_ANNOTATION_SOURCE, StandardCharsets.UTF_8);
        ResourcesPlugin.getWorkspace().getRoot()
            .refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        assertEquals(0, countLoneLf(Files.readString(file, StandardCharsets.UTF_8)),
            "PROOF OF LIFE: the probe file must be pure CRLF before the operation, or this"
                + " test measures nothing");

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode();
        args.put("kind", "add");
        args.put("symbol", "com.example.CrlfAnnotationTarget#find");
        args.put("nullness", "nullable");
        args.put("parameter", "key");
        args.put("style", "JSPECIFY");
        ToolResponse r = new ApplyNullAnnotationsTool(() -> service, new RefactoringChangeCache())
            .execute(args);
        assertTrue(r.isSuccess(), "PROOF OF LIFE: the annotation must actually be applied, or"
            + " the byte assertion below holds over an unchanged file: "
            + (r.getError() != null ? r.getError().getCode() + " / " + r.getError().getMessage()
                                    : "(no error)"));

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(after.contains("Nullable"),
            "PROOF OF LIFE: the annotation must be in the file: " + after);
        assertEquals(0, countLoneLf(after),
            "An annotation added to a CRLF file must be written with CRLF. Lone LFs here mean"
                + " the rewrite used the type root's options rather than the document's —"
                + " mixed endings spliced into somebody's file, invisible to every parity"
                + " golden. File now:\n" + after);
    }

    @Test
    @DisplayName("mcp#75: an EXTRACTED method keeps the file's delimiter too")
    void anExtractedMethodKeepsTheFilesDelimiter() throws Exception {
        // A SECOND WRITER, because one is not a population. `extract` is the higher-value
        // second case rather than another generator: Stage 3 recorded it emitting TAB-indented
        // bodies into space-indented files regardless of what the file used — the same family
        // of defect, one axis over — and that finding is still open for two rewrite sites.
        JdtServiceImpl service = helper.loadProjectCopy("simple-maven");
        Path root = service.allProjects().iterator().next().projectRoot();
        Path file = root.resolve("src/main/java/com/example/CrlfDelimiterTarget.java");
        Files.writeString(file, CRLF_SOURCE, StandardCharsets.UTF_8);
        ResourcesPlugin.getWorkspace().getRoot()
            .refreshLocal(IResource.DEPTH_INFINITE, new NullProgressMonitor());

        ObjectMapper mapper = new ObjectMapper();
        ObjectNode args = mapper.createObjectNode();
        args.put("filePath", file.toString());
        args.put("startLine", 8);      // zero-based: `int a = size * 2;`
        args.put("startColumn", 8);
        args.put("endLine", 9);        // `int b = a + 1;`
        args.put("endColumn", 22);
        args.put("methodName", "computed");
        ToolResponse r = new org.jawata.mcp.tools.ExtractMethodTool(
            () -> service, new RefactoringChangeCache()).execute(args);
        assertTrue(r.isSuccess(), "PROOF OF LIFE: the extraction must succeed, or the byte"
            + " assertion below holds over an unchanged file: "
            + (r.getError() != null ? r.getError().getCode() + " / " + r.getError().getMessage()
                                    : "(no error)"));

        String after = Files.readString(file, StandardCharsets.UTF_8);
        assertTrue(after.contains("computed"),
            "PROOF OF LIFE: the extracted method must be in the file: " + after);
        assertEquals(0, countLoneLf(after),
            "An extracted method must be written with the delimiter the FILE uses. File now:\n"
                + after);
    }

    /** Line feeds NOT preceded by a carriage return — i.e. delimiters foreign to this file. */
    private static int countLoneLf(String source) {
        int lone = 0;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n' && (i == 0 || source.charAt(i - 1) != '\r')) {
                lone++;
            }
        }
        return lone;
    }
}
