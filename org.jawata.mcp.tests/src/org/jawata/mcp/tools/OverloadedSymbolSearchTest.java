package org.jawata.mcp.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.eclipse.jdt.core.IJavaElement;
import org.jawata.core.JdtServiceImpl;
import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.fqn.FqnResolver;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#46 — <b>a bare {@code Type#member} names EVERY overload, and searching one of them is
 * not an answer about the member.</b>
 *
 * <p>Measured on this product's own source: {@code find_references} on
 * {@code FindDuplicateCodeTool#collectPool} answered {@code totalReferences: 0} while a
 * caller of the four-argument overload existed. The resolver returned the first overload —
 * the three-argument one, which genuinely has no callers — and its correct zero was
 * published as the member's answer.</p>
 *
 * <p><b>A bare zero is indistinguishable from a real absence</b>, which is the defect class
 * this product fights first: an agent deciding whether a member is safe to delete acts on
 * it, and the schema had promised this form covers "any overload".</p>
 *
 * <p>THE FIXTURE IS WRITTEN INTO AN ISOLATED COPY, not into the shared sample project.
 * `simple-maven` is shared and monotonically growing, and this sprint has recorded it
 * moving a counted population four times — a findings page, a clone-group page, a naming
 * census and a search ranking. `HelloWorld` has no overloaded method at all (the existing
 * resolver test's comment saying it does is false about its own fixture), so this needs
 * new input; taking a copy is how it gets one without moving anybody's count.</p>
 */
class OverloadedSymbolSearchTest {

    private static final ObjectMapper OM = new ObjectMapper();

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private JdtServiceImpl service;

    /** Two overloads of one name; ONLY the two-argument one is called. */
    private static final String TARGET = """
        package com.example;

        public class Overloaded {
            /** No caller anywhere — the overload the old resolver picked. */
            public static int widen(String only) {
                return only.length();
            }

            /** Called from OverloadCaller below. */
            public static int widen(String first, int second) {
                return first.length() + second;
            }
        }
        """;

    private static final String CALLER = """
        package com.example;

        public class OverloadCaller {
            public int callIt() {
                return Overloaded.widen("two", 2);
            }
        }
        """;

    @BeforeEach
    void setUp() throws Exception {
        service = helper.loadProjectCopy("simple-maven");
        Path pkg = service.getProjectRoot().resolve("src/main/java/com/example");
        Files.createDirectories(pkg);
        Files.writeString(pkg.resolve("Overloaded.java"), TARGET);
        Files.writeString(pkg.resolve("OverloadCaller.java"), CALLER);
        // The production reconcile, which is how the product itself notices a file written
        // outside it — rather than a test-only refresh that would prove less.
        new org.jawata.core.workspace.StrictDiskSync(() -> service).syncBeforeCall();
    }

    @Test
    @DisplayName("mcp#46: the bare member form resolves EVERY overload, in declaration order")
    void theBareFormNamesEveryOverload() {
        List<IJavaElement> all =
            FqnResolver.resolveAllWorkspace("com.example.Overloaded#widen", service);

        assertEquals(2, all.size(), "both overloads are named by the bare form: " + all);
        // AND the single-answer resolver still returns the first, unchanged — every one of
        // its twenty-odd callers must see exactly what it saw before.
        assertEquals(all.get(0),
            FqnResolver.resolveWorkspace("com.example.Overloaded#widen", service).orElseThrow(),
            "resolve() is the first element of resolveAll(), not a second implementation");
    }

    @Test
    @DisplayName("mcp#46: find_references on the bare form finds the caller of the OTHER overload")
    void findReferencesUnionsTheOverloads() {
        FindReferencesTool tool = new FindReferencesTool(() -> service);
        ObjectNode args = OM.createObjectNode().put("symbol", "com.example.Overloaded#widen");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), "got: " + response.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();

        assertAll(
            // THE DEFECT: this was 0, because the search ran over the uncalled overload.
            () -> assertEquals(1, ((Number) data.get("totalReferences")).intValue(),
                "the caller of the two-argument overload must be found: " + data),
            // THE DENOMINATOR, so the number can be trusted — rule 1 of the degradation
            // stamp: a count states what was examined to produce it.
            () -> assertEquals(2, ((Number) data.get("overloadsSearched")).intValue(),
                "and the answer says how many overloads it covered: " + data));
    }

    @Test
    @DisplayName("THE CONTROL — a member with ONE overload reports no denominator, and is unchanged")
    void anUnambiguousMemberIsUntouched() {
        // Without this, a fix that always reported `overloadsSearched` would pass above
        // while adding a noise field to every ordinary answer — and a fix that unioned
        // nothing would be indistinguishable from one that unioned correctly on a
        // single-overload member.
        FindReferencesTool tool = new FindReferencesTool(() -> service);
        ObjectNode args = OM.createObjectNode()
            .put("symbol", "com.example.HelloWorld#getGreeting");

        ToolResponse response = tool.execute(args);

        assertTrue(response.isSuccess(), "got: " + response.getError());
        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) response.getData();
        assertFalse(data.containsKey("overloadsSearched"),
            "one overload needs no denominator — it would be noise on every ordinary "
                + "answer: " + data);
    }
}
