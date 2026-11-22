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

## After 3b–3e and 4a–4d (2026-11-22)

The four answers above are what the code did before this work. What it does now:

1. The chat still reads `_jawata/brains`. A pick on a card sends `_jawata/hire/brain`. `AcpServer.hireBrain` writes `SeatBrains.Store` and does not call `HireRegistry.replace`. The studio writes `brains` action `set` and reads action `choices`. The principal's pick still uses `useBrain` / `useModel`, and also saves seat `main`.
2. `AcpServer` is the only caller of `Store.listen` (`AcpServer` constructor, `tellSeatChoice`). It sends `session/update` `jawata_seat_choice` to every open chat that is not the side thread. The studio is another process. It does not hold that listener. After its own write it reads the engine's answer. While the Seats page is open it reads `choices` again every second (`SeatsView.svelte`).
3. The per-seat choice is one file, `jawata.seats.brains.file`, or `~/.local/share/jawata/seats-brains` when the property is unset. The text is `seat=brain` or `seat=brain/model`, joined by `;`. A semicolon, slash, equals or backslash inside a name is escaped. The studio passes `-Djawata.seats.brains.file` to that same path. The file survives an engine restart. `jawata.agent.hireBrains` is still only the registry.
4. The principal's running turn is unchanged, as section 2.4 describes.

Defaults removed in 3a are listed above. Also removed: `seat_settings.rs` `unwrap_or_else(|| "claude-code")` is now `unwrap_or_default()`, and the page the person sees replaces that with the engine's stored choice or an empty brain, which the page draws as "not set". `seat_file.rs` still uses `unwrap_or("claude-code")` inside an error sentence when a script line is on a brain that is missing. That sentence is not a hire.

`btw.md` was `brain: cursor`. `rottenmeier.md` was `brain: claude-code` and `model: sonnet`. Those lines are gone from `jawata-studio/seats` and from `jawata-mcp/resources/seats`. When the store file does not exist, `seed_moved_seat_choices` writes `btw=cursor;rottenmeier=claude-code/sonnet`. An existing file is not rewritten. Old assertion in `seat_file.rs` `no_shipped_seat_names_a_model_nobody_verified`: rottenmeier's model was `"sonnet"`. It is now `""`, and no shipped seat file names a brain.

### Steps

| Step | What | Red | Green | Plant |
|---|---|---|---|---|
| 3a | no blank-to-Claude | `SeatHireTest` expected null, was `CLAUDE_CODE` | 188 failed=0, recorded above | plant restored Claude; 2 of 30 failed; plant removed |
| 3b | `SeatBrains.choose` once | before the method existed, a hire with no entry ran on the file | `HireToolTest` with `SeatBrainsTest` and `SeatHireTest`: 149 failed=0 (`/tmp/hire-wire5.txt`) | not run again after that green |
| 3c | stored model is the start | the page said Haiku and the journal said null, measured before this work | dialect: `--model` then `Haiku 5.5`, journal `Haiku 5.5`. ACP: answer text `haiku`, record `Haiku 5.5`. `commandLineModelIn` returns `haiku` when the catalogue holds `Haiku 5.5`. Class run 129 failed=0 (`/tmp/seat-green.txt`) | not run |
| 3d | page shows not set; two lines moved | the page used `unwrap_or("claude-code")` | `no_shipped_seat_names_a_model_nobody_verified`, `the_moved_brain_lines_are_written_only_when_the_store_file_is_missing`, `knowledge_jvm_properties_carries_no_crawl_caps`: 3 passed (`/tmp/studio-seed.txt`) | not run |
| 3e | one pair, one seat, rename into place | a corrupt value `this is not a seat map` is refused | `writersDoNotLoseOrMixAPair` inside the 129. A choice already read does not change | removing the lock was not re-run after the green |
| 4a | one `save` | a pick did not write the file | `BrainsTool` `set` / `choices` call `SeatSettings.storedSeatChoice`. Studio `seat_change` for a brain calls `engine_store_seat_choice` | not run |
| 4b | the listener | no event, section 2.2 | `aSaveTellsTheListener` in the 129. `AcpServer` listens and sends `jawata_seat_choice`. The studio polls | not run |
| 4c | menu says next hire | the menu said the pick replaces this agent; the test expected `✓claude · sonnet` and a row that became `codex` | chat-view passed after the test expected no mark until `seatChoice`, and the button stayed `claude · sonnet ▾` | not run |
| 4d | card shows what ran | the row changed to the pick | the same view test: the button stays the hire's brain. `live-controls.test.ts` waits on `seatChoices`, not on the card's brain | not run |

Item 7, red then green. `SeatBrainsTest` 12 tests, 2 failed (`/tmp/seatbrains-red.txt`): `aModelNameMayContainASemicolon` threw `an entry is not seat=brain` on `Haiku; 5.5`. `anUnreadableFileDoesNotBlockTheNextSave` threw the same, because that save also used a semicolon, so it did not reach the "unreadable file blocks the save" line on its own. After the escape and the save-from-empty, the class is inside the 129 failed=0. A plant that made `encode` return the raw name was not run: further script edits of `.java` were refused after the green.

Item 8. `SeatBrainsTest` and `AgentContractLevel1Test` call `SeatBrains.uninstall()` in `@AfterEach`. The contract test also uninstalls in its `finally`.

Commands:

- `mvn -B -f build/pom.xml package -DskipTests` from `jawata-mcp`. `BUILD SUCCESS` after the store wiring and again before `/tmp/seat-green.txt`.
- `java -Djawata.test.fixtures="$PWD/org.jawata.core.tests/test-resources/sample-projects" -Djawata.test.classlist=/tmp/one-class.txt -jar build/dist/target/dist/jawata.jar -runTests` with `SeatBrainsTest`, `HireToolTest`, `AgentContractLevel1Test`. SPIKE-TESTS total=129 succeeded=129 failed=0.
- `cargo test --lib -- no_shipped_seat_names_a_model the_moved_brain_lines_are_written knowledge_jvm_properties_carries_no_crawl_caps` from `jawata-studio/src-tauri`. 3 passed.
- Chat: `npm test` in `packages/chat-core` (226 passed), `packages/chat-view`, `packages/screens`, `packages/acp-contract`.

`docs/release-notes/v4.6.15.md` and `v4.6.16.md` were already modified when this session started. The diff is the hire_wait client list and "plans are per workspace". This work did not edit them. They were not reverted, and they are not in these commits.

Not committed, because the diff also holds earlier unfinished edits: `HireWatch.java`, `HireEntry.java`, `Hirer.java`, `SeatTree.java`, `pm.md`, `HireEntriesTest.java`, `HirerTest.java`, `SeatTreeFromSeatFilesTest.java`, `AcpBrainSession.java`, `AcpBrainSessionTest.java` (that file holds `noteOffered`'s test and the earlier model-menu tests), `manager_service.rs` (the engine write, the seed, and the plans-per-workspace edits), `Agents.tsx` (the menu sentence, and also a change to `UseFigures` and `PrincipalCalls`), `InputArea.tsx`, `styles.ts`, the keystroke test, `input-lines.test.mjs`. `HireTool.java` and `HireToolTest.java` are committed with 3b; they also contain the z39 `hireThrough` hunks and the z53 lead-file attribution that were already in those files.

Part 3 was not started. The whole suite was not run.
