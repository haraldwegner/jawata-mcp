package org.jawata.mcp.refactoring;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Sprint 18 — one step of a multi-step refactoring plan: a primitive tool call the
 * orchestration loop (Stage 5) performs, gates (parity + purity), and rolls back on
 * failure. {@code tool} is a front-door tool name (e.g. {@code extract},
 * {@code refactor_to_pattern}); {@code args} is the exact JSON to invoke it with.
 * {@code rollbackTo} is the step index to unwind to on failure ({@code -1} = the
 * pre-plan state — the loop's atomic rollback reverts every applied step).
 */
public record PlanStep(int index, String tool, JsonNode args, String expectedStateAfter, int rollbackTo) {}
