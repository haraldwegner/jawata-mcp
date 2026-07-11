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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sprint 14 Phase B.3 — {@code find_duplicate_code} clone-detection
 * contract tests. Drives against the simple-maven fixture's Animal.java
 * which has three structurally-identical method bodies (Animal.speak,
 * Animal.move, Dog.speak — all of shape
 * {@code void NAME() { System.out.println("…"); }}).
 */
class FindDuplicateCodeToolTest {

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
    @DisplayName("detects obvious clones within project (Animal.speak / Animal.move / Dog.speak share normalized shape)")
    void detects_obvious_clone_within_project() {
        ObjectNode args = objectMapper.createObjectNode();
        // Use low minTokens so the short Animal.java methods qualify.
        args.put("minTokens", 5);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "find_duplicate_code must succeed; got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("find_duplicate_code", data.get("operation"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        assertNotNull(groups, "groups list must be present");

        // At least one group must contain BOTH Animal.speak and Animal.move
        // (their bodies are byte-identical except for the string literal,
        // which the normalizer collapses to STR). Other structurally-
        // identical methods may join the group from the wider fixture; we
        // only assert the speak+move pair lands together.
        boolean foundSpeakAndMoveTogether = groups.stream().anyMatch(g -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> instances = (List<Map<String, Object>>) g.get("instances");
            boolean hasSpeak = instances.stream().anyMatch(i -> "speak".equals(i.get("methodName")));
            boolean hasMove = instances.stream().anyMatch(i -> "move".equals(i.get("methodName")));
            return hasSpeak && hasMove;
        });
        assertTrue(foundSpeakAndMoveTogether,
            "expected Animal.speak and Animal.move to land in the same clone group; got groups=" + groups);
    }

    @Test
    @DisplayName("respects minTokens floor — short clones below the threshold are NOT grouped")
    void respects_minTokens_floor() {
        ObjectNode args = objectMapper.createObjectNode();
        // Set minTokens absurdly high so short Animal.java methods are excluded.
        args.put("minTokens", 1000);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        assertTrue(groups.isEmpty(),
            "no method in simple-maven has 1000+ tokens; groups must be empty; got: " + groups);
    }

    @Test
    @DisplayName("dissimilar methods are not grouped (HelloWorld constructors vs setGreeting)")
    void dissimilar_methods_not_grouped() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 5);
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");

        // No group should contain a method named "HelloWorld" (the
        // constructor) paired with one named "setGreeting" — they're
        // structurally distinct.
        boolean falseClone = groups.stream().anyMatch(g -> {
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> instances = (List<Map<String, Object>>) g.get("instances");
            boolean hasCtor = instances.stream().anyMatch(i -> "HelloWorld".equals(i.get("methodName")));
            boolean hasSetter = instances.stream().anyMatch(i -> "setGreeting".equals(i.get("methodName")));
            return hasCtor && hasSetter;
        });
        assertTrue(!falseClone,
            "must not group dissimilar methods (HelloWorld ctor vs setGreeting); got groups=" + groups);
    }

    @Test
    @DisplayName("each clone-group instance carries the documented contract fields")
    void instanceShape_carriesContractFields() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 5);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        assertTrue(!groups.isEmpty(), "must have at least one clone group at low minTokens");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> instances =
            (List<Map<String, Object>>) groups.get(0).get("instances");
        for (Map<String, Object> inst : instances) {
            assertNotNull(inst.get("filePath"), "instance must carry filePath");
            assertNotNull(inst.get("line"), "instance must carry line");
            assertNotNull(inst.get("methodName"), "instance must carry methodName");
            assertNotNull(inst.get("tokenCount"), "instance must carry tokenCount");
            assertNotNull(inst.get("similarity"), "instance must carry similarity");
            assertNotNull(inst.get("sourceProject"), "instance must carry sourceProject");
            assertEquals(1.0, ((Number) inst.get("similarity")).doubleValue(),
                "v1.8.0 MVP exact-match always yields similarity 1.0");
        }
    }

    @Test
    @DisplayName("v2.7.1 SOE regression: giant string literals + text blocks must scan, not StackOverflow")
    void giantStringLiteral_doesNotBlowTheStack() throws Exception {
        // Dogfood find 2026-07-10: the regex tokenizer's quantified-alternation
        // literal branches overflow the stack at ~2k chars of string literal
        // (Java regex backtracking frames). jawata-mcp's own tool descriptions
        // are text blocks far past that — the self-scan killed the transport.
        // A file carrying BOTH shapes must scan cleanly.
        // 60k: the SOE threshold scales with thread stack size (the surefire
        // main thread survives 4k where the server's worker thread died at
        // ~2k) — size the literal so the OLD tokenizer overflows on ANY stack.
        Path copy = helper.copyFixtureAs("simple-maven", "soe-fixture");
        String giant = "x".repeat(60_000);
        String source = "package com.example;\n"
            + "public class GiantLiteralHolder {\n"
            + "    static final String GIANT = \"" + giant + "\";\n"
            + "    static final String BLOCK = \"\"\"\n"
            + "        " + giant + "\n"
            + "        \"\"\";\n"
            + "    void a() { System.out.println(GIANT); int i = 1 + 2 + 3; }\n"
            + "}\n";
        Files.writeString(
            copy.resolve("src/main/java/com/example/GiantLiteralHolder.java"), source);

        JdtServiceImpl svc = new JdtServiceImpl();
        svc.loadProject(copy);
        FindDuplicateCodeTool giantTool = new FindDuplicateCodeTool(() -> svc);

        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 5);
        ToolResponse r = giantTool.execute(args);

        assertTrue(r.isSuccess(),
            "scan over giant literals must succeed, not StackOverflow; got: " + r.getError());
    }

    @Test
    @DisplayName("v2.8.1 paging: limit/offset page the groups; groupCount always reflects the full set")
    void paging_limitsAndOffsetsGroups() {
        ObjectNode full = objectMapper.createObjectNode();
        full.put("minTokens", 5);
        ToolResponse rFull = tool.execute(full);
        assertTrue(rFull.isSuccess(), "got: " + rFull.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> dFull = (Map<String, Object>) rFull.getData();
        int totalGroups = ((Number) dFull.get("groupCount")).intValue();
        assertTrue(totalGroups >= 1, "fixture must yield at least one clone group");

        // limit=1 → exactly one group returned, groupCount still the full total.
        ObjectNode paged = objectMapper.createObjectNode();
        paged.put("minTokens", 5);
        paged.put("limit", 1);
        ToolResponse rPaged = tool.execute(paged);
        assertTrue(rPaged.isSuccess(), "got: " + rPaged.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> dPaged = (Map<String, Object>) rPaged.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pagedGroups = (List<Map<String, Object>>) dPaged.get("groups");
        assertEquals(1, pagedGroups.size(), "limit=1 must return one group");
        assertEquals(totalGroups, ((Number) dPaged.get("groupCount")).intValue(),
            "groupCount must reflect the FULL set, not the page");

        // offset past the end → empty page, same full groupCount.
        ObjectNode past = objectMapper.createObjectNode();
        past.put("minTokens", 5);
        past.put("offset", totalGroups);
        ToolResponse rPast = tool.execute(past);
        assertTrue(rPast.isSuccess(), "got: " + rPast.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> dPast = (Map<String, Object>) rPast.getData();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> pastGroups = (List<Map<String, Object>>) dPast.get("groups");
        assertTrue(pastGroups.isEmpty(), "offset past the end must return an empty page");
        assertEquals(totalGroups, ((Number) dPast.get("groupCount")).intValue());
    }

    @Test
    @DisplayName("v2.8.1 summary: counts only, NO groups array")
    void summary_returnsCountsOnly() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 5);
        args.put("summary", true);
        ToolResponse r = tool.execute(args);
        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertTrue(((Number) data.get("groupCount")).intValue() >= 1);
        assertNotNull(data.get("instanceCount"), "summary must carry instanceCount");
        assertTrue(!data.containsKey("groups"), "summary must NOT carry the groups array");
    }

    @Test
    @DisplayName("clean-fixture invariant: even with no clones, the response shape is valid")
    void emptyResult_isStillValidShape() {
        ObjectNode args = objectMapper.createObjectNode();
        args.put("minTokens", 1000);  // ensures no clones found
        ToolResponse r = tool.execute(args);

        assertTrue(r.isSuccess(), "got: " + r.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) r.getData();
        assertEquals("find_duplicate_code", data.get("operation"));
        assertEquals(0, ((Number) data.get("groupCount")).intValue());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> groups = (List<Map<String, Object>>) data.get("groups");
        assertTrue(groups.isEmpty());
    }
}
