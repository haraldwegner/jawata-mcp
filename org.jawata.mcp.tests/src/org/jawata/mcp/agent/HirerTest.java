package org.jawata.mcp.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.jawata.mcp.agent.RoleFigures.HireFacts;
import org.jawata.mcp.execution.Hirer;
import org.jawata.mcp.execution.SeatTree;
import org.jawata.mcp.execution.Hirer.Brain;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sprint 28h, S7 (D8 to D11, D48): the hierarchy, the brain per seat, the consultant rule, the allowlists and the per-role figures. Each
 * rule is shown beside the case that must NOT trigger it, so a rule that refuses everything or allows everything fails here.
 */
class HirerTest {

    private static final Brain CLAUDE = new Brain("anthropic", "strong");
    private static final Brain CHEAP = new Brain("anthropic", "small");
    private static final Brain OTHER_VENDOR = new Brain("openai", "strong");

    private static final List<String> ENGINE_TOOLS = List.of("inspect", "find_references", "rename_symbol", "extract");
    private static final Set<String> CODE_CHANGING = Set.of("rename_symbol", "extract");

    private static Hirer hirer(Map<String, Brain> brains) {
        return new Hirer(SeatTree.standard(), brains, CODE_CHANGING);
    }

    @Test
    @DisplayName("the hierarchy as signed: a seat is hired by its own parent and by nobody else")
    void aSeatIsHiredByItsParentOnly() {
        Hirer hirer = hirer(Map.of());

        assertEquals(Optional.empty(), hirer.refuse("main", "pm"));
        assertEquals(Optional.empty(), hirer.refuse("pm", "chief-engineer"));
        assertEquals(Optional.empty(), hirer.refuse("chief-engineer", "developer"));
        assertEquals(Optional.empty(), hirer.refuse("test-manager", "test-writer"));
        assertTrue(hirer.refuse("main", "developer").orElse("").contains("its hirer is chief-engineer"), "main hired a developer over the chief engineer");
        assertTrue(hirer.refuse("developer", "pm").isPresent(), "a developer hired a pm");
        assertTrue(hirer.refuse("main", "nobody").orElse("").contains("no seat"), "an unknown seat was hired");
    }

    @Test
    @DisplayName("D48: a consultant on main's own vendor is refused, and one on another vendor is not")
    void aSameVendorConsultantIsRefused() {
        Hirer same = hirer(Map.of("main", CLAUDE, "consultant", CHEAP));
        Hirer other = hirer(Map.of("main", CLAUDE, "consultant", OTHER_VENDOR));
        Hirer unknown = hirer(Map.of("main", CLAUDE));

        assertTrue(same.refuse("main", "consultant").orElse("").contains("same vendor"), "a same-vendor consultant was hired");
        assertEquals(Optional.empty(), other.refuse("main", "consultant"));
        assertTrue(unknown.refuse("main", "consultant").isPresent(), "a consultant with no brain of its own was hired");
        assertTrue(other.refuse("pm", "consultant").isPresent(), "a working seat hired the consultant");
    }

    @Test
    @DisplayName("D11: choosing another brain for one seat changes that seat's next hire and no other seat's")
    void anotherBrainForOneSeatChangesItsNextHire() {
        Hirer before = hirer(Map.of("developer", CHEAP, "pm", CLAUDE));
        Hirer after = before.withBrain("developer", OTHER_VENDOR);

        assertEquals(Optional.of(CHEAP), before.brainFor("developer"));
        assertEquals(Optional.of(OTHER_VENDOR), after.brainFor("developer"));
        assertEquals(Optional.of(CLAUDE), after.brainFor("pm"), "another seat's brain changed with it");
        assertEquals(Optional.empty(), after.brainFor("architect"));
    }

    @Test
    @DisplayName("D11: the registry gives each brain its vendor and model, an unnamed field reads as blank, and a text that is not a registry gives none")
    void theRegistryIsReadPerBrain() {
        String registry = "{\"claude-code\":{\"command\":[\"x\"],\"vendor\":\"anthropic\",\"model\":\"opus\"},\"codex\":{\"command\":[\"y\"]}}";

        assertEquals(Optional.of(new Brain("anthropic", "opus")), Hirer.brainIn(registry, "claude-code"));
        assertEquals(Optional.of(new Brain("", "")), Hirer.brainIn(registry, "codex"));
        assertEquals(Optional.empty(), Hirer.brainIn(registry, "cursor"));
        assertEquals(Optional.empty(), Hirer.brainIn("not json", "claude-code"));
        assertEquals(Optional.of(new Brain("anthropic", "")), Hirer.brainOf("{\"command\":[\"x\"],\"vendor\":\"anthropic\"}"));
        assertEquals(Optional.empty(), Hirer.brainOf("[1]"));
    }

    @Test
    @DisplayName("D48: a vendor that is not named is not a vendor that differs, so the consultant is refused on a blank vendor")
    void aBlankVendorIsNotADifferentVendor() {
        Hirer unnamed = hirer(Map.of("main", CLAUDE, "consultant", new Brain("", "m")));
        Hirer unnamedMain = hirer(Map.of("main", new Brain("", ""), "consultant", OTHER_VENDOR));

        assertTrue(unnamed.refuse("main", "consultant").isPresent(), "a consultant with no named vendor was hired");
        assertTrue(unnamedMain.refuse("main", "consultant").isPresent(), "a consultant was hired against a main with no named vendor");
    }

    @Test
    @DisplayName("D11: only a developer escalates, and to the senior developer")
    void aDeveloperEscalatesToTheSeniorDeveloper() {
        Hirer hirer = hirer(Map.of());

        assertEquals(Optional.of("senior-developer"), hirer.escalateTo("developer"));
        assertEquals(Optional.empty(), hirer.escalateTo("senior-developer"));
        assertEquals(Optional.empty(), hirer.escalateTo("pm"));
    }

    @Test
    @DisplayName("D9: a lead's allowlist holds no code-changing tool and no editor; a developer's holds every tool and the brain's editors")
    void leadsCarryNoCodeChangingTool() {
        Hirer hirer = hirer(Map.of());

        List<String> chief = hirer.allowlist("chief-engineer", ENGINE_TOOLS);
        List<String> developer = hirer.allowlist("developer", ENGINE_TOOLS);

        assertEquals(List.of("inspect", "find_references"), chief);
        assertTrue(developer.containsAll(ENGINE_TOOLS), developer.toString());
        assertTrue(developer.contains("Edit") && developer.contains("Write"), "a developer has no editor of its own");
        assertFalse(chief.contains("Edit"), "a lead was given an editor");
        assertEquals(List.of("inspect", "find_references"), hirer.allowlist("architect", ENGINE_TOOLS), "a reviewer was given a code-changing tool");
    }

    @Test
    @DisplayName("D10: per-role figures are totals over the hire records, and only the two edit measures count anything against a role")
    void figuresAreComputedPerRoleFromTheRecords() {
        List<RoleFigures> figures = RoleFigures.of(List.of(
            new HireFacts("developer", 1000, 4, 2, 0),
            new HireFacts("developer", 500, 3, 1, 0),
            new HireFacts("chief-engineer", 2000, 6, 0, 3),
            new HireFacts("pm", 300, 1, 5, 5)));

        assertEquals(new RoleFigures("developer", 2, 1500, 7, 3), figures.get(0));
        assertEquals(new RoleFigures("chief-engineer", 1, 2000, 6, 3), figures.get(1));
        assertEquals(new RoleFigures("pm", 1, 300, 1, 0), figures.get(2), "an edit count was held against a role whose measure has none");
    }
}
