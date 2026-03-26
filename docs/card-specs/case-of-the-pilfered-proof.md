# Case of the Pilfered Proof — Card Spec

## Identity

- **Name:** Case of the Pilfered Proof
- **grpId:** 88929
- **Set:** MKM (Murders at Karlov Manor)
- **Type:** Enchantment — Case
- **Cost:** {1}{W}
- **Forge script:** `forge/forge-gui/res/cardsfolder/c/case_of_the_pilfered_proof.txt`

## What it does

1. **Cast** ({1}{W}): enchantment goes on stack. AbilityWordActive pAnn appears immediately on the stack object with value=0, threshold=3, AbilityWordName="ToSolveCondition".
2. **Resolve** (Stack→BF): ZoneTransfer category=Resolve, ResolutionComplete. EnteredZoneThisTurn pAnn fires. The AbilityWordActive pAnn transfers to the BF object.
3. **Detective ETB trigger** (Forge `T:Mode$ ChangesZone`): whenever a Detective you control enters or is turned face up, put a +1/+1 counter on it (CounterAdded counter_type=1). Not observed — no Detectives in this session's deck.
4. **Detect face-up trigger** (Forge `T:Mode$ TurnFaceUp`): same +1/+1 counter effect as above, for face-up events. Not observed.
5. **To solve** — you control three or more Detectives (threshold=3). Tracked by AbilityWordActive pAnn (value = detective count you control). Checked at beginning of your end step via Forge `T:Mode$ Phase | Phase$ End of Turn`. When value ≥ threshold, Forge fires the `AlterAttribute … Attributes$ Solved` effect; no separate "CaseSolved" annotation observed.
6. **Solved** (replacement effect): if one or more tokens would be created under your control, those tokens plus a Clue token are created instead. Forge `R:Event$ CreateToken … ReplaceWith$ DBReplace`.

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Cast enchantment {1}{W} | `A:SP$ …` | ZoneTransfer CastSpell + Resolve | wired |
| AbilityWordActive pAnn at cast | — (server-side) | AbilityWordActive pAnn (type ToSolveCondition) on stack obj, affectorId=iid | **missing — pAnn fires at cast, not resolve** |
| Detective ETB → +1/+1 counter | `T:Mode$ ChangesZone … Execute$ TrigPutCounter` | AbilityInstanceCreated + CounterAdded counter_type=1 | **unobserved** |
| Detective face-up → +1/+1 counter | `T:Mode$ TurnFaceUp … Execute$ TrigPutCounter` | AbilityInstanceCreated + CounterAdded counter_type=1 | **unobserved** |
| To solve (end step check) | `T:Mode$ Phase \| Phase$ End of Turn \| IsPresent$ Detective.YouCtrl \| PresentCompare$ GE3` | AbilityWordActive value update; no discrete "CaseSolved" annotation observed | **unobserved — solve never triggered** |
| Solved replacement: token + Clue | `R:Event$ CreateToken … DB$ ReplaceToken … TokenScript$ c_a_clue_draw` | ZoneTransfer(BF) for Clue token alongside original token(s) | **unobserved** |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 88929 | The card itself |
| 170353 | To solve condition (AbilityGrpId in AbilityWordActive pAnn) |
| uniqueAbilityCount: 3 | Three uniqueAbilities present at BF (object reports count, not list) |

The full uniqueAbility list was not observed in this session (object diff sends `uniqueAbilityCount` only). The three abilities are: (1) Detective ETB trigger, (2) face-up trigger, (3) solved replacement effect.

## Trace (session 2026-03-22_23-10-20, seat 1)

Case of the Pilfered Proof was cast turn 11 (gsId 254→256). iid 177 was in library through gs=194; iid 358 was drawn but discarded in the same turn (gs=251); iid 359 was cast and resolved. No Detectives entered the battlefield during the session — the deck contained no Detective-type creatures. The game ended on T12 with a loss. The solve condition was never triggered and the Solved replacement effect was never observed.

### Cast and resolve (gsId 254 → 256)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 254 | 358→359 | Hand→Stack (31→27) | Cast: ObjectIdChanged 358→359, ZoneTransfer category=CastSpell; 2 Plains tapped for {1}{W}; AbilityWordActive pAnn fires immediately on stack object (value=0, threshold=3, AbilityGrpId=170353) |
| 256 | 359 | Stack→BF (27→28) | Resolve: ResolutionComplete grpId=88929; ZoneTransfer category=Resolve; EnteredZoneThisTurn pAnn affector=28 (BF zone) |

### AbilityWordActive annotation shape (gs=254)

```
persistentAnnotations {
  types:      [AbilityWordActive]
  affectorId: 359          // the Case's own iid
  affectedIds: [359]
  details: {
    threshold:       3
    value:           0
    AbilityGrpId:    170353
    AbilityWordName: "ToSolveCondition"
  }
}
```

- Appears on the cast diff (gs=254), while the object is still on the stack — same timing as Case of the Uneaten Feast and Case of the Gateway Express.
- AbilityWordName is always "ToSolveCondition" for Case-type enchantments.
- value tracks the solve-relevant counter (here: Detectives you control). Resets are expected at end-of-turn.
- threshold=3 corresponds to "you control three or more Detectives."

### Case of the Gateway Express (iid 295, grpId 88928) — solve condition reached

The other Case in this session (Case of the Gateway Express, AbilityGrpId=170350) reached value=3 twice (gs=179 and gs=281), equaling its threshold=3. Observations:

- **No discrete "CaseSolved" annotation fired.** The AbilityWordActive pAnn updated value→3 in the same diff as three creatures tapping to attack. There was no separate resolve event for the "To solve" trigger.
- **Value resets at start of new turn.** gs=192 (NewTurnStarted) carried a fresh AbilityWordActive update resetting value to 0. Gateway Express tracks "creatures that dealt combat damage to a player this turn."
- **Solve did not persist** in these observations because the game ended before the bonus static effect was visible. The "Solved" flag is likely communicated via `AlterAttribute` on the BF object (sets `IsSolved`), but this was not observed.

## Gaps for leyline

1. **AbilityWordActive timing.** Server emits the pAnn on the cast diff (stack object), not on the resolve diff. Leyline must emit it when the spell is cast, not when it resolves. This matches Case of the Uneaten Feast behavior.
2. **Detective ETB/face-up trigger not wired.** `T:Mode$ ChangesZone` and `T:Mode$ TurnFaceUp` triggers need to produce AbilityInstanceCreated + CounterAdded (counter_type=1). Zero data points — requires a session with a Detective creature.
3. **Solve transition unobserved.** The wire shape for Case "solving" (value reaching threshold, Forge `AlterAttribute` Solved flag, what annotation if any fires) has no data point. Flag for engine-bridge.
4. **Solved replacement effect unobserved.** The token+Clue replacement (`R:Event$ CreateToken`) requires a solved Case and a token-creation event. No data point exists.
5. **uniqueAbilities list absent.** At cast and resolve, the object sends `uniqueAbilityCount: 3` without the full ability list. Whether the full list is sent in any GSM for this card is unknown; the initial full-game-state GSM was not observed.

## Supporting evidence needed

- [ ] Session where a Detective creature ETBs while Case of the Pilfered Proof is on BF: confirm counter trigger shape (AbilityInstanceCreated, CounterAdded counter_type=1, affectorIds)
- [ ] Session where the solve condition is met (≥3 Detectives at end step): confirm what annotation or GSM diff signals the Case is Solved
- [ ] Session where a Solved Case is in play and tokens are created: confirm Clue token appears alongside the triggered token creation
