package org.jawata.mcp.tools;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.jawata.mcp.ShippedTools;
import org.jawata.mcp.engine.ProviderPorts;
import org.jawata.mcp.fixtures.InertShippedTools;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which tools ship can be ASKED (Sprint 28g C9a-1).
 *
 * <p>It could not before: the list lived inside a private method of the application, so every
 * statement about it was a hand-kept copy, and the copies drifted. {@link ShippedTools#build} is the
 * one producer, and these tests build the real set with inert wiring and ask it.</p>
 */
class ShippedToolsTest {

    @Test
    @DisplayName("the real set builds with inert wiring, and every tool has its own name")
    void theRealSetBuildsAndEveryNameIsDistinct() {
        List<String> all = new ArrayList<>();
        for (Tool tool : InertShippedTools.tools()) {
            all.add(tool.getName());
        }

        assertAll(
            () -> assertEquals(new HashSet<>(all).size(), all.size(),
                "two tools answer to one name: " + all),
            () -> assertTrue(all.containsAll(List.of("health_check", "experience", "hire",
                    "rename_symbol", "inspect", "refactoring", "debug", "profile", "run_tests")),
                "the anchors a real set must hold: " + all),
            () -> assertTrue(all.size() > 30, "a real set, not a stub: " + all.size()));
    }

    @Test
    @DisplayName("building the set registers nothing, so asking which tools ship does not publish them")
    void buildingTheSetRegistersNothing() {
        ToolRegistry registry = new ToolRegistry();

        ShippedTools.neutral(InertShippedTools.wiring(registry), new ProviderPorts(() -> null, () -> null, java.util.List::of));

        assertEquals(0, registry.getToolCount(),
            "the producer builds; only the application registers what it returns");
    }

    @Test
    @DisplayName("every name the registry's policy sets mention is a tool that ships")
    void theRegistrysNameKeyedPolicyNamesOnlyShippedTools() {
        Set<String> stale = new TreeSet<>(ToolRegistry.namesFixedByPolicy());
        stale.removeAll(InertShippedTools.names());

        assertEquals(Set.of(), stale,
            "READ_ONLY_NAMES and NO_STEER hold names of tools that no longer ship. A policy keyed"
                + " by name fails open when a tool is renamed, because no reference-updating"
                + " refactoring touches a string; this is the check that does");
    }
}
