---
summary: "Convoke: tapping creatures to pay a spell's mana; Make_Payment actions with ability 172, the per-creature payment bracket, the ConvokeCount row and the Convoke label at resolution."
entity: mechanic/Convoke
---
# Convoke

Convoke lets a player tap creatures to pay part of a spell's mana cost. The
spell is cast through an ordinary [Cast](../actions/Cast.md); the creatures
are offered as `Make_Payment` steps in the mana-payment family of
[PayCostsReq](../prompts/PayCostsReq.md), each paid creature leaves a short
ability bracket and a substitution [ManaPaid](../annotations/ManaPaid.md)
row, and [AbilityWordActive](../annotations/AbilityWordActive.md) counts and
labels the convoked spell.

## Rules grounding

Convoke applies after the spell's total cost is determined. Each creature
tapped this way pays for one generic mana or one mana of one of its colors.
It is neither an additional nor an alternative cost (CR 702.51a, CR 702.51b).

## Surfaces

| Surface | Entity pages | Convoke values |
|---|---|---|
| Payment step | [PayCostsReq](../prompts/PayCostsReq.md) | `Make_Payment` with `abilityGrpId` `172` |
| Payment bracket | [AbilityInstanceCreated](../annotations/AbilityInstanceCreated.md), [ResolutionStart](../annotations/ResolutionStart.md), [ManaPaid](../annotations/ManaPaid.md), [UserActionTaken](../annotations/UserActionTaken.md) | `grpid` `172`, `substitution_grpid` `52`, `actionType` `12` |
| Count | [AbilityWordActive](../annotations/AbilityWordActive.md) | `ConvokeCount`, `AbilityGrpId` `52` |
| Resolution label | [AbilityWordActive](../annotations/AbilityWordActive.md) | Transient `Convoke` |

Convoke adds no casting option of its own: no `CastingTimeOptionsReq` row and
no `CastingTimeOption` row. Other costs of the same cast, such as kicker,
keep their own options.

## Payment lifecycle

1. After the cast puts the spell on the stack, a mana-payment `PayCostsReq`
   lists each untapped creature that can pay as a `Make_Payment` action with
   the creature as `instanceId` and `facetId` and `abilityGrpId` `172`,
   beside the ordinary `Activate_Mana` sources.
2. The client answers with `PerformActionResp` naming one creature's action.
3. The GSM that applies it carries, for that creature, in order:
   `AbilityInstanceCreated` from the creature with `source_zone` `28`;
   `TappedUntappedPermanent` tapping it, with the new ability as
   `affectorId`; `ResolutionStart` with `grpid` `172`; `ManaPaid` from the
   creature to the spell with `substitution_grpid` `52` and no `id`;
   `AbilityInstanceDeleted`; and `UserActionTaken` with `actionType` `12` and
   `abilityGrpId` `172`. No `ResolutionComplete` closes the bracket.
4. The same GSM publishes the persistent `ConvokeCount` row on the spell:
   `affectorId` and `affectedIds` the spell, `AbilityGrpId` `52` and `value`
   the number of creatures paid so far.
5. While cost remains, a new `PayCostsReq` follows without the paid creature,
   and the next payment raises `value`.
6. When the spell resolves, the GSM of its `Resolve` transfer deletes the
   `ConvokeCount` row and carries transient `AbilityWordActive` rows named
   `Convoke`, with no other detail, on the resolved object.

A spell paid only with mana carries neither the count row nor the label.

## Sibling shapes

Improvise uses the same payment-step family with artifacts and its own
ability, `384`. Crew also taps creatures, but as an object-selection cost
of an activated ability, not as mana.

## Contract

- <a id="Convoke.payment-action"></a>**Convoke.payment-action.** A creature
  that can pay through Convoke `MUST` be offered as a `Make_Payment` action
  with the creature as `instanceId` and `abilityGrpId` `172`.
- <a id="Convoke.payment-bracket"></a>**Convoke.payment-bracket.** Each paid
  creature `MUST` produce, in one GSM and in this order,
  `AbilityInstanceCreated` from the creature, `TappedUntappedPermanent` for
  it, `ResolutionStart` with `grpid` `172`, `ManaPaid`,
  `AbilityInstanceDeleted`, and `UserActionTaken` with `actionType` `12` and
  `abilityGrpId` `172`.
- <a id="Convoke.mana-paid"></a>**Convoke.mana-paid.** A creature's `ManaPaid`
  row `MUST` name the creature as `affectorId` and the spell in
  `affectedIds`, carry `substitution_grpid` `52`, and `MUST NOT` carry `id`.
- <a id="Convoke.count"></a>**Convoke.count.** While a spell paid with Convoke
  is on the stack, it `MUST` carry a persistent `ConvokeCount` row naming the
  spell as `affectorId`, with `AbilityGrpId` `52` and `value` equal to the
  number of creatures paid.
- <a id="Convoke.label"></a>**Convoke.label.** When a spell paid with Convoke
  resolves, the GSM of its `Resolve` transfer `MUST` carry a transient
  `AbilityWordActive` row named `Convoke` on the resolved object, with no
  `value`, `threshold` or `AbilityGrpId`.
- <a id="Convoke.no-cast-option"></a>**Convoke.no-cast-option.** A spell paid
  with Convoke `MUST NOT` carry a `CastingTimeOption` row for Convoke.

## Open points

- What does the number of `Convoke` label rows at resolution count?
- Which `color` does a creature's `ManaPaid` row carry when it pays a colored
  symbol?
- In what order are a multicolored creature's `manaPaymentOptions` listed?
