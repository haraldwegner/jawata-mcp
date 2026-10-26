package org.jawata.mcp.engine;

import java.util.function.Function;
import java.util.function.Supplier;

import org.jawata.mcp.change.RefactoringChangeCache;
import org.jawata.mcp.knowledge.ExperienceStore;
import org.jawata.mcp.models.ToolResponse;
import org.jawata.mcp.runtime.RuntimeSessionRegistry;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What the engine hands every {@link ToolSource} beyond the language's own model (Sprint 28k, E19): the engine-wide objects a
 * language's tools share with the tools that name no language.
 *
 * @param changeCache     the staged refactoring changes and their undo handles
 * @param storeSupplier   the knowledge store, read at call time
 * @param experienceStore the knowledge store as it is now (the debugger recalls from it)
 * @param runtimeSessions the live debug and profile sessions
 * @param impactedTests   how {@code inspect} asks {@code run_tests} for the tests a change reaches
 * @param runtimeArtifacts where the debug and profile sessions keep what they record
 * @param debugTurn       whether the person is debugging, which the runtime tools respect
 * @param plans           where a language offers the plan actions of the refactoring front door
 * @param advisor         what the plan lifecycle consults and records against the knowledge store
 */
public record ToolNeeds(
        RefactoringChangeCache changeCache,
        Supplier<ExperienceStore> storeSupplier,
        ExperienceStore experienceStore,
        RuntimeSessionRegistry runtimeSessions,
        Function<JsonNode, ToolResponse> impactedTests, org.jawata.mcp.runtime.RuntimeArtifactStore runtimeArtifacts, org.jawata.spi.host.DebugTurn debugTurn,
        org.jawata.mcp.change.PlanSlot plans, org.jawata.mcp.domain.Advisor advisor) {
}
