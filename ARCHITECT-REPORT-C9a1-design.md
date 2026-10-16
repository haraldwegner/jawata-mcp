# Architect report — ledger row C9a-1: "which tools ship" (design mode)

**Seat:** architect, design mode, run as a fresh-context subagent on 2026-10-16. **Degradation
declared:** the jawata engine was not usable by the seat; every finding is from Read/grep. Nothing
was built or run by the seat, so "stale" below means written-down numbers disagree with each other,
not a live check. The seat counted **7 number copies and about 8 name copies, not nine**, and could
not reproduce the ledger's "nine".

## 1. The copies

Numbers:
- N1 `build/c5-m15-published-surface.sh` `EXPECTED_TOOLS = 43` (script literal; stale, others say 44)
- N2 `build/end-to-end-test.sh` `EXPECTED_TOOLS=44` (script literal)
- N3 `README.md:29` "44 MCP tools"; N4 `README.md:336` "44 tools total" (prose counts)
- N5 `server.json:5` "63 semantic Java analysis tools" (manifest prose; stale)
- N6/N7 studio `src/assets/help.md:34` and `:120` "45 tools" (prose; stale)

Names:
- M1 `README.md:242-260` TOOLS:BEGIN/END, 44 names (the one committed list)
- M2/M3 `ToolRegistry.java:53-58` READ_ONLY_NAMES and `:377-379` NO_STEER (literal sets in main
  code; `list_projects`, `inspect_refactoring`, `suggest_imports` are absent from the README's 44)
- M4 `ToolRegistry.java:419` RENAMED_TOOLS (retired name to live door)
- M5 `learn/ToolHowTo.java:23` HOW_TO (map keyed by tool)
- M6 `McpProtocolHandler.java:188` SERVER_INSTRUCTIONS (prose names)
- M7 studio `help.md:122,123,131` (prose names; only `undo_refactoring` is pinned)
- M8 studio `seats/*.md`, `skills/sprint.md` (prose names, unexamined)
- M9 `PublishedToolOrderTest` stub list: a deliberate sample, not a copy.

Not copies: `HealthCheckToolTest` `() -> 56` (a stub echo), studio `manager_service.rs:12004` (a
recorded response fixture).

## 2. Disposition

- N2, N3, N4, M1: already reconciled against the live artifact (e2e set comparison,
  `build/readme-tool-count.sh`). Keep. N2 duplicates what M1's set comparison proves: delete the
  literal.
- N1: delete the literal; the door-kind counts in that script are a different fact and stay.
- N5, N6, N7: prose counts no code needs to generate. Remove the digit; a tested `\d+ tools` ban in
  help.md is cheaper than any generator.
- M2, M3, M5: need the real tool-name set in a unit test, and today none can be built.
- M4, M6: already checked against the retired set (`ServerInstructionsCatalogTest:80`); no change.
- M7, M8: prose.

**Can a real registry be built without an application? Yes, with one bounded move**, which the seat
reads differently from the ledger's "redesign of the application bootstrap". `registerTools`
(`JawataApplication.java:1119-1363`) mostly constructs tools from `() -> jdtService`. The state it
needs is small: suppliers already read at call time; `refactoringChangeCache`; `runtimeSessions`;
the `experienceStore` field; the setter at :1146; `this::fieldDir`, `this::selfEndpoint`,
`this::defaultMemoryRoots`. Three results are kept for later (`findQualityIssueTool`,
`experienceAdvisor`, `experienceTool`). Registry configuration (setDiskSync, setProjectsMutatedHook,
setExperienceAdvisor) is not "which tools ship" and stays. Unverified: whether the other ~20 tool
constructors tolerate null. Hazard: `ToolRegistry.register` publishes into the process-wide
OperationRegistry, so the answer must come from constructed, UNREGISTERED tools.

## 3. Target (D-ONE) and migration (D-TWO)

One producer, the pattern the code already used for the refactoring doors (`RefactoringDoors.all`):
`ShippedTools.build(ApplicationWiring)` returns the tools plus handles for the three kept results;
`ApplicationWiring` is a record of the suppliers and consumers above; `registerTools` shrinks to
loop-register plus the registry setters; `names(wiring)` is the askable answer. No test-only factory
in main code. Hollow-wiring: `ShippedTools` has a production caller in `registerTools`.

Ordered steps, each reversible, each one commit:
1. A producer for the stateless group, and a test that READ_ONLY_NAMES, NO_STEER and HOW_TO keys are
   all shipped names (red on the three stale names).
2. Widen to the wired tools via `ApplicationWiring`; red `every_tool_builds_with_inert_wiring`.
3. Remove the count literals N1 and N2 (red before: c5 fails today, 43 vs 44).
4. Drop the digits in `server.json` and `help.md` (studio test `help_states_no_tool_count`).

End-state tests (D-THREE): `ShippedToolsTest` (inert build, distinct names, literals-subset); the
end-to-end test unchanged (README both ways, published-not-just-built); `readme-tool-count.sh`; the
c5 door-kind counts; one studio test.

## 4. Refused (D-SIX)

Generating help.md / server.json / README lists; a cross-repo golden file (studio CI has no engine
checkout and a skip-if-absent test is a lie); migrating the ~20 tests that use `RefactoringDoors`;
moving the e2e/c5 checks in-process (they prove the jar publishes, which a unit test cannot).
REL44-18's "generate the tool lists" is declined in favour of removing the number and keeping the
pin test.

## 5. Consumers

No wire contract changes: `tools/list` and `health_check.toolCount` are identical, so the studio's
runtime manager is untouched. The new Java API's only consumer is `JawataApplication`. Changed text
is read by the MCP registry (`server.json`, re-publish needed) and `readme-tool-count.sh` (keep the
README counts). The other side must change only for step 4 (studio help.md).

## Status (main loop, 2026-10-16)

Step 3 is done in the main loop with a different cure than the report's "delete": the number is now
read from the README's inventory, which is the one committed list, so a vanished tool still fails
(commit message and ledger row C9a-1 carry the detail). Steps 1, 2 and 4: see the ledger row.
