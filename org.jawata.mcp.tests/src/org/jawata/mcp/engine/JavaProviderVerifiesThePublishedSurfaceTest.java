package org.jawata.mcp.engine;

import static org.junit.jupiter.api.Assertions.assertThrows;

import org.jawata.mcp.change.OperationRegistry;
import org.jawata.mcp.java.JavaProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28k, Stage 9 (C9 re-audit, finding 1): the engine no longer checks Java's cure table and engine coverage itself, it asks the
 * Java provider to. The engine's own test uses a stand-in that throws, which proves the engine asks and says nothing about what the Java
 * provider does when asked, so a provider whose check had become empty would pass every other test. This one starts the real provider
 * against an engine-wide registry that backs none of the cures it declares and requires the check to refuse.
 */
class JavaProviderVerifiesThePublishedSurfaceTest {

    @Test
    @DisplayName("the Java provider refuses a published surface that backs none of the cures and refactorings it declares")
    void theJavaProviderRefusesAnEmptySurface() {
        OperationRegistry registry = OperationRegistry.theRegistry();
        OperationRegistry.Snapshot saved = registry.snapshot();
        try {
            registry.clear();

            assertThrows(RuntimeException.class, () -> new JavaProvider(() -> null).verifyPublishedSurface(),
                "an empty registry backs no cure step, so a provider that really checks cannot accept it");
        } finally {
            registry.restore(saved);
        }
    }
}
