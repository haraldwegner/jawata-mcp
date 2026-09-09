package org.jawata.core.workspace;

import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.core.runtime.Platform;
import org.eclipse.core.runtime.preferences.InstanceScope;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mcp#78's CLOSING PROOF — the settings this product depends on are STATED, not inherited.
 *
 * <p>The issue is explicit about what closing it means, because the usual proof is not
 * available: "the population is settings, not call sites, so the closing proof is an explicit
 * list of the settings the product depends on, each with the value it sets and the reason. A
 * setting on that list with no assignment is an open instance; a defect arriving later that
 * names a setting absent from the list means the enumeration was incomplete."</p>
 *
 * <p>So this class asserts the two halves that can be asserted: every entry is complete
 * enough to review, and every setting jawata OWNS actually reads back what it declared. What
 * it cannot assert is that the list is exhaustive — no query enumerates an unset setting,
 * which is the whole reason mcp#78 exists — and saying so here is more useful than a check
 * that would look like coverage.</p>
 */
class WorkspaceSettingsTest {

    @Test
    @DisplayName("every declared setting carries a value, a reason and who applies it")
    void everySettingIsReviewable() {
        List<WorkspaceSettings.Setting> declared = WorkspaceSettings.declared();
        assertFalse(declared.isEmpty(), "the register is the deliverable; an empty one is none");
        for (WorkspaceSettings.Setting s : declared) {
            assertNotNull(s.id());
            assertFalse(s.id().isBlank(), "a setting must say how it is spelled");
            assertFalse(s.value().isBlank(), "a setting must say what we state it to");
            assertFalse(s.appliedBy().isBlank(), "a setting must say WHO writes it, because"
                + " not all of them are written here");
            // THE REASON IS THE HALF THAT DECAYS, so it is the one with a length floor: an
            // entry nobody can justify is an entry nobody can review, and a list of
            // unreviewable entries closes nothing.
            assertTrue(s.reason().length() > 40,
                "a setting must say WHY the product depends on it: " + s.id() + " -> " + s.reason());
        }
    }

    @Test
    @DisplayName("the workspace charset is UTF-8, not whatever the host defaults to")
    void theCharsetIsStated() throws Exception {
        // WHY THIS IS THE FOURTH INSTANCE OF mcp#78's CLASS, found by its method rather than
        // by a defect: jawata reads and writes source as UTF-8 explicitly, while JDT decodes
        // compilation units through THIS setting. Unset, it is the platform charset — so the
        // two disagree about the same bytes on any host whose default is not UTF-8.
        // THE CONTROL IS THE WHOLE TEST, and its first version did not have one. This host's
        // platform default IS UTF-8, so asserting the charset alone passes whether or not
        // anything set it — the assertion would have been green with applyOwned() deleted.
        // Setting it to something else first is what makes the assertion measure the WRITE.
        ResourcesPlugin.getWorkspace().getRoot().setDefaultCharset("ISO-8859-1", null);
        assertEquals("ISO-8859-1", ResourcesPlugin.getWorkspace().getRoot().getDefaultCharset(),
            "PROOF OF LIFE: the charset must be settable, or the assertion below is about a"
                + " value nothing can change");

        WorkspaceSettings.applyOwned();

        assertEquals("UTF-8", ResourcesPlugin.getWorkspace().getRoot().getDefaultCharset(),
            "unset, JDT decodes source with the platform default and disagrees with every"
                + " read jawata does itself");
    }

    @Test
    @DisplayName("the line delimiter is stated rather than inherited from the platform")
    void theLineDelimiterIsStated() {
        // APPLIED HERE RATHER THAN ASSUMED. This test loads no project, so
        // WorkspaceManager.initialize() — the production wiring point — has not run in this
        // JVM, and the first version of this assertion read a preference nothing had written
        // and reported it as a product defect. The call is idempotent.
        WorkspaceSettings.applyOwned();
        assertEquals("\n",
            InstanceScope.INSTANCE.getNode(Platform.PI_RUNTIME)
                .get(Platform.PREF_LINE_SEPARATOR, null),
            "mcp#78: an unset delimiter is System.lineSeparator(), which differs by host —"
                + " which is what made mcp#75 present as a Windows-only defect");
    }

    @Test
    @DisplayName("a setting applied elsewhere still appears here, or the list is only what was convenient")
    void aSettingAppliedElsewhereIsStillDeclared() {
        // Compliance is applied by ProjectImporter, because its value depends on the
        // project's own declared level. If the register only listed what it writes itself,
        // it would be a list of what was easy rather than of what the product depends on —
        // and mcp#69, the defect that opened this class, would not be in it.
        assertTrue(WorkspaceSettings.declared().stream()
                .anyMatch(s -> s.id().contains("compiler.compliance")
                    && s.appliedBy().contains("ProjectImporter")),
            "the compliance setting must be declared here even though it is written"
                + " elsewhere: " + WorkspaceSettings.declared());
    }
}
