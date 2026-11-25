---
summary: "Spree mechanic: one or more modes chosen while casting, each with its own additional cost, carried by a single modal CastingTimeOptionsReq row."
entity: mechanic/Spree
---
# Spree

Spree is a modal spell whose caster chooses one or more modes and pays each
chosen mode's additional cost on top of the spell's mana cost. The mechanic
uses the modes family of
[CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md): one `Modal` row
whose options carry their own costs. The rest is the ordinary spell
lifecycle.

## Rules grounding

CR 702.172a defines spree: the spell's controller chooses one or more modes
and pays the additional cost of each chosen mode as they cast it.

## Surfaces

| Surface | Entity pages |
|---|---|
| Mode and cost choice | [CastingTimeOptionsReq](../prompts/CastingTimeOptionsReq.md) modes family |
| Cast and resolution | [Cast](../actions/Cast.md), [UserActionTaken](../annotations/UserActionTaken.md) |

## Cast lifecycle

1. The cast moves the spell to the stack; the request follows with the spell
   as the row's `affectedId`.
2. The request has one row, of type `Modal`, with the spell's `grpId` and
   `isRequired` `true`, and no `Done` row
   ([CastingTimeOptionsReq.modal-single](../prompts/CastingTimeOptionsReq.md#CastingTimeOptionsReq.modal-single)).
3. `modalReq` carries:

   | Field | Spree value |
   |---|---|
   | `abilityGrpId` | The card's modes ability, distinct from the spell's `grpId`. |
   | `minSel` | `1`. |
   | `maxSel` | The number of entries in `modalOptions`. |
   | `modalOptions[].grpId` | One mode's ability. |
   | `modalOptions[].modeCost` | That mode's additional cost only, without the spell's mana cost. |
   | `excludedOptions` | Modes that cannot be chosen now. |
   | `modeCostSolutions` | Ready-made mana payments, each for a set of modes in `modeGrpIds`. |

   Each `modeCost` entry has an `id` that is unique within the request and has
   no meaning outside it.
4. The client answers with the chosen modes in `chooseModalResp`.
5. Mana payment covers the spell's cost plus the chosen modes' costs, and the
   spell resolves the chosen modes.

## Sibling shapes

Tiered uses the same row and per-mode costs, but `maxSel` is always `1`. A
charm or other modal spell without per-mode costs carries no `modeCost`.
Optional costs such as kicker use a cost row followed by a `Done` row instead
of a `Modal` row.

## Contract

- <a id="Spree.selection-bounds"></a>**Spree.selection-bounds.** A spree
  request `MUST` carry `minSel` `1` and `maxSel` equal to the number of
  `modalOptions` entries.
- <a id="Spree.mode-cost"></a>**Spree.mode-cost.** Each `modalOptions` entry
  of a spree request `MUST` carry, in `modeCost`, that mode's additional cost
  and not the spell's mana cost.
- <a id="Spree.parent-ability"></a>**Spree.parent-ability.** The spree
  request's `modalReq.abilityGrpId` `MUST` name the card's modes ability, not
  the spell's `grpId`.

## Open points

- Which sets of modes does `modeCostSolutions` cover: every affordable set,
  or a selection?
- What does the request carry when every mode is offered and the spell has
  three or more modes?
