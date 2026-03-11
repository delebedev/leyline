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
- Layered effects doc: `docs/forge-layered-effects.md`
- Rosetta table: `docs/rosetta.md`
- Arena priority ref: from client decompilation (phase transitions and autopass)
- Arena auto-pass: from client decompilation (AutoPassOption protocol)
- Arena actions: from client decompilation (action submission flow)

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

## Ability ID Mapping (docs/forge-ability-id-mapping.md)

- Three independent ID counters: StaticAbility, SpellAbility, Trigger (each `++maxId`)
- ptBoostTable staticId: continuous effects = StaticAbility.getId(); resolved spells/perpetual = 0
- No global staticId→StaticAbility lookup in engine; must scan cards
- Card trait creation order: replacements → statics → triggers → keywords → activated abilities
- Keywords expand to sub-traits via KeywordInstance.createTraits() (grouped per KeywordInterface)
- CardData.abilityIds slots are positional (card text order); Forge splits by type
- Current workaround: `.firstOrNull()` everywhere (breaks multi-ability cards)
- Bridge registry approach: map forge trait IDs → abilityGrpId slots at card registration

## Layered Effects (docs/forge-layered-effects.md)

- All per-card modifications use `Table<Long(timestamp), Long(staticAbilityId), Value>`
- P/T split into 4 tables: newPTText (L3), newPTCharacterDefining (L7a), newPT (L7b/SetPT), boostPT (L7c/Modify)
- Continuous effects: staticId = StaticAbility.getId() (stable int); resolved spells: staticId = 0
- `checkStaticAbilities()` clears and reapplies all continuous effects every state check
- `StatBreakdown(currentValue, tempBoost, bonusFromCounters)` decomposes final P/T
- `getPTBoostTable()` returns immutable copy -- diffable between snapshots
- `GameEventCardStatsChanged` fires with affected cards list but no delta
