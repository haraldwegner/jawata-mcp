package org.jawata.mcp.execution;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S13 (the audit of the seat allowlist, point 1): the permission path accepts a brain's own name for a tool or its bare name, and
 * the engine's choke sees only the bare name the registry knows. A seat that wrote a prefixed name must therefore be read by its bare name too,
 * or the choke would refuse a tool the permission path allowed.
 */
class HiredRunToolListTest {

    @Test
    @DisplayName("a seat that wrote a tool in its prefixed form is also read by the bare name the choke sees, and a bare list is left as it was")
    void aPrefixedNameIsAlsoReadByItsBareName() {
        Set<String> widened = Set.copyOf(HiredRun.listForTheChoke(List.of("mcp__jawata__inspect", "experience")));

        assertAll(
            () -> assertTrue(widened.contains("inspect"), "the bare form of a prefixed name was not added: " + widened),
            () -> assertTrue(widened.contains("mcp__jawata__inspect"), "the form the seat wrote was dropped: " + widened),
            () -> assertEquals(Set.of("experience"), Set.copyOf(HiredRun.listForTheChoke(List.of("experience"))),
                "control: a bare name gained something it did not have"),
            () -> assertTrue(HiredRun.listForTheChoke(List.of()).isEmpty(), "control: an empty list gained a name"));
    }
}
