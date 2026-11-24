---
summary: "UserActionTaken annotation: a seat performed an action such as casting, playing a land or activating an ability; actionType values, the acted-on identity and abilityGrpId."
entity: annotation/UserActionTaken
---
# UserActionTaken

## Meaning

`UserActionTaken` attributes a state change to a player's action: casting a
spell, playing a land, activating an ability or activating a mana ability. It
names the acting seat, the object the action produced or used, and the kind of
action.

## Variants

One transient row per action. `actionType` is the discriminator and carries the
`ActionType` enum's integer value:

| `actionType` | Action | `affectedIds` | `abilityGrpId` |
|---:|---|---|---|
| `1` | `Cast` | The spell on the stack | `0` for a spell cast for its mana cost |
| `2` | `Activate` | The ability instance on the stack | The activated ability's grpId |
| `3` | `Play` | The land on the battlefield | `0` |
| `4` | `ActivateMana` | The mana ability instance | The mana ability's grpId |

Other `ActionType` values, such as `Special`, `PlayMdfc` (`19`) for a modal
double-faced card's back face, and the face-specific casts `CastLeft`,
`CastRight`, `CastAdventure`, `CastLeftRoom`, `CastRightRoom` and
`CastOmen`, use the same shape. An alternative cast adds `alternativeGrpId`, the
grpId of the cost or permission used.

## Fields

| Field | Role |
|---|---|
| `affectorId` | The acting seat. |
| `affectedIds` | The object the action produced or used, as a one-element list. |
| `actionType` | The `ActionType` integer of the action. |
| `abilityGrpId` | The ability used, or `0` when no ability identifies the action. |
| `alternativeGrpId` | Present only for an alternative cast; the alternative cost or permission. |

## Identity model

`affectedIds` names the result of the action under its new ID: the stack ID of
a cast spell, the battlefield ID of a played land, the instance ID created by
the activation's [AbilityInstanceCreated](AbilityInstanceCreated.md) for an
ability. It never names the hand ID the card had before the action.

## Lifecycle

- **Play a land.** `ObjectIdChanged`, `ZoneTransfer` with `PlayLand`, then
  `UserActionTaken` with `actionType` `3`, in one GSM.
- **Activate a mana ability.** Inside the mana-ability sequence, after the tap
  and before `ManaPaid`.
- **Cast a spell.** The `actionType` `1` row appears in the GSM where the
  spell's costs are paid. For a spell with targets that is after the targets
  are submitted, not in the GSM that first moves the card to the stack.

## Contract

- <a id="UserActionTaken.seat-affector"></a>**UserActionTaken.seat-affector.**
  A `UserActionTaken` row `MUST` carry the acting seat as `affectorId`.
- <a id="UserActionTaken.single-affected"></a>**UserActionTaken.single-affected.**
  A row `MUST` carry exactly one ID in `affectedIds`.
- <a id="UserActionTaken.play-land"></a>**UserActionTaken.play-land.**
  A land played from hand by its front face `MUST` produce a row with
  `actionType` `3`, `abilityGrpId` `0` and `affectedIds` equal to the land's
  battlefield ID.
- <a id="UserActionTaken.activate-mana"></a>**UserActionTaken.activate-mana.**
  A mana ability activation `MUST` produce a row with `actionType` `4`,
  `abilityGrpId` equal to the mana ability's grpId and `affectedIds` equal to
  the instance named by the activation's `AbilityInstanceCreated`.
- <a id="UserActionTaken.activate"></a>**UserActionTaken.activate.**
  *(provisional)* An activated ability put on the stack `MUST` produce a row
  with `actionType` `2`, `abilityGrpId` equal to the ability's grpId and
  `affectedIds` equal to the ability's stack instance ID.
- <a id="UserActionTaken.cast"></a>**UserActionTaken.cast.**
  *(provisional)* A spell cast from hand for its mana cost, other than by a
  face-specific cast, `MUST` produce a row with `actionType` `1`,
  `abilityGrpId` `0` and `affectedIds` equal to the spell's stack ID.

## Open points

- What does a nonzero `abilityGrpId` on a `Cast` row identify?
- Which `ActionType` values appear on this row for special actions and
  face-specific casts, and what does each name in `affectedIds`?
- A Reconfigure activation carried an `abilityGrpId` that differs from its
  stack object's `grpId`. Which activations do that?
