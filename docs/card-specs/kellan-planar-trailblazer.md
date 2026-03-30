# Kellan, Planar Trailblazer — Card Spec

## Identity

- **Name:** Kellan, Planar Trailblazer
- **grpId:** 93804 (also 95239 — alternate printing)
- **Set:** FDN
- **Type:** Legendary Creature — Human Faerie Scout
- **Cost:** {R}
- **P/T:** 2/1
- **Forge script:** `forge/forge-gui/res/cardsfolder/k/kellan_planar_trailblazer.txt`

## Mechanics

| Mechanic | Forge DSL | Forge event | Catalog status |
|----------|-----------|-------------|----------------|
| Activated ability (type change) | `AB$ Animate` | `GameEventCardStatsChanged` | type-change: wired |
| Condition check (Scout gate) | `ConditionPresent$ Card.Self+Scout` | none | missing (ShouldntPlay advisory) |
| Condition check (Detective gate) | `ConditionPresent$ Card.Self+Detective` | none | missing (ShouldntPlay advisory) |
| Combat damage trigger | `Mode$ DamageDone \| CombatDamage$ True` | `GameEventDamageDealt` (inferred) | combat-damage: wired |
| Impulse draw (exile top, play this turn) | `DB$ Dig \| DestinationZone$ Exile` + `MayPlay$ True` | none (zone transfer only) | missing (CardRevealed #47 needed) |
| Double strike (gained) | `Keywords$ Double Strike` | engine-native | double-strike: wired |
| Legendary | supertype | none | legend-rule: wired |
| P/T override (3/2) | `Power$ 3 \| Toughness$ 2` | `GameEventCardStatsChanged` | type-change: wired |
| Creature type replacement | `RemoveCreatureTypes$ True \| Types$` | `GameEventCardStatsChanged` | type-change: wired |

## What it does

Kellan has a "ladder" of activated abilities — each transforms it into a progressively stronger form:

1. **Scout → Detective** ({1}{R}): If Kellan is a Scout, it permanently becomes a Human Faerie Detective and gains "Whenever Kellan deals combat damage to a player, exile the top card of your library. You may play that card this turn."
2. **Detective → Rogue** ({2}{R}): If Kellan is a Detective, it permanently becomes a 3/2 Human Faerie Rogue with double strike.
3. **Combat damage trigger** (Detective form): When Detective-Kellan deals combat damage to a player, exile the top card of the controller's library. That card is playable until end of turn (impulse draw).

The abilities are gated: you must go Scout → Detective → Rogue in order. Activating the wrong one (e.g., Detective→Rogue while still a Scout) does nothing because the condition isn't met. The server annotates this with `ShouldntPlay(ConsequentialConditionNotMet)`.

**Unobserved:** The combat damage trigger (impulse draw) was not exercised in this game — Kellan never connected as a Detective. Needs a dedicated game or puzzle.

## Trace (game 2026-03-30_20-06-50, seat 1)

Kellan was cast once, activated Scout→Detective (T6), activated Detective→Rogue (T10), attacked as a 3/2 double striker (T13), and died to combat damage. Two additional copies were drawn but never cast.

### Cast (T4)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 79 | 162→296 (ObjectIdChanged) | Hand→Stack | CastSpell, ManaPaid color=4 (R) |
| 81 | 296 | Stack→Battlefield | Resolve. Kellan enters as Scout 2/1 |

### Scout→Detective activation (T6)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 126 | 306 (ability) | — | AbilityInstanceCreated from source 296, abilityGrpId=175854 |
| 128 | 306 | — | AbilityInstanceDeleted — ability resolves. Kellan is now Detective |

After resolution (gs=128), `ShouldntPlay(ConsequentialConditionNotMet)` switches from abilityGrpId=175855 to 175854 — the server now warns about the Scout→Detective ability (already used) instead of the Detective→Rogue ability (now available).

### Detective→Rogue activation (T10)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 228 | 331 (ability) | — | AbilityInstanceCreated from source 296, abilityGrpId=175855 |
| 230 | 331 | — | AbilityInstanceDeleted — ability resolves. Kellan is now 3/2 Rogue with double strike |

After resolution (gs=230), BOTH abilities show `ShouldntPlay(ConsequentialConditionNotMet)` — neither can be activated again since Kellan is no longer a Scout or Detective.

### Death (T13)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 309 | 296→343 (ObjectIdChanged) | Battlefield→Graveyard | SBA_Damage (3 damage dealt to 3/2) |
| 309 | 306, 331 | — | AbilityInstanceDeleted ×2 — combat trigger and Rogue grants cleaned up |

### Second copy drawn (T8)

| gsId | instanceId | Zone | What happened |
|------|-----------|------|---------------|
| 168 | 170→311 (ObjectIdChanged) | Library→Hand | Draw step. `ShouldntPlay(Legendary)` begins firing for both 282 and 311 |

### Annotations

**ShouldntPlay — ConsequentialConditionNotMet:**

The `ConsequentialConditionNotMet` reason is new — not previously documented. It fires for Kellan's conditional abilities and carries the `abilityGrpId` detail key identifying which ability shouldn't be activated.

Schema:
```
affectorId = permanent on battlefield (296 = Kellan)
affectedIds = [same permanent] (296)
details:
  Reason = "ConsequentialConditionNotMet"
  abilityGrpId = <the ability that can't be activated>
```

abilityGrpId values observed:
- **175854** = {1}{R} Scout→Detective ability
- **175855** = {2}{R} Detective→Rogue ability

Lifecycle:
- Fires on every priority point while the condition fails (transient, not accumulated)
- Before Scout→Detective: only 175855 is flagged (Detective→Rogue unavailable)
- After Scout→Detective: only 175854 is flagged (Scout→Detective no longer applicable)
- After Detective→Rogue: both 175854 and 175855 are flagged (neither applicable)

**ShouldntPlay — Legendary:**

Fires when a second Kellan copy is in hand while one is on the battlefield. Previously documented (field note `docs/field-notes/ShouldntPlay.md`). Here it follows the same pattern:

```
affectorId = 296 (Kellan on battlefield)
affectedIds = [282] initially, then [282, 311] after second copy drawn T8
details:
  Reason = "Legendary"
```

Note: `affectedIds` grows as more copies enter hand — the annotation targets ALL copies simultaneously, not one per copy.

## Modal activation protocol

**Key finding: There is no modal UI.** The "choose which ability to activate" prompt is NOT a separate ChoiceReq, GroupReq, or OptionalActionMessage. It is entirely client-side rendering driven by the standard `ActionsAvailableReq.actions` array.

### How it works

The server sends a normal `ActionsAvailableReq` (inside the GSM diff) with **two separate `ActionType_Activate` entries** for the same `instanceId`, disambiguated by `abilityGrpId`:

```json
// GSM 125, actions array (abridged — same pattern in 123, 124, 126, 127)
{ "actionType": "ActionType_Activate", "instanceId": 296, "abilityGrpId": 175854,
  "manaCost": [{ "color": ["ManaColor_Generic"], "count": 1, "abilityGrpId": 175854 },
               { "color": ["ManaColor_Red"],     "count": 1, "abilityGrpId": 175854 }] }
{ "actionType": "ActionType_Activate", "instanceId": 296, "abilityGrpId": 175855,
  "manaCost": [{ "color": ["ManaColor_Generic"], "count": 2, "abilityGrpId": 175855 },
               { "color": ["ManaColor_Red"],     "count": 1, "abilityGrpId": 175855 }] }
```

The client sees two `Activate` actions for one permanent and renders a modal picker. This is the same pattern as any permanent with multiple activated abilities — the client decides when to show a modal vs inline buttons.

### Key protocol details

1. **Both abilities are always offered.** Even though one will fail its condition check, the server includes both in `actions`. The `ShouldntPlay(ConsequentialConditionNotMet)` annotation marks which one would fail — the client uses this to grey out the unavailable option in the modal.

2. **`abilityGrpId` is the disambiguator.** Each `manaCost` entry also carries an `abilityGrpId` field matching the action's `abilityGrpId`. This lets the client render cost text per ability.

3. **Client response is a standard `PerformActionResp`.** The `UserActionTaken` annotation in the resolution GSM (126) confirms the player picked `abilityGrpId=175854`:
   ```
   UserActionTaken: actionType=2 abilityGrpId=175854 affectedIds=[306]
   ```
   `actionType=2` = `ActionType_Activate`. The ability object (instanceId 306) appears on the stack with `grpId=175854`.

4. **After transformation, the pattern persists.** At GSM 227 (Detective form), the server still offers both abilities — 175854 (Scout→Detective, now invalid) and 175855 (Detective→Rogue, now valid). The `ShouldntPlay` annotation has flipped to flag 175854 instead of 175855.

### GSM sequence for Scout→Detective activation (T6)

| gsId | What | Detail |
|------|------|--------|
| 124-125 | `ActionsAvailableReq` | Both `Activate(296, 175854)` and `Activate(296, 175855)` in actions; `ShouldntPlay` flags 175855 |
| 126 | Ability goes on stack | `AbilityInstanceCreated`: source=296, ability=306, `grpId=175854`. `UserActionTaken(actionType=2, abilityGrpId=175854)`. Mana paid from lands 283 and 305. Both abilities still offered in actions. |
| 127 | Priority pass (HiFi) | Opponent gets priority. Same actions array. |
| 128 | Resolution | `ResolutionStart/Complete(grpid=175854)`, `LayeredEffectCreated` x2, `AbilityInstanceDeleted(306)`. Kellan now Detective. `ShouldntPlay` flips to flag 175854. |

### Implication for leyline

Leyline's `ActionMapper` already loops through all `spellAbilities` per permanent (lines 106-121 in `ActionMapper.kt`) and emits one `ActionType_Activate` per ability with its `abilityGrpId`. This means:

- **The action-building side is already correct** for multi-ability permanents.
- **Missing piece: `manaCost` on Activate actions.** The real server includes `manaCost` with per-entry `abilityGrpId` on each Activate action. Leyline's current `ActionMapper` does not populate `manaCost` for Activate actions (only for Cast). The client may use this for modal cost display.
- **Missing piece: `ShouldntPlay(ConsequentialConditionNotMet)`.** The grey-out of the invalid ability in the modal depends on this annotation. Without it, both abilities appear equally valid to the client.

## Key findings

- **New `ShouldntPlay` reason: `ConsequentialConditionNotMet`.** This is an ability-ladder gate — it warns that an activated ability's `ConditionPresent` check would fail. The `abilityGrpId` detail key identifies which ability. This pattern applies to any card with conditional activated abilities (Class enchantments, other level-up patterns).
- **`ShouldntPlay(Legendary)` grows its `affectedIds` array** — when a second copy is drawn (T8), both hand copies appear in the same annotation. The field note only documented single-target cases.
- **Multi-ability activation is NOT a special message type.** Arena's modal picker for "choose which ability" is purely client-side. The server sends two `ActionType_Activate` entries in the standard `actions` array, same `instanceId`, different `abilityGrpId`. The client detects multiple Activate actions for one permanent and renders a modal. No ChoiceReq, GroupReq, or OptionalActionMessage involved.
- **Kellan's abilities resolve as stack objects** with their own grpIds (175854, 175855). The `AbilityInstanceCreated`/`AbilityInstanceDeleted` lifecycle is clean — created when activated, deleted on resolution. On death, both ability instances are deleted in the same GSM.
- **Board state accumulator correctly tracks P/T change** — at gs=230, board shows Kellan as 3/2 after Detective→Rogue resolves. The `Power$/Toughness$` override from Animate is reflected.
- **Impulse draw trigger was NOT exercised.** Kellan never dealt combat damage as a Detective. The `KellanCombat` triggered ability, `DB$ Dig` exile, and `MayPlay$ True` continuous effect are all unverified from wire.

## Gaps for leyline

1. **`ShouldntPlay` annotation (type 53) — not implemented.** Three reasons now documented: `EntersTapped`, `Legendary`, `ConsequentialConditionNotMet`. All are advisory/cosmetic — no gameplay impact, but affect client UX (dim/badge on unplayable cards). The `ConsequentialConditionNotMet` variant needs engine-side condition evaluation per priority point.

2. **Animate ability support.** Forge's `AB$ Animate` changes creature types, P/T, and grants keywords. The engine fires `GameEventCardStatsChanged`. Leyline's type-change pipeline (wired for Crew/Vehicles) should handle the `ModifiedType` + `LayeredEffect` persistent annotations, but the `ConditionPresent$` gate and `RemoveCreatureTypes$` override are untested for activated abilities outside Vehicle context.

3. **Impulse draw ("exile top, play this turn").** Forge's `DB$ Dig` + `MayPlay$ True` continuous effect has no dedicated wire path. Requires: `ZoneTransfer(Library→Exile)` with a reveal/impulse category, `CardRevealed` annotation (#47, missing), and a `Qualification` persistent annotation (#42, missing) marking the exiled card as playable this turn. Need a game where the trigger fires.

4. **Combat damage trigger wiring.** `DamageDone` mode triggers are wired at the engine level, but Kellan's gained trigger (via Animate) creates a new trigger instance at runtime — need to verify that dynamically-added triggers fire events and get correct `AbilityInstanceCreated` annotation.

5. **`cast-adventure` still missing (catalog).** While Kellan isn't an adventure card, its ability ladder pattern resembles the adventure "prerequisite chain" — the `ConsequentialConditionNotMet` reason may also apply to adventure cards where the creature half can't be cast until the adventure was played. Cross-reference when adventure support lands.

6. **Update `docs/field-notes/ShouldntPlay.md`** — add `ConsequentialConditionNotMet` reason, `abilityGrpId` detail key, multi-target `affectedIds` for Legendary.

## Supporting evidence needed

- [ ] Game where Kellan deals combat damage as Detective — exercise impulse draw trigger, observe ZoneTransfer(Library→Exile) + CardRevealed + MayPlay annotation chain
- [ ] Game where the exiled card is actually played from exile — confirm the `Qualification` / `MayPlay` wire path
- [ ] Puzzle: Kellan on battlefield as Scout, activate both abilities in one turn (enough mana for {1}{R} then {2}{R}) — verify ability ladder in controlled environment
- [ ] Cross-reference Cleric Class (`docs/card-specs/cleric-class.md`) — another ability-ladder card; does `ConsequentialConditionNotMet` fire for Class level-up?

---

## Tooling Feedback

### Session 1 (initial spec)

#### Commands used

| Command | Worked? | Notes |
|---------|---------|-------|
| `just scry-ts --help` | Yes | Good overview, discoverable |
| `just scry-ts trace "Kellan" --game 2026-03-30` | Yes | Excellent. Rich output with turn grouping, all annotation types inline, zone names resolved. Best command for card investigation. |
| `just scry-ts game cards --game 2026-03-30` | Yes | Quick card manifest, useful for orientation |
| `just scry-ts game notes --game 2026-03-30` | Yes | Helpful for context on saved games |
| `just scry-ts board --gsid N --game 2026-03-30` | Yes | Great for snapshots at key moments. Action log at the bottom is a nice bonus. |
| `just scry-ts game list --saved` | Yes | Clean catalog view |
| `just scry-ts gsm show 81 --game 2026-03-30` | **BROKEN** | "No games found in Player.log" — `gsm` commands don't work with saved games, only live Player.log. `trace`, `board`, and `game cards` all work with `--game` for saved games, but `gsm` doesn't. |
| `just scry-ts gsm list --has ShouldntPlay --game 2026-03-30 --view annotations` | **BROKEN** | Same issue — saved game not supported |

#### What was missing or awkward

1. **`gsm show` and `gsm list` don't support saved games.** This is the biggest gap. `trace` and `board` correctly resolve `--game 2026-03-30` against saved games in `~/.scry/games/`, but `gsm` only reads live Player.log. For post-session analysis (which is the primary card-spec workflow), `gsm` commands are unusable.

2. **No way to see raw annotations for a specific gsId in a saved game.** Without `gsm show`, I couldn't inspect the full annotation detail for key moments (e.g., gs=81 where ShouldntPlay first fires). The `trace` output includes annotation types and key details, which partially compensates, but sometimes you need the full annotation JSON with all detail keys.

3. **`--view annotations` on gsm list would have been perfect** for seeing all ShouldntPlay instances with their detail keys — but it's blocked by the saved-game issue.

#### What would have made the investigation easier

1. **Fix `gsm` saved-game support** — `selectGames()` in gsm.ts calls `loadEvents()` which only reads Player.log. It should fall back to saved game resolution like `trace` and `board` do.

2. **`trace` with `--json` flag** — the human-readable trace output is great, but for novel annotation types I wanted to see the raw detail keys. A `--json` escape hatch on trace would let me pipe to `jq` for deep inspection.

3. **`trace --gsid N`** — filter trace output to a single GSM. When a card has 103 annotations across 55 GSMs, scrolling through the full trace to find gs=81 is noisy. Being able to say "show me just what happened at gs=81 for this card" would be ideal.

#### Commands I wished existed

- **`scry gsm show N --game <ref>`** — working version against saved games
- **`scry trace "Kellan" --game 2026-03-30 --json`** — raw annotation JSON for the traced card
- **`scry abilityGrp <id>`** — resolve abilityGrpId to a card name + ability description (had to use `board --gsid` to see what was on the stack, then correlate manually)
- **`scry annotations --type ShouldntPlay --game <ref>`** — cross-card annotation search (not filtered to a single card like trace, not filtered to a single GSM like gsm show — a horizontal slice across the whole game for one annotation type)

### Session 2 (modal activation investigation)

#### Commands used

| Command | Worked? | Notes |
|---------|---------|-------|
| `just scry-ts trace "Kellan" --game 2026-03-30_20-06` | Yes | Confirmed activation GSM sequence, annotation lifecycle |
| `just scry-ts gsm show 123-128 --game 2026-03-30_20-06 --json` | **YES** | `gsm show` now works with saved games! The fix from session 1 feedback landed. Raw JSON piped to `jq`/`python3` was essential for this investigation. |
| `just scry-ts gsm show 227-228 --game 2026-03-30_20-06 --json` | Yes | Cross-checked Detective→Rogue activation for consistency |

#### What worked well

1. **`gsm show --json` with saved games is the killer combo.** Being able to pipe raw GSM JSON to `python3 -c` for targeted field extraction (actions array filtered by instanceId, UserActionTaken annotations) made the protocol analysis fast and precise.

2. **The `actions` array in GSM diffs is the key artifact.** The modal activation question was answered entirely by examining the actions array — no special message type, no ChoiceReq. The answer was hiding in plain sight in the standard ActionsAvailableReq.

3. **`trace` for orientation, `gsm show --json` for deep inspection** is the right two-tool workflow. Trace tells you which GSMs to look at; gsm show gives you the raw protocol data.

#### What would have made it easier

1. **`scry actions --instanceId 296 --game <ref>`** — show all actions offered for a specific permanent across all GSMs. Currently requires manually checking each GSM's actions array. Would have immediately shown "both abilities always offered, disambiguated by abilityGrpId".

2. **`scry gsm show N --json | jq '.actions[] | select(.action.instanceId == 296)'`** works, but a built-in filter like `--actions-for 296` would save the python/jq pipeline.
