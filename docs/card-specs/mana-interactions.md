# Mana Interactions — Meta Card Spec

Protocol layer research for mana production, floating, undo, color choice, and mana pool state.
Source game: `2026-04-02_22-32-36` (Sparky bot match, T8 Main1 primary sequence).

## Forge Mana System Overview

### ManaPool (forge-game `mana/ManaPool.java`)

- `ArrayListMultimap<Byte, Mana> floatingMana` — the pool, keyed by color byte
- `addMana(Mana)` — adds to pool, fires `GameEventManaPool(Added)`
- `removeMana(Mana)` — removes from pool, fires `GameEventManaPool(Removed)`
- `clearPool(isEndOfPhase)` — drains at phase boundary (respects persistent/combat mana, replacement effects)
- `resetPool()` — hard clear (snapshot restore only)
- Pool supports `Iterable<Mana>` — each `Mana` carries source card, ability, player, color byte

### Mana Abilities in Forge

- `Card.manaAbilities` — list of `SpellAbility` with `isManaAbility()` true
- `AbilityManaPart` (`sa.manaPart`) — holds production info:
  - `isComboMana` — true for dual/multi-color lands (Temple Garden, etc.)
  - `getComboColors(sa)` — returns space-separated string like `"W G"`
  - `origProduced` — raw production string (`"G"`, `"Combo W G"`, `"Combo Chosen"`)
- Cost payment: `CostPayment.payComputerCosts()` handles tap cost, then `sa.resolve()` produces mana
- Color choice for "any color" abilities: `resolve()` triggers `chooseColor()` callback on the player controller

### Undo / Snapshot Restore

Forge supports game state snapshots via `GameSnapshot`:
- `makeCopy()` — deep-copies entire game state
- `restoreGameState(currentGame)` — reverts to snapshot, fires `GameEventSnapshotRestored`
- `resetPool()` clears mana pool during restore

In leyline, the `WebPlayerController.executeActivateMana()` path does NOT use snapshots — it directly resolves mana abilities. Undo is a client-side concept that the real Arena server handles.

## Protocol Observations

### 1. Basic Land Tap (Float Mana)

**Example:** Forest (iid=293, grp=98595) tapped at gs=181

The server sends a Diff GSM containing:

**Annotations (3):**
1. `AbilityInstanceCreated` — affector=293 (Forest), affected=[426], source_zone=28 (Battlefield)
2. `TappedUntappedPermanent` — affector=426 (ability), affected=[293], tapped=1
3. `UserActionTaken` — affector=1 (player seat), affected=[426], actionType=4 (ActivateMana), abilityGrpId=1005

**Objects (2):**
- Card iid=293 updated: `isTapped=true`
- Ability iid=426: grp=1005, type=Ability, zone=Pending (25), parentId=293

**Player state:**
- `manaPool: [{ manaId: 412, color: ManaColor_Green, srcInstanceId: 293, abilityGrpId: 1005, count: 1 }]`

**Key insight:** The ability object appears in the Pending zone (25). It is NOT on the Stack. Mana abilities don't use the stack in MTG rules, but the protocol still creates a transient ability instance for animation purposes.

### 2. Dual Land Tap (Temple Garden)

**Example:** Temple Garden (iid=348, grp=68739) tapped at gs=177

Identical structure to basic land, but:
- grp=1005 when tapping for Green, grp=1001 when tapping for White
- The **client chooses which color** by sending the `abilityGrpId` in the PerformActionResp

**In the ActionsAvailableReq**, Temple Garden generates TWO separate ActivateMana actions:
```
{ actionType: ActivateMana, instanceId: 348, abilityGrpId: 1001 }  // White
{ actionType: ActivateMana, instanceId: 348, abilityGrpId: 1005 }  // Green
```

**ManaPool entry after tapping for Green:**
```json
{ "manaId": 412, "color": "ManaColor_Green", "srcInstanceId": 348, "abilityGrpId": 1005, "count": 1 }
```

**ColorProduction annotation** (persistent, set on land ETB):
```json
{ "type": "ColorProduction", "affectorId": 348, "details": { "colors": [1, 5] } }
```
Colors are Arena ManaColor ordinals: 1=White, 5=Green.

### 3. Undo (Z Key) — GameStateUpdate_Undo

**The undo is entirely server-side.** The server sends a Full GSM with `update: GameStateUpdate_Undo`.

**Observed undo sequence (T8 Main1):**

| gsId | type | update | prevGameStateId | Description |
|------|------|--------|-----------------|-------------|
| 176 | Diff | SendAndRecord | 175 | Priority with available actions |
| 177 | Diff | SendAndRecord | 176 | Temple Garden tapped (Green) |
| 178 | **Full** | **Undo** | **176** | Undo → state reverts to gs=176, manaPool=[] |
| 179 | Diff | SendAndRecord | 178 | Temple Garden tapped again (White this time) |
| 180 | **Full** | **Undo** | **178** | Undo again → reverts to gs=178 base state |
| 181 | Diff | SendAndRecord | 180 | Forest tapped |
| 182 | Diff | SendAndRecord | 181 | Temple Garden tapped (Green) |
| 183 | Diff | SendHiFi | 182 | CastSpell (mana consumed) |

**Undo semantics:**
- `update: GameStateUpdate_Undo` signals "revert to `prevGameStateId`"
- The Full GSM contains the complete restored state (all objects, zones, players)
- `prevGameStateId` points to the last `SendAndRecord` GSM before the undone action
- Mana pool is cleared in the restored state
- Lands are untapped in the restored state
- Ability objects (Pending zone) are removed
- Multiple undos chain: each new Undo references the previous restored Full GSM

**Client behavior:** Pressing Z sends a message (likely `CancelActionReq` or a dedicated undo message type) and the server responds with the Full Undo GSM.

### 4. Rootrider Faun — Activated Mana Ability with Cost + Color Wheel

**Card:** Rootrider Faun (iid=349, grp=86890) — `{1}, {T}: Add one mana of any color.`
**abilityGrpId:** 1962

**gs=187 annotation sequence (8 annotations):**

1. `AbilityInstanceCreated` — affector=349 (Faun), affected=[431], source_zone=28
2. `TappedUntappedPermanent` — affector=431, affected=[349], tapped=1
3. `AbilityInstanceCreated` — affector=430 (Fountainport), affected=[432], source_zone=28
4. `TappedUntappedPermanent` — affector=432, affected=[430], tapped=1
5. `UserActionTaken` — affector=1, affected=[432], actionType=4, abilityGrpId=1152 (Fountainport's mana)
6. `ManaPaid` — affector=430 (Fountainport), affected=[431 (Faun ability)], id=471, color=12
7. `AbilityInstanceDeleted` — affector=430, affected=[432] (Fountainport ability consumed)
8. `UserActionTaken` — affector=1, affected=[431], actionType=4, abilityGrpId=1962

**Key observations:**
- The {1} cost was paid by tapping Fountainport (a separate mana source)
- The cost payment generates its own AbilityInstanceCreated/Deleted bracket for Fountainport
- `ManaPaid` links Fountainport (affector=430) to the Faun's ability (affected=431)
- ManaPaid color=12 — this is ManaColor_Colorless ordinal for the generic cost payment
- The Faun's ability (431) stays in Pending zone (not deleted — it's the "add any color" part)

**ManaPool after resolution:**
```json
{ "manaId": 472, "color": "ManaColor_Red", "srcInstanceId": 349, "abilityGrpId": 1962, "count": 1 }
```

**Color choice mechanism:** The color wheel prompt is NOT visible as a SelectNReq or PromptReq in the protocol. The client likely handles it locally — the server sends the mana ability with "any color" metadata, and the client picks the color before sending the PerformActionResp. The ActivateMana action in the ActionsAvailableReq for Rootrider Faun has:
```json
{ "actionType": "ActivateMana", "instanceId": 349, "abilityGrpId": 1962,
  "manaCost": [{ "color": ["ManaColor_Generic"], "count": 1, "abilityGrpId": 1962 }] }
```

The `manaCost` field on the ActivateMana action indicates the ability has a mana cost (unlike free tap abilities). The color selection for "any color" production may be embedded in the PerformActionResp or may be server-auto-resolved.

### 5. Rootrider Faun Undo

gs=188 is `Full / Undo / prev=186` — identical undo mechanism as land taps. The mana pool is cleared, the Faun and Fountainport are untapped, and the ability objects disappear.

### 6. Mana Consumed During Spell Cast

**gs=183 (CastSpell for Flourishing Bloom-Kin):**

```
ObjectIdChanged: 167 → 428
ZoneTransfer: Hand → Stack, category=CastSpell
ManaPaid: affector=293 (Forest), affected=[428], id=412, color=5 (Green)
AbilityInstanceDeleted: affector=293, affected=[426]
ManaPaid: affector=348 (Temple Garden), affected=[428], id=422, color=5 (Green)
AbilityInstanceDeleted: affector=348, affected=[427]
UserActionTaken: affector=1, affected=[428], actionType=1 (Cast)
```

**Key patterns:**
- Each land's mana ability bracket is: `AbilityInstanceCreated → TappedUntapped → ... → ManaPaid → AbilityInstanceDeleted`
- The `AbilityInstanceCreated` happened in earlier GSMs (181, 182) during manual float
- The `ManaPaid + AbilityInstanceDeleted` happen in the CastSpell GSM
- `ManaPaid.id` matches the `manaId` from the manaPool entry
- After cast, `manaPool = []` (all floating mana consumed)
- The `update` field is `SendHiFi` (not `SendAndRecord`) — cast GSMs are non-undoable

### 7. Mana Pool in PlayerInfo

**ManaInfo fields per pool entry:**

| Field | Type | Description |
|-------|------|-------------|
| `manaId` | int | Unique ID within the pool, matches `ManaPaid.id` detail key |
| `color` | ManaColor | The color of mana (White=1, Blue=2, Black=3, Red=4, Green=5, Colorless=12, Generic=0) |
| `srcInstanceId` | int | instanceId of the permanent that produced this mana |
| `abilityGrpId` | int | Which ability produced it (distinguishes dual land taps) |
| `count` | int | Always 1 in observed data |

**ManaColor ordinals observed:**

| Ordinal | ManaColor | Example Source |
|---------|-----------|---------------|
| 1 | White | Temple Garden (abilityGrpId=1001) |
| 3 | Black | Swamp (abilityGrpId=1003) |
| 5 | Green | Forest (abilityGrpId=1005), Temple Garden (abilityGrpId=1005) |
| 4 | Red | Rootrider Faun (abilityGrpId=1962, "any color" → chose Red) |
| 12 | Colorless | ManaPaid for generic cost payment |

### 8. ActivateMana Action Shape

**Basic land (free tap):**
```json
{ "actionType": "ActionType_Activate_Mana", "instanceId": 293, "abilityGrpId": 1005 }
```

**Dual land (two actions, one per color):**
```json
{ "actionType": "ActionType_Activate_Mana", "instanceId": 348, "abilityGrpId": 1001 }
{ "actionType": "ActionType_Activate_Mana", "instanceId": 348, "abilityGrpId": 1005 }
```

**Creature with mana cost (Rootrider Faun):**
```json
{ "actionType": "ActionType_Activate_Mana", "instanceId": 349, "abilityGrpId": 1962,
  "manaCost": [{ "color": ["ManaColor_Generic"], "count": 1, "abilityGrpId": 1962 }] }
```

### 9. FloatMana Action

The `FloatMana` action appears in ActionsAvailableReq alongside Pass. It has no instanceId or payload — it's a pure action type signal that tells the client "manual mana floating is available":
```json
{ "actionType": "ActionType_FloatMana" }
```

This action type (ordinal 17) is NOT sent by the client in a PerformActionResp. The client sends `ActivateMana` responses instead. FloatMana is purely a client UI hint.

### 10. GameStateUpdate Types for Mana Flow

| Update | Meaning | Undoable? |
|--------|---------|-----------|
| `SendAndRecord` | Standard state change, saved for undo | Yes |
| `Undo` | Revert to prevGameStateId (always Full GSM) | N/A |
| `SendHiFi` | Spell resolution, combat — high-fidelity animation | No |

## Implementation Gaps

### Critical

1. **Dual land: only one ActivateMana action per permanent.**
   Current `buildActivateManaAction()` takes `card.manaAbilities.first()` and emits one action. Real server emits one per distinct mana ability. Temple Garden needs TWO actions (grpId 1001 + 1005).
   - Fix: iterate `card.manaAbilities`, build one action per ability with correct abilityGrpId.

2. **Mana pool not sent in PlayerInfo.**
   `PlayerMapper.buildPlayerInfo()` has the mana pool code commented out with a TODO about cost rendering. The real server ALWAYS includes mana pool in GSMs after a mana float. Without this, the client has no way to show floating mana pips.
   - Fix: re-enable mana pool serialization. The "confusing 0-cost display" issue is likely because the client auto-subtracts floating mana from displayed costs — this is correct behavior, not a bug.

3. **Undo (GameStateUpdate_Undo) not implemented.**
   Leyline has `CancelActionReq` handling for targeting cancellation, but no mechanism for mana undo. The real server sends `GameStateUpdate_Undo` with a Full GSM reverting to the previous `SendAndRecord` state.
   - This requires Forge `GameSnapshot` integration or equivalent state tracking.
   - Undo chain: must track which GSMs are `SendAndRecord` to know the revert target.

### Important

4. **"Any color" mana ability — color selection mechanism.**
   Rootrider Faun's color wheel prompt doesn't appear as a GRE prompt type. The selection may be embedded in the client's action response or may be a client-side UI with server auto-resolution. Need more recordings to clarify.
   - Current leyline: `executeActivateMana()` calls `resolve()` which triggers `chooseColor()` callback via the interactive prompt bridge. This works but may not match the real protocol's color selection flow.

5. **ManaPaid color=12 for generic cost payment.**
   The `ManaPaid` annotation uses color=12 (Colorless) when paying generic costs. Leyline's current `ManaPaid` annotation builder may not distinguish generic-payment-color from produced-mana-color.

6. **Mana ability cost payment annotations.**
   Rootrider Faun's {1} cost creates a nested annotation bracket (Fountainport's tap/produce/delete inside the Faun's bracket). Current leyline may not handle this nested structure for paid mana abilities.

### Nice to Have

7. **ManaDetails annotation (type 49).**
   Not observed in this game. May appear for special mana types (snow, etc.). Currently MISSING in rosetta table.

8. **ActionType 17 (FloatMana) in actions.**
   Leyline sends FloatMana in ActionsAvailableReq (correct), but should confirm it's stripped from embedded GSM actions.

## Reference IDs

| Card | instanceId | grpId | abilityGrpIds | Notes |
|------|-----------|-------|---------------|-------|
| Forest | 293 | 98595 | 1005 (tap for G) | Basic land |
| Temple Garden | 348 | 68739 | 1001 (W), 1005 (G), 90846 (shock land ETB) | Dual land |
| Rootrider Faun | 349 | 86890 | 1005 (tap for G basic), 1962 (pay 1, any color) | Creature with paid mana ability |
| Fountainport | 430 | 91789 | 1152 (tap for C), 174173-174175 (activated abilities) | Land with non-mana abilities too |
| Swamp (opponent) | 286,346,353,367 | 75557 | 1003 (tap for B) | Basic land |

## Ability GrpId Reference

| grpId | Card text (from scry ability lookup) |
|-------|--------------------------------------|
| 1001 | `{T}: Add {W}` |
| 1003 | `{T}: Add {B}` |
| 1005 | `{T}: Add {G}` |
| 1152 | `{T}: Add {C}` |
| 1962 | `{1}, {T}: Add one mana of any color` |

## Mana Color Ordinals (Arena Protocol)

| Ordinal | Color | Notes |
|---------|-------|-------|
| 0 | Generic | Used in manaCost, not in manaPool |
| 1 | White | W |
| 2 | Blue | U |
| 3 | Black | B |
| 4 | Red | R |
| 5 | Green | G |
| 6 | X | Variable cost |
| 12 | Colorless | C — used in ManaPaid for generic payments |
