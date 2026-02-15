# Action Types Reference

Proto enum, card-type mapping, field requirements, and golden-derived invariants.

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

## Action Field Requirements (from golden data)

### Per ActionType

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
- `shouldStop=true, highlight=Hot` → urgent (counterspell available)
- `shouldStop=true, highlight=Cold` → available but not critical
- `shouldStop=false` → don't break auto-pass

### abilityGrpId

From card's `AbilityIds` column in Arena SQLite DB. For Cast: first ability entry. For Activate: the specific activated ability being offered. Our `CardDb.lookup(grpId).abilityIds` provides these.

### autoTapSolution

Server-provided mana payment recommendation for one-click casting. Proto:

```
AutoTapSolution {
    repeated AutoTapAction autoTapActions = 1;     // which lands to tap
    repeated ManaPaymentCondition manaPaymentConditions = 2;
    repeated ManaColor selectedManaColors = 3;     // colors produced
    repeated AutoTapManaPayment manaPayments = 4;  // mana payments
}

AutoTapAction {
    uint32 instanceId = 1;     // permanent to tap
    uint32 abilityGrpId = 2;   // which mana ability
    uint32 manaId = 3;
    ManaPaymentOption manaPaymentOption = 4;
}
```

Without autoTapSolution, client falls back to manual mana tapping via ActivateMana actions. With it, client can one-click cast.

## Golden Invariants

### Action locations

Actions appear in two places per GRE bundle:
1. **GameStateMessage.actions** — wrapped in `ActionInfo { actionId, seatId, action {} }`
2. **ActionsAvailableReq.actions** — flat `Action {}` (interactive prompt)

### Observed action types by location (arena-* goldens)

| ActionType | In GSM embedded | In ActionsAvailableReq |
|---|---|---|
| `ActivateMana` | yes | yes |
| `Activate` | yes (deck-dependent) | yes (deck-dependent, full-game recordings) |
| `Cast` | yes | yes |
| `CastLeftRoom` | yes (deck-dependent) | yes (deck-dependent) |
| `CastRightRoom` | yes (deck-dependent) | yes (deck-dependent) |
| `FloatMana` | not observed* | yes |
| `Pass` | not observed** | yes (always) |
| `Play` | yes | yes |

\* FloatMana absence from GSM is likely recording artifact (player never manually tapped to float).
\** Pass absence from GSM is consistent across all arena-* recordings. Our self-generated goldens include Pass in GSM — worth investigating whether Arena truly omits it or our recordings just didn't capture it.

### Deck-dependent types

Activate appears in goldens with non-mana activated abilities on board (arena-combat-simple, arena-combat-damage, arena-cast-spell-player). Absent from simpler board states. NOT a load-bearing invariant for shape comparison.

CastLeftRoom/CastRightRoom appear only in decks with room cards (arena-targeted-spell, arena-edictal-pass).

## Implementation Status

| Feature | Status | Notes |
|---|---|---|
| ActivateMana generation | done | Untapped mana sources on battlefield |
| Cast with abilityGrpId | done | First ability from CardDb |
| Cast with manaCost | done | From CardDb.manaCost |
| shouldStop on Cast/Play | done | Set to true |
| Activate generation | TODO | Non-mana activated abilities |
| autoTapSolution on Cast | TODO | Mana payment recommendation |
| FloatMana | TODO | After mana activation |
| Multi-face variants | future | CastMdfc, PlayMdfc, CastAdventure, etc. |
