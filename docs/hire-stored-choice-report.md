# Stored seat choice — findings before the hire change

Written 2026-11-22 from the code, before Part 1 changed a hire. Text searches, not a compiler symbol search. Where this note and a later edit disagree, the edit's commit says what changed.

## 2.1 Does the chat already read the brain list, and do the drop-downs write the stored choice?

The chat reads the engine's brain list. It does not write `jawata.seats.brains`.

- List: ACP method `_jawata/brains`. `AcpServer.brainsResult` (`org.jawata.mcp/src/org/jawata/mcp/agent/AcpServer.java`) reads `jawata.agent.hireBrains` through `offerBrains` (`AgentFace.java` around the `offerBrains` call) and asks `modelsOf` for each brain's models. The chat calls that as `listBrains` (`packages/chat-core/src/chat-controller.ts`, `packages/chat-core/src/session-types.ts`) and stores the answer on `state.brains` (`packages/chat-view/src/view-state.ts`). Agent Control draws that list in `packages/chat-view/src/Agents.tsx` (`state.brains.map`).
- Agent Control's pick sends `_jawata/hire/brain` (`chooseBrain` in `chat-controller.ts` → `AcpServer.hireBrain`). That calls `HireRegistry.replace`: it stops the running hire and hires the same seat again. It does not write `jawata.seats.brains`.
- The chat's own brain drop-down (the principal, not a hired seat) sends `session/set_config_option` with id `principal-brain` (`AcpServer.setPrincipalBrain` → `PrincipalBrain.useBrain` / `useModel`). That changes the principal's session. It does not write `jawata.seats.brains`.
- The studio Seats page writes `seats/.settings.json` (`seat_settings.rs` `SeatSettings::set_brain` / `save`) and `materialize_seats` copies `brain:` / `model:` into the deployed seat file. A search of `jawata-studio` for `jawata.seats.brains` finds no writer.
- Eclipse's Seats screen writes the key into Eclipse instance preferences (`EngineSeatDoor.store`).

`HireTool` does read `jawata.seats.brains` after `SeatHire.read`, and only when an entry for the seat is present (`HireTool.java` the unsealed branch around `storedBrainOf`). The studio never writes that key, so a studio hire does not see the page.

## 2.2 Does one change reach all three places?

No. There is no engine event for a change of `jawata.seats.brains`.

- The studio re-reads `.settings.json` when `seats_view` runs.
- The chat asks `_jawata/brains` when the Agent Control menu opens. That list is the registry's brains and models, not the stored per-seat choice.
- A change of the principal's brain is pushed as `session/update` `config_option_update` (`AcpServer.configOptionUpdate`). That is the principal's session option, not a seat's stored choice.
- `HireWatch` reads the seat-brains supplier when it reads a hire's seat file. It is not a push to the page.

Part 2 has to add the event. Nothing in Part 1 adds it.

## 2.3 Storage shape and owner

One string, key `jawata.seats.brains` (`SettingKeys.SEATS_BRAINS`). Shape: `seat=brain` or `seat=brain/model`, joined by `;`. Read by `SeatSettingsService.storedBrainOf`. Not one key per seat.

Who holds it:

- The standalone engine (`DefaultEngineSettings`) reads a system property of that name. No environment variable is registered for this key (`SettingKeys.register` passes a null environment; `DefaultEngineSettings.ENVIRONMENT` does not list it). The studio does not pass `-Djawata.seats.brains`. A value set only as a system property does not survive a restart unless the launcher passes it again.
- Eclipse (`EclipseSettings`) reads the workspace instance preference of the engine plug-in, written by `EngineSeatDoor.store` (`preferences.flush()`). That survives an Eclipse restart. The file is Eclipse's instance preference store for that plug-in.
- The studio's own file is `seats/.settings.json` (`SETTINGS_FILE` in `seat_settings.rs`). The comment at the top of that file says the settings live there on purpose, beside the seat files, because writing them into the seat file would mark the seat as edited. The engine does not read that file. This is the studio's own store by design. It is a second store from `jawata.seats.brains`.

The setting's fallback text `"the brain each seat file names"` is the key's description string. `DefaultEngineSettings.get` does not return it.

## 2.4 The principal's drop-down while a turn is running

Report only. Not changed.

`PrincipalBrain.answer` sets `turnRunning` around `brain.prompt`. `useModel`, when a session is open and `turnRunning` is true, stores the pick in `pendingModel` and `preferredModel` and returns. It does not call `selectModel` on that turn. The next `answer` applies `pendingModel` with `selectModel` before the prompt. `useBrain` replaces `program` and sets `brainChanged` immediately, and clears a pending model. The turn already holds the `Conversation` it obtained, so that turn finishes on the brain it started on. The next prompt opens the new program because `liveConversation` is empty while `brainChanged` is set.

## Seat files that name a brain today

Text search of `jawata-studio/seats/*.md` and `jawata-mcp/org.jawata.mcp/resources/seats/*.md`:

- `btw.md`: `brain: cursor`. No `model:` line. `SeatPrompts.brainNamedBy` reads a seat file's `brain:` for the principal and the side seat (`PrincipalBrain.programOfSeat`). The studio page also shows the file's brain when `.settings.json` has no override (`seats_view` uses the file, then `"claude-code"` when the file has none).
- `rottenmeier.md`: `brain: claude-code` and `model: sonnet`. Same two readers.

`developer.md` and `pm.md` have neither line. Every other shipped seat file in those two folders has neither line, except the two above.

`seats_view` (`seat_settings.rs`) sets a row's brain to `seat.brain.clone().unwrap_or_else(|| "claude-code".to_string())`. `SeatSettingsService.row` does the same with `SeatHire.Brain.CLAUDE_CODE` when nothing is stored and the file names none. Those are code defaults, not stored values. Part 1 does not copy them into a store.

## What Part 1 will not treat as a source

A seat file's `brain:` and `model:` lines are not the hire's choice. `jawata.agent.hireBrains` is the registry of programs, not the per-seat choice.

## Step 3a — the Claude fill-in is gone

Removed, main source, text search then the compiler:

- `SeatHire.read`: a missing or blank `brain` line was `Brain.CLAUDE_CODE`. It is now no brain. `dialect` throws if asked before a brain is bound. A switch that only some brains can carry is not checked until a brain is named, so a blank line is not treated as Claude for that check either.
- `SeatSettingsService.row`: a seat with nothing stored and no brain line was shown as `claude-code`. The row's brain is now empty.
- `HireWatch.readSeatFile`: it called `brain().label()` and would have crashed, or, before this, shown Claude. A file with no brain leaves the card's brain empty.
- `HireTool`: if the brain is still none after the stored overlay, the hire is refused `PRECONDITION_NOT_MET` ("has no brain yet") and no process is started. The message does not yet say where to set it. That is step 3b.

Not removed, and why:

- `SeatHire.Brain.CLAUDE_CODE` stays as the dialect identifier.
- `HireTool` still writes the label `THE BRAIN'S OWN DEFAULT` when the model is null. That is a record label, not a model the process is started with. Step 3c deals with the record.
- `SettingKeys.SEATS_BRAINS` fallback text `"the brain each seat file names"` is the key's description. `DefaultEngineSettings.get` does not return it.
- The studio page still uses `unwrap_or("claude-code")` in `seat_settings.rs`. That is step 3d, and it is not the engine.

Tests that named no brain and then read Claude's command line now write `brain: claude-code`, because they measure that dialect. Old assertion in `SeatHireTest#everyShippedSeatReads`: `allMatch(s -> s.brain() == CLAUDE_CODE)`, "none of them declares a brain, and claude-code is what they all mean". It is now `brain() == null`. Old assertion in `SeatSettingsServiceTest`: `assertEquals("claude-code", seat("developer").brain())`. The file names no brain and nothing is stored, so it is now `""`.

Red before the change (`SeatHireTest`, 30 tests, 1 failed): `expected: <null> but was: <CLAUDE_CODE>` for a missing line, a blank line and a whitespace line. Green after: `SeatHireTest`, `SeatSettingsServiceTest`, `AHiredClaudeDoesNotSeeTheEnginesCodeChangingToolsTest`, `HireToolTest`, `HireEntriesTest` — 188 tests, failed=0. Plant: `read` set the blank line back to `Brain.CLAUDE_CODE`. `SeatHireTest` 30 tests, 2 failed, including `aMissingOrBlankBrainLineIsNotClaude` with the same `CLAUDE_CODE` failure. The plant was removed. A sandboxed run of `HireToolTest` failed 4 stop/replace tests with a timeout; the same class outside the sandbox was 110 of 110. The whole suite was not run.

A seat file that names a brain is still used when nothing is stored. That is not this step.
