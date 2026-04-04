---
summary: "Proto field layout, ActionType enum (24 values), per-type field requirements, and golden-derived invariants for GRE actions."
read_when:
  - "implementing or debugging action generation (Cast, Activate, Play, etc.)"
  - "understanding how actions appear in GameStateMessage vs ActionsAvailableReq"
  - "adding support for a new ActionType"
---
# Action Reference

Proto field layout, ActionType enum, per-type field requirements, and golden-derived invariants.

## Action Locations

Actions appear in **two** places per GRE bundle:

1. **GameStateMessage.actions** — wrapped in `ActionInfo { actionId, seatId, action {} }`
2. **ActionsAvailableReq.actions** — flat `Action {}` (no wrapper)

Both contain identical `Action` payloads. The `actionId` in wrapper is sequential (1, 2, 3...).

## ActionType Enum (24 values)

| ActionType | # | When generated | Card/ability type |
|---|---|---|---|
| `Cast` | 1 | Spell in hand is castable | Creature, instant, sorcery, artifact, enchantment, PW |
| `Activate` | 2 | Non-mana activated ability available | Creature/artifact/enchantment abilities, PW loyalty |
| `Play` | 3 | Land in hand, haven't played this turn | Land (front face) |
| `ActivateMana` | 4 | Untapped permanent with mana ability | Lands, mana dorks, mana rocks |
| `Pass` | 5 | Always (priority pass) | — |
| `ResolutionCost` | 9 | Paying cost during resolution | — |
| `CastLeft` | 10 | Left half of split card castable | Split cards (Fire // Ice) |
| `CastRight` | 11 | Right half of split card castable | Split cards |
| `MakePayment` | 12 | Generic payment prompt | — |
| `CombatCost` | 14 | Combat-related cost | Ninjutsu, etc. |
| `OpeningHandAction` | 15 | Opening hand trigger | Leylines |
| `CastAdventure` | 16 | Adventure half castable | Adventure cards |
| `FloatMana` | 17 | Mana available to float explicitly | After ActivateMana resolves |
| `CastMdfc` | 18 | MDFC back face (spell) castable | Modal DFC spell face |
| `PlayMdfc` | 19 | MDFC back face (land) playable | Modal DFC land face |
| `CastPrototype` | 21 | Prototype cost available | Prototype cards |
| `CastLeftRoom` | 22 | Left room castable | Room cards |
| `CastRightRoom` | 23 | Right room castable | Room cards |
| `CastOmen` | 24 | Foretold card castable | Foretell/omen |

Multi-face variants map 1:1 with `GameObjectType` (SplitLeft→CastLeft, Mdfcback→CastMdfc, etc.).

## Field Requirements (from golden data)

| Field | Cast | Play | ActivateMana | Activate | Pass |
|---|---|---|---|---|---|
| `grpId` | yes | yes | yes | yes | no |
| `instanceId` | yes | yes | yes | yes | no |
| `abilityGrpId` | yes (first from DB) | no | no | yes | no |
| `manaCost` | yes (from DB) | no | no | varies | no |
| `shouldStop` | true | true | no (false) | varies | no |
| `autoTapSolution` | yes (payment plan) | no | no | no | no |
| `highlight` | optional | no | no | no | no |

### shouldStop

Breaks client auto-pass when `true`. Set on Cast and Play actions. NOT set on ActivateMana (mana abilities don't interrupt auto-pass). Pass never has it.

Interaction with `highlight` field:
- `shouldStop=true, highlight=Hot` — urgent (counterspell available)
- `shouldStop=true, highlight=Cold` — available but not critical
- `shouldStop=false` — don't break auto-pass

### abilityGrpId

From card's `AbilityIds` column in Arena SQLite DB. For Cast: first ability entry. For Activate: the specific activated ability being offered. Our `CardDb.lookup(grpId).abilityIds` provides these.

### autoTapSolution

Server-provided mana payment recommendation for one-click casting. Proto:

```
AutoTapSolution {
    repeated AutoTapAction autoTapActions = 1;     // which lands to tap
    repeated ManaPaymentCondition manaPaymentConditions = 2;
    repeated ManaColor selectedManaColors = 3;     // colors produced
    repeated ManaPaymentOption manaPayments = 4;   // mana payments
}

AutoTapAction {
    uint32 instanceId = 1;     // permanent to tap
    uint32 abilityGrpId = 2;   // which mana ability
    uint32 manaId = 3;
    ManaPaymentOption manaPaymentOption = 4;
}
```

Without autoTapSolution, client falls back to manual mana tapping via ActivateMana actions. With it, client can one-click cast.

## Action Examples

### Play (play land)

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

**`abilityGrpId`** = first entry in the card's `AbilityIds` column (SQLite). For Llanowar Elves (grpId 75570), this is 1005 ("{T}: Add {G}").

**`manaCost`** = parsed from `OldSchoolManaText` column. One `ManaRequirement` per color component.

**No `facetId`** — Cast actions never include `facetId` (proto field 4).

### Pass

```protobuf
action {
  actionType: Pass
}
```

No other fields.

## GameStateMessage Structure

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

### Zone Layout (fixed IDs)

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

### GameObject Fields (creature example)

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
