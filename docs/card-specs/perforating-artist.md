# Perforating Artist — Card Spec

## Identity

- **Name:** Perforating Artist
- **grpId:** 93837
- **Set:** FDN (Foundations)
- **Type:** Creature — Devil
- **Cost:** {1}{B}{R}
- **P/T:** 3/2
- **Keywords:** Deathtouch
- **Forge script:** `forge/forge-gui/res/cardsfolder/p/perforating_artist.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Deathtouch (intrinsic keyword) | `K:Deathtouch` | — | wired |
| Raid — end step trigger (if attacked) | `T:Mode$ Phase \| Phase$ End of Turn \| CheckSVar$ RaidTest` | AbilityInstanceCreated + PhaseOrStepModified (phase=5, step=0) | **partial** — trigger fires, but Raid AbilityWordActive pAnn is a known gap (turn-scoped ability words need event-driven detection) |
| GenericChoice — opponent picks branch | `DB$ GenericChoice \| Choices$ SacNonland,Discard \| FallbackAbility$ LoseLifeFallback` | SelectNReq (idType=PromptParameterIndex, 2–3 branch ids) | **unknown** — GenericChoice SelectNReq not implemented in leyline |
| Sacrifice nonland (unless-cost branch) | `DB$ LoseLife \| UnlessCost$ Sac<1/Permanent.nonLand>` | ChoiceResult + ObjectIdChanged + ZoneTransfer(Sacrifice) | wired (sacrifice path), **unknown** (unless-cost wrapping) |
| Discard (unless-cost branch) | `DB$ LoseLife \| UnlessCost$ Discard<1/Card>` | ChoiceResult + ZoneTransfer(Discard) | wired (discard path), **unknown** (unless-cost wrapping) |
| Lose 3 life (fallback / direct) | `DB$ LoseLife \| LifeAmount$ 3` | SyntheticEvent(type=1) + ModifiedLife(life=-3) | wired |

### Ability grpIds

| grpId | Description |
|-------|-------------|
| 175894 | Raid triggered ability: "At the beginning of your end step, if you attacked this turn, each opponent loses 3 life unless that player sacrifices a nonland permanent of their choice or discards a card." |

## What it does

1. **Enters battlefield** (3/2 Deathtouch). Two `uniqueAbilities` on the object: Deathtouch + the raid end-step trigger (175894).
2. **End step trigger (grpId=175894)**: fires at the beginning of controller's end step (phase=Ending, step=End) if an attack was declared this turn. The ability goes on the stack. `AbilityWordActive` persistent annotation with `AbilityWordName: "Raid"` tracks the condition across the turn.
3. **GenericChoice resolution**: on resolution, each opponent gets a SelectNReq with `idType: PromptParameterIndex`. The server dynamically prunes unavailable branches:
   - **3 branches** (ids 8, 9, 11) when opponent has nonland permanents AND cards in hand: sacrifice nonland / discard / lose 3 life
   - **2 branches** (ids 9, 11) when opponent has no nonland permanents: discard / lose 3 life
   - **0 branches → fallback** when opponent has neither: straight to LoseLife (SyntheticEvent + ModifiedLife, no SelectNReq)
4. **Branch execution**: after opponent selects, the chosen branch resolves immediately in the same ability resolution:
   - **Sacrifice**: `ChoiceResult` annotation (Choice_Value=sacrificed iid, Choice_Sentiment=1) → ObjectIdChanged → ZoneTransfer(BF→GY, category=Sacrifice)
   - **Discard**: `ChoiceResult` → ObjectIdChanged → ZoneTransfer(Hand→GY, category=Discard) (unobserved — opponent always chose sacrifice in recorded sessions)
   - **Lose 3 life**: SyntheticEvent(type=1) + ModifiedLife(life=-3), no ChoiceResult

## Forge script decomposition

```
T:Mode$ Phase | Phase$ End of Turn | ValidPlayer$ You | TriggerZones$ Battlefield
  | CheckSVar$ RaidTest | Execute$ TrigTorment
```
- `Mode$ Phase` + `Phase$ End of Turn` → beginning-of-end-step trigger
- `CheckSVar$ RaidTest` → `Count$AttackersDeclared` — only fires if ≥1 attacker was declared this turn
- `ValidPlayer$ You` → only on controller's end step

```
SVar:TrigTorment:DB$ GenericChoice | Defined$ Opponent | TempRemember$ Chooser
  | Choices$ SacNonland,Discard | FallbackAbility$ LoseLifeFallback | AILogic$ PayUnlessCost
```
- `DB$ GenericChoice` → presents branches to each opponent
- `Defined$ Opponent` → each opponent is the chooser
- `TempRemember$ Chooser` → the choosing player is remembered for branch execution
- `Choices$ SacNonland,Discard` → two named branches; if neither is payable, `FallbackAbility$ LoseLifeFallback` fires
- Each branch is `DB$ LoseLife | UnlessCost$ <cost>` — opponent loses 3 life UNLESS they pay the cost (sacrifice or discard)

## Trace (session 2026-03-21_22-31-46, seat 1)

Perforating Artist (iid=159 in hand) → cast at gs=112 (iid=301) → BF at gs=114. Three end-step triggers observed across turns 6, 8, and ~12.

### Trigger 1 — fallback to LoseLife (gs=132–135, turn 6)

Opponent had no nonland permanents on battlefield. SelectNReq offered only 2 branches (ids 9, 11 — discard, lose life). Opponent chose lose life (no discard either, or chose not to).

| gsId | instanceId | What happened |
|------|-----------|---------------|
| 132 | 307 | PhaseOrStepModified(phase=5, step=0). AbilityInstanceCreated affector=301 affected=[307]. pAnn: AbilityWordActive "Raid" on [301, 165, 307]. |
| 134 | 307 | ResolutionStart grpId=175894. **SelectNReq** (promptId=112, ids=[9,11], idType=PromptParameterIndex, minSel=1, maxSel=1, sourceId=301). |
| 135 | 307 | SyntheticEvent(type=1) affector=307 affected=[2]. ModifiedLife affector=307 affected=[2] life=-3. ResolutionComplete grpId=175894. AbilityInstanceDeleted affector=301 affected=[307]. |

### Trigger 2 — sacrifice branch (gs=183–187, turn 8)

Opponent had a nonland permanent (Woodland Mystic, iid=309, grpId=75550). All 3 branches offered. Opponent chose sacrifice.

| gsId | instanceId | What happened |
|------|-----------|---------------|
| 183 | 319 | PhaseOrStepModified(phase=5, step=0). AbilityInstanceCreated affector=301 affected=[319]. pAnn: AbilityWordActive "Raid" on [301, 319, 315]. |
| 185 | 319 | ResolutionStart grpId=175894. **SelectNReq** (promptId=112, ids=[8,9,11], idType=PromptParameterIndex, minSel=1, maxSel=1, sourceId=301, prompts=[1216,1217,1218]). |
| 186 | — | **SelectNReq** (promptId=1219, ids=[309], idType=InstanceId, minSel=1, maxSel=1, sourceId=301) — follow-up: select WHICH nonland permanent to sacrifice. |
| 187 | 319 | ChoiceResult affector=301 affected=[2] (Choice_Value=309, Choice_Sentiment=1). ObjectIdChanged 309→320. ZoneTransfer(28→37, Sacrifice) affector=319. ResolutionComplete grpId=175894. AbilityInstanceDeleted affector=301 affected=[319]. |

### Trigger 3 — fallback to LoseLife, no SelectNReq (gs=234–237, turn ~12)

Opponent had nonland permanents (3 branches offered). Opponent chose lose life.

| gsId | instanceId | What happened |
|------|-----------|---------------|
| 234 | 329 | AbilityInstanceCreated affector=301 affected=[329]. |
| 236 | 329 | ResolutionStart grpId=175894. **SelectNReq** (promptId=112, ids=[8,9,11], same shape as trigger 2). |
| 237 | 329 | ModifiedLife affector=329 affected=[2] life=-3 (no SyntheticEvent this time). ResolutionComplete. AbilityInstanceDeleted. |

## Annotations

### GenericChoice SelectNReq shape (consistent across all 3 triggers)

```
promptId: 112
sourceId: 301 (Perforating Artist permanent iid)
context: "Resolution_a163"
optionContext: "Resolution_a9d7"
listType: Dynamic
idType: PromptParameterIndex
minSel: 1
maxSel: 1
validationType: NonRepeatable
ids: [8, 9, 11]  (or subset when branches pruned)
prompt.parameters: [
  { type: PromptId, promptId: 1216 },  // "Sacrifice a nonland permanent"
  { type: PromptId, promptId: 1217 },  // "Discard a card"
  { type: PromptId, promptId: 1218 }   // "Lose 3 life" (or similar)
]
```

- `ids` are NOT instanceIds — they are `PromptParameterIndex` values (abstract branch indices)
- Server prunes `ids` to match available branches (no sacrifice branch when no nonland permanents)
- `sourceId` is the **permanent** (301), not the ability instance on stack

### Follow-up SelectNReq for sacrifice target (gs=186)

```
promptId: 1219
sourceId: 301
idType: InstanceId_ab2c
ids: [309]  (the one nonland permanent)
minSel: 1, maxSel: 1
```

This is a second SelectNReq in the same resolution — after opponent picks "sacrifice", they pick WHICH permanent. `idType` switches to `InstanceId`.

### ChoiceResult annotation (gs=187)

```
ChoiceResult  affectorId=301 (permanent)  affectedIds=[2] (opponent seatId)
  Choice_Value: 309 (instanceId of sacrificed permanent)
  Choice_Sentiment: 1
```

`ChoiceResult` uses the permanent as affectorId (not the ability instance on stack). `Choice_Value` is the instanceId of the selected item. `Choice_Sentiment=1` meaning is unclear — possibly "positive" (player chose to pay the cost rather than take damage).

### AbilityWordActive persistent annotation

```
AbilityWordActive  affectorId=1  affectedIds=[301, 307, 315]
  AbilityWordName: "Raid"
```

Tracks all raid-enabled permanents + their active trigger instances. The ability instance (307/319/329) is added when the trigger goes on stack, removed when it resolves. `affectorId=1` (system/game-level, not a specific card).

## Key findings

- **GenericChoice uses `idType: PromptParameterIndex`, not `InstanceId`.** The branch selection is abstract (pick a prompt, not a card). Only the follow-up "select which permanent to sacrifice" uses `InstanceId`. This is a fundamentally different SelectNReq shape from card-selection (Duress) or target-selection mechanics.
- **Two-phase SelectNReq for sacrifice branch.** First SelectNReq picks the branch (sacrifice/discard/lose life). If sacrifice chosen, a SECOND SelectNReq picks the permanent. Both happen within the same ability resolution window.
- **Branch pruning is server-side.** When opponent has no nonland permanents, `ids` omits the sacrifice branch entirely. The client never sees unavailable options. When nothing is payable (no permanents AND no cards), no SelectNReq fires at all — goes straight to fallback LoseLife.
- **SyntheticEvent inconsistency.** Trigger 1 (gs=135) has SyntheticEvent(type=1) before ModifiedLife. Trigger 3 (gs=237) has ModifiedLife WITHOUT SyntheticEvent. Both are "lose 3 life" from the same ability. Possibly path-dependent: fallback (no GenericChoice) vs explicit "lose life" choice.
- **ChoiceResult affectorId = permanent, not ability instance.** Unlike ZoneTransfer (affectorId=ability instance 319), ChoiceResult uses the permanent (301). This split is consistent with the pattern: annotations about "what happened" use the resolving ability, annotations about "who decided" use the source permanent.
- **Prompt IDs are stable.** promptId=112 (branch selector) and promptId=1219 (sacrifice target) appeared identically across all 3 triggers in this session.

## Gaps for leyline

1. **GenericChoice SelectNReq emission.** Forge's `GenericChoice` API must be bridged to Arena's SelectNReq with `idType: PromptParameterIndex`. The branch indices (8, 9, 11) and prompt references (1216, 1217, 1218) need mapping from Forge's named choices to Arena prompt IDs. This is a new SelectNReq shape — existing code handles `InstanceId` and `AbilityId` types but likely not `PromptParameterIndex`.

2. **Branch pruning logic.** Server dynamically removes branches when their cost is unpayable (no nonland permanents → no sacrifice branch; no cards in hand → no discard branch). Leyline must evaluate payability per-opponent before constructing the SelectNReq `ids` list. When ALL branches are unpayable, skip SelectNReq entirely and execute fallback.

3. **Two-phase SelectNReq for sacrifice.** After the branch-selection SelectNReq, if "sacrifice" is chosen, a follow-up SelectNReq with `idType: InstanceId` must be emitted for the opponent to pick which nonland permanent. This is a nested prompt within a single ability resolution — verify leyline's resolution pipeline supports sequential SelectNReqs without re-entering the stack.

4. **ChoiceResult annotation.** New annotation type not currently tracked. Shape: `affectorId=permanent, affectedIds=[seatId], Choice_Value=instanceId, Choice_Sentiment=1`. Must emit after the branch resolves (sacrifice/discard) but not for the fallback lose-life path.

5. **AbilityWordActive "Raid" pAnn.** Catalog notes Raid is a known gap for turn-scoped ability words. The real server emits `AbilityWordActive` with `AbilityWordName: "Raid"` tracking all raid permanents and their active trigger instances. Affects: ability instance added to `affectedIds` when trigger stacks, removed on resolution.

6. **SyntheticEvent before ModifiedLife.** The SyntheticEvent(type=1) preceding ModifiedLife appears inconsistently (present on fallback path, absent on explicit choice path). Determine whether leyline should always emit it or match the server's path-dependent behavior.

## Games

| Session | Seat | PA observed? | Branches exercised | Notes |
|---------|------|-------------|-------------------|-------|
| 2026-03-21_22-31-46 | 1 (human controlled PA) | Yes — 3 triggers | Sacrifice (1x), LoseLife fallback (1x), LoseLife choice (1x) | Discard branch never chosen. ChoiceResult observed. |
| 2026-03-25_21-59-57 | unknown | In deck (cards.json) | Unknown — no notes.md | Needs trace analysis |

## Supporting evidence needed

- [ ] Trace 2026-03-25_21-59-57 for additional trigger observations — particularly the discard branch and multi-opponent scenarios
- [ ] Confirm prompt IDs (1216/1217/1218 for branches, 1219 for sacrifice target) are stable across sessions or card-specific
- [ ] Cross-reference with other GenericChoice cards (e.g., Browbeat, Vexing Devil) if present in games — verify the SelectNReq shape generalizes
- [ ] Puzzle: Perforating Artist vs opponent with exactly 1 nonland permanent and 1 card in hand — verify all 3 branches appear and each resolves correctly
- [ ] Puzzle: Perforating Artist vs opponent with no permanents and empty hand — confirm fallback fires with no SelectNReq
