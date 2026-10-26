package org.jawata.mcp.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.jawata.spi.host.HostCapabilities;
import org.jawata.spi.host.HostWorkspace;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * What every host promises when it says what it is (Sprint 28k, Stage 9; contract §4.2 and §8): a kind a person can read, an answer about
 * owning its workspace that agrees with the workspace's own, and the languages it serves as a list nobody can change under the engine,
 * which waits for exactly those.
 */
public abstract class HostCapabilitiesContract {

    /**
     * @return the capabilities under test
     * @throws Exception when the implementation cannot be built
     */
    protected abstract HostCapabilities capabilities() throws Exception;

    /**
     * @return the workspace of the same host, whose answer about ownership the capabilities repeat
     * @throws Exception when the implementation cannot be built
     */
    protected abstract HostWorkspace workspaceOfTheSameHost() throws Exception;

    @Test
    @DisplayName("the host says what kind it is")
    void theHostSaysWhatKindItIs() throws Exception {
        assertFalse(capabilities().hostKind().isBlank(), "a host names its kind");
    }

    @Test
    @DisplayName("what the host says about owning its workspace is what its workspace says")
    void ownershipAgreesWithTheWorkspace() throws Exception {
        assertEquals(workspaceOfTheSameHost().ownsWorkspace(), capabilities().ownsWorkspace());
    }

    @Test
    @DisplayName("the languages it serves are names, each once, in a list that cannot be changed")
    void theLanguagesAreFixedNames() throws Exception {
        java.util.List<String> languages = capabilities().languages();

        assertTrue(languages.stream().noneMatch(String::isBlank), "a language has a name: " + languages);
        assertEquals(languages.size(), java.util.Set.copyOf(languages).size(), "each language once: " + languages);
        assertThrows(UnsupportedOperationException.class, () -> languages.add("cpp"));
    }
}
