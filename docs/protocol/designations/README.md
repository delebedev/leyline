---
summary: "Designation family overview: gain, persistent and loss rails, identity domains, detail keys, and one row per DesignationType."
read_when:
  - "emitting or reading a Designation, GainDesignation or LoseDesignation row"
  - "finding the page for a DesignationType"
---
# Designations

A designation is durable state attached to the game, a seat or a card. It is
carried on the designation rails rather than by an ability that fires, and other
rows interpret it: a suspecting effect publishes the behaviour rows that menace
and the blocking restriction need.

A mechanic can own an activation or resolution lifecycle and a separate
designation surface. The persistent row owns the durable state; gain and loss
rows mark transitions where the protocol exposes them.

## Rails

| Rail | Row | Role |
|---|---|---|
| `annotations` | `GainDesignation` | The state was gained in this GSM. |
| `annotations` | `LoseDesignation` | The state was lost in this GSM. |
| `persistentAnnotations` | `Designation` | The state holds, and is restated while it holds. |

Every designation row carries `DesignationType` in its details. The identity
domain decides which IDs the row names:

- A game-scoped row names `affectedIds` `[0]` and no `affectorId`.
- A seat-scoped row names the seat in `affectorId` and `affectedIds`.
- A card-scoped row names the card instance. `affectorId` and the first of
  `affectedIds` are the same instance unless a resolving ability is the source
  of the change, and some types add further affected IDs beside it.

Gain and loss are not symmetric. A state can end because the object carrying it
retires, with no loss row; some states are only ever published as a persistent
row. Each page says which transitions it has.

## Detail keys

`DesignationType` is required on every row. Types whose state needs more carry
one extra key, such as `ActivePlayerSpellCount` for Day and Night,
`PreparedCopyZcid` for Prepared, `ControllerId` for AnimatedLand and `value` for
Player Speed; Commander carries four identity and tax keys. A type with no extra
key carries none, and the state is read from the row's type alone.

## Types

| Value | Designation | Domain | Role |
|---:|---|---|---|
| 1 | Commander | seat and card | A commander's identity and command-zone tax: colour identity, commander `grpId` and cumulative cost increase, on both the seat and the card. |
| 10 | [Day](DayNight.md) | game | Day is established; the persistent row carries the active player's current-turn spell count. |
| 11 | [Night](DayNight.md) | game | Night is established, in the same shape as Day. |
| 15 | `Solved` | card | A Case permanent whose solve condition has resolved. |
| 16 | `Suspected` | card | A permanent that has menace and cannot block. |
| 17 | [Saddled](Saddled.md) | card | A Mount saddled this turn. |
| 18 | [Plotted](Plotted.md) | card | A card exiled by plot and castable later without paying its mana cost. |
| 19 | `LeftUnlocked` | card | The left door of a Room is unlocked; the door action is in [Rooms](../actions/Rooms.md). |
| 20 | `RightUnlocked` | card | The right door of a Room is unlocked; the door action is in [Rooms](../actions/Rooms.md). |
| 21 | [PlayerSpeed](PlayerSpeed.md) | seat | A player's current speed, from 1 to 4. |
| 23 | [AnimatedLand](AnimatedLand.md) | card | A land currently animated as a creature. |
| 24 | [Prepared](Prepared.md) | card | A prepared permanent, linked to a prepare-spell copy in exile. |
| 26 | Unearthed | card | A permanent created on the battlefield by an effect rather than cast, carrying its source card and the kind of creation. |

A type with no established meaning is not listed until its meaning is grounded.
