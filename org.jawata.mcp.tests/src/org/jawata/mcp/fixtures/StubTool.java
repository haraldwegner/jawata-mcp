package org.jawata.mcp.fixtures;

import java.util.Map;

import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.tools.Tool;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * A tool that does nothing, under a name the test chooses.
 *
 * <p>Registry tests ask how tools are published (their order, their read-only hint, the flag that says whether a call
 * changes code), and for that a tool only needs a name. The registry decides some of those answers from the NAME alone, so a
 * stub called {@code load_project} is classified as the real one is. It says nothing about which tools the product registers;
 * that is asserted elsewhere.</p>
 *
 * <p>Sprint 28k, ledger 28k-C4-9: four test classes each held a private copy of this; they share this one.</p>
 */
public final class StubTool implements Tool {

    private final String name;
    private final Boolean changesCode;

    private StubTool(String name, Boolean changesCode) {
        this.name = name;
        this.changesCode = changesCode;
    }

    /** A stub named {@code name} that answers {@link Tool#changesCode()} as the interface's own default does. */
    public static Tool named(String name) {
        return new StubTool(name, null);
    }

    /** A stub named {@code name} that answers {@link Tool#changesCode()} with {@code changesCode}. */
    public static Tool named(String name, boolean changesCode) {
        return new StubTool(name, changesCode);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public String getDescription() {
        return "stub";
    }

    @Override
    public Map<String, Object> getInputSchema() {
        return Map.of("type", "object", "properties", Map.of());
    }

    @Override
    public ToolResponse execute(JsonNode arguments) {
        return ToolResponse.success(Map.of());
    }

    @Override
    public boolean changesCode() {
        return changesCode != null ? changesCode : Tool.super.changesCode();
    }
}
