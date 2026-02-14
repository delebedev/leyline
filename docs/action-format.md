# Action Format Reference

Field-by-field reference from a Sparky game session.

## Action locations

Actions appear in **two** places per GRE bundle:

1. **GameStateMessage.actions** — wrapped in `ActionInfo { actionId, seatId, action {} }`
2. **ActionsAvailableReq.actions** — flat `Action {}` (no wrapper)

Both contain identical `Action` payloads. The `actionId` in wrapper is sequential (1, 2, 3...).

## Action types

### Play_add3 (play land)

```protobuf
action {
  actionType: Play_add3
  grpId: 91309        # card art ID from ArenaCardDb
  instanceId: 100     # runtime object instance
  shouldStop: true
}
```

No `abilityGrpId`, no `manaCost`, no `facetId`.

### Cast (cast spell)

```protobuf
action {
  actionType: Cast
  grpId: 75570        # card art ID
  instanceId: 103     # runtime object instance
  abilityGrpId: 1005  # first ability from ArenaCardDb.abilityIds
  manaCost {
    color: Green_afc9
    count: 1
  }
  shouldStop: true
}
```

**`abilityGrpId`** = first entry in the card's `AbilityIds` column (SQLite). For Llanowar Elves (grpId 75570), this is 1005 ("{T}: Add {G}"). The value identifies the card's primary ability — the client uses it to resolve which spell/ability is being cast.

**`manaCost`** = parsed from `OldSchoolManaText` column. One `ManaRequirement` per color component.

**No `facetId`** — Cast actions in the captures never include `facetId` (proto field 4).

### Pass

```protobuf
action {
  actionType: Pass
}
```

No other fields.

## GameStateMessage structure (Full, Main1)

```
GameStateMessage {
  type: Full
  gameStateId: N
  gameInfo { matchID, gameNumber, stage, type, variant, matchState, matchWinCondition, mulliganType }
  teams[2] { id, playerIds, status }
  players[2] { lifeTotal, systemSeatNumber, status, maxHandSize, teamId, controllerType, startingLifeTotal }
  turnInfo { phase, step?, turnNumber, activePlayer, priorityPlayer, decisionPlayer }
  zones[] { zoneId, type, visibility, ownerSeatId?, objectInstanceIds[] }
  gameObjects[] { instanceId, grpId, type, zoneId, visibility, ownerSeatId, controllerSeatId, ... }
  update: SendAndRecord
  actions[] { actionId, seatId, action { ... } }
}
```

### Zone layout (fixed IDs)

| zoneId | type | visibility | owner |
|--------|------|-----------|-------|
| 18 | Revealed | Public | seat 1 |
| 19 | Revealed | Public | seat 2 |
| 24 | Suppressed | Hidden | — |
| 25 | Pending | Hidden | — |
| 26 | Command | Public | — |
| 27 | Stack | Public | — |
| 28 | Battlefield | Public | — |
| 29 | Exile | Public | — |
| 30 | Limbo | Hidden | — |
| 31 | Hand | Private | seat 1 |
| 32 | Library | Hidden | seat 1 |
| 33 | Graveyard | Public | seat 1 |
| 34 | Sideboard | Hidden | seat 1 |
| 35 | Hand | Private | seat 2 |
| 36 | Library | Hidden | seat 2 |
| 37 | Graveyard | Public | seat 2 |
| 38 | Sideboard | Hidden | seat 2 |

### GameObject fields (creature example)

```protobuf
gameObjects {
  instanceId: 103
  grpId: 75570
  type: Card
  zoneId: 31
  visibility: Private
  ownerSeatId: 1
  controllerSeatId: 1
  cardTypes: Creature
  subtypes: Elf
  subtypes: Druid
  color: Green_a3b0
  power { value: 1 }
  toughness { value: 1 }
  name: 2688            # localization string ID
  overlayGrpId: 75570
  uniqueAbilities {
    id: 50              # sequential per object (50, 51, 52...)
    grpId: 1005         # ability lookup ID
  }
}
```

## Game-start bundle (4 GRE messages)

```
GRE 1: GameStateMessage_695e  gsId=4  Diff, Beginning/Upkeep, SendHiFi
GRE 2: GameStateMessage_695e  gsId=5  Diff, empty (Send) — priority-pass marker
GRE 3: GameStateMessage_695e  gsId=6  Full, Main1, SendAndRecord, zones+objects+actions
GRE 4: ActionsAvailableReq_695e  gsId=6  prompt { promptId: 2 }  actions (flat)
```

gsIds are sequential. msgIds are sequential. GRE 3 and GRE 4 share the same gsId.

## Post-land-play bundle (2 GRE messages)

After playing a land (file 013):
```
GRE 1: GameStateMessage_695e  gsId=7  Full, Main1, SendAndRecord
        — land moved to Battlefield, hand updated, new actions (Cast now available)
GRE 2: ActionsAvailableReq_695e  gsId=7  prompt { promptId: 2 }
```

## Differences from our implementation

| Aspect | Real Arena | Our code | Status |
|--------|-----------|----------|--------|
| Cast: `abilityGrpId` | Present (first ability) | Now included | Fixed |
| Cast: `facetId` | Absent | Was wrongly included | Fixed |
| Cast: `manaCost` | Present | Included | OK |
| Play_add3 format | `grpId + instanceId + shouldStop` | Same | OK |
| GameStateMessage.actions | Wrapped in `ActionInfo` | Same | OK |
| ActionsAvailableReq.actions | Flat `Action` | Same | OK |
| Double-diff | 2 diffs per phase transition | Implemented | OK |
| Prompt id | 2 for priority | Set to 2 | OK |
