package org.jawata.mcp.java;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;

import org.jawata.mcp.fixtures.TestProjectHelper;
import org.jawata.resident.workspace.JdtServiceImpl;
import org.jawata.spi.lang.SymbolAnchors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;

/**
 * 28k E9: Java's {@link SymbolAnchors} asked directly, not through the knowledge store. The knowledge suites pin what the store
 * does with the three answers; this pins the port itself — the pointer map (resolved / stale / neither), the two-valued
 * {@code locate} the interface derives from it, and the null-safe supplier the knowledge classes hold.
 */
class JavaSymbolAnchorsTest {

    @RegisterExtension
    TestProjectHelper helper = new TestProjectHelper();

    private static final String PRESENT = "com.example.HelloWorld#printGreeting";
    private static final String MEMBER_GONE = "com.example.HelloWorld#noSuchMember";

    @Test
    @DisplayName("a live member's pointer is resolved with its file and line")
    void thePointerAnswersTheFileAndLineOfALiveSymbol() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        SymbolAnchors anchors = new JavaSymbolAnchors(service);

        Map<String, Object> pointer = anchors.pointer(PRESENT);

        assertEquals(Boolean.TRUE, pointer.get("resolved"), pointer.toString());
        assertTrue(String.valueOf(pointer.get("file")).endsWith("HelloWorld.java"), pointer.toString());
        assertTrue(pointer.get("line") instanceof Integer line && line > 0, "a live member has a line: " + pointer);
    }

    @Test
    @DisplayName("a member that is gone is stale and has no location; a closed workspace is neither, and has none either")
    void goneAndCouldNotCheckAreTwoAnswersWithOneEmptyLocation() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");
        SymbolAnchors anchors = new JavaSymbolAnchors(service);
        SymbolAnchors noModel = new JavaSymbolAnchors(null);

        Map<String, Object> gone = anchors.pointer(MEMBER_GONE);
        Map<String, Object> unknown = noModel.pointer(PRESENT);

        assertEquals(Boolean.TRUE, gone.get("stale"), "positively found gone: " + gone);
        assertEquals(Boolean.FALSE, gone.get("resolved"), "a gone member is not resolved: " + gone);
        assertNull(unknown.get("stale"), "could not check is not an absence: " + unknown);
        assertEquals(Boolean.FALSE, unknown.get("resolved"), unknown.toString());
        assertNull(unknown.get("file"), "nothing to ask means no file: " + unknown);
    }

    @Test
    @DisplayName("the supplier holds anchors only while a model is loaded")
    void theSupplierAnswersNullWhileNoModelIsLoaded() throws Exception {
        JdtServiceImpl service = helper.loadProject("simple-maven");

        assertNull(JavaSymbolAnchors.over(() -> null).get(), "no model loaded");
        assertNull(JavaSymbolAnchors.over(null).get(), "no supplier at all");
        assertNotNull(JavaSymbolAnchors.over(() -> service).get(), "a loaded model gets its anchors");
    }

}
