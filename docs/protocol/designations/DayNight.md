---
summary: "Day and Night designations: the game-scoped state machine, its persistent spell-count row, and the flip transition."
entity: designation/DayNight
---
# Day and Night

Day and Night are mutually exclusive designations of the game once either has
been established. The persistent row for either state carries the active
player's running spell count for the current turn.

## Rules grounding

CR 731.1 defines the initial neither state and the transition into Day or Night.
Once established, exactly one of the two states remains. CR 731.2 checks the
previous turn's spell activity during the untap step to decide whether the state
changes.

Those rules define the state machine. The rows below say how the state travels in
messages.

## State

| Surface | Role |
|---|---|
| `GainDesignation` | The incoming state was established in this GSM. |
| `LoseDesignation` | The outgoing state ended at a flip. |
| `Designation` | The persistent state; carries `ActivePlayerSpellCount`. |
| Persistent-row deletion | Retires the outgoing state's row at a flip. |
| `ActivePlayerSpellCount` | The active player's running spell total for the current turn. |

These rows are game-scoped: `affectedIds` is `[0]` and there is no `affectorId`.
The gain row carries the incoming state's identity and nothing else; the
persistent row owns the spell count.

The neither state is wire-silent. A game with no Day/Night persistent row is in
the neither state, and no third designation marks it.

## Lifecycle

1. A first transition is published in the GSM that resolves the permanent
   establishing Day or Night: the gain row and the first persistent row appear
   in that GSM.
2. The persistent row carries the active player's current-turn spell count. The
   count resets at the start of a turn and rises as that player casts spells.
3. At a flip, the outgoing state emits its loss row and its persistent row is
   deleted; the incoming state emits its gain row, and the incoming persistent
   row carries the new turn's count.
4. The state persists until the game ends.

## Sibling shapes

Day and Night are one state machine, so one page owns both types. The
transformation of daybound and nightbound permanents is an adjacent card-object
surface and not part of this designation's contract.

## Contract

- <a id="DayNight.gain-first"></a>**DayNight.gain-first.** *(provisional)* The
  first transition into Day or Night `MUST` carry a `GainDesignation` with
  `DesignationType` `10` or `11`, `affectedIds` `[0]` and no `affectorId`, in the
  GSM whose resolution establishes the state.
- <a id="DayNight.persistent-apsc"></a>**DayNight.persistent-apsc.** While Day or
  Night is established, its persistent `Designation` `MUST` carry
  `DesignationType` `10` or `11` and `ActivePlayerSpellCount` equal to the
  active player's spells cast in the current turn.
- <a id="DayNight.flip-pair"></a>**DayNight.flip-pair.** *(provisional)* A flip
  `MUST` carry a `LoseDesignation` for the outgoing state and delete its
  persistent row, then carry the incoming state's `GainDesignation` and a
  persistent row for the incoming state with the new turn's count.
- <a id="DayNight.mutual-exclusion"></a>**DayNight.mutual-exclusion.** One GSM
  `MUST NOT` carry persistent Day and Night rows at the same time.

## Open points

- Is the persistent row's annotation ID stable across spell-count updates?
- Can a non-daybound effect establish the state, and does it use the same rows?
