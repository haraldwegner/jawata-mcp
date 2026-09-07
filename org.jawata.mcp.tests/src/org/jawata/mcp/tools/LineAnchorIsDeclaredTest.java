package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#20 — <b>two tools answer different questions and publish both answers under {@code line}.</b>
 *
 * <p>{@code search_symbols} reports where the search engine found the NAME token;
 * {@code inspect(kind=document_symbols)} reports JDT's DECLARATION range, which by definition
 * begins at the doc comment. Both are correct, neither said which it was, and the gap is as
 * wide as the javadoc.</p>
 *
 * <p>This is the degradation stamp's first rule applied to a coordinate: <b>a value is never
 * bare.</b> A number whose meaning depends on which tool produced it, published under a key
 * that does not say, is the same defect as a count with no denominator — a reader reconciling
 * two answers has no way to know they were answering different questions.</p>
 *
 * <p>THE LABEL IS ASSERTED AGAINST THE NUMBER, not merely asserted to exist. A declared anchor
 * that nothing ties to the value it describes is a comment in the payload: it would survive
 * the anchor changing underneath it, which is precisely the drift this closes.</p>
 */
class LineAnchorIsDeclaredTest {

    private static final ObjectMapper OM = new ObjectMapper();

    /**
     * A javadoc'd type, so the two anchors are FORCED apart. Without the comment they coincide
     * and every assertion below passes while measuring nothing.
     */
    private static final String TARGET = """
        package com.example;

        /**
         * A deliberately multi-line doc comment.
         *
         * <p>The declaration range starts HERE; the name token is five lines lower. A fixture
         * without this gap cannot tell the two anchors apart.</p>
         */
        public class AnchorTarget {
            int value() {
                return 1;
            }
        }
        """;

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;
    private Path targetFile;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        targetFile = service.getProjectRoot()
            .resolve("src/main/java/com/example/AnchorTarget.java");
        Files.writeString(targetFile, TARGET);
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();
    }

    @Test
    @DisplayName("mcp#20: each tool declares WHICH anchor its line is, and the label matches it")
    void bothToolsSayWhichAnchorTheyUsed() {
        // The two lines the fixture forces apart, computed from its own text so an edit to the
        // target above cannot silently make them coincide.
        int declarationLine = lineContaining("/**");
        int nameLine = lineContaining("public class AnchorTarget");

        Map<String, Object> search = dataOf(new SearchSymbolsTool(() -> service)
            .execute(OM.createObjectNode().put("query", "AnchorTarget").put("kind", "Class")));

        ObjectNode docArgs = OM.createObjectNode();
        docArgs.put("kind", "document_symbols");
        docArgs.put("filePath", targetFile.toString());
        Map<String, Object> document = dataOf(new InspectTool(() -> service).execute(docArgs));

        assertAll(
            // PRECONDITION: the fixture really does separate them. If the javadoc were gone
            // the two anchors would agree and this test would prove nothing at all.
            () -> assertNotEquals(declarationLine, nameLine,
                "the fixture must place the doc comment above the declaration"),

            () -> assertEquals("name", search.get("lineAnchor"),
                "search_symbols reports the name token: " + search.get("lineAnchor")),
            () -> assertEquals("declaration", document.get("lineAnchor"),
                "document_symbols reports the declaration range: " + document.get("lineAnchor")),

            // THE LABEL IS TIED TO THE VALUE. Asserting only that a label exists would pass
            // with the two labels swapped, which is the drift this closes.
            () -> assertEquals(nameLine, lineOfSearchHit(search),
                "search_symbols' line must BE the name anchor it declares"),
            () -> assertEquals(declarationLine, lineOfTypeSymbol(document),
                "document_symbols' line must BE the declaration anchor it declares"));
    }

    /** Zero-based, which is what both tools report. */
    private int lineContaining(String needle) {
        try {
            List<String> lines = Files.readAllLines(targetFile);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains(needle)) return i;
            }
        } catch (Exception e) {
            throw new IllegalStateException("could not read " + targetFile, e);
        }
        throw new IllegalStateException("fixture no longer contains: " + needle);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dataOf(ToolResponse response) {
        assertTrue(response.isSuccess(), "got: " + response.getError());
        return (Map<String, Object>) response.getData();
    }

    @SuppressWarnings("unchecked")
    private static int lineOfSearchHit(Map<String, Object> data) {
        List<Map<String, Object>> results = (List<Map<String, Object>>) data.get("results");
        return results.stream()
            .filter(r -> "AnchorTarget".equals(r.get("name")))
            .map(r -> ((Number) r.get("line")).intValue())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("AnchorTarget absent from: " + results));
    }

    @SuppressWarnings("unchecked")
    private static int lineOfTypeSymbol(Map<String, Object> data) {
        List<Map<String, Object>> symbols = (List<Map<String, Object>>) data.get("symbols");
        return symbols.stream()
            .filter(s -> "AnchorTarget".equals(s.get("name")))
            .map(s -> ((Number) s.get("line")).intValue())
            .findFirst()
            .orElseThrow(() -> new IllegalStateException("AnchorTarget absent from: " + symbols));
    }
}
