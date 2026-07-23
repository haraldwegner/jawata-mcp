package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * jawata-mcp#9 (Sprint 27a Stage 8) — the main-vs-test classification matrix,
 * BOTH conventions. The Maven path rule alone left every PDE test fragment
 * ("neither" segment) cross-cutting, so scope=main and scope=test returned
 * the same set on jawata's own repository.
 */
class ScopeClassificationTest {

    private static final String MAVEN_MAIN = "/repo/app/src/main/java/com/x/A.java";
    private static final String MAVEN_TEST = "/repo/app/src/test/java/com/x/ATest.java";
    private static final String PDE_MAIN = "/repo/org.jawata.mcp/src/org/jawata/mcp/T.java";
    private static final String PDE_TEST_FRAGMENT =
        "/repo/org.jawata.mcp.tests/src/org/jawata/mcp/TTest.java";
    private static final String PROJECT_LEVEL = "/repo/org.jawata.mcp/META-INF/MANIFEST.MF";

    @Test
    @DisplayName("Maven conventions classify as before")
    void mavenConventionsHold() {
        assertTrue(CompileWorkspaceTool.matchesScope(MAVEN_MAIN, "app", "main"));
        assertFalse(CompileWorkspaceTool.matchesScope(MAVEN_MAIN, "app", "test"));
        assertTrue(CompileWorkspaceTool.matchesScope(MAVEN_TEST, "app", "test"));
        assertFalse(CompileWorkspaceTool.matchesScope(MAVEN_TEST, "app", "main"));
    }

    @Test
    @DisplayName("a *.tests bundle IS the test half — its plain src/ sources are test scope")
    void pdeTestFragmentIsTestScope() {
        assertTrue(CompileWorkspaceTool.matchesScope(
            PDE_TEST_FRAGMENT, "org.jawata.mcp.tests", "test"));
        assertFalse(CompileWorkspaceTool.matchesScope(
            PDE_TEST_FRAGMENT, "org.jawata.mcp.tests", "main"),
            "the defect: this returned TRUE, making both scopes identical");
    }

    @Test
    @DisplayName("a main bundle's plain src/ sources are main scope")
    void pdeMainBundleIsMainScope() {
        assertTrue(CompileWorkspaceTool.matchesScope(PDE_MAIN, "org.jawata.mcp", "main"));
        assertFalse(CompileWorkspaceTool.matchesScope(PDE_MAIN, "org.jawata.mcp", "test"));
    }

    @Test
    @DisplayName("project-level markers stay cross-cutting, visible in both scopes")
    void projectLevelMarkersCrossCut() {
        assertTrue(CompileWorkspaceTool.matchesScope(PROJECT_LEVEL, "org.jawata.mcp", "main"));
        assertTrue(CompileWorkspaceTool.matchesScope(PROJECT_LEVEL, "org.jawata.mcp", "test"));
    }

    /** The issue's own acceptance line: the two scopes DIFFER on jawata's layout. */
    @Test
    @DisplayName("scope=main and scope=test classify jawata's own layout differently")
    void theTwoScopesDifferOnJawatasOwnLayout() {
        boolean mainSeesFragment = CompileWorkspaceTool.matchesScope(
            PDE_TEST_FRAGMENT, "org.jawata.mcp.tests", "main");
        boolean testSeesFragment = CompileWorkspaceTool.matchesScope(
            PDE_TEST_FRAGMENT, "org.jawata.mcp.tests", "test");
        boolean mainSeesBundle = CompileWorkspaceTool.matchesScope(
            PDE_MAIN, "org.jawata.mcp", "main");
        boolean testSeesBundle = CompileWorkspaceTool.matchesScope(
            PDE_MAIN, "org.jawata.mcp", "test");
        assertTrue(mainSeesBundle && !mainSeesFragment && testSeesFragment && !testSeesBundle,
            "main sees the bundle only, test sees the fragment only");
    }
}
