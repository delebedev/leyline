# Engine Analyst Memory

## Key Architecture

- Forge priority loop: `PhaseHandler.mainLoopStep()` (line 1042) -- single iteration per priority grant
- Bridge intercept: `WebPlayerController.chooseSpellAbilityToPlay()` (line 938) -- blocks on `GameActionBridge.awaitAction()`
- Two-layer auto-pass: engine-side (`PlayableActionQuery`, own-turn only) + session-side (`BundleBuilder.shouldAutoPass`, covers opp turn)
- `chooseSpellAbilityToPlay()` returns null = pass, non-null = action taken
- `pFirstPriority` tracks "all passed" detection -- reset on action (rule 117.3c)

## Key Files

- Priority loop doc: `docs/priority-loop.md`
- Priority analysis: `docs/priority-system-analysis.md`
- Rosetta table: `docs/rosetta.md`
- Arena priority ref: `~/src/mtga-internals/docs/phase-transitions-and-autopass.md`
- Arena auto-pass: `~/src/mtga-internals/docs/auto-pass-protocol.md`
- Arena actions: `~/src/mtga-internals/docs/action-submission.md`

## Known Gaps (priority-system-analysis.md)

1. `autoPassPriority` field from PerformActionResp not read (FullControl ignored)
2. `AutoPassOption` from settings not tracked
3. `shouldStop` always true (no selective intelligence)
4. `autoPassCancel` is a no-op in WebGuiGame
5. No TimerStateMessage sent (no rope UI)
6. Transient stops, yields not implemented
7. `next_phase`/`next_step` not set in TurnInfo

## Patterns

- Engine thread blocks on CompletableFuture; session thread completes it
- PrioritySignal (semaphore) wakes awaitPriority without polling
- PhaseStopProfile: human defaults = MAIN1, COMBAT_DECLARE_ATTACKERS/BLOCKERS, MAIN2
- EdictalMessage = server-forced pass (sent during auto-pass to break client out of waiting state)
